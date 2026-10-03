package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 采石场清场爆破合成指令系统入口。
 *
 * <p>本地入口默认使用 H2 内存库（MODE=MySQL）自动建表并初始化合成演示数据，
 * 不连接真实起爆设备，也不依赖外部 MySQL。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动本地服务，默认监听 8080 端口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}