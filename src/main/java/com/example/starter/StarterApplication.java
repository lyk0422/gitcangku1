package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 航班除冰时隙业务工程入口；本地启动使用 H2 内存库（MODE=MySQL），自动建表并初始化合成数据。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用内置 H2 内存库配置启动服务，进程退出后数据不保留。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}