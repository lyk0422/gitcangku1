package com.example.coral;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 珊瑚移植样方追踪应用入口，仅处理合成观测数据。
 */
@SpringBootApplication
public class CoralApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoralApplication.class, args);
    }
}