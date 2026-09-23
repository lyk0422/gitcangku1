package com.example.starter.restitution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * H2 真实数据库集成测试基类：每个用例后清空全部业务表并重置自增列，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractH2IntegrationTest {

    private static final List<String> TABLES = List.of(
            "frozen_approval",
            "frozen_evidence",
            "frozen_artifact",
            "frozen_claim",
            "frozen_decision",
            "approval",
            "evidence",
            "claim_artifact",
            "claim",
            "case_artifact",
            "restitution_case",
            "request_record"
    );

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.AfterEach
    void cleanTables() {
        for (String table : TABLES) {
            jdbcTemplate.update("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
    }
}
