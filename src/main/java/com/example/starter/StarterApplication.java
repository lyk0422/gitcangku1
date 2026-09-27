package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供设备工时读数、保养阈值与能源隔离的 Web 服务及数据库健康检查。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用内置 H2（MySQL 兼容模式，默认配置）或外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
