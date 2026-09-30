package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 制品依赖锁定与依赖风险豁免服务入口；默认使用自动建表的 H2 内存库（MODE=MySQL）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 使用外部提供的数据库连接配置启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
