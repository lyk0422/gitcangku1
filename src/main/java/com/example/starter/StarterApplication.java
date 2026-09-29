package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供多语种译文、术语版本、发布快照及译文归档包的 Web 服务。
 * 默认使用 H2 内存库（MODE=MySQL），启动时自动建表并写入本地合成演示数据。
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
