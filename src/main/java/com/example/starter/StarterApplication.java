package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供合成雪况记录、封闭命令、解除审核与区域状态查询，
 * 不提供真实安全预报。数据存储使用本地 H2 内存库（MODE=MySQL）。
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