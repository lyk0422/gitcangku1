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

/**
 * 旧代载荷清除任务集成测试：覆盖分批清除、重放、角色门禁、缓存清除、跨代隔离与并发执行。
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
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
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

    private ResultActions execute(String role, String requestId, String subjectKey,
                                  String purpose, int epoch) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/erasure-executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch));
        if (role != null) {
            builder = builder.header("X-Actor-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions progress(String role, String subjectKey, String purpose, int epoch) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/v1/erasure-tasks")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch));
        if (role != null) {
            builder = builder.header("X-Actor-Role", role);
        }
        return mockMvc.perform(builder);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /**
     * 合成旧代 7 条记录并撤回，使该代次进入清除流程。
     */
    private void seedRevokedEpochWithSevenRecords() throws Exception {
        grant("g-seed", "subj-e", "RESEARCH").andExpect(status().isOk());
        for (int i = 1; i <= 7; i++) {
            write("w-seed-" + i, "subj-e", "RESEARCH", "rec-" + i, "payload-old-" + i)
                    .andExpect(status().isOk());
        }
        revoke("r-seed", "subj-e", "RESEARCH", 1).andExpect(status().isOk());
    }

    // ---------- 任务建立与进度 ----------

    @Test
    void revokeCreatesUniquePendingErasureTask() throws Exception {
        grant("g-1", "subj-t", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-t", "RESEARCH", 1).andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM erasure_task"
                + " WHERE subject_key = 'subj-t' AND epoch = 1 AND status = 'PENDING' AND erased_count = 0"))
                .isEqualTo(1);
        progress("ERASER", "subj-t", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(0));
        // 无未清项时执行直接转 DONE
        execute("ERASER", "e-1", "subj-t", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(0));
    }

    @Test
    void nonEraserRoleGets403OnExecuteAndProgress() throws Exception {
        seedRevokedEpochWithSevenRecords();
        execute(null, "e-1", "subj-e", "RESEARCH", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        execute("VIEWER", "e-1", "subj-e", "RESEARCH", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        progress(null, "subj-e", "RESEARCH", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        progress("VIEWER", "subj-e", "RESEARCH", 1)
                .andExpect(status().isForbidden());
        // 403 未推进任何清除
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE payload_erased = TRUE")).isZero();
    }

    // ---------- 分批清除主流程 ----------

    @Test
    void sweepErasesSevenRecordsInTwoBatchesThenDone() throws Exception {
        seedRevokedEpochWithSevenRecords();

        execute("ERASER", "e-1", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.remainingCount").value(2));
        execute("ERASER", "e-2", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.remainingCount").value(0));

        // 记录身份与清除标记保留，载荷物理清空
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-e' AND epoch = 1"))
                .isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-e' AND epoch = 1"
                + " AND payload IS NULL AND payload_erased = TRUE")).isEqualTo(7);
        List<String> keys = jdbc.queryForList(
                "SELECT record_key FROM consent_record WHERE subject_key = 'subj-e' AND epoch = 1"
                        + " ORDER BY record_key", String.class);
        assertThat(keys).containsExactly("rec-1", "rec-2", "rec-3", "rec-4", "rec-5", "rec-6", "rec-7");
        progress("ERASER", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7));
    }

    // ---------- 执行幂等与失败分支 ----------

    @Test
    void firstBatchReplayReturnsOriginalCountEvenAfterDone() throws Exception {
        seedRevokedEpochWithSevenRecords();
        execute("ERASER", "e-1", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
        execute("ERASER", "e-2", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7));
        // 第一批重放仍返回首次的无载荷计数 5，且不重复清除
        execute("ERASER", "e-1", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(5));
        assertThat(count("SELECT erased_count FROM erasure_task WHERE subject_key = 'subj-e' AND epoch = 1"))
                .isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'ERASE'")).isEqualTo(2);
    }

    @Test
    void executeSameRequestIdWithDifferentParamsReturns409() throws Exception {
        seedRevokedEpochWithSevenRecords();
        execute("ERASER", "e-1", "subj-e", "RESEARCH", 1).andExpect(status().isOk());
        execute("ERASER", "e-1", "subj-e", "RESEARCH", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void executeUnknownTaskReturns404AndFailedRequestDoesNotConsumeRequestId() throws Exception {
        execute("ERASER", "e-x", "subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
        progress("ERASER", "subj-none", "RESEARCH", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
        // 失败不占键：任务建立后同一 requestId 可成功执行
        seedRevokedEpochWithSevenRecords();
        execute("ERASER", "e-x", "subj-e", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
    }

    // ---------- 跨代与跨用途隔离 ----------

    @Test
    void newEpochReusesRecordKeyAndStaysReadableAfterErasure() throws Exception {
        seedRevokedEpochWithSevenRecords();
        execute("ERASER", "e-1", "subj-e", "RESEARCH", 1).andExpect(status().isOk());
        execute("ERASER", "e-2", "subj-e", "RESEARCH", 1).andExpect(status().isOk());

        grant("g-new", "subj-e", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        write("w-new-1", "subj-e", "RESEARCH", "rec-1", "payload-new-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        read("subj-e", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("payload-new-1"));
        // 旧代载荷已清空且不受新代写入影响；游标计数不跨代
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1 AND payload IS NULL"))
                .isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND payload = 'payload-new-1'"))
                .isEqualTo(1);
        assertThat(count("SELECT erased_count FROM erasure_task WHERE subject_key = 'subj-e' AND epoch = 1"))
                .isEqualTo(7);
    }

    @Test
    void sweepDoesNotTouchOtherPurposesOrNewEpoch() throws Exception {
        grant("g-1", "subj-i", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-i", "PERSONALIZATION").andExpect(status().isOk());
        for (int i = 1; i <= 6; i++) {
            write("w-r-" + i, "subj-i", "RESEARCH", "rec-" + i, "payload-r-" + i).andExpect(status().isOk());
        }
        write("w-p-1", "subj-i", "PERSONALIZATION", "rec-1", "payload-p-1").andExpect(status().isOk());
        revoke("r-1", "subj-i", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-3", "subj-i", "RESEARCH").andExpect(status().isOk());
        write("w-r-new", "subj-i", "RESEARCH", "rec-9", "payload-r-new").andExpect(status().isOk());

        execute("ERASER", "e-1", "subj-i", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.remainingCount").value(1));
        execute("ERASER", "e-2", "subj-i", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(6));

        // 其他用途与新代记录完整可读
        read("subj-i", "PERSONALIZATION", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-p-1"));
        read("subj-i", "RESEARCH", "rec-9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-r-new"));
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE payload_erased = TRUE"
                + " AND NOT (purpose = 'RESEARCH' AND epoch = 1)")).isZero();
    }

    // ---------- 缓存清除与撤回门禁 ----------

    @Test
    void revokePurgesPayloadFromIdempotencyCacheAndFingerprint() throws Exception {
        grant("g-1", "subj-c", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-c", "RESEARCH", "rec-1", "payload-secret-1").andExpect(status().isOk());
        String bodyBefore = jdbc.queryForObject(
                "SELECT response_body FROM idempotency_request WHERE request_id = 'w-1'", String.class);
        assertThat(bodyBefore).contains("payload-secret-1");

        revoke("r-1", "subj-c", "RESEARCH", 1).andExpect(status().isOk());

        // 缓存与请求指纹均不残留原文；元数据与原成功身份保留
        String bodyAfter = jdbc.queryForObject(
                "SELECT response_body FROM idempotency_request WHERE request_id = 'w-1'", String.class);
        assertThat(bodyAfter).doesNotContain("payload-secret-1");
        String fingerprint = jdbc.queryForObject(
                "SELECT params_fingerprint FROM idempotency_request WHERE request_id = 'w-1'", String.class);
        assertThat(fingerprint).doesNotContain("payload-secret-1");
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'"
                + " AND operation = 'WRITE' AND subject_key = 'subj-c' AND epoch = 1")).isEqualTo(1);
    }

    @Test
    void writeReplayAfterRevokeAndRegrantReturns410() throws Exception {
        grant("g-1", "subj-w", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-w", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-w", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-w", "RESEARCH").andExpect(status().isOk());
        // 撤回门禁优先于旧写重放：即使指纹因代次推进而不同，也返回 410 而非 409
        write("w-1", "subj-w", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 并发执行 ----------

    @Test
    void concurrentExecutionsConvergeToDoneWithoutHalfBatch() throws Exception {
        seedRevokedEpochWithSevenRecords();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ErasureTaskResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "e-conc-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return erasureService.execute(ErasureService.ERASER_ROLE,
                        new ErasureExecuteRequest(requestId, "subj-e", Purpose.RESEARCH, 1));
            });
        }
        List<Future<ErasureTaskResponse>> futures = new ArrayList<>();
        for (Callable<ErasureTaskResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int maxErased = 0;
        for (Future<ErasureTaskResponse> future : futures) {
            maxErased = Math.max(maxErased, future.get(20, TimeUnit.SECONDS).erasedCount());
        }
        pool.shutdown();

        assertThat(maxErased).isEqualTo(7);
        // 最终状态：任务 DONE、累计 7、无未清项、无半批残留
        assertThat(count("SELECT COUNT(*) FROM erasure_task WHERE subject_key = 'subj-e' AND epoch = 1"
                + " AND status = 'DONE' AND erased_count = 7")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-e' AND epoch = 1"
                + " AND payload_erased = FALSE")).isZero();
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-e' AND epoch = 1"
                + " AND payload IS NULL AND payload_erased = TRUE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'ERASE'"))
                .isEqualTo(threads);
    }
}
