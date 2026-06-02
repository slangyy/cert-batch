package com.certbatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.certbatch.common.R;
import com.certbatch.entity.License;
import com.certbatch.mapper.LicenseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private final LicenseMapper licenseMapper;

    @Value("${app.data-dir}")
    private String dataDir;

    private KeyPair keyPair;

    /**
     * 初始化 RSA 密钥对（首次启动时自动生成并保存）
     */
    private KeyPair getKeyPair() {
        if (keyPair != null) return keyPair;
        try {
            Path privateKeyPath = Paths.get(dataDir, "license-private.key");
            Path publicKeyPath = Paths.get(dataDir, "license-public.key");

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
                // 从文件加载已有密钥
                byte[] privBytes = Base64.getDecoder().decode(Files.readString(privateKeyPath).trim());
                byte[] pubBytes = Base64.getDecoder().decode(Files.readString(publicKeyPath).trim());
                PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(pubBytes));
                keyPair = new KeyPair(publicKey, privateKey);
            } else {
                // 首次启动，生成新的 RSA-2048 密钥对
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                keyPair = generator.generateKeyPair();

                // 保存到文件
                Files.createDirectories(privateKeyPath.getParent());
                Files.writeString(privateKeyPath,
                        Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
                Files.writeString(publicKeyPath,
                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));

                log.info("============================================");
                log.info("  RSA 密钥对已生成并保存到: {}", dataDir);
                log.info("  请将以下公钥复制到 Electron 代码中：");
                log.info("  {}", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
                log.info("============================================");
            }
            return keyPair;
        } catch (Exception e) {
            throw new RuntimeException("初始化 RSA 密钥对失败", e);
        }
    }

    /**
     * 查询所有授权
     */
    public List<License> list() {
        return licenseMapper.selectList(null);
    }

    /**
     * 激活授权码，绑定机器，返回签名授权数据
     */
    public R<License> activate(String licenseKey, String machineId) {
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            return R.fail("授权码不能为空");
        }
        if (machineId == null || machineId.trim().isEmpty()) {
            return R.fail("机器标识不能为空");
        }

        License license = licenseMapper.selectOne(
                new LambdaQueryWrapper<License>().eq(License::getLicenseKey, licenseKey.trim())
        );
        if (license == null) {
            return R.fail("授权码不存在");
        }
        if (license.getStatus() == 2) {
            return R.fail("该授权码已被禁用");
        }
        if (license.getStatus() == 1 && license.getMachineId() != null
                && !license.getMachineId().equals(machineId)) {
            return R.fail("该授权码已绑定其他设备，如需换机请联系客服解绑");
        }
        if (license.getExpireAt() != null && license.getExpireAt().isBefore(LocalDateTime.now())) {
            return R.fail("该授权码已过期");
        }

        // 激活
        license.setStatus(1);
        license.setMachineId(machineId);
        license.setActivatedAt(LocalDateTime.now());
        license.setToken(UUID.randomUUID().toString().replace("-", ""));
        licenseMapper.updateById(license);

        // 生成签名
        try {
            String signedData = buildSignedData(machineId, licenseKey.trim(), license.getExpireAt());
            String signature = sign(signedData);
            license.setLicenseSignature(signature); // 附加签名到返回数据
        } catch (Exception e) {
            log.error("签名失败", e);
            return R.fail("激活失败：签名错误");
        }

        log.info("授权码激活成功: key={}, machineId={}", licenseKey, machineId);
        return R.ok(license);
    }

    /**
     * 验证授权（客户端在线时调用，用于检查是否被禁用）
     */
    public R<License> validate(String token, String machineId) {
        if (token == null || token.trim().isEmpty()) {
            return R.fail("未授权，请先激活");
        }
        if (machineId == null || machineId.trim().isEmpty()) {
            return R.fail("机器标识不能为空");
        }

        License license = licenseMapper.selectOne(
                new LambdaQueryWrapper<License>().eq(License::getToken, token)
        );
        if (license == null) {
            return R.fail("授权信息无效");
        }
        if (license.getStatus() == 2) {
            return R.fail("授权已被禁用");
        }
        if (license.getStatus() != 1) {
            return R.fail("授权未激活");
        }
        if (!machineId.equals(license.getMachineId())) {
            return R.fail("授权与当前设备不匹配");
        }
        if (license.getExpireAt() != null && license.getExpireAt().isBefore(LocalDateTime.now())) {
            return R.fail("授权已过期，请联系客服续期");
        }

        return R.ok(license);
    }

    /**
     * 生成授权码（管理端使用）
     */
    public License generateLicense(String customer, Integer days) {
        License license = new License();
        license.setLicenseKey(generateLicenseKey());
        license.setStatus(0);
        license.setCustomer(customer);
        if (days != null && days > 0) {
            license.setExpireAt(LocalDateTime.now().plusDays(days));
        }
        licenseMapper.insert(license);
        return license;
    }

    /**
     * 禁用授权
     */
    public R<Void> disable(Long licenseId) {
        License license = licenseMapper.selectById(licenseId);
        if (license == null) {
            return R.fail("授权不存在");
        }
        license.setStatus(2);
        license.setToken(null);
        licenseMapper.updateById(license);
        return R.ok();
    }

    /**
     * 解绑机器（允许换机激活）
     */
    public R<Void> unbind(Long licenseId) {
        License license = licenseMapper.selectById(licenseId);
        if (license == null) {
            return R.fail("授权不存在");
        }
        license.setMachineId(null);
        license.setToken(null);
        license.setStatus(0);
        licenseMapper.updateById(license);
        return R.ok();
    }

    /**
     * 获取公钥（供开发者嵌入客户端）
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(getKeyPair().getPublic().getEncoded());
    }

    // ===== 签名相关 =====

    /**
     * 构建待签名字符串: machineId|licenseKey|expireAt|issuedAt
     */
    private String buildSignedData(String machineId, String licenseKey, LocalDateTime expireAt) {
        String expire = expireAt != null ? expireAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "permanent";
        String issued = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return machineId + "|" + licenseKey + "|" + expire + "|" + issued;
    }

    /**
     * 用私钥对数据进行 RSA-SHA256 签名
     */
    private String sign(String data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(getKeyPair().getPrivate());
        sig.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * 生成格式为 XXXX-XXXX-XXXX-XXXX 的授权码
     */
    private String generateLicenseKey() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            int idx = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(idx));
        }
        String key = sb.toString();
        License existing = licenseMapper.selectOne(
                new LambdaQueryWrapper<License>().eq(License::getLicenseKey, key)
        );
        if (existing != null) {
            return generateLicenseKey();
        }
        return key;
    }
}
