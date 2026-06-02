package com.certbatch.config;

import com.certbatch.common.R;
import com.certbatch.entity.License;
import com.certbatch.service.LicenseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/**
 * 授权拦截器：非授权接口需验证 token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 放行授权相关接口
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/license/")) {
            return true;
        }

        // 图片接口：支持 URL 参数传递 token（用于 <img> 标签等直接访问场景）
        if (uri.matches("/api/template/\\d+/image")) {
            String queryToken = request.getParameter("token");
            String queryMachineId = request.getParameter("machineId");
            if (queryToken != null && queryMachineId != null) {
                R<License> result = licenseService.validate(queryToken, queryMachineId);
                return result.getCode() == 200;
            }
            // 没有参数则走 Header 验证
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
