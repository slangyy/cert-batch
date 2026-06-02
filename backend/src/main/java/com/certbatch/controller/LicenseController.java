package com.certbatch.controller;

import com.certbatch.common.R;
import com.certbatch.entity.License;
import com.certbatch.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    /**
     * 激活授权码（客户端调用）
     */
    @PostMapping("/activate")
    public R<License> activate(@RequestBody Map<String, String> params) {
        String licenseKey = params.get("licenseKey");
        String machineId = params.get("machineId");
        return licenseService.activate(licenseKey, machineId);
    }

    /**
     * 验证授权（客户端调用）
     */
    @PostMapping("/validate")
    public R<License> validate(@RequestBody Map<String, String> params) {
        String token = params.get("token");
        String machineId = params.get("machineId");
        return licenseService.validate(token, machineId);
    }

    /**
     * 生成授权码（管理端调用）
     */
    @PostMapping("/generate")
    public R<License> generate(@RequestBody Map<String, Object> params) {
        String customer = (String) params.get("customer");
        Integer days = params.get("days") != null ? (Integer) params.get("days") : null;
        License license = licenseService.generateLicense(customer, days);
        return R.ok(license);
    }

    /**
     * 查询所有授权（管理端调用）
     */
    @GetMapping("/list")
    public R<List<License>> list() {
        return R.ok(licenseService.list());
    }

    /**
     * 禁用授权（管理端调用）
     */
    @PostMapping("/disable/{id}")
    public R<Void> disable(@PathVariable Long id) {
        return licenseService.disable(id);
    }

    /**
     * 解绑机器（管理端调用）
     */
    @PostMapping("/unbind/{id}")
    public R<Void> unbind(@PathVariable Long id) {
        return licenseService.unbind(id);
    }

    /**
     * 获取公钥（开发者构建客户端时使用）
     */
    @GetMapping("/public-key")
    public R<String> publicKey() {
        return R.ok(licenseService.getPublicKeyBase64());
    }
}
