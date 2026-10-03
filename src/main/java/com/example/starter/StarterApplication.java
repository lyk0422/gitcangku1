package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 野生动物标签业务工程入口：标签登记、附着、摘除、站点间交接与历史时间线查询。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务；默认使用 H2 内存库（MODE=MySQL），可通过 DB_URL 等环境变量覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}