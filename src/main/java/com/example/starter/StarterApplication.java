package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 禁飞区域与航线版本审查服务入口。
 * 默认使用嵌入式 H2（MODE=MySQL）内存库，启动时自动建表并初始化全局空域版本，
 * 不依赖外部 MySQL 或 Docker；数据仅保留在 JVM 生命周期内。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动禁飞区航线审查 Web 服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
