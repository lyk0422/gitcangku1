package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 地热井注入/采出切换审核服务入口；本地运行使用 H2 内存库（MODE=MySQL），自动建表并写入合成井况数据。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用本地 H2 内存库配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}