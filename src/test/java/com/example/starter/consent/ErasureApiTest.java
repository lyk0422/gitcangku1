package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureExecuteResponse;

/**
 * 旧代载荷清除 API 集成测试：覆盖分批清除、幂等重放、角色门禁、隔离与回滚边界。
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

    @MockitoSpyBean
    private ErasureRepository erasureRepository;

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
        return write(requestId, subjectKey, purpose, recordKey, payload, 1);
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s","operator":"op-test","expectedVersion":%d}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload, expectedVersion)));
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
        String roleJson = role == null ? "" : ",\"role\":\"" + role + "\"";
        return mockMvc.perform(post("/api/v1/erasure-tasks/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d%s}
                        """.formatted(requestId, subjectKey, purpose, epoch, roleJson)));
    }

    private ResultActions progress(String subjectKey, String purpose, int epoch, String role) throws Exception {
        var request = get("/api/v1/erasure-tasks/progress")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch));
        if (role != null) {
            request = request.param("role", role);
        }
        return mockMvc.perform(request);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /** 合成 7 条旧代记录并撤回，返回时清除任务已建立。 */
    private void seedSevenRecordsAndRevoke(String subjectKey) throws Exception {
        grant("g-1", subjectKey, "RESEARCH").andExpect(status().isOk());
        for (int i = 1; i <= 7; i++) {
            write("w-0" + i, subjectKey, "RESEARCH", "rec-0" + i, "old-payload-" + i)
                    .andExpect(status().isOk());
        }
        revoke("r-1", subjectKey, "RESEARCH", 1).andExpect(status().isOk());
    }

    // ---------- 任务建立 ----------

    @Test
    void revokeCreatesUniquePendingErasureTask() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM erasure_task"
                + " WHERE subject_key = 'subj-a' AND epoch = 1 AND status = 'PENDING' AND erased_count = 0"))
                .isEqualTo(1);
        // 撤回重放与重复撤回都不会重复建任务
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        revoke("r-2", "subj-a", "RESEARCH", 1).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM erasure_task")).isEqualTo(1);
    }

    // ---------- 分批清除主流程 ----------

    @Test
    void erasureSweepsOldEpochInBatchesThenDoneAndKeepsNewEpochReadable() throws Exception {
        seedSevenRecordsAndRevoke("subj-a");
        // 撤回后读取立即 410；清除前缓存中仍有原文快照
        read("subj-a", "RESEARCH", "rec-01").andExpect(status().isGone());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request"
                + " WHERE response_body LIKE '%old-payload%'")).isGreaterThan(0);

        // 第一批：按 recordKey 字典序清除 rec-01..rec-05
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.erasedInBatch").value(5));
        for (int i = 1; i <= 5; i++) {
            assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-0" + i + "'"
                    + " AND payload IS NULL AND payload_erased = TRUE")).isEqualTo(1);
        }
        for (int i = 6; i <= 7; i++) {
            assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-0" + i + "'"
                    + " AND payload = 'old-payload-" + i + "' AND payload_erased = FALSE")).isEqualTo(1);
        }

        // 第一批重放：仍返回首次的无载荷计数 5，且不继续清除
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5))
                .andExpect(jsonPath("$.erasedInBatch").value(5));
        assertThat(count("SELECT COUNT(*) FROM erasure_task WHERE erased_count = 5")).isEqualTo(1);

        // 重新授权生成新代，同名 recordKey 可复用且完整可读
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        write("w-n1", "subj-a", "RESEARCH", "rec-01", "new-payload-1", 2).andExpect(status().isOk());

        // 第二批：清除剩余 2 条，任务转 DONE
        execute("e-2", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7))
                .andExpect(jsonPath("$.erasedInBatch").value(2));
        progress("subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(7));

        // 旧代 7 条记录身份保留、载荷全部清空；新代同名记录未受影响
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1"
                + " AND payload IS NULL AND payload_erased = TRUE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND record_key = 'rec-01'"
                + " AND payload = 'new-payload-1' AND payload_erased = FALSE")).isEqualTo(1);
        read("subj-a", "RESEARCH", "rec-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("new-payload-1"));

        // 旧代缓存快照与请求指纹均不残留原文
        assertThat(count("SELECT COUNT(*) FROM idempotency_request"
                + " WHERE response_body LIKE '%old-payload%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request"
                + " WHERE params_fingerprint LIKE '%old-payload%'")).isZero();
        // 撤回门禁优先于旧写重放：旧文本不可恢复
        write("w-01", "subj-a", "RESEARCH", "rec-01", "old-payload-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 角色门禁 ----------

    @Test
    void onlyEraserRoleCanExecuteAndViewProgress() throws Exception {
        seedSevenRecordsAndRevoke("subj-a");
        execute("e-x", "subj-a", "RESEARCH", 1, "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        execute("e-x", "subj-a", "RESEARCH", 1, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        progress("subj-a", "RESEARCH", 1, "VIEWER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        progress("subj-a", "RESEARCH", 1, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ERASER_ROLE_REQUIRED"));
        // 失败请求不占键：同 requestId 以 ERASER 角色可正常执行
        execute("e-x", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
        progress("subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
    }

    // ---------- 404 与 409 ----------

    @Test
    void executeAndProgressWithoutTaskReturn404() throws Exception {
        execute("e-1", "subj-none", "RESEARCH", 1, "ERASER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
        progress("subj-none", "RESEARCH", 1, "ERASER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
    }

    @Test
    void executeSameRequestIdWithDifferentParamsReturns409() throws Exception {
        seedSevenRecordsAndRevoke("subj-a");
        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER").andExpect(status().isOk());
        execute("e-1", "subj-a", "RESEARCH", 2, "ERASER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        execute("e-1", "subj-b", "RESEARCH", 1, "ERASER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 用途隔离 ----------

    @Test
    void erasureDoesNotTouchOtherPurposes() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "research-payload").andExpect(status().isOk());
        write("w-2", "subj-a", "PERSONALIZATION", "rec-1", "personal-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        execute("e-1", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.erasedCount").value(1));

        // 另一用途记录与读取不受影响
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'PERSONALIZATION'"
                + " AND payload = 'personal-payload' AND payload_erased = FALSE")).isEqualTo(1);
        read("subj-a", "PERSONALIZATION", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("personal-payload"));
        // 另一用途不存在清除任务
        progress("subj-a", "PERSONALIZATION", 1, "ERASER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERASURE_TASK_NOT_FOUND"));
    }

    // ---------- 事务回滚 ----------

    @Test
    void executeFailureRollsBackWholeBatchAndDoesNotConsumeRequestId() throws Exception {
        seedSevenRecordsAndRevoke("subj-a");
        // 强制进度更新失败：本批 5 条载荷清除必须整体回滚
        doThrow(new IllegalStateException("boom")).when(erasureRepository)
                .updateProgress(anyString(), any(), anyInt(), anyInt(), any());
        execute("e-fail", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isInternalServerError());

        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1"
                + " AND payload IS NOT NULL AND payload_erased = FALSE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM erasure_task"
                + " WHERE status = 'PENDING' AND erased_count = 0")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-fail'")).isZero();

        // 失败不占键：恢复后同 requestId 可重新执行
        reset(erasureRepository);
        execute("e-fail", "subj-a", "RESEARCH", 1, "ERASER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(5));
    }

    // ---------- 并发执行 ----------

    @Test
    void concurrentExecutionsSweepExactlyOnceWithoutPartialBatches() throws Exception {
        seedSevenRecordsAndRevoke("subj-a");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ErasureExecuteResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "e-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return erasureService.execute(new ErasureExecuteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, 1, ErasureService.ROLE_ERASER));
            });
        }
        List<Future<ErasureExecuteResponse>> futures = new ArrayList<>();
        for (Callable<ErasureExecuteResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int sweptInBatches = 0;
        for (Future<ErasureExecuteResponse> future : futures) {
            ErasureExecuteResponse response = future.get(20, TimeUnit.SECONDS);
            assertThat(response.erasedCount()).isBetween(0, 7);
            sweptInBatches += response.erasedInBatch();
        }
        pool.shutdown();

        // 全部 7 条恰好被清除一次：没有半批残留，也没有重复计数
        assertThat(sweptInBatches).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1"
                + " AND payload IS NULL AND payload_erased = TRUE")).isEqualTo(7);
        assertThat(count("SELECT COUNT(*) FROM erasure_task"
                + " WHERE status = 'DONE' AND erased_count = 7")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'ERASE'"))
                .isEqualTo(threads);
    }
}