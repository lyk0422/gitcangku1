package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 业务题目工程入口；当前仅提供 Web 服务及数据库健康检查，不包含题目业务实现。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 业务时钟：限流等待时刻统一取服务器本地时区，测试可替换为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
