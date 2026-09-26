package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：提供多语种段落修订、术语版本、发布快照与法定引文锚点锁定能力。
 * 默认使用嵌入式 H2（MODE=MySQL），启动时自动执行 schema.sql 建表，无需外部数据库。
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
