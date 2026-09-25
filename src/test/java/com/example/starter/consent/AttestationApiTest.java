package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.AttestRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接收方证明与批次查询门禁集成测试：覆盖精确代次作用域、批次全拒绝、撤销前后快照、
 * 接收方禁用、证明历史与并发幂等边界，基于真实 H2（MySQL 兼容模式）数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttestationApiTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String FUTURE = "2026-01-01T01:00:00Z";
    private static final String FUTURE_LATER = "2026-01-01T02:00:00Z";

    @TestConfiguration
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AttestationService attestationService;

    @Autowired
    private QueryBatchService queryBatchService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM query_batch_item");
        jdbc.update("DELETE FROM query_batch");
        jdbc.update("DELETE FROM recipient_attestation");
        jdbc.update("DELETE FROM recipient_state");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.set(T0);
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions revokeGrant(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions attest(String attestKey, String recipientId, String purpose, int epoch,
                                 String expiresAt, String digest) throws Exception {
        return mockMvc.perform(post("/api/v1/recipients/attestations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"attestKey":"%s","recipientId":"%s","purpose":"%s","epoch":%d,"expiresAt":"%s","statementDigest":"%s"}
                        """.formatted(attestKey, recipientId, purpose, epoch, expiresAt, digest)));
    }

    private ResultActions revokeAttest(String requestId, String recipientId, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/recipients/attestations/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientId":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, recipientId, purpose, epoch)));
    }

    private ResultActions disable(String requestId, String recipientId) throws Exception {
        return mockMvc.perform(post("/api/v1/recipients/disables")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientId":"%s"}
                        """.formatted(requestId, recipientId)));
    }

    private ResultActions createBatch(String recipientId, String purpose, String... subjectKeys) throws Exception {
        String keys = java.util.Arrays.stream(subjectKeys)
                .map(key -> "\"" + key + "\"")
                .collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/v1/query-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"recipientId":"%s","purpose":"%s","subjectKeys":[%s]}
                        """.formatted(recipientId, purpose, keys)));
    }

    private ResultActions getBatch(long batchId) throws Exception {
        return mockMvc.perform(get("/api/v1/query-batches/{batchId}", batchId));
    }

    private long batchIdOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("batchId").asLong();
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 证明提交与续签 ----------

    @Test
    void attestSubmitCreatesActiveVersionOne() throws Exception {
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value("rcp-1"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.expiresAt").value(FUTURE))
                .andExpect(jsonPath("$.statementDigest").value("digest-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void renewalCreatesNewVersionAndSupersedesOld() throws Exception {
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        attest("ak-2", "rcp-1", "RESEARCH", 1, FUTURE_LATER, "digest-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 旧版本保留但不再生效，任一时刻仅一条生效证明
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'SUPERSEDED'")).isEqualTo(1);

        // 证明历史按版本倒序返回全部版本
        mockMvc.perform(get("/api/v1/recipients/{recipientId}/attestations", "rcp-1")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"));
    }

    @Test
    void attestWithPastExpiryReturns400AndDoesNotConsumeKey() throws Exception {
        attest("ak-1", "rcp-1", "RESEARCH", 1, "2025-12-31T23:00:00Z", "digest-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTESTATION_EXPIRES_IN_PAST"));
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ak-1'")).isEqualTo(0);

        // 失败不占键：同一 attestKey 修正参数后可成功
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void attestReplaySameKeySameParamsReturnsFirstResponse() throws Exception {
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 重放不生成新版本
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ak-1'")).isEqualTo(1);
    }

    @Test
    void attestSameKeyWithDifferentParamsReturns409() throws Exception {
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 批次查询门禁 ----------

    @Test
    void batchQuerySucceedsWithValidAttestationAndPersistsSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a").andExpect(status().isOk());
        write("w-2", "subj-b", "RESEARCH", "rec-1", "payload-b").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());

        MvcResult created = createBatch("rcp-1", "RESEARCH", "subj-b", "subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value("rcp-1"))
                .andExpect(jsonPath("$.items.length()").value(2))
                // 主体按字典序稳定排列
                .andExpect(jsonPath("$.items[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].attestationVersion").value(1))
                .andExpect(jsonPath("$.items[0].records[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.items[0].records[0].payload").value("payload-a"))
                .andExpect(jsonPath("$.items[1].subjectKey").value("subj-b"))
                .andReturn();
        long batchId = batchIdOf(created);

        // 快照已持久化，可整体读取
        getBatch(batchId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.items[0].attestationVersion").value(1))
                .andExpect(jsonPath("$.items[1].records[0].payload").value("payload-b"));
        assertThat(count("SELECT COUNT(*) FROM query_batch")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM query_batch_item")).isEqualTo(2);
    }

    @Test
    void batchQueryWithAnyMissingAttestationRejectsWholeBatch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // subj-b 授权换代到 epoch 2，而证明只覆盖 epoch 1：精确代次作用域下视为缺失
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        revokeGrant("r-1", "subj-b", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-3", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a").andExpect(status().isOk());
        // 仅 epoch 1 有证明；subj-b 当前代次缺证明；subj-c 无有效授权
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());

        createBatch("rcp-1", "RESEARCH", "subj-c", "subj-a", "subj-b")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_GATE_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.violations[0].reason").value("ATTESTATION_MISSING"))
                .andExpect(jsonPath("$.violations[1].subjectKey").value("subj-c"))
                .andExpect(jsonPath("$.violations[1].reason").value("NO_ACTIVE_GRANT"))
                // 不返回任何部分数据
                .andExpect(jsonPath("$.items").doesNotExist());
        // 整次拒绝：不落任何批次数据
        assertThat(count("SELECT COUNT(*) FROM query_batch")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM query_batch_item")).isEqualTo(0);
    }

    @Test
    void expiredAttestationBlocksBatch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 提交时刻为 T0，到期 T0+60s 合法
        attest("ak-1", "rcp-1", "RESEARCH", 1, "2026-01-01T00:01:00Z", "digest-1")
                .andExpect(status().isOk());
        createBatch("rcp-1", "RESEARCH", "subj-a").andExpect(status().isOk());

        // 时钟推进到到期之后：同一证明不再有效
        clock.advance(Duration.ofSeconds(120));
        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTESTATION_GATE_FAILED"))
                .andExpect(jsonPath("$.violations[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.violations[0].reason").value("ATTESTATION_EXPIRED"));
    }

    @Test
    void attestationIsScopedToExactEpochAndNotReusedAfterMigration() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        createBatch("rcp-1", "RESEARCH", "subj-a").andExpect(status().isOk());

        // 授权撤回后重新授权产生新代次（用途迁移/拆分场景）：旧代次证明不得复用
        revokeGrant("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.violations[0].reason").value("ATTESTATION_MISSING"));

        // 新代次需重新提交证明
        attest("ak-2", "rcp-1", "RESEARCH", 2, FUTURE, "digest-2").andExpect(status().isOk());
        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(2))
                .andExpect(jsonPath("$.items[0].attestationVersion").value(1));
    }

    @Test
    void renewedAttestationVersionIsUsedBySubsequentBatches() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        attest("ak-2", "rcp-1", "RESEARCH", 1, FUTURE_LATER, "digest-2").andExpect(status().isOk());

        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].attestationVersion").value(2));
    }

    // ---------- 撤销与快照不可改写 ----------

    @Test
    void revokeAttestationBlocksNewQueriesButKeepsSnapshotUnchanged() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        MvcResult created = createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isOk())
                .andReturn();
        long batchId = batchIdOf(created);

        revokeAttest("ar-1", "rcp-1", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.version").value(1));

        // 撤销仅影响后续查询
        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.violations[0].reason").value("ATTESTATION_MISSING"));

        // 已生成快照及其授权代次、证明版本不可改写
        getBatch(batchId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].attestationVersion").value(1))
                .andExpect(jsonPath("$.items[0].records[0].payload").value("payload-a"));
    }

    @Test
    void revokeAttestationReplayAlreadyRevokedAndNotFound() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());

        revokeAttest("ar-1", "rcp-1", "RESEARCH", 1).andExpect(status().isOk());
        // 同 requestId 重放返回首次结果
        revokeAttest("ar-1", "rcp-1", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 新 requestId 撤销已撤销证明返回 409
        revokeAttest("ar-2", "rcp-1", "RESEARCH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTESTATION_ALREADY_REVOKED"));
        // 从未存在的证明返回 404
        revokeAttest("ar-3", "rcp-1", "RESEARCH", 9)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTESTATION_NOT_FOUND"));
    }

    @Test
    void snapshotRemainsImmutableAfterRegrantAndNewWrites() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "old-payload").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        MvcResult created = createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isOk())
                .andReturn();
        long batchId = batchIdOf(created);

        // 授权换代并写入新数据、证明续签，均不得改写旧快照
        revokeGrant("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "new-payload").andExpect(status().isOk());
        attest("ak-2", "rcp-1", "RESEARCH", 2, FUTURE, "digest-2").andExpect(status().isOk());

        getBatch(batchId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].attestationVersion").value(1))
                .andExpect(jsonPath("$.items[0].records[0].payload").value("old-payload"));
    }

    // ---------- 接收方整体禁用 ----------

    @Test
    void disabledRecipientForbidsAllNewQueriesEvenWithValidAttestation() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        attest("ak-1", "rcp-1", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        createBatch("rcp-1", "RESEARCH", "subj-a").andExpect(status().isOk());

        disable("d-1", "rcp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId").value("rcp-1"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // 证明仍有效，但接收方被整体禁用：所有新查询 403
        createBatch("rcp-1", "RESEARCH", "subj-a")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECIPIENT_DISABLED"));
        // 禁用不影响其他接收方
        attest("ak-2", "rcp-2", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());
        createBatch("rcp-2", "RESEARCH", "subj-a").andExpect(status().isOk());
        // 同 requestId 重放返回首次结果
        disable("d-1", "rcp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    // ---------- 404 与 400 分支 ----------

    @Test
    void getMissingBatchReturns404() throws Exception {
        getBatch(99999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    @Test
    void emptySubjectKeysReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/query-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"rcp-1\",\"purpose\":\"RESEARCH\",\"subjectKeys\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 并发与幂等边界 ----------

    @Test
    void concurrentRenewalsProduceUniqueVersionsAndSingleActive() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<AttestationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String attestKey = "ak-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestationService.attest(new AttestRequest(
                        attestKey, "rcp-c", Purpose.RESEARCH, 1, T0.plusSeconds(3600), "digest-" + attestKey));
            });
        }
        List<Future<AttestationResponse>> futures = new ArrayList<>();
        for (Callable<AttestationResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        Set<Integer> versions = new HashSet<>();
        for (Future<AttestationResponse> future : futures) {
            versions.add(future.get(30, TimeUnit.SECONDS).version());
        }
        pool.shutdown();
        // 并发续签按事务提交顺序裁决：版本唯一且连续，仅一条生效
        assertThat(versions).hasSize(threads);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE recipient_id = 'rcp-c'")).isEqualTo(threads);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE recipient_id = 'rcp-c' AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameAttestKeyReplaysFirstResponseWithSingleVersion() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<AttestationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestationService.attest(new AttestRequest(
                        "ak-same", "rcp-s", Purpose.RESEARCH, 1, T0.plusSeconds(3600), "digest-1"));
            });
        }
        List<Future<AttestationResponse>> futures = new ArrayList<>();
        for (Callable<AttestationResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<AttestationResponse> future : futures) {
            AttestationResponse response = future.get(30, TimeUnit.SECONDS);
            // 同键同参并发重放：所有调用方都得到首次完整响应
            assertThat(response.version()).isEqualTo(1);
            assertThat(response.status()).isEqualTo(AttestationStatus.ACTIVE);
        }
        pool.shutdown();
        // 仅产生一条证明版本与一条幂等记录
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE recipient_id = 'rcp-s'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'ak-same'")).isEqualTo(1);
    }

    @Test
    void concurrentDisableAndQueriesAreAdjudicatedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        attest("ak-1", "rcp-d", "RESEARCH", 1, FUTURE, "digest-1").andExpect(status().isOk());

        int queryThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(queryThreads + 1);
        CountDownLatch ready = new CountDownLatch(queryThreads + 1);
        CountDownLatch start = new CountDownLatch(1);

        record Outcome(Long batchId, String errorCode) {
        }
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < queryThreads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    BatchQueryResponse response = queryBatchService.create(
                            new BatchQueryRequest("rcp-d", Purpose.RESEARCH, List.of("subj-a")));
                    return new Outcome(response.batchId(), null);
                } catch (ApiException e) {
                    return new Outcome(null, e.getCode());
                }
            });
        }
        tasks.add(() -> {
            ready.countDown();
            start.await();
            attestationService.disable(new RecipientDisableRequest("d-concurrent", "rcp-d"));
            return new Outcome(null, null);
        });

        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int successes = 0;
        int forbidden = 0;
        for (Future<Outcome> future : futures) {
            Outcome outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome.batchId() != null) {
                successes++;
            } else if (outcome.errorCode() != null) {
                // 禁用提交后的查询只能以 RECIPIENT_DISABLED 失败
                assertThat(outcome.errorCode()).isEqualTo(QueryBatchService.CODE_RECIPIENT_DISABLED);
                forbidden++;
            }
        }
        pool.shutdown();
        assertThat(successes + forbidden).isEqualTo(queryThreads);
        // 每个成功的查询都留下了不可改写快照；失败的不落任何数据
        assertThat(count("SELECT COUNT(*) FROM query_batch WHERE recipient_id = 'rcp-d'")).isEqualTo(successes);
        assertThat(count("SELECT COUNT(*) FROM recipient_state WHERE recipient_id = 'rcp-d' AND disabled = TRUE"))
                .isEqualTo(1);
        // 禁用生效后新查询一律 403
        createBatch("rcp-d", "RESEARCH", "subj-a")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECIPIENT_DISABLED"));
    }
}
