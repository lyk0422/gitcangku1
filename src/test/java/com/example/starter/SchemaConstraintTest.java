package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接对 H2 数据库验证关键约束：检测编号全库唯一（只归属一个批次）、批次唯一、
 * 一批次只放行一次、样本数/发芽数 CHECK 约束、事务回滚不留部分数据。
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:cert-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class SchemaConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM batch_release");
        jdbcTemplate.update("DELETE FROM germination_certificate");
        jdbcTemplate.update("DELETE FROM germination_test");
        jdbcTemplate.update("DELETE FROM seed_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    @Test
    void testNoIsUniqueAcrossBatches_andCheckConstraintsEnforced() {
        long batchA = insertBatch("UT-S-A");
        long batchB = insertBatch("UT-S-B");

        jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-SHARED", batchA, 100, 90, Timestamp.valueOf(now), Timestamp.valueOf(now));

        // 同一检测编号归属另一个批次：数据库唯一约束拒绝，这是跨批次归属的最终保障。
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-SHARED", batchB, 100, 88, Timestamp.valueOf(now), Timestamp.valueOf(now)));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_test WHERE test_no = 'T-SHARED'", Integer.class);
        assertEquals(1, rows, "冲突插入必须回滚，同一检测编号全库仅一行");

        // CHECK 约束：样本数越界、发芽数大于样本数均被数据库拒绝。
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-LOW", batchA, 99, 90, Timestamp.valueOf(now), Timestamp.valueOf(now)));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-HIGH", batchA, 401, 90, Timestamp.valueOf(now), Timestamp.valueOf(now)));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-EXCEED", batchA, 100, 101, Timestamp.valueOf(now), Timestamp.valueOf(now)));
    }

    @Test
    void batchNoAndReleaseAreUnique_andFailedTransactionRollsBack() {
        long batchA = insertBatch("UT-S-C");
        assertThrows(DuplicateKeyException.class, () -> insertBatch("UT-S-C"));

        jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-C1", batchA, 100, 86, Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update(
                "INSERT INTO germination_test (test_no, batch_id, sample_size, germinated_count, "
                        + "status, tested_at, created_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?)",
                "T-C2", batchA, 100, 90, Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update(
                "INSERT INTO germination_certificate (certificate_no, batch_id, first_test_no, "
                        + "first_sample_size, first_germinated_count, first_ratio, second_test_no, "
                        + "second_sample_size, second_germinated_count, second_ratio, status, "
                        + "request_id, issued_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "'VALID', 'R1', ?, ?)",
                "CERT-UT-S-C-1", batchA, "T-C1", 100, 86, new java.math.BigDecimal("0.8600"),
                "T-C2", 100, 90, new java.math.BigDecimal("0.9000"),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        Long certId = jdbcTemplate.queryForObject(
                "SELECT id FROM germination_certificate WHERE certificate_no = 'CERT-UT-S-C-1'",
                Long.class);

        jdbcTemplate.update(
                "INSERT INTO batch_release (batch_id, certificate_id, certificate_no, first_test_no, "
                        + "first_ratio, second_test_no, second_ratio, request_id, released_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchA, certId, "CERT-UT-S-C-1", "T-C1", new java.math.BigDecimal("0.8600"),
                "T-C2", new java.math.BigDecimal("0.9000"), "REL-1",
                Timestamp.valueOf(now), Timestamp.valueOf(now));

        // 一批次只允许一行放行事实。
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_release (batch_id, certificate_id, certificate_no, first_test_no, "
                        + "first_ratio, second_test_no, second_ratio, request_id, released_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchA, certId, "CERT-UT-S-C-1", "T-C1", new java.math.BigDecimal("0.8600"),
                "T-C2", new java.math.BigDecimal("0.9000"), "REL-2",
                Timestamp.valueOf(now), Timestamp.valueOf(now)));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_release WHERE batch_id = ?", Integer.class, batchA));

        // 事务失败整体回滚：幂等键原键可重试。
        String requestId = "RETRY-KEY";
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                txTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            "INSERT INTO idempotency_record (scope, request_id, fingerprint, "
                                    + "response_json, created_at) VALUES ('ISSUE', ?, 'UT-S-C', '{}', ?)",
                            requestId, Timestamp.valueOf(now));
                    throw new RuntimeException("模拟事务失败");
                }));
        assertTrue(failure.getMessage().contains("模拟事务失败"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Integer.class, requestId);
        assertEquals(0, rows, "失败事务的插入必须回滚");

        jdbcTemplate.update(
                "INSERT INTO idempotency_record (scope, request_id, fingerprint, response_json, created_at) "
                        + "VALUES ('ISSUE', ?, 'UT-S-C', '{}', ?)",
                requestId, Timestamp.valueOf(now));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Integer.class, requestId), "原键重试应当成功");
    }

    private long insertBatch(String batchNo) {
        jdbcTemplate.update(
                "INSERT INTO seed_batch (batch_no, crop_name, created_at) VALUES (?, '合成作物', ?)",
                batchNo, Timestamp.valueOf(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM seed_batch WHERE batch_no = ?", Long.class, batchNo);
        return id == null ? -1 : id;
    }
}
