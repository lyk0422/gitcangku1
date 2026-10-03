package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供清场业务 Web 服务，默认使用 H2 内存库（MODE=MySQL）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 默认使用内置 H2 内存库启动，可通过 DB_URL/DB_USER 环境变量切换到外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}