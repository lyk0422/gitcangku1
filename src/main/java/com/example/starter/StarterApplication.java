package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供铁路日计划、原子改签与区段维护封锁的 Web 服务，
 * 默认使用自动建表的嵌入式 H2 内存库（MODE=MySQL），无需外部数据库。
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
