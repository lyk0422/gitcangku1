package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；使用 H2 内存库（MODE=MySQL）启动，自动建表并初始化合成站点数据。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地服务入口，数据仅保留在当前 JVM 内。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}