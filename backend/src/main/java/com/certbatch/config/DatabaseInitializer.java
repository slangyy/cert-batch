package com.certbatch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据库初始化：自动建表
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 创建模板表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS template (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    image_file_name TEXT NOT NULL,
                    image_width INTEGER,
                    image_height INTEGER,
                    create_time TEXT,
                    update_time TEXT
                )
            """);

            // 创建占位符表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS placeholder (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    template_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    pos_x REAL NOT NULL,
                    pos_y REAL NOT NULL,
                    font_name TEXT DEFAULT '宋体',
                    font_size INTEGER DEFAULT 24,
                    font_color TEXT DEFAULT '#000000',
                    alignment TEXT DEFAULT 'LEFT',
                    FOREIGN KEY (template_id) REFERENCES template(id)
                )
            """);

            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_placeholder_template_id ON placeholder(template_id)");

            // 创建授权表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS license (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    license_key TEXT NOT NULL UNIQUE,
                    machine_id TEXT,
                    status INTEGER DEFAULT 0,
                    customer TEXT,
                    expire_at TEXT,
                    activated_at TEXT,
                    token TEXT,
                    create_time TEXT
                )
            """);
        }
    }
}
