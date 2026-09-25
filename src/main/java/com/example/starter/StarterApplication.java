package com.example.starter;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口；提供 Web 服务、数据库健康检查与授权/证明业务实现。
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
     * 业务时钟：默认 UTC 系统时钟，测试中可替换为可控时钟以验证到期语义。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
