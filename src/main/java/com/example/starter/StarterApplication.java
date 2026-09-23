package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：提供本地数据授权 API（授权、写入、撤回、单条查询与固定代次原子批量查询）。
 * 默认使用嵌入式 H2 内存库（MODE=MySQL），无需外部数据库；可通过 DB_URL 等环境变量覆盖数据源。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用默认 H2 内存库或环境变量指定的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
