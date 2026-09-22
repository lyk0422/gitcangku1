package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务题目工程入口；提供赛事计时处罚与成绩封榜 REST 服务（见 com.example.starter.race 包）。
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 本地默认使用嵌入式 H2（MODE=MySQL）启动；也可通过 DB_URL/DB_USER 等环境变量切换外部数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
