package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 考古层位样本封存系统入口：层位登记、样本采集、封存交接与背景查询。
 * 本地默认使用 H2 内存库（MODE=MySQL），数据仅保留在本 JVM 内。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动服务，默认监听 8080 端口。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}