package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 业务题目工程入口；提供 Web 服务及数据库健康检查。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /** 业务时间源：默认 UTC 系统时钟，测试可替换为固定时钟以验证时间字段。 */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
