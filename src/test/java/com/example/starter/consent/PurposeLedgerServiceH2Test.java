package com.example.starter.consent;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.LedgerEntryResponse;
import com.example.starter.consent.dto.PurposeQueryRequest;
import com.example.starter.consent.dto.PurposeQueryResponse;
import com.example.starter.consent.dto.Queries;
import com.example.starter.consent.dto.QuotaAdjustRequest;
import com.example.starter.consent.dto.QuotaResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权用途账本 H2 集成测试：覆盖主流程、403/422/410 失败分支、事务原子性、
 * 并发不超扣、幂等重放与边界、UTC 左闭右开区间、证据不可变及只读语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class PurposeLedgerServiceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private PurposeLedgerService ledgerService;

    @Autowired
    private MutableClockConfig.MutableClock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM purpose_query_ledger");
        jdbc.update("DELETE FROM purpose_quota");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.set(Instant.parse("2026-09-29T00:00:00Z"));
    }

    // ---------- 辅助方法 ----------

    private void grant(String requestId, String subject, Purpose purpose) {
        consentService.grant(new GrantRequest(requestId, subject, purpose));
    }

    private void writeRecord(String requestId, String subject, Purpose purpose, String key, String payload) {
        consentService.write(new RecordWriteRequest(requestId, subject, purpose, key, payload));
    }

    private QuotaResponse adjust(String requestId, String subject, Purpose purpose, int epoch, long limit) {
        return ledgerService.adjustQuota(new QuotaAdjustRequest(requestId, subject, purpose, epoch, limit));
    }

    private PurposeQueryRequest queryReq(String requestId, String subject, Purpose purpose,
                                         int epoch, int quantity, List<String> keys) {
        return new PurposeQueryRequest(requestId, subject, purpose, epoch, quantity, keys);
    }

    private long usedAmount(String subject, Purpose purpose, int epoch) {
        Long used = jdbc.queryForObject(
                "SELECT used_amount FROM purpose_quota WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Long.class, subject, purpose.name(), epoch);
        return used == null ? -1L : used;
    }

    private long ledgerCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM purpose_query_ledger", Long.class);
        return count == null ? 0L : count;
    }

    private long idempotencyCount(String requestId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = ?", Long.class, requestId);
        return count == null ? 0L : count;
    }

    private org.springframework.test.web.servlet.ResultActions httpQuery(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/purpose-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---------- 主流程 ----------

    @Test
    void declaredQuotaAndSuccessfulQueryDeductsAtomicallyAndWritesLedger() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "payload-1");
        writeRecord("w-2", "subj-a", Purpose.RESEARCH, "rec-2", "payload-2");
        QuotaResponse quota = adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 5L);
        assertThat(quota.declared()).isTrue();
        assertThat(quota.quotaLimit()).isEqualTo(5L);
        assertThat(quota.remainingBalance()).isEqualTo(5L);

        PurposeQueryResponse response = ledgerService.query(queryReq(
                "query-1", "subj-a", Purpose.RESEARCH, 1, 2, List.of("rec-2", "rec-1")));

        // 实际数量、扣减后余额、结果按对象键字典序稳定返回
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.remainingBalance()).isEqualTo(3L);
        assertThat(response.records()).extracting(r -> r.recordKey())
                .containsExactly("rec-1", "rec-2");
        assertThat(response.records()).extracting(r -> r.payload())
                .containsExactly("payload-1", "payload-2");
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(2L);
        assertThat(ledgerCount()).isEqualTo(1L);

        List<LedgerEntryResponse> entries = ledgerService.listEntries("subj-a", Purpose.RESEARCH, 1);
        assertThat(entries).hasSize(1);
        LedgerEntryResponse entry = entries.get(0);
        assertThat(entry.objectCount()).isEqualTo(2);
        assertThat(entry.balanceBefore()).isEqualTo(5L);
        assertThat(entry.balanceAfter()).isEqualTo(3L);
        assertThat(entry.quotaLimitAtDeduction()).isEqualTo(5L);
        assertThat(entry.canonicalSetDigest()).hasSize(64);
        assertThat(entry.fingerprint()).hasSize(64);
        assertThat(entry.occurredAtUtc()).isEqualTo("2026-09-29T00:00:00Z");
    }

    @Test
    void purposesAndEpochsAreCountedIndependently() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        grant("g-2", "subj-a", Purpose.PERSONALIZATION);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "r");
        writeRecord("w-2", "subj-a", Purpose.PERSONALIZATION, "rec-1", "p");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 2L);
        adjust("q-2", "subj-a", Purpose.PERSONALIZATION, 1, 10L);

        ledgerService.query(queryReq("query-r", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
        ledgerService.query(queryReq("query-p", "subj-a", Purpose.PERSONALIZATION, 1, 1, List.of("rec-1")));

        // 用途独立计数：各自余额互不影响
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(usedAmount("subj-a", Purpose.PERSONALIZATION, 1)).isEqualTo(1L);
        assertThat(ledgerService.getQuota("subj-a", Purpose.RESEARCH, 1).remainingBalance()).isEqualTo(1L);
        assertThat(ledgerService.getQuota("subj-a", Purpose.PERSONALIZATION, 1).remainingBalance()).isEqualTo(9L);

        // epoch 间独立：撤回 epoch1 后重新授权 epoch2，额度重新声明、独立计数
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));
        grant("g-3", "subj-a", Purpose.RESEARCH);
        writeRecord("w-3", "subj-a", Purpose.RESEARCH, "rec-1", "r2");
        adjust("q-3", "subj-a", Purpose.RESEARCH, 2, 4L);
        PurposeQueryResponse response = ledgerService.query(queryReq(
                "query-r2", "subj-a", Purpose.RESEARCH, 2, 1, List.of("rec-1")));
        assertThat(response.epoch()).isEqualTo(2);
        assertThat(response.remainingBalance()).isEqualTo(3L);
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 2)).isEqualTo(1L);
        // epoch1 账本固化，不被新 epoch 操作影响
        assertThat(ledgerService.listEntries("subj-a", Purpose.RESEARCH, 1)).hasSize(1);
    }

    // ---------- 失败分支：403 / 422 / 410 ----------

    @Test
    void undeclaredPurposeReturns403AndDoesNotDeductOrWriteLedger() throws Exception {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "payload-1");

        httpQuery("""
                {"requestId":"query-1","subjectKey":"subj-a","purpose":"RESEARCH","epoch":1,
                 "quantity":1,"recordKeys":["rec-1"]}
                """)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_DECLARED"))
                .andExpect(jsonPath("$.details[0].reason").value("PURPOSE_NOT_DECLARED"))
                .andExpect(jsonPath("$.details[0].requiredValue").value(1))
                .andExpect(jsonPath("$.details[0].difference").value(1));

        assertThat(ledgerCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purpose_quota", Long.class)).isEqualTo(0L);
        // 失败不占 requestId：之后声明额度再用同一 requestId 可以成功
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 1L);
        assertThat(ledgerService.query(queryReq(
                "query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1"))).quantity()).isEqualTo(1);
        assertThat(idempotencyCount("query-1")).isEqualTo(1L);
    }

    @Test
    void insufficientQuotaReturns403WithActualRequiredDifferenceAndNoPartialResult() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-a", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 3L);
        ledgerService.query(queryReq("query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1")));

        // 已用 1：把总额度下调到 2，剩余仅 1；本次要求 2，整体失败不扣减不记账
        adjust("q-2", "subj-a", Purpose.RESEARCH, 1, 2L);
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-2", "subj-a", Purpose.RESEARCH, 1, 2, List.of("rec-1", "rec-2"))));
        assertThat(ex.getStatus().value()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo("QUOTA_INSUFFICIENT");
        assertThat(ex.getDetails()).hasSize(1);
        assertThat(ex.getDetails().get(0).actualValue()).isEqualTo(1L);
        assertThat(ex.getDetails().get(0).requiredValue()).isEqualTo(2L);
        assertThat(ex.getDetails().get(0).difference()).isEqualTo(1L);

        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(1L);
        assertThat(idempotencyCount("query-2")).isZero();
    }

    @Test
    void objectFromOtherEpochReturns422WithPerObjectReasonAndNoDeduction() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "epoch1-data");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));
        grant("g-2", "subj-a", Purpose.RESEARCH);
        adjust("q-2", "subj-a", Purpose.RESEARCH, 2, 10L);
        // rec-1 物理保留在 epoch1；epoch2 未写该对象

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-x", "subj-a", Purpose.RESEARCH, 2, 1, List.of("rec-1"))));
        assertThat(ex.getStatus().value()).isEqualTo(422);
        assertThat(ex.getCode()).isEqualTo("OBJECT_CROSS_EPOCH");
        assertThat(ex.getDetails()).hasSize(1);
        assertThat(ex.getDetails().get(0).reason()).isEqualTo(QueryFailureCode.OBJECT_CROSS_EPOCH);
        assertThat(ex.getDetails().get(0).recordKey()).isEqualTo("rec-1");
        assertThat(ex.getDetails().get(0).actualValue()).isEqualTo(1L);
        assertThat(ex.getDetails().get(0).requiredValue()).isEqualTo(2L);
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 2)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void missingObjectReturns422AndBatchNeverReturnsPartialData() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);

        // 一个存在、一个不存在：整单失败，不返回 rec-1 的部分数据
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-1", "subj-a", Purpose.RESEARCH, 1, 2, List.of("rec-1", "rec-missing"))));
        assertThat(ex.getStatus().value()).isEqualTo(422);
        assertThat(ex.getCode()).isEqualTo("OBJECT_NOT_FOUND");
        assertThat(ex.getDetails()).hasSize(1);
        assertThat(ex.getDetails().get(0).reason()).isEqualTo(QueryFailureCode.OBJECT_NOT_FOUND);
        assertThat(ex.getDetails().get(0).recordKey()).isEqualTo("rec-missing");
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void quantityMismatchReturns422WithActualRequiredDifference() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-a", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-1", "subj-a", Purpose.RESEARCH, 1, 3, List.of("rec-1", "rec-2"))));
        assertThat(ex.getStatus().value()).isEqualTo(422);
        assertThat(ex.getCode()).isEqualTo("QUANTITY_MISMATCH");
        assertThat(ex.getDetails().get(0).actualValue()).isEqualTo(2L);
        assertThat(ex.getDetails().get(0).requiredValue()).isEqualTo(3L);
        assertThat(ex.getDetails().get(0).difference()).isEqualTo(1L);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void duplicateKeysReturn422() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-1", "subj-a", Purpose.RESEARCH, 1, 2, List.of("rec-1", "rec-1"))));
        assertThat(ex.getStatus().value()).isEqualTo(422);
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_OBJECT");
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount("query-1")).isZero();
    }

    @Test
    void queryAfterRevokeReturns410AndNeverDeducts() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1"))));
        assertThat(ex.getStatus().value()).isEqualTo(410);
        assertThat(ex.getCode()).isEqualTo("CONSENT_REVOKED");
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void quotaAdjustmentOnRevokedEpochReturns410AndBelowUsedReturns422() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 5L);
        ledgerService.query(queryReq("query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1")));

        // 已核销 1：调低到 0（低于已用）拒绝并给差额
        ApiException below = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> adjust("q-2", "subj-a", Purpose.RESEARCH, 1, 0L));
        assertThat(below.getStatus().value()).isEqualTo(422);
        assertThat(below.getCode()).isEqualTo("QUOTA_LIMIT_BELOW_USED");
        assertThat(below.getDetails().get(0).actualValue()).isEqualTo(0L);
        assertThat(below.getDetails().get(0).requiredValue()).isEqualTo(1L);
        assertThat(below.getDetails().get(0).difference()).isEqualTo(1L);
        // 调整失败，额度保持 5
        assertThat(ledgerService.getQuota("subj-a", Purpose.RESEARCH, 1).quotaLimit()).isEqualTo(5L);

        // 调高成功且不影响已扣减记录
        adjust("q-3", "subj-a", Purpose.RESEARCH, 1, 8L);
        assertThat(ledgerService.getQuota("subj-a", Purpose.RESEARCH, 1).remainingBalance()).isEqualTo(7L);

        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));
        ApiException gone = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> adjust("q-4", "subj-a", Purpose.RESEARCH, 1, 20L));
        assertThat(gone.getStatus().value()).isEqualTo(410);
    }

    @Test
    void clearOnlyAfterRevokeAndNeverTouchesLedger() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-a", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);
        ledgerService.query(queryReq("query-1", "subj-a", Purpose.RESEARCH, 1, 2, List.of("rec-1", "rec-2")));

        // 未撤回不能清除
        ApiException notRevoked = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.clear(new com.example.starter.consent.dto.ClearRequest(
                        "c-1", "subj-a", Purpose.RESEARCH, 1)));
        assertThat(notRevoked.getStatus().value()).isEqualTo(409);

        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));
        var first = ledgerService.clear(new com.example.starter.consent.dto.ClearRequest(
                "c-1", "subj-a", Purpose.RESEARCH, 1));
        assertThat(first.clearedRecords()).isEqualTo(2);
        // 重放：返回首次快照（首次清除数 2），不重复变更
        var replay = ledgerService.clear(new com.example.starter.consent.dto.ClearRequest(
                "c-1", "subj-a", Purpose.RESEARCH, 1));
        assertThat(replay.clearedRecords()).isEqualTo(2);
        // 新 requestId 再清除：实际新标记 0
        var second = ledgerService.clear(new com.example.starter.consent.dto.ClearRequest(
                "c-2", "subj-a", Purpose.RESEARCH, 1));
        assertThat(second.clearedRecords()).isZero();

        // 记录物理保留、账本不受影响
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_record", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE cleared_at IS NOT NULL", Long.class)).isEqualTo(2L);
        assertThat(ledgerCount()).isEqualTo(1L);
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysFirstSnapshotWithoutSecondDeduction() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);

        PurposeQueryRequest request = queryReq(
                "query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1"));
        PurposeQueryResponse first = ledgerService.query(request);
        PurposeQueryResponse replay = ledgerService.query(request);

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        // 重放首次完整成功响应快照：扣减后余额也固定为首次的 9
        assertThat(replay.remainingBalance()).isEqualTo(9L);
        assertThat(first.remainingBalance()).isEqualTo(9L);
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(1L);
        assertThat(idempotencyCount("query-1")).isEqualTo(1L);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-a", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);
        ledgerService.query(queryReq("query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1")));

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(queryReq(
                        "query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-2"))));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(1L);
    }

    @Test
    void replayAfterRevokeReturns410AndDoesNotReturnSnapshot() {
        grant("g-1", "subj-a", Purpose.RESEARCH);
        writeRecord("w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-a", Purpose.RESEARCH, 1, 10L);
        PurposeQueryRequest request = queryReq(
                "query-1", "subj-a", Purpose.RESEARCH, 1, 1, List.of("rec-1"));
        ledgerService.query(request);
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-a", Purpose.RESEARCH, 1));

        // 即使 requestId 命中成功快照，撤回后仍 410，且不再次扣减
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.query(request));
        assertThat(ex.getStatus().value()).isEqualTo(410);
        assertThat(usedAmount("subj-a", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(1L);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentQueriesNeverOverDeduct() throws Exception {
        grant("g-1", "subj-conc", Purpose.RESEARCH);
        writeRecord("w-1", "subj-conc", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-conc", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-conc", Purpose.RESEARCH, 1, 10L);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "query-conc-" + i;
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ledgerService.query(queryReq(
                            requestId, "subj-conc", Purpose.RESEARCH, 1, 2, List.of("rec-1", "rec-2")));
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus().value();
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int success = 0;
        int forbidden = 0;
        for (Future<Integer> future : futures) {
            int code = future.get(30, TimeUnit.SECONDS);
            assertThat(code).isIn(200, 403);
            if (code == 200) {
                success++;
            } else {
                forbidden++;
            }
        }
        pool.shutdown();

        // 每次核销 2、总额度 10：恰好 5 次成功、3 次 403，绝无超扣
        assertThat(success).isEqualTo(5);
        assertThat(forbidden).isEqualTo(3);
        assertThat(usedAmount("subj-conc", Purpose.RESEARCH, 1)).isEqualTo(10L);
        assertThat(ledgerService.getQuota("subj-conc", Purpose.RESEARCH, 1).remainingBalance()).isZero();
        assertThat(ledgerCount()).isEqualTo(5L);
    }

    @Test
    void concurrentSameRequestIdDeductsOnceAndEveryoneReplaysFirstSnapshot() throws Exception {
        grant("g-1", "subj-replay", Purpose.RESEARCH);
        writeRecord("w-1", "subj-replay", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-replay", Purpose.RESEARCH, 1, 10L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<PurposeQueryResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return ledgerService.query(queryReq(
                        "same-query", "subj-replay", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
            });
        }
        List<Future<PurposeQueryResponse>> futures = new ArrayList<>();
        for (Callable<PurposeQueryResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<PurposeQueryResponse> future : futures) {
            PurposeQueryResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.requestId()).isEqualTo("same-query");
            assertThat(response.remainingBalance()).isEqualTo(9L);
        }
        pool.shutdown();

        assertThat(usedAmount("subj-replay", Purpose.RESEARCH, 1)).isEqualTo(1L);
        assertThat(ledgerCount()).isEqualTo(1L);
        assertThat(idempotencyCount("same-query")).isEqualTo(1L);
    }

    @Test
    void concurrentRevokeAndQueryAreDecidedByCommitOrderWithoutInconsistentState() throws Exception {
        // 多次跑提高两种提交顺序都被覆盖的概率，但任一顺序下最终不变量必须成立
        for (int round = 0; round < 5; round++) {
            final int r = round;
            String subjectRaw = "subj-race-" + r;
            final String subject = subjectRaw;
            grant("g-" + r, subject, Purpose.RESEARCH);
            writeRecord("w-" + r, subject, Purpose.RESEARCH, "rec-1", "p1");
            adjust("q-" + r, subject, Purpose.RESEARCH, 1, 10L);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> queryFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    ledgerService.query(queryReq("query-race-" + r, subject,
                            Purpose.RESEARCH, 1, 1, List.of("rec-1")));
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus().value();
                }
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                        "revoke-race-" + r, subject, Purpose.RESEARCH, 1));
                return 200;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int queryCode = queryFuture.get(30, TimeUnit.SECONDS);
            assertThat(revokeFuture.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            pool.shutdown();

            long used = usedAmount(subject, Purpose.RESEARCH, 1);
            long entries = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM purpose_query_ledger WHERE subject_key = ?", Long.class, subject);
            if (queryCode == 200) {
                // 查询先提交：扣减与账本各 1
                assertThat(used).isEqualTo(1L);
                assertThat(entries).isEqualTo(1L);
            } else {
                // 撤回先提交：查询 410，未扣减未记账
                assertThat(queryCode).isEqualTo(410);
                assertThat(used).isZero();
                assertThat(entries).isZero();
            }
            // 终态一致：已撤回，之后任何查询都 410 且不扣减
            ApiException after = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> ledgerService.query(queryReq("query-after-" + r, subject,
                            Purpose.RESEARCH, 1, 1, List.of("rec-1"))));
            assertThat(after.getStatus().value()).isEqualTo(410);
            assertThat(usedAmount(subject, Purpose.RESEARCH, 1)).isEqualTo(used);
        }
    }

    // ---------- 时间区间、排序、证据不可变、只读 ----------

    @Test
    void historyWindowIsUtcHalfOpenWithStableOrderingAndDoesNotMutateState() {
        grant("g-1", "subj-h", Purpose.RESEARCH);
        for (int i = 1; i <= 3; i++) {
            writeRecord("w-" + i, "subj-h", Purpose.RESEARCH, "rec-" + i, "p" + i);
        }
        adjust("q-1", "subj-h", Purpose.RESEARCH, 1, 100L);

        // t1 时刻两条（同刻按顺序号决胜），t2 时刻一条
        clock.set(Instant.parse("2026-09-29T10:00:00Z"));
        ledgerService.query(queryReq("h-1", "subj-h", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
        ledgerService.query(queryReq("h-2", "subj-h", Purpose.RESEARCH, 1, 1, List.of("rec-2")));
        clock.set(Instant.parse("2026-09-29T11:00:00Z"));
        ledgerService.query(queryReq("h-3", "subj-h", Purpose.RESEARCH, 1, 1, List.of("rec-3")));

        // 左闭右开：[10:00, 11:00) 只含前两条，且同刻按顺序号升序
        Queries.HistoryWindow window = ledgerService.history("subj-h", Purpose.RESEARCH, null,
                "2026-09-29T10:00:00Z", "2026-09-29T11:00:00Z");
        assertThat(window.count()).isEqualTo(2);
        assertThat(window.entries()).extracting(e -> e.requestId()).containsExactly("h-1", "h-2");
        // 上界不含 11:00；下界含 11:00
        Queries.HistoryWindow fromEleven = ledgerService.history("subj-h", Purpose.RESEARCH, null,
                "2026-09-29T11:00:00Z", null);
        assertThat(fromEleven.entries()).extracting(e -> e.requestId()).containsExactly("h-3");
        // 时间精度为毫秒
        assertThat(window.entries().get(0).occurredAtUtc()).isEqualTo("2026-09-29T10:00:00Z");

        // 非法区间与非法时间
        ApiException badRange = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.history("subj-h", Purpose.RESEARCH, null,
                        "2026-09-29T12:00:00Z", "2026-09-29T11:00:00Z"));
        assertThat(badRange.getCode()).isEqualTo("INVALID_TIME_RANGE");
        ApiException badTime = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> ledgerService.history("subj-h", Purpose.RESEARCH, null, "not-a-time", null));
        assertThat(badTime.getCode()).isEqualTo("INVALID_TIME_RANGE");

        // 只读：重复读取不改变余额与账本
        long ledgerBefore = ledgerCount();
        ledgerService.history("subj-h", Purpose.RESEARCH, null, null, null);
        ledgerService.listEntries("subj-h", Purpose.RESEARCH, 1);
        ledgerService.diagnostics("subj-h", Purpose.RESEARCH);
        ledgerService.getQuota("subj-h", Purpose.RESEARCH, 1);
        assertThat(ledgerCount()).isEqualTo(ledgerBefore);
        assertThat(usedAmount("subj-h", Purpose.RESEARCH, 1)).isEqualTo(3L);
    }

    @Test
    void ledgerEvidenceIsImmutableAfterAdjustmentAndRevokeAndSnapshotRoundtrips() throws Exception {
        grant("g-1", "subj-e", Purpose.RESEARCH);
        writeRecord("w-1", "subj-e", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-e", Purpose.RESEARCH, 1, 5L);
        PurposeQueryResponse first = ledgerService.query(queryReq(
                "e-1", "subj-e", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
        LedgerEntryResponse before = ledgerService.listEntries("subj-e", Purpose.RESEARCH, 1).get(0);

        // 后续额度调整与撤回
        adjust("q-2", "subj-e", Purpose.RESEARCH, 1, 50L);
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-e", Purpose.RESEARCH, 1));
        LedgerEntryResponse after = ledgerService.getEntry(before.ledgerSeq());

        assertThat(after).isEqualTo(before);
        assertThat(after.balanceBefore()).isEqualTo(5L);
        assertThat(after.balanceAfter()).isEqualTo(4L);
        assertThat(after.quotaLimitAtDeduction()).isEqualTo(5L);
        // 查询快照可还原为首次完整成功响应
        PurposeQueryResponse snapshotted =
                objectMapper.readValue(after.querySnapshot(), PurposeQueryResponse.class);
        assertThat(snapshotted).isEqualTo(first);
    }

    @Test
    void fingerprintsDifferAcrossOperatorsAndReadsExposeDiagnostics() {
        grant("g-1", "subj-x", Purpose.RESEARCH);
        grant("g-2", "subj-y", Purpose.RESEARCH);
        writeRecord("w-1", "subj-x", Purpose.RESEARCH, "rec-1", "p");
        writeRecord("w-2", "subj-y", Purpose.RESEARCH, "rec-1", "p");
        adjust("q-1", "subj-x", Purpose.RESEARCH, 1, 5L);
        adjust("q-2", "subj-y", Purpose.RESEARCH, 1, 5L);
        ledgerService.query(queryReq("f-1", "subj-x", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
        ledgerService.query(queryReq("f-2", "subj-y", Purpose.RESEARCH, 1, 1, List.of("rec-1")));

        String fingerprintX = ledgerService.listEntries("subj-x", Purpose.RESEARCH, 1).get(0).fingerprint();
        String fingerprintY = ledgerService.listEntries("subj-y", Purpose.RESEARCH, 1).get(0).fingerprint();
        // 操作者不同，指纹必须不同
        assertThat(fingerprintX).isNotEqualTo(fingerprintY);

        Queries.Diagnostics diagnostics = ledgerService.diagnostics("subj-x", Purpose.RESEARCH);
        assertThat(diagnostics.subjectKey()).isEqualTo("subj-x");
        assertThat(diagnostics.epochs()).hasSize(1);
        assertThat(diagnostics.epochs().get(0).status()).isEqualTo("ACTIVE");
        assertThat(diagnostics.epochs().get(0).ledgerEntries()).isEqualTo(1L);
        assertThat(diagnostics.totalLedgerEntries()).isEqualTo(1L);
    }

    @Test
    void quotaReadEndpointsExposeBalancesOverHttp() throws Exception {
        grant("g-1", "subj-h2", Purpose.RESEARCH);
        writeRecord("w-1", "subj-h2", Purpose.RESEARCH, "rec-1", "p1");
        adjust("q-1", "subj-h2", Purpose.RESEARCH, 1, 3L);
        ledgerService.query(queryReq("query-1", "subj-h2", Purpose.RESEARCH, 1, 1, List.of("rec-1")));

        mockMvc.perform(get("/api/v1/purpose-quotas")
                        .param("subjectKey", "subj-h2")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declared").value(true))
                .andExpect(jsonPath("$.quotaLimit").value(3))
                .andExpect(jsonPath("$.usedAmount").value(1))
                .andExpect(jsonPath("$.remainingBalance").value(2));

        // 未声明用途：declared=false，数值字段为 null（null 含义稳定）
        mockMvc.perform(get("/api/v1/purpose-quotas")
                        .param("subjectKey", "subj-h2")
                        .param("purpose", "PERSONALIZATION")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declared").value(false))
                .andExpect(jsonPath("$.quotaLimit").doesNotExist());
    }

    @Test
    void crossEpochFailureOverHttpExposesLocatableDetails() throws Exception {
        grant("g-1", "subj-http", Purpose.RESEARCH);
        writeRecord("w-1", "subj-http", Purpose.RESEARCH, "rec-1", "epoch1");
        adjust("q-1", "subj-http", Purpose.RESEARCH, 1, 10L);
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-1", "subj-http", Purpose.RESEARCH, 1));
        grant("g-2", "subj-http", Purpose.RESEARCH);
        adjust("q-2", "subj-http", Purpose.RESEARCH, 2, 10L);

        mockMvc.perform(post("/api/v1/purpose-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"query-x","subjectKey":"subj-http","purpose":"RESEARCH",
                                 "epoch":2,"quantity":1,"recordKeys":["rec-1"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJECT_CROSS_EPOCH"))
                .andExpect(jsonPath("$.details[0].reason").value("OBJECT_CROSS_EPOCH"))
                .andExpect(jsonPath("$.details[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.details[0].actualValue").value(1))
                .andExpect(jsonPath("$.details[0].requiredValue").value(2));
    }

    @Test
    void fingerprintCoversBalancesAndSnapshotAndChangesAcrossDeductions() {
        grant("g-1", "subj-fp", Purpose.RESEARCH);
        writeRecord("w-1", "subj-fp", Purpose.RESEARCH, "rec-1", "p1");
        writeRecord("w-2", "subj-fp", Purpose.RESEARCH, "rec-2", "p2");
        adjust("q-1", "subj-fp", Purpose.RESEARCH, 1, 10L);

        ledgerService.query(queryReq("fp-1", "subj-fp", Purpose.RESEARCH, 1, 1, List.of("rec-1")));
        ledgerService.query(queryReq("fp-2", "subj-fp", Purpose.RESEARCH, 1, 1, List.of("rec-2")));

        List<LedgerEntryResponse> entries = ledgerService.listEntries("subj-fp", Purpose.RESEARCH, 1);
        assertThat(entries).hasSize(2);
        LedgerEntryResponse first = entries.get(0);
        LedgerEntryResponse second = entries.get(1);
        // 顺序号、扣减前后余额不同，指纹不同；顺序号单调递增、排序稳定
        assertThat(first.ledgerSeq()).isLessThan(second.ledgerSeq());
        assertThat(first.balanceBefore()).isEqualTo(10L);
        assertThat(first.balanceAfter()).isEqualTo(9L);
        assertThat(second.balanceBefore()).isEqualTo(9L);
        assertThat(second.balanceAfter()).isEqualTo(8L);
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
        assertThat(first.canonicalSetDigest()).isNotEqualTo(second.canonicalSetDigest());
        // 快照包含全部结果相关字段
        assertThat(first.querySnapshot()).contains("\"requestId\":\"fp-1\"")
                .contains("\"remainingBalance\":9")
                .contains("\"recordKey\":\"rec-1\"");
    }
}
