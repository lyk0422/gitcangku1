package com.example.starter;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口；提供 Web 服务、数据库健康检查与铁路走廊日计划/气象限速能力。
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
     * 业务时钟（UTC），集中注入便于时间相关逻辑使用可控时钟测试。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
