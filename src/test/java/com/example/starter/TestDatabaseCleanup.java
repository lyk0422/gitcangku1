package com.example.starter;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据库清理：测试上下文关闭时释放 H2 内存库（DB_CLOSE_DELAY=-1 仅保证
 * 测试期间库不被提前关闭，结束时显式 DROP 全部对象）。
 */
@TestConfiguration
public class TestDatabaseCleanup {

    @Bean
    public TestDatabaseReleaser testDatabaseReleaser(JdbcTemplate jdbc) {
        return new TestDatabaseReleaser(jdbc);
    }

    /** 上下文销毁时执行 DROP ALL OBJECTS 释放内存库。 */
    public static final class TestDatabaseReleaser {

        private final JdbcTemplate jdbc;

        TestDatabaseReleaser(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @PreDestroy
        public void release() {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}