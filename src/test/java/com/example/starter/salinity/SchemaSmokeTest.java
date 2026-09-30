package com.example.starter.salinity;

import com.example.starter.salinity.repository.SalinityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：Spring 上下文可启动，H2（MODE=MySQL）建表脚本执行成功，关键表存在。
 */
@SpringBootTest
class SchemaSmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SalinityRepository repository;

    @Test
    void contextLoadsAndAllTablesExist() {
        for (String table : new String[]{"VOYAGES", "SAMPLES", "ADOPTIONS", "SEALED_PROFILES", "CORRECTIONS"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                    Integer.class, table);
            assertThat(count).as("表 %s 应已创建", table).isEqualTo(1);
        }
        assertThat(repository).isNotNull();
    }
}
