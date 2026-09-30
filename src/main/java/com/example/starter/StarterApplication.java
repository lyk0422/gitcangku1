package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 业务题目工程入口；提供 Web 服务、数据库健康检查与译文术语冻结业务。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /** 统一时钟：生产使用 UTC 系统时钟，测试替换为可控时钟以验证冻结窗口边界。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
