package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口：提供校准标准器证书版本化、测量版本血缘与放行门禁等 Web 服务。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 统一时钟：默认系统 UTC 时钟；测试可替换为固定时钟以确定性裁决到期与放行时刻。
     */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    /**
     * 使用数据库连接配置启动服务；本地缺省使用嵌入式 H2（MODE=MySQL），无需外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
