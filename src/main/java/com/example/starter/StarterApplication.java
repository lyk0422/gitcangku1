package com.example.starter;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 业务题目工程入口：温室花序授粉窗口管理服务。
 * 提供花序、授粉窗口、授粉操作与结果确认的登记和查询接口。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地 H2 内存库（MODE=MySQL）或外部数据源配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }

    /**
     * 全系统统一使用 UTC 时钟，业务时间判断均基于该时钟。
     */
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}