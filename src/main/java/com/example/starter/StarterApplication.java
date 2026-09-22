package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供生产批次隔离、放行、拆分血缘与召回追溯 Web 服务及数据库健康检查。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 默认使用嵌入式 H2（MySQL 兼容模式）内存库启动，可通过 DB_URL/DB_USER 等环境变量覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
