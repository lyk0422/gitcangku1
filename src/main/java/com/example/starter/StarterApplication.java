package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口：奶酪熟成批次翻面、测温与质检放行服务。
 * 默认使用 H2 内存库（MODE=MySQL）启动，自动建表并加载合成演示数据。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地 H2 内存库配置启动服务，进程退出后数据不保留。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}