package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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
     * 应用时钟：快照生成时刻统一取自该时钟，测试可替换为可控时钟。
     */
    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
