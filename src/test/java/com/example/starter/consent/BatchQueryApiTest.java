package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.consent.dto.BatchQueryItem;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 固定授权代次原子批量查询测试：覆盖主流程、404/410/409/400 失败分支、
 * 幂等重放（含撤回后重放拒绝）以及基于真实 H2 行锁的撤回/查询提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchQueryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ConsentRepository consentRepository;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    @Autowired
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
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

    private ResultActions revokeHttp(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions batch(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/records/batch-query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private BatchQueryRequest batchRequest(String requestId, Purpose purpose, Object... items) {
        List<BatchQueryItem> list = new ArrayList<>();
        for (int i = 0; i < items.length; i += 3) {
            list.add(new BatchQueryItem((String) items[i], (Integer) items[i + 1], (String) items[i + 2]));
        }
        return new BatchQueryRequest(requestId, purpose, list);
    }

    private String batchBody(String requestId, String purpose, Object... items) {
        StringBuilder builder = new StringBuilder("""
                {"requestId":"%s","purpose":"%s","items":[""".formatted(requestId, purpose));
        for (int i = 0; i < items.length; i += 3) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("""
                    {"subjectKey":"%s","expectedEpoch":%d,"recordKey":"%s"}"""
                    .formatted(items[i], items[i + 1], items[i + 2]));
        }
        return builder.append("]}").toString();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void seedTwoSubjectsWithRecords() throws Exception {
        grant("g-a", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-b", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-a1", "subj-a", "RESEARCH", "rec-a1", "payload-a1").andExpect(status().isOk());
        write("w-a2", "subj-a", "RESEARCH", "rec-a2", "payload-a2").andExpect(status().isOk());
        write("w-b1", "subj-b", "RESEARCH", "rec-b1", "payload-b1").andExpect(status().isOk());
    }

    // ---------- 主流程 ----------

    @Test
    void batchQuerySuccessReturnsResultsInInputOrder() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-1", "RESEARCH",
                "subj-b", 1, "rec-b1",
                "subj-a", 1, "rec-a2",
                "subj-a", 1, "rec-a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.results[0].payload").value("payload-b1"))
                .andExpect(jsonPath("$.results[1].recordKey").value("rec-a2"))
                .andExpect(jsonPath("$.results[1].payload").value("payload-a2"))
                .andExpect(jsonPath("$.results[2].recordKey").value("rec-a1"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-1'")).isEqualTo(1);
    }

    @Test
    void batchQueryPurposeIsolation() throws Exception {
        grant("g-a", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-p", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-r", "subj-a", "RESEARCH", "rec-1", "research-data").andExpect(status().isOk());
        write("w-p", "subj-a", "PERSONALIZATION", "rec-1", "personal-data").andExpect(status().isOk());

        batch(batchBody("bq-r", "RESEARCH", "subj-a", 1, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].payload").value("research-data"));
        batch(batchBody("bq-p", "PERSONALIZATION", "subj-a", 1, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].payload").value("personal-data"));
    }

    // ---------- 失败分支：404 / 410 / 409 / 400 ----------

    @Test
    void unknownSubjectRejectsWholeBatchWith404() throws Exception {
        seedTwoSubjectsWithRecords();
        String body = batch(batchBody("bq-x", "RESEARCH",
                "subj-a", 1, "rec-a1",
                "subj-unknown", 1, "rec-x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("GRANT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        // 失败响应不含任何记录内容或主体之外的 payload 字段
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("payload")
                .doesNotContain("payload-a1");
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-x'")).isZero();
    }

    @Test
    void missingRecordReturns404WithIndexOnly() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-miss", "RESEARCH",
                "subj-a", 1, "rec-a1",
                "subj-b", 1, "rec-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void revokedEpochReturns410() throws Exception {
        seedTwoSubjectsWithRecords();
        revokeHttp("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        batch(batchBody("bq-rev", "RESEARCH",
                "subj-b", 1, "rec-b1",
                "subj-a", 1, "rec-a1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void epochAheadOfLatestReturns409() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-ahead", "RESEARCH",
                "subj-a", 1, "rec-a1",
                "subj-b", 9, "rec-b1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("EPOCH_AHEAD"));
    }

    @Test
    void oldEpochAfterRegrantReturns410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "old-data").andExpect(status().isOk());
        revokeHttp("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "new-data").andExpect(status().isOk());

        // 指定旧代整批 410，不自动转读新代
        batch(batchBody("bq-old", "RESEARCH", "subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
        // 重新授权后须以新 epoch 和新 requestId 读取
        batch(batchBody("bq-new", "RESEARCH", "subj-a", 2, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].epoch").value(2))
                .andExpect(jsonPath("$.results[0].payload").value("new-data"));
    }

    @Test
    void httpStatusFollowsFirstFailureInInputOrder() throws Exception {
        seedTwoSubjectsWithRecords();
        // 输入顺序首个失败为下标 0 的 409（epoch 超前），尽管下标 1 是 404
        batch(batchBody("bq-order1", "RESEARCH",
                "subj-a", 9, "rec-a1",
                "subj-unknown", 1, "rec-x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures.length()").value(2));
        // 交换顺序后整体状态变为 404
        batch(batchBody("bq-order2", "RESEARCH",
                "subj-unknown", 1, "rec-x",
                "subj-a", 9, "rec-a1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures.length()").value(2));
    }

    @Test
    void multipleFailuresAllReturnedWithoutPayload() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-multi", "RESEARCH",
                "subj-a", 9, "rec-a1",
                "subj-unknown", 1, "rec-x",
                "subj-b", 1, "rec-nope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures.length()").value(3))
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[1].index").value(1))
                .andExpect(jsonPath("$.failures[2].index").value(2))
                .andExpect(jsonPath("$.failures[0].payload").doesNotExist())
                .andExpect(jsonPath("$.failures[0].subjectKey").doesNotExist());
    }

    @Test
    void sameSubjectWithTwoEpochsReturns400() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-epochs", "RESEARCH",
                "subj-a", 1, "rec-a1",
                "subj-a", 2, "rec-a2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void duplicateTripleReturns400() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-dup", "RESEARCH",
                "subj-a", 1, "rec-a1",
                "subj-a", 1, "rec-a1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void emptyItemsReturns400() throws Exception {
        batch("""
                {"requestId":"bq-empty","purpose":"RESEARCH","items":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void moreThanFiftyItemsReturns400() throws Exception {
        StringBuilder body = new StringBuilder("{\"requestId\":\"bq-51\",\"purpose\":\"RESEARCH\",\"items\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"subjectKey\":\"subj-a\",\"expectedEpoch\":1,\"recordKey\":\"rec-%d\"}".formatted(i));
        }
        body.append("]}");
        batch(body.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 幂等与重放 ----------

    @Test
    void replaySameRequestIdReturnsFirstSnapshotOrderAndContent() throws Exception {
        seedTwoSubjectsWithRecords();
        String body = batchBody("bq-replay", "RESEARCH",
                "subj-a", 1, "rec-a2",
                "subj-a", 1, "rec-a1");
        batch(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].payload").value("payload-a2"));
        batch(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].recordKey").value("rec-a2"))
                .andExpect(jsonPath("$.results[1].recordKey").value("rec-a1"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-replay'")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        seedTwoSubjectsWithRecords();
        batch(batchBody("bq-conf", "RESEARCH", "subj-a", 1, "rec-a1"))
                .andExpect(status().isOk());
        batch(batchBody("bq-conf", "RESEARCH", "subj-a", 1, "rec-a2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedBatchDoesNotConsumeRequestId() throws Exception {
        seedTwoSubjectsWithRecords();
        // 首次 404 失败不占键
        batch(batchBody("bq-retry", "RESEARCH", "subj-a", 1, "rec-missing"))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-retry'")).isZero();
        // 同键改正参数后成功，且只保留一条幂等记录
        batch(batchBody("bq-retry", "RESEARCH", "subj-a", 1, "rec-a1"))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-retry'")).isEqualTo(1);
    }

    @Test
    void replayAfterRevokeRejects410WithoutCachedContent() {
        BatchQueryRequest request = batchRequest("bq-then-revoke", Purpose.RESEARCH, "subj-a", 1, "rec-1");
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "secret-payload"));
        BatchQueryResponse first = consentService.batchQuery(request);
        assertThat(first.results()).hasSize(1);

        consentService.revoke(new RevokeRequest("r-1", "subj-a", Purpose.RESEARCH, 1));

        assertThatThrownBy(() -> consentService.batchQuery(request))
                .isInstanceOf(BatchQueryException.class)
                .satisfies(ex -> {
                    BatchQueryException batch = (BatchQueryException) ex;
                    assertThat(batch.getStatus().value()).isEqualTo(410);
                    assertThat(batch.getFailures()).hasSize(1);
                    assertThat(batch.getFailures().get(0).index()).isZero();
                    assertThat(batch.getFailures().get(0).code()).isEqualTo("CONSENT_REVOKED");
                });
        // 原快照仍只有首次一条，未被失败重放覆盖
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-then-revoke'")).isEqualTo(1);
    }

    @Test
    void replayAfterSupersedingRejects410() {
        BatchQueryRequest request = batchRequest("bq-sup", Purpose.RESEARCH, "subj-a", 1, "rec-1");
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "old-payload"));
        consentService.batchQuery(request);

        consentService.revoke(new RevokeRequest("r-1", "subj-a", Purpose.RESEARCH, 1));
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-2", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-2", "subj-a", Purpose.RESEARCH, "rec-1", "new-payload"));

        // 重放旧 requestId：不转读 epoch 2 的新内容，整批 410
        assertThatThrownBy(() -> consentService.batchQuery(request))
                .isInstanceOf(BatchQueryException.class)
                .satisfies(ex -> assertThat(((BatchQueryException) ex).getStatus().value()).isEqualTo(410));
    }

    // ---------- 并发：提交顺序裁决与幂等 ----------

    @Test
    void revokeCommitsFirstThenWaitingBatchFails410() throws Exception {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "payload-1"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch revokeApplied = new CountDownLatch(1);
        CountDownLatch allowRevokeCommit = new CountDownLatch(1);
        try {
            // 撤回事务先拿到授权行排他锁但暂不提交
            Future<?> revokeFuture = pool.submit(() -> txTemplate.executeWithoutResult(status -> {
                consentRepository.revokeGrant("subj-a", Purpose.RESEARCH, 1);
                revokeApplied.countDown();
                try {
                    allowRevokeCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(revokeApplied.await(10, TimeUnit.SECONDS)).isTrue();

            // 批量查询在另一事务中等待行锁；撤回先提交后必须看到 REVOKED 并整批 410
            Future<Object> batchFuture = pool.submit(() -> {
                try {
                    consentService.batchQuery(batchRequest("bq-race", Purpose.RESEARCH,
                            "subj-a", 1, "rec-1"));
                    return null;
                } catch (BatchQueryException ex) {
                    return ex;
                }
            });
            allowRevokeCommit.countDown();
            revokeFuture.get(20, TimeUnit.SECONDS);

            Object result = batchFuture.get(20, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(BatchQueryException.class);
            assertThat(((BatchQueryException) result).getStatus().value()).isEqualTo(410);
            assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-race'")).isZero();
        } finally {
            allowRevokeCommit.countDown();
            pool.shutdown();
        }
    }

    @Test
    void batchCommitsFirstSucceedsThenRevokeAndReplayMustReject() throws Exception {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "payload-1"));
        BatchQueryRequest request = batchRequest("bq-first", Purpose.RESEARCH, "subj-a", 1, "rec-1");
        consentService.batchQuery(request);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            // 模拟批量查询事务先持有授权行锁：并发撤回必须等待其提交
            Future<?> holder = pool.submit(() -> txTemplate.execute(status -> {
                assertThat(consentRepository.findLatestGrantForUpdate("subj-a", Purpose.RESEARCH)).isPresent();
                lockHeld.countDown();
                try {
                    allowCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> revokeFuture = pool.submit(() ->
                    consentService.revoke(new RevokeRequest("r-later", "subj-a", Purpose.RESEARCH, 1)));
            // 锁未释放时撤回无法提交（以有界超时验证真实互斥，而非无限等待）
            assertThatThrownBy(() -> revokeFuture.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            allowCommit.countDown();
            holder.get(20, TimeUnit.SECONDS);
            revokeFuture.get(20, TimeUnit.SECONDS);

            // 查询先提交可以成功；撤回提交后，同键同参重放必须拒绝，不返回缓存
            assertThatThrownBy(() -> consentService.batchQuery(request))
                    .isInstanceOf(BatchQueryException.class)
                    .satisfies(ex -> assertThat(((BatchQueryException) ex).getStatus().value()).isEqualTo(410));
        } finally {
            allowCommit.countDown();
            pool.shutdown();
        }
    }

    @Test
    void concurrentSameRequestIdSameParamsSavesSingleSnapshot() throws Exception {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "same-payload"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<BatchQueryResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.batchQuery(
                        batchRequest("bq-concurrent", Purpose.RESEARCH, "subj-a", 1, "rec-1"));
            });
        }
        List<Future<BatchQueryResponse>> futures = new ArrayList<>();
        for (Callable<BatchQueryResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<BatchQueryResponse> future : futures) {
            BatchQueryResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().get(0).payload()).isEqualTo("same-payload");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-concurrent'")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentParamsOnlyOneSucceeds() throws Exception {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g-1", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "payload-1"));
        consentService.write(new RecordWriteRequest(
                "w-2", "subj-a", Purpose.RESEARCH, "rec-2", "payload-2"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> one = () -> {
            ready.countDown();
            start.await();
            try {
                consentService.batchQuery(batchRequest("bq-key", Purpose.RESEARCH, "subj-a", 1, "rec-1"));
                return "OK";
            } catch (ApiException ex) {
                return ex.getCode();
            }
        };
        Callable<String> other = () -> {
            ready.countDown();
            start.await();
            try {
                consentService.batchQuery(batchRequest("bq-key", Purpose.RESEARCH, "subj-a", 1, "rec-2"));
                return "OK";
            } catch (ApiException ex) {
                return ex.getCode();
            }
        };
        Future<String> f1 = pool.submit(one);
        Future<String> f2 = pool.submit(other);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> results = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        // 同键不同参只能一方成功，与哪个线程先提交无关
        assertThat(results).containsExactlyInAnyOrder("OK", "REQUEST_ID_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'bq-key'")).isEqualTo(1);
    }

    // ---------- 单条接口保留 ----------

    @Test
    void singleReadEndpointStillWorks() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }
}
