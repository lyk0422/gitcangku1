package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口；提供 Web 服务、授权域业务及数据库健康检查。
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
     * 统一 UTC 时钟：业务时间戳均取自该 Bean，测试可替换为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
