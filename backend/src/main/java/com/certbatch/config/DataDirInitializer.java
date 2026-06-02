package com.certbatch.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 在所有 Bean 创建之前确保数据目录存在（早于 DataSource 初始化）
 */
@Component
public class DataDirInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String dataDir = environment.getProperty("app.data-dir", "./cert-batch-data");
        Path dir = Paths.get(dataDir).toAbsolutePath();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.println("[DataDirInitializer] 创建数据目录: " + dir);
            }
        } catch (Exception e) {
            throw new RuntimeException("无法创建数据目录: " + dir, e);
        }
    }
}
