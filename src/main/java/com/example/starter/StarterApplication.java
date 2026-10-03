package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：天线指向校准、偏差审核、有效期与任务绑定服务。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 默认使用 H2 内存库（MODE=MySQL）启动，可通过 DB_URL 等环境变量覆盖数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}