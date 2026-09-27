package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供公告曝光频控与 UTC 归因窗口的 Web 服务及数据库健康检查。
 * 默认使用 H2 内存库（MODE=MySQL）启动，自动建表，无需外部数据库。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用内置 H2 内存库或外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
