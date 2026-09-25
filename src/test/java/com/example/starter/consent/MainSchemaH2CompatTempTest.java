package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 主 schema 兼容性测试：含 MySQL COMMENT 语法的主 schema.sql 必须能在嵌入式
 * H2（MODE=MySQL）下直接执行，保证本地无需外部 MySQL 即可启动并自动建表。
 */
class MainSchemaH2CompatTempTest {

    @Test
    void mainSchemaRunsOnEmbeddedH2MysqlMode() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(Class.forName("org.h2.Driver").asSubclass(java.sql.Driver.class));
        dataSource.setUrl("jdbc:h2:mem:main-schema-check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            for (String table : new String[] {"consent_grant", "consent_record",
                    "idempotency_request", "retention_hold", "retention_hold_release"}) {
                try (Statement statement = connection.createStatement();
                     var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(rs.next()).isTrue();
                }
            }
        }
    }
}
