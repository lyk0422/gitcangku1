package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：测量校准（线性/二次证书）、测量提交与反解、批量放行、幂等去重。
 * 默认使用 H2 内存库（MODE=MySQL）自动建表；可通过 DB_URL/DB_USER/DB_PASSWORD 覆盖。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用配置的数据库连接启动服务（默认 H2 内存库）。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
