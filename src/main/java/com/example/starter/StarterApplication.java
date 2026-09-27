package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：提供多语种段落修订、术语版本、发布快照与译文引文锁定的 Web 服务。
 * 默认使用 H2 内存库（MODE=MySQL）本地启动，无需外部数据库。
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
