package com.example.starter;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：野外标签登记、附着、摘除、站点间交接与时间线查询。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 默认使用 H2 内存库（MODE=MySQL）启动，可通过 DB_URL 等环境变量覆盖。
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(StarterApplication.class, args);
    }
}