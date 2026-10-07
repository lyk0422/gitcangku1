package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureTaskResponse;
import com.example.starter.consent.dto.RecordWriteRequest;

/**
 * 旧代载荷清除 API 集成测试：覆盖分批清除、重放、角色门禁、失败分支、
 * 跨代/跨用途隔离与并发重试，全部基于真实 H2（MySQL 兼容模式）验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErasureApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ErasureService erasureService;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM erasure_task");
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload, int expectedEpoch) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s","operator":"op-test","expectedEpoch":%d}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload, expectedEpoch)));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions read(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private ResultActions execute(String requestId, String subjectKey, String purpose,
                                  int epoch, String role) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/erasure/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch));
        if (role != null) {
            builder = builder.header("X-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions progress(String subjectKey, String purpose, int epoch, String role) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/v1/erasure/tasks")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch));
        if (role != null) {
            builder = builder.header("X-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /**
     * 合成旧代数据：授权后写入 recordCount 条记录（rec-01 起按字典序命名）。
     */
    private void seedOldEpoch(String subjectKey, int recordCount) throws Exception {
        grant("g-seed-" + subjectKey, subjectKey, "RESEARCH").andExpect(status().isOk());
        for (int i = 1; i <= recordCount; i++) {
            write("w-old-" + subjectKey + "-" + i, subjectKey, "RESEARCH",
                    "rec-" + String.format("%02d", i), "payload-old-" + i, 1)
                    .andExpect(status().isOk());
        }
    }

    // ---------- 任务建立与分批清除主流程 ----------

    @Test
    void revokeCreatesUniquePendingTaskAndReadGone() throws Exception {
        seedOldEpoch("subj-a", 2);
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 撤回成功即建立唯一 PENDING 任务，原授权读取立即 410
        progress("subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(2));
        read("subj-a", "RESEARCH", "rec-01").andExpect(status().isGone());

        // 撤回重放不重复建任务
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM erasure_task WHERE subject_key = 'subj-a'")).isEqualTo(1);
    }

    @Test
    void executeSweepsBatchesOfFiveInRecordKeyOrderThenDone() throws Exception {
        seedOldEpoch("subj-a", 7);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 第一批：按 recordKey 字典序清除 5 条，任务仍 PENDING
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.remainingCount").value(2));
        for (int i = 1; i <= 5; i++) {
            assertThat(count("SELECT COUNT(*) FROM consent_record"
                    + " WHERE record_key = ? AND payload_erased = TRUE", "rec-0" + i)).isEqualTo(1);
        }
        assertThat(count("SELECT COUNT(*) FROM consent_record"
                + " WHERE record_key IN ('rec-06', 'rec-07') AND payload_erased = FALSE")).isEqualTo(2);

        // 第二批：清除剩余 2 条，累计 7 且转 DONE
        execute("e-2", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.remainingCount").value(0));

        // 旧代载荷全部清空，记录身份与清除标记保留
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1 AND payload_erased = TRUE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1 AND payload <> ''")).isEqualTo(0);
        // 旧代缓存载荷全部清空，元数据与原成功身份保留
        assertThat(count("SELECT COUNT(*) FROM idempotency_request"
                + " WHERE operation = 'WRITE' AND response_body LIKE '%payload-old%'")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'WRITE'")).isEqualTo(7);
    }

    @Test
    void executeReplayReturnsFirstPayloadlessCount() throws Exception {
        seedOldEpoch("subj-a", 7);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
        execute("e-2", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.status").value("DONE"));

        // 第一批重放仍返回首次的无载荷计数 5，不再触发清除
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(count("SELECT erased_count FROM erasure_task"
                + " WHERE subject_key = 'subj-a' AND epoch = 1")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'ERASE'")).isEqualTo(2);
    }

    // ---------- 角色门禁 ----------

    @Test
    void executeAndProgressRequireEraserRole() throws Exception {
        seedOldEpoch("subj-a", 1);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        execute("e-1", "subj-a", "RESEARCH", 1, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        execute("e-1", "subj-a", "RESEARCH", 1, "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        progress("subj-a", "RESEARCH", 1, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
        progress("subj-a", "RESEARCH", 1, "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));

        // 被拒请求未触发任何清除
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE payload_erased = TRUE")).isEqualTo(0);
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(1));
    }

    // ---------- 失败分支 ----------

    @Test
    void executeMissingTaskReturns404AndFailureDoesNotConsumeRequestId() throws Exception {
        execute("e-9", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
        progress("subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));

        // 失败不占键：撤回建任务后同一 requestId 可成功执行
        seedOldEpoch("subj-a", 1);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        execute("e-9", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void executeSameRequestIdWithDifferentParamsReturns409() throws Exception {
        seedOldEpoch("subj-a", 1);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER").andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 2, "ERASER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void executeOnTaskWithoutRecordsTransitionsToDone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(0));
    }

    @Test
    void executeWithBlankRequestIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/erasure/executions")
                        .header("X-Role", "ERASER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","subjectKey":"subj-a","purpose":"RESEARCH","epoch":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 跨代 / 跨用途隔离 ----------

    @Test
    void sweepOnlyTouchesSpecifiedEpochAndPurpose() throws Exception {
        // RESEARCH 旧代 7 条；PERSONALIZATION 3 条；撤回后重新授权的新代 1 条同名记录
        seedOldEpoch("subj-a", 7);
        grant("g-p-1", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        for (int i = 1; i <= 3; i++) {
            write("w-p-" + i, "subj-a", "PERSONALIZATION", "rec-p-" + i, "payload-personal-" + i, 1)
                    .andExpect(status().isOk());
        }
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-n-1", "subj-a", "RESEARCH", "rec-01", "payload-new-1", 2).andExpect(status().isOk());

        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
        execute("e-2", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.status").value("DONE"));

        // 其他用途不受影响
        assertThat(count("SELECT COUNT(*) FROM consent_record"
                + " WHERE purpose = 'PERSONALIZATION' AND payload_erased = FALSE")).isEqualTo(3);
        // 新代不受影响，同名记录完整可读
        assertThat(count("SELECT COUNT(*) FROM consent_record"
                + " WHERE epoch = 2 AND payload_erased = FALSE")).isEqualTo(1);
        read("subj-a", "RESEARCH", "rec-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("payload-new-1"));
        // 游标计数不跨代：只有旧代任务，累计 7 不含新代记录
        assertThat(count("SELECT COUNT(*) FROM erasure_task")).isEqualTo(1);
        progress("subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.remainingCount").value(0));
    }

    @Test
    void oldEpochPayloadAndCacheCannotBeRecoveredAfterSweep() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-secret-1", 1).andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // 旧写重放仍 410，撤回门禁优先，不能恢复旧文本
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-secret-1", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 记录载荷与缓存快照均无原文残留
        String recordPayload = jdbc.queryForObject(
                "SELECT payload FROM consent_record WHERE record_key = 'rec-1'", String.class);
        assertThat(recordPayload).isEmpty();
        String cachedBody = jdbc.queryForObject(
                "SELECT response_body FROM idempotency_request WHERE request_id = 'w-1'", String.class);
        assertThat(cachedBody).doesNotContain("payload-secret-1");
        String fingerprint = jdbc.queryForObject(
                "SELECT params_fingerprint FROM idempotency_request WHERE request_id = 'w-1'", String.class);
        assertThat(fingerprint).doesNotContain("payload-secret-1");
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentExecutesProduceConsistentFinalState() throws Exception {
        seedOldEpoch("subj-a", 7);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErasureTaskResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "e-concurrent-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return erasureService.execute(
                        new ErasureExecuteRequest(requestId, "subj-a", Purpose.RESEARCH, 1));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int maxErased = 0;
        for (Future<ErasureTaskResponse> future : futures) {
            ErasureTaskResponse response = future.get(20, TimeUnit.SECONDS);
            assertThat(response.erasedCount()).isBetween(0, 7);
            maxErased = Math.max(maxErased, response.erasedCount());
        }
        pool.shutdown();

        // 并发执行最终一致：7 条全部清除，任务 DONE，计数不重复
        assertThat(maxErased).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE payload_erased = TRUE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM erasure_task"
                + " WHERE status = 'DONE' AND erased_count = 7")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'ERASE'"))
                .isEqualTo(threads);
    }

    @Test
    void concurrentSweepOldEpochWriteAndNewEpochWriteAreConsistent() throws Exception {
        seedOldEpoch("subj-a", 7);
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> {
            ready.countDown();
            start.await();
            erasureService.execute(new ErasureExecuteRequest("e-s-1", "subj-a", Purpose.RESEARCH, 1));
            return erasureService.execute(new ErasureExecuteRequest("e-s-2", "subj-a", Purpose.RESEARCH, 1));
        });
        tasks.add(() -> {
            ready.countDown();
            start.await();
            // 撤回后旧代不得写：期望版本停留在旧代的写入被拒绝
            try {
                consentService.write(new RecordWriteRequest(
                        "w-stale", "subj-a", Purpose.RESEARCH, "rec-08", "payload-stale", "op-test", 1));
                return "WRITTEN";
            } catch (ApiException ex) {
                return ex.getCode();
            }
        });
        tasks.add(() -> {
            ready.countDown();
            start.await();
            // 新代可复用 recordKey 正常写入
            return consentService.write(new RecordWriteRequest(
                    "w-new", "subj-a", Purpose.RESEARCH, "rec-01", "payload-new", "op-test", 2)).payload();
        });
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        ErasureTaskResponse sweepResult = (ErasureTaskResponse) futures.get(0).get(20, TimeUnit.SECONDS);
        assertThat(sweepResult.status()).isEqualTo(ErasureTaskStatus.DONE);
        assertThat(sweepResult.erasedCount()).isEqualTo(7);
        assertThat(futures.get(1).get(20, TimeUnit.SECONDS)).isEqualTo("GRANT_EPOCH_MISMATCH");
        assertThat(futures.get(2).get(20, TimeUnit.SECONDS)).isEqualTo("payload-new");
        pool.shutdown();

        // 最终核验：旧代 7 条全清且无新增，新代记录完整可读
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record"
                + " WHERE epoch = 1 AND payload_erased = TRUE")).isEqualTo(7);
        read("subj-a", "RESEARCH", "rec-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("payload-new"));
    }
}
