package com.example.snowpack;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 雪崩控制区积雪巡查应用入口。 */
@SpringBootApplication
public class SnowpackApplication {

    public static void main(String[] args) {
        // 数据库时间列按 UTC 读写，避免依赖运行环境的默认时区
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SnowpackApplication.class, args);
    }
}