package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供铁路日计划与编组到发站台适配的 Web 服务，
 * 默认使用 H2 内存库（MODE=MySQL）自动建表并初始化合成站台数据，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动 Web 服务；数据源可通过 DB_URL / DB_USER / DB_PASSWORD 环境变量覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
