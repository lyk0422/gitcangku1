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

import com.example.starter.consent.dto.RecordWriteRequest;

/**
 * 本地数据授权 API 集成测试：覆盖主流程、失败分支、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_scope");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
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

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 授权主流程 ----------

    @Test
    void grantFirstTimeCreatesEpochOneAndReusesWhileActive() throws Exception {
        grant("g-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 当前授权仍有效时重复授权返回原 epoch，不新增代次
        grant("g-2", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a'")).isEqualTo(1);
    }

    @Test
    void grantReplaySameRequestIdReturnsOriginalWithoutNewEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'g-1'")).isEqualTo(1);
    }

    @Test
    void grantSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-1", "subj-b", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void grantAfterRevokeGeneratesNextEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 写入与查询主流程 ----------

    @Test
    void writeThenReadRoundtrip() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("rec-1"));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }

    @Test
    void writeSameKeySamePayloadDeduplicates() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        // 新 requestId、相同 key 与 payload：返回原记录，不重复写入
        write("w-2", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void writeSameKeyDifferentPayloadReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "payload-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
    }

    @Test
    void writeReplaySameRequestIdReturnsOriginalRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void writeSameRequestIdWithDifferentPayloadReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 撤回与 410 语义 ----------

    @Test
    void revokeMakesOldEpochImmediatelyGone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 无需物理删除：数据仍保留在库中但不可见
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void writeReplayAfterRevokeCannotBypassConsentState() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 重放原写入请求（同 requestId 同参数）也必须被拒绝
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void revokeReplaySameRequestIdReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'r-1'")).isEqualTo(1);
    }

    @Test
    void revokeAlreadyRevokedWithNewRequestIdReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        revoke("r-2", "subj-a", "RESEARCH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_ALREADY_REVOKED"));
    }

    @Test
    void revokeNonexistentEpochReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 9)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void revokeSameRequestIdWithDifferentEpochReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 404 分支 ----------

    @Test
    void writeWithoutGrantReturns404() throws Exception {
        write("w-1", "subj-none", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void readWithoutGrantReturns404() throws Exception {
        read("subj-none", "RESEARCH", "rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void readMissingRecordReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        read("subj-a", "RESEARCH", "rec-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    // ---------- 400 分支 ----------

    @Test
    void missingRequestIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/consents/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectKey\":\"subj-a\",\"purpose\":\"RESEARCH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidPurposeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/consents/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"g-1\",\"subjectKey\":\"subj-a\",\"purpose\":\"MARKETING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void blankRecordKeyReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "", "payload-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 隔离与重新授权 ----------

    @Test
    void purposesAreIndependent() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "research-data").andExpect(status().isOk());
        write("w-2", "subj-a", "PERSONALIZATION", "rec-1", "personal-data").andExpect(status().isOk());

        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        read("subj-a", "RESEARCH", "rec-1").andExpect(status().isGone());
        // 另一用途不受影响
        read("subj-a", "PERSONALIZATION", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("personal-data"));
    }

    @Test
    void regrantAllowsReusingRecordKeyWithoutMixingOldData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 新 epoch 可复用 recordKey
        write("w-2", "subj-a", "RESEARCH", "rec-1", "new-payload")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        // 查询只返回新代数据，不混入旧代
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("new-payload"));
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-1'")).isEqualTo(2);
    }

    // ---------- 幂等边界 ----------

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        // 无授权时写入失败，requestId 不应被占用
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1")
                .andExpect(status().isOk());
    }

    @Test
    void successResultsArePersistedForRestartDurability() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 幂等记录与业务数据均持久化在数据库中，重启后语义仍成立
        assertThat(count("SELECT COUNT(*) FROM idempotency_request")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'REVOKED'")).isEqualTo(1);
        // 已撤回数据物理保留但不可见，重启后也不会重新可见
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        read("subj-a", "RESEARCH", "rec-1").andExpect(status().isGone());
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentWritesSameRecordKeySamePayloadProduceSingleRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "w-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.write(new RecordWriteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, "rec-1", "same-payload")).payload();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("same-payload");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'WRITE'")).isEqualTo(threads);
    }

    @Test
    void concurrentGrantsProduceSingleEpoch() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "g-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.grant(
                        new com.example.starter.consent.dto.GrantRequest(requestId, "subj-c", Purpose.RESEARCH)).epoch();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-c'")).isEqualTo(1);
    }
}
