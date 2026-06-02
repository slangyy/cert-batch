package com.certbatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.certbatch.common.R;
import com.certbatch.entity.License;
import com.certbatch.mapper.LicenseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private final LicenseMapper licenseMapper;

    /**
     * 查询所有授权
     */
    public List<License> list() {
        return licenseMapper.selectList(null);
    }

    /**
     * 激活授权码，绑定机器
     */
    public R<License> activate(String licenseKey, String machineId) {
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            return R.fail("授权码不能为空");
        }
        if (machineId == null || machineId.trim().isEmpty()) {
            return R.fail("机器标识不能为空");
        }

        // 查询授权码
        License license = licenseMapper.selectOne(
                new LambdaQueryWrapper<License>().eq(License::getLicenseKey, licenseKey.trim())
        );
        if (license == null) {
            return R.fail("授权码不存在");
        }

        // 检查状态
        if (license.getStatus() == 2) {
            return R.fail("该授权码已被禁用");
        }

        // 检查是否已绑定其他机器
        if (license.getStatus() == 1 && license.getMachineId() != null
                && !license.getMachineId().equals(machineId)) {
            return R.fail("该授权码已绑定其他设备，如需换机请联系客服解绑");
        }

        // 检查是否过期
        if (license.getExpireAt() != null && license.getExpireAt().isBefore(LocalDateTime.now())) {
            return R.fail("该授权码已过期");
        }

        // 激活
        license.setStatus(1);
        license.setMachineId(machineId);
        license.setActivatedAt(LocalDateTime.now());
        license.setToken(UUID.randomUUID().toString().replace("-", ""));
        licenseMapper.updateById(license);

        log.info("授权码激活成功: key={}, machineId={}", licenseKey, machineId);
        return R.ok(license);
    }

    /**
     * 验证授权（客户端每次启动或定期调用）
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

        // 检查状态
        if (license.getStatus() == 2) {
            return R.fail("授权已被禁用");
        }
        if (license.getStatus() != 1) {
            return R.fail("授权未激活");
        }

        // 检查机器绑定
        if (!machineId.equals(license.getMachineId())) {
            return R.fail("授权与当前设备不匹配");
        }

        // 检查过期
        if (license.getExpireAt() != null && license.getExpireAt().isBefore(LocalDateTime.now())) {
            return R.fail("授权已过期，请联系客服续期");
        }

        return R.ok(license);
    }

    /**
     * 生成授权码（管理端使用）
     * @param customer 客户名称
     * @param days 有效天数（null表示永久）
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
        // 确保不重复
        String key = sb.toString();
        License existing = licenseMapper.selectOne(
                new LambdaQueryWrapper<License>().eq(License::getLicenseKey, key)
        );
        if (existing != null) {
            return generateLicenseKey(); // 递归重试
        }
        return key;
    }
}
