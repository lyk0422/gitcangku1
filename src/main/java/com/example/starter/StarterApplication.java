package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：温室花序授粉窗口管理。
 * 提供花序、花粉批次、授粉操作与结果确认的 REST 接口，默认使用 H2 内存库（MODE=MySQL）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务；默认连接 H2 内存库，可通过 DB_URL/DB_USER/DB_PASSWORD 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}