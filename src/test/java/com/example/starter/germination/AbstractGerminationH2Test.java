package com.example.starter.germination;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * H2 集成测试基类：每个子类使用独立前缀的合成数据，并在每个用例前清理本前缀场景数据，
 * 与内置合成批次（BATCH-2026-*）互不干扰。
 */
@SpringBootTest
public abstract class AbstractGerminationH2Test {

    @Autowired
    protected JdbcTemplate jdbc;

    /** 子类场景数据前缀，例如 "ITWF-"（workflow）、"ITCJ-"（并发）。 */
    protected abstract String tag();

    @BeforeEach
    final void resetScenario() {
        String tag = tag();
        jdbc.update("DELETE FROM batch_release WHERE batch_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM germination_certificate WHERE batch_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM germination_test WHERE test_no LIKE ? OR batch_no LIKE ?",
                tag + "%", tag + "%");
        jdbc.update("DELETE FROM idempotent_request WHERE request_id LIKE ?", tag + "%");
        jdbc.update("DELETE FROM seed_batch WHERE batch_no LIKE ?", tag + "%");
        setupScenario();
    }

    /** 子类重写以登记本用例所需的合成批次。 */
    protected void setupScenario() {
    }

    /** 登记一个合成批次。 */
    protected void createBatch(String batchNo, String cropName) {
        jdbc.update("INSERT INTO seed_batch (batch_no, crop_name, created_at) VALUES (?, ?, ?)",
                batchNo, cropName, java.sql.Timestamp.from(Instant.parse("2026-09-10T00:00:00Z")));
    }
}
