package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务工程入口：证物封存交接与证物披露包服务。
 * 默认使用 H2 内存库（MODE=MySQL）启动，schema.sql 自动建表；可通过 DB_URL 等环境变量切换 MySQL。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
