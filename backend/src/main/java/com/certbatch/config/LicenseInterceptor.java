package com.certbatch.config;

import com.certbatch.common.R;
import com.certbatch.entity.License;
import com.certbatch.service.LicenseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 授权拦截器：非授权接口需验证 token
 * 桌面客户端启动时通过 --app.license-check=false 关闭此拦截
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;
    private final ObjectMapper objectMapper;

    @Value("${app.license-check:true}")
    private boolean licenseCheck;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 桌面客户端模式：跳过授权检查（Electron 主进程已验证）
        if (!licenseCheck) {
            return true;
        }

        // 放行 OPTIONS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 放行授权相关接口
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/license/")) {
            return true;
        }

        // 验证授权
        String token = request.getHeader("X-License-Token");
        String machineId = request.getHeader("X-Machine-Id");

        R<License> result = licenseService.validate(token, machineId);
        if (result.getCode() != 200) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }

        return true;
    }
}
