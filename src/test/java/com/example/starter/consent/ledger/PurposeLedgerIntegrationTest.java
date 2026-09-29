package com.example.starter.consent.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.Purpose;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.ledger.dto.QuotaAdjustRequest;
import com.example.starter.consent.ledger.dto.QuotaClearRequest;
import com.example.starter.consent.ledger.dto.RestrictedQueryRequest;

/**
 * 授权用途账本 H2（MODE=MySQL）集成测试：主流程、失败分支、事务原子性、并发不超扣与幂等边界。
 *
 * <p>时间断言使用 {@link BusinessClock} 可控时钟；并发测试用闩锁协调真实线程并设超时，
 * 最终核对余额与账本数据，而非以打印或休眠判定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurposeLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurposeLedgerService ledgerService;

    @Autowired
    private com.example.starter.consent.ConsentService consentService;

    @Autowired
    private BusinessClock clock;

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM purpose_ledger");
        jdbc.update("DELETE FROM purpose_quota");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("UPDATE ledger_seq SET next_val = 1 WHERE id = 1");
        clock.reset();
    }

    // ---------- 测试数据准备辅助 ----------

    private void grant(String subject, String purpose) {
        consentService.grant(new GrantRequest("grant-" + subject + "-" + purpose, subject,
                Purpose.valueOf(purpose)));
    }

    private void revoke(String subject, String purpose, int epoch) {
        consentService.revoke(new RevokeRequest("revoke-" + subject + "-" + purpose + "-" + epoch,
                subject, Purpose.valueOf(purpose), epoch));
    }

    private void grantEpoch2AfterRevoke(String subject, String purpose) {
        revoke(subject, purpose, 1);
        consentService.grant(new GrantRequest("grant2-" + subject + "-" + purpose, subject,
                Purpose.valueOf(purpose)));
    }

    private void writeRecord(String subject, String purpose, int epoch, String recordKey, String payload) {
        // 记录写入始终针对当前有效代次；测试通过先撤回/再授权切换 epoch
        consentService.write(new RecordWriteRequest(
                "write-" + subject + "-" + purpose + "-" + epoch + "-" + recordKey,
                subject, Purpose.valueOf(purpose), recordKey, payload));
    }

    private ResultActions adjustQuota(String requestId, String subject, String purpose, int epoch, long quota)
            throws Exception {
        return mockMvc.perform(post("/api/v1/consents/quotas")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"quota":%d}
                        """.formatted(requestId, subject, purpose, epoch, quota)));
    }

    private ResultActions clearQuota(String requestId, String subject, String purpose, int epoch)
            throws Exception {
        return mockMvc.perform(post("/api/v1/consents/quotas/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subject, purpose, epoch)));
    }

    private ResultActions queryJson(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/restricted-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions restrictedQuery(String requestId, String subject, String purpose, int epoch,
                                          String objectsJson) throws Exception {
        return queryJson("""
                {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"objects":[%s]}
                """.formatted(requestId, subject, purpose, epoch, objectsJson));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long balanceOf(String subject, String purpose, int epoch) {
        Long value = jdbc.queryForObject(
                "SELECT balance FROM purpose_quota WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Long.class, subject, purpose, epoch);
        return value == null ? -1L : value;
    }

    // ---------- 额度调整主流程 ----------

    @Test
    void firstQuotaAdjustDeclaresBalanceAndWritesLedger() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("QUOTA_ADJUST"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balanceBefore").isEmpty())
                .andExpect(jsonPath("$.balanceAfter").value(10))
                .andExpect(jsonPath("$.ledgerSeq").value(1))
                .andExpect(jsonPath("$.occurredAtUtc").exists());
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUOTA_ADJUST'")).isEqualTo(1);
    }

    @Test
    void adjustQuotaUpAndDownReportsActualBeforeBalance() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        adjustQuota("q-2", "s1", "RESEARCH", 1, 25)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceBefore").value(10))
                .andExpect(jsonPath("$.balanceAfter").value(25))
                .andExpect(jsonPath("$.ledgerSeq").value(2));
        adjustQuota("q-3", "s1", "RESEARCH", 1, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceBefore").value(25))
                .andExpect(jsonPath("$.balanceAfter").value(3));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(3);
    }

    @Test
    void adjustQuotaReplayReturnsOriginalSnapshotWithoutNewLedgerRow() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerSeq").value(1))
                .andExpect(jsonPath("$.balanceAfter").value(10));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isEqualTo(1);
    }

    @Test
    void adjustQuotaSameRequestIdDifferentQuotaReturns409() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        adjustQuota("q-1", "s1", "RESEARCH", 1, 11)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void adjustQuotaForNonexistentGrantReturns404AndDoesNotWriteLedger() throws Exception {
        adjustQuota("q-1", "s1", "RESEARCH", 9, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isZero();
        assertThat(count("SELECT COUNT(*) FROM purpose_quota")).isZero();
    }

    @Test
    void adjustQuotaForRevokedGrantReturns410() throws Exception {
        grant("s1", "RESEARCH");
        revoke("s1", "RESEARCH", 1);
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void failedAdjustDoesNotConsumeRequestId() throws Exception {
        // 撤回状态下失败一次，重新授权 epoch2 后同 requestId 可成功使用
        grant("s1", "RESEARCH");
        revoke("s1", "RESEARCH", 1);
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isGone());
        consentService.grant(new GrantRequest("g2", "s1", Purpose.RESEARCH));
        adjustQuota("q-1", "s1", "RESEARCH", 2, 10).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'q-1'")).isEqualTo(1);
    }

    // ---------- 受限查询主流程 ----------

    @Test
    void restrictedQuerySucceedsAndAtomiclyDeductsByObjectCounts() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        writeRecord("s1", "RESEARCH", 1, "rec-b", "2");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());

        // 入参乱序、重复：规范化后按字典序，总数 1+2=3
        restrictedQuery("rq-1", "s1", "RESEARCH", 1,
                "{\"recordKey\":\"rec-b\",\"count\":2},{\"recordKey\":\"rec-a\",\"count\":1},"
                        + "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectCount").value(2))
                .andExpect(jsonPath("$.balanceBefore").value(10))
                .andExpect(jsonPath("$.balanceAfter").value(7))
                .andExpect(jsonPath("$.ledgerSeq").value(2))
                .andExpect(jsonPath("$.objectDigest").isString())
                .andExpect(jsonPath("$.results[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.results[1].recordKey").value("rec-b"))
                .andExpect(jsonPath("$.results[1].payload").value("2"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(1);
    }

    @Test
    void exactRemainingQuotaSucceedsAndZeroBalanceFails() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "2");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 2).andExpect(status().isOk());

        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(0));
        restrictedQuery("rq-2", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":2}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_INSUFFICIENT"))
                .andExpect(jsonPath("$.actual").value(0))
                .andExpect(jsonPath("$.required").value(2))
                .andExpect(jsonPath("$.difference").value(2));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isZero();
        // 失败查询不写账
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(1);
    }

    @Test
    void restrictedQueryReplayReturnsFirstSnapshotWithoutDeductingAgain() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 5).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerSeq").value(2))
                .andExpect(jsonPath("$.balanceBefore").value(5))
                .andExpect(jsonPath("$.balanceAfter").value(4));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(1);
    }

    @Test
    void restrictedQuerySameRequestIdDifferentObjectsReturns409AndDoesNotDeduct() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        writeRecord("s1", "RESEARCH", 1, "rec-b", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 5).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-b\",\"count\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(1);
    }

    // ---------- 失败分支：403 / 422 / 410 ----------

    @Test
    void queryWithoutDeclaredQuotaReturns403AndWritesNothing() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_DECLARED"));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rq-1'")).isZero();
    }

    @Test
    void insufficientQuotaReturns403WithActualRequiredDifference() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "3");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 2).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":3}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_INSUFFICIENT"))
                .andExpect(jsonPath("$.actual").value(2))
                .andExpect(jsonPath("$.required").value(3))
                .andExpect(jsonPath("$.difference").value(1))
                .andExpect(jsonPath("$.detail").value("purpose=RESEARCH,epoch=1"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(2);
    }

    @Test
    void unknownObjectReturns422WithLocatableDetail() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"missing\",\"count\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("recordKey=missing"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
    }

    @Test
    void objectFromOtherEpochReturns422WithActualAndRequiredEpoch() throws Exception {
        // epoch1 的独有记录；撤回 epoch1 后授权 epoch2，在仍有效的 epoch2 查询 epoch1 对象即跨 epoch
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-only-e1", "1");
        grantEpoch2AfterRevoke("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 2, "rec-e2", "1");
        adjustQuota("q-2", "s1", "RESEARCH", 2, 10).andExpect(status().isOk());

        // rec-only-e1 仅存在于 epoch 1：在 epoch 2 查询即跨 epoch
        restrictedQuery("rq-1", "s1", "RESEARCH", 2, "{\"recordKey\":\"rec-only-e1\",\"count\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJECT_EPOCH_MISMATCH"))
                .andExpect(jsonPath("$.actual").value(1))
                .andExpect(jsonPath("$.required").value(2))
                .andExpect(jsonPath("$.detail").value("recordKey=rec-only-e1"));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isZero();
        // 失败不占用 requestId，也不扣减
        assertThat(balanceOf("s1", "RESEARCH", 2)).isEqualTo(10);
    }

    @Test
    void quantityMismatchReturns422WithActualRequiredDifference() throws Exception {
        grant("s1", "RESEARCH");
        // 记录实际承载 2 个对象，调用方声明 1 个
        writeRecord("s1", "RESEARCH", 1, "rec-a", "2");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_MISMATCH"))
                .andExpect(jsonPath("$.actual").value(2))
                .andExpect(jsonPath("$.required").value(1))
                .andExpect(jsonPath("$.difference").value(-1))
                .andExpect(jsonPath("$.detail").value("recordKey=rec-a"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isZero();
    }

    @Test
    void duplicateObjectWithConflictingCountsReturns422() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "2");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1,
                "{\"recordKey\":\"rec-a\",\"count\":2},{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_MISMATCH"))
                .andExpect(jsonPath("$.actual").value(2))
                .andExpect(jsonPath("$.required").value(1));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
    }

    @Test
    void batchFailureReturnsNoPartialDataAndWritesNoLedger() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        writeRecord("s1", "RESEARCH", 1, "rec-b", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());

        // 第一个对象合法、第二个不存在：整体失败，余额与账本均无变化
        restrictedQuery("rq-1", "s1", "RESEARCH", 1,
                "{\"recordKey\":\"rec-a\",\"count\":1},{\"recordKey\":\"rec-missing\",\"count\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rq-1'")).isZero();
    }

    @Test
    void emptyObjectListReturns400() throws Exception {
        grant("s1", "RESEARCH");
        queryJson("""
                {"requestId":"rq-bad","subjectKey":"s1","purpose":"RESEARCH","epoch":1,"objects":[]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void unknownPurposeReturns400() throws Exception {
        queryJson("""
                {"requestId":"rq-bad","subjectKey":"s1","purpose":"MARKETING","epoch":1,
                 "objects":[{"recordKey":"rec-a","count":1}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void queryAfterRevokeReturns410AndDoesNotDeduct() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        revoke("s1", "RESEARCH", 1);
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isZero();
    }

    @Test
    void replayedQueryAfterRevokeAlsoReturns410AndDoesNotDeduct() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(9);
        revoke("s1", "RESEARCH", 1);
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 重放不再次扣减
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(9);
    }

    // ---------- 用途独立 ----------

    @Test
    void purposesCountIndependently() throws Exception {
        grant("s1", "RESEARCH");
        grant("s1", "PERSONALIZATION");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        writeRecord("s1", "PERSONALIZATION", 1, "rec-a", "1");
        adjustQuota("qr", "s1", "RESEARCH", 1, 1).andExpect(status().isOk());
        adjustQuota("qp", "s1", "PERSONALIZATION", 1, 100).andExpect(status().isOk());

        restrictedQuery("rr", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());
        // RESEARCH 用尽
        restrictedQuery("rr2", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_INSUFFICIENT"));
        // PERSONALIZATION 不受影响
        restrictedQuery("rp", "s1", "PERSONALIZATION", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(99));
    }

    // ---------- 清除与历史不可变 ----------

    @Test
    void clearQuotaMarksClearedAndKeepsHistory() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());

        clearQuota("c-1", "s1", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("QUOTA_CLEAR"))
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.balanceBefore").value(9))
                .andExpect(jsonPath("$.balanceAfter").isEmpty())
                .andExpect(jsonPath("$.ledgerSeq").value(3));

        // 清除后查询按用途未声明拒绝（403），且不扣减
        restrictedQuery("rq-2", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_DECLARED"));
        // CLEARED 为终态，再调整 409
        adjustQuota("q-2", "s1", "RESEARCH", 1, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_CLEARED"));

        // 历史扣减记录保持固化：seq=2 仍是扣减前 10、扣减后 9，快照不变
        mockMvc.perform(get("/api/v1/consents/ledger/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("QUERY"))
                .andExpect(jsonPath("$.balanceBefore").value(10))
                .andExpect(jsonPath("$.balanceAfter").value(9))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.querySnapshot").isString());
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isEqualTo(3);
    }

    @Test
    void clearWithoutDeclaredQuotaReturns403AndDoesNotWriteLedger() throws Exception {
        grant("s1", "RESEARCH");
        clearQuota("c-1", "s1", "RESEARCH", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_DECLARED"));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c-1'")).isZero();
    }

    @Test
    void clearReplayReturnsOriginalSnapshot() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        clearQuota("c-1", "s1", "RESEARCH", 1).andExpect(status().isOk());
        clearQuota("c-1", "s1", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerSeq").value(2));
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isEqualTo(2);
    }

    @Test
    void laterAdjustDoesNotRewriteHistoricalLedgerRows() throws Exception {
        grant("s1", "RESEARCH");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        adjustQuota("q-2", "s1", "RESEARCH", 1, 20).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consents/ledger/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 历史区间、明细、诊断：读取不改状态 ----------

    @Test
    void historyUsesUtcHalfOpenIntervalAndStableSeqOrdering() throws Exception {
        grant("s1", "RESEARCH");
        clock.pin(Instant.parse("2026-09-01T00:00:00.000000Z"));
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        clock.pin(Instant.parse("2026-09-02T00:00:00.000000Z"));
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());
        clock.pin(Instant.parse("2026-09-03T00:00:00.000000Z"));
        restrictedQuery("rq-2", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());

        // [09-01, 09-03)：含调整与第一次查询，排除第三次时刻条目
        mockMvc.perform(get("/api/v1/consents/ledger")
                        .param("subjectKey", "s1")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-03T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.entries[0].seq").value(1))
                .andExpect(jsonPath("$.entries[1].seq").value(2));

        // [09-02, 09-03)：仅第一次查询
        mockMvc.perform(get("/api/v1/consents/ledger")
                        .param("subjectKey", "s1")
                        .param("from", "2026-09-02T00:00:00Z")
                        .param("to", "2026-09-03T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.entries[0].seq").value(2));

        // from >= to：400
        mockMvc.perform(get("/api/v1/consents/ledger")
                        .param("subjectKey", "s1")
                        .param("from", "2026-09-03T00:00:00Z")
                        .param("to", "2026-09-03T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void ledgerDetailMissingReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/consents/ledger/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));
    }

    @Test
    void readEndpointsDoNotChangeState() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isOk());

        long seqBefore = jdbc.queryForObject("SELECT MAX(seq) FROM purpose_ledger", Long.class);
        long ledgerCountBefore = count("SELECT COUNT(*) FROM purpose_ledger");
        long quotaCountBefore = count("SELECT COUNT(*) FROM purpose_quota");
        long idempotencyBefore = count("SELECT COUNT(*) FROM idempotency_request");

        mockMvc.perform(get("/api/v1/consents/ledger/2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consents/ledger").param("subjectKey", "s1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consents/diagnostics")
                .param("subjectKey", "s1").param("purpose", "RESEARCH").param("epoch", "1"))
                .andExpect(status().isOk());
        // 再读一轮
        mockMvc.perform(get("/api/v1/consents/diagnostics")
                .param("subjectKey", "s1").param("purpose", "RESEARCH").param("epoch", "1"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT MAX(seq) FROM purpose_ledger", Long.class)).isEqualTo(seqBefore);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger")).isEqualTo(ledgerCountBefore);
        assertThat(count("SELECT COUNT(*) FROM purpose_quota")).isEqualTo(quotaCountBefore);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request")).isEqualTo(idempotencyBefore);
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(9);
    }

    @Test
    void diagnosticsReportsGrantQuotaAndLedgerState() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "2");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 5).andExpect(status().isOk());
        restrictedQuery("rq-1", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":2}")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/consents/diagnostics")
                        .param("subjectKey", "s1")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantExists").value(true))
                .andExpect(jsonPath("$.grantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.quotaDeclared").value(true))
                .andExpect(jsonPath("$.quotaStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(3))
                .andExpect(jsonPath("$.ledgerCount").value(2))
                .andExpect(jsonPath("$.queryCount").value(1))
                .andExpect(jsonPath("$.totalDeducted").value(2))
                .andExpect(jsonPath("$.lastSeq").value(2))
                .andExpect(jsonPath("$.lastOccurredAt").exists());

        // 未声明用途的诊断
        mockMvc.perform(get("/api/v1/consents/diagnostics")
                        .param("subjectKey", "s1")
                        .param("purpose", "PERSONALIZATION")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantExists").value(false))
                .andExpect(jsonPath("$.grantStatus").isEmpty())
                .andExpect(jsonPath("$.quotaDeclared").value(false))
                .andExpect(jsonPath("$.balance").isEmpty())
                .andExpect(jsonPath("$.totalDeducted").value(0));
    }

    // ---------- 并发：不超扣、同 requestId 单次核销 ----------

    @Test
    void concurrentQueriesNeverOverdraw() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 5).andExpect(status().isOk());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "cq-" + i;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ledgerService.restrictedQuery(new RestrictedQueryRequest(
                            requestId, "s1", "RESEARCH", 1,
                            List.of(new RestrictedQueryRequest.ObjectSpec("rec-a", 1L))));
                    success.incrementAndGet();
                } catch (LedgerApiException ex) {
                    assertThat(ex.getCode()).isEqualTo("QUOTA_INSUFFICIENT");
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(3);
        assertThat(balanceOf("s1", "RESEARCH", 1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'RESTRICTED_QUERY'"))
                .isEqualTo(5);
    }

    @Test
    void concurrentSameRequestIdDeductsExactlyOnceAndAllReplaySnapshot() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return ledgerService.restrictedQuery(new RestrictedQueryRequest(
                        "same-rq", "s1", "RESEARCH", 1,
                        List.of(new RestrictedQueryRequest.ObjectSpec("rec-a", 1L)))).ledgerSeq();
            });
        }
        List<Future<Long>> futures = new ArrayList<>();
        for (Callable<Long> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Long> future : futures) {
            // 所有线程都重放同一首次成功快照：同一 ledgerSeq
            assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(2L);
        }
        pool.shutdown();
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(9);
        assertThat(count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'same-rq'")).isEqualTo(1);
    }

    @Test
    void concurrentAdjustAndQueryAreOrderedByCommitWithNoOverdraw() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 1).andExpect(status().isOk());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        // 1 个调整把额度补到 10，3 个查询各扣 1
        tasks.add(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            ledgerService.adjustQuota(new QuotaAdjustRequest("ca", "s1", "RESEARCH", 1, 10L));
            return "ADJUST";
        });
        for (int i = 0; i < 3; i++) {
            String requestId = "cq-" + i;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ledgerService.restrictedQuery(new RestrictedQueryRequest(
                            requestId, "s1", "RESEARCH", 1,
                            List.of(new RestrictedQueryRequest.ObjectSpec("rec-a", 1L))));
                    return "OK";
                } catch (LedgerApiException ex) {
                    return ex.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long balance = balanceOf("s1", "RESEARCH", 1);
        long queryCount = count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'");
        // 无论提交顺序如何，绝不为负；按账本 seq 推导期望余额：
        // 调整条目把余额 SET 为 10；其后每条 QUERY 扣 1；调整前最多因初始额度 1 成交 1 条
        Long adjustSeq = jdbc.queryForObject(
                "SELECT seq FROM purpose_ledger WHERE operation = 'QUOTA_ADJUST' AND request_id = 'ca'",
                Long.class);
        assertThat(adjustSeq).isNotNull();
        long queriesAfterAdjust = count(
                "SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY' AND seq > ?", adjustSeq);
        long queriesBeforeAdjust = count(
                "SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY' AND seq < ?", adjustSeq);
        assertThat(queriesBeforeAdjust).isLessThanOrEqualTo(1L);
        assertThat(balance).isEqualTo(10L - queriesAfterAdjust);
        assertThat(queryCount).isEqualTo(queriesBeforeAdjust + queriesAfterAdjust);
        assertThat(results).contains("ADJUST");
    }

    @Test
    void concurrentRevokeAndQueryAreOrderedByCommit() throws Exception {
        grant("s1", "RESEARCH");
        writeRecord("s1", "RESEARCH", 1, "rec-a", "1");
        adjustQuota("q-1", "s1", "RESEARCH", 1, 10).andExpect(status().isOk());

        int queryThreads = 6;
        int total = queryThreads + 1;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < queryThreads; i++) {
            String requestId = "rq-c-" + i;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ledgerService.restrictedQuery(new RestrictedQueryRequest(
                            requestId, "s1", "RESEARCH", 1,
                            List.of(new RestrictedQueryRequest.ObjectSpec("rec-a", 1L))));
                    ok.incrementAndGet();
                } catch (LedgerApiException ex) {
                    assertThat(ex.getCode()).isEqualTo("CONSENT_REVOKED");
                    gone.incrementAndGet();
                }
                return null;
            });
        }
        tasks.add(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            revoke("s1", "RESEARCH", 1);
            return null;
        });
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long successCount = count("SELECT COUNT(*) FROM purpose_ledger WHERE operation = 'QUERY'");
        // 成功数 + 410 数 = 查询线程总数；余额恰好被成功查询扣减，撤回后无新增扣减
        assertThat((long) ok.get()).isEqualTo(successCount);
        assertThat(ok.get() + gone.get()).isEqualTo(queryThreads);
        assertThat(balanceOf("s1", "RESEARCH", 1)).isEqualTo(10L - successCount);
        // 撤回后再查一律 410
        restrictedQuery("rq-after", "s1", "RESEARCH", 1, "{\"recordKey\":\"rec-a\",\"count\":1}")
                .andExpect(status().isGone());
    }
}