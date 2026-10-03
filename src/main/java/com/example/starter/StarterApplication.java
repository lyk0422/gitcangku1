package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口：潜水训练作业记录与上浮审核服务。
 * 默认使用 H2 内存库（MODE=MySQL）自动建表，可通过 DB_URL/DB_USER 覆盖。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务；默认连接 H2 内存库，无需外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 业务时钟，测试中可替换为可控时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}