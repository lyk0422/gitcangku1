package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：染色批次整批放行链路（批次、布卷测色、复检裁决、放行冻结与勘误）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 本地默认使用 H2 内存库（MODE=MySQL）启动，可通过 DB_URL/DB_USER/DB_PASSWORD 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}