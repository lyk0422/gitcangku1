package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 雪崩控制区合成业务工程入口：雪况记录、封闭命令、解除审核与区域状态查询。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 默认使用 H2 内存库(MODE=MySQL)启动，可通过 DB_URL 等环境变量切换外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}