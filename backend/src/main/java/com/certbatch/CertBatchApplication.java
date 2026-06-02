package com.certbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CertBatchApplication {

    public static void main(String[] args) {
        // 启用 headless 模式，Linux 无图形环境时仍可使用 Java2D 渲染图片
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(CertBatchApplication.class, args);
    }
}
