package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证应用上下文可在嵌入式 H2 上启动且自动建表成功。 */
class SchemaInitializationTest extends AbstractIntegrationTest {

    @Test
    void allTablesAreCreatedOnH2() {
        JdbcTemplate jdbc = jdbcTemplate;
        for (String table : new String[] {
                "experiment", "experiment_seat", "allocation", "unblind_request", "idempotency_record"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                    Integer.class, table);
            assertThat(count).as("table %s should exist", table).isEqualTo(1);
        }
    }
}
