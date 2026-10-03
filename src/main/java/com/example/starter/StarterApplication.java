package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 天线指向校准业务链工程入口：提供校准登记、复核作废、任务绑定与查询接口。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地 H2 内存库（MODE=MySQL）启动服务，可通过 DB_URL 等环境变量覆盖数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 统一业务时钟（UTC），测试可替换为可控时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}