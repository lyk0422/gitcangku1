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

import com.example.starter.consent.dto.RectifyRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 记录更正 API 集成测试：覆盖版本链主流程、失败分支、幂等、撤回 410 与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentRectificationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record_version");
        jdbc.update("DELETE FROM consent_record");
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

    private ResultActions rectify(String requestId, String subjectKey, String purpose, int epoch,
                                  String recordKey, int expectedRecordVersion, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records/rectifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"recordKey":"%s","expectedRecordVersion":%d,"payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, epoch, recordKey,
                        expectedRecordVersion, payload)));
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

    private ResultActions readHistory(String subjectKey, String purpose, int epoch,
                                      String recordKey, int version) throws Exception {
        return mockMvc.perform(get("/api/v1/records/history")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("epoch", String.valueOf(epoch))
                .param("recordKey", recordKey)
                .param("version", String.valueOf(version)));
    }

    private ResultActions metadata(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records/metadata")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 更正主流程 ----------

    @Test
    void rectifyAppendsNewVersionAndReadReturnsLatest() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").value("a"));

        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("b"));

        // 普通读取返回当前有效代次的最近版
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("b"));
        // 旧版本保留在版本链中
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE record_key = 'rec-1'")).isEqualTo(2);
    }

    @Test
    void historyReadRequiresExplicitEpochAndVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b").andExpect(status().isOk());

        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").value("a"));
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("b"));
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 3)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        readHistory("subj-a", "RESEARCH", 9, "rec-1", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        // 历史读取必须明确版本号
        mockMvc.perform(get("/api/v1/records/history")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 版本与失败分支 ----------

    @Test
    void rectifyWithStaleExpectedVersionReturns409AndDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b").andExpect(status().isOk());

        // 旧版本（v1）不能作为下一次更正基准
        rectify("c-2", "subj-a", "RESEARCH", 1, "rec-1", 1, "x")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_VERSION_CONFLICT"));
        // 409 失败不占请求键：同一 requestId 携带正确版本可成功
        rectify("c-2", "subj-a", "RESEARCH", 1, "rec-1", 2, "x")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(3))
                .andExpect(jsonPath("$.payload").value("x"));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(3));
    }

    @Test
    void rectifyWithSamePayloadReturnsCurrentSnapshotWithoutNewVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").value("a"));
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE record_key = 'rec-1'")).isEqualTo(1);
    }

    @Test
    void rectifyNonexistentGrantEpochOrRecordReturns404() throws Exception {
        rectify("c-0", "subj-none", "RESEARCH", 1, "rec-1", 1, "x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));

        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        rectify("c-1", "subj-a", "RESEARCH", 9, "rec-1", 1, "x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
        rectify("c-2", "subj-a", "RESEARCH", 1, "rec-none", 1, "x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void rectifyPayloadLengthBoundary() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        rectify("c-1000", "subj-a", "RESEARCH", 1, "rec-1", 1, "x".repeat(1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
        rectify("c-1001", "subj-a", "RESEARCH", 1, "rec-1", 2, "y".repeat(1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 幂等边界 ----------

    @Test
    void rectifyReplaySameRequestIdReturnsOriginalResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        // payload 含分隔符字符，验证结构化指纹不发生碰撞
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "x|1|y")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));

        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "x|1|y")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("x|1|y"));
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE record_key = 'rec-1'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c-1'")).isEqualTo(1);

        // 同 requestId 异参（payload 不同）返回 409
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "x|1|z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 异参（期望版本不同）返回 409
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 2, "x|1|y")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 撤回 410 与合成场景 ----------

    @Test
    void revokeBlocksRectifyHistoryAndReplayButMetadataSurvives() throws Exception {
        // 第 1 代：v1=a，更正为 v2=b
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 撤回后：新更正 410
        rectify("c-2", "subj-a", "RESEARCH", 1, "rec-1", 2, "d")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 撤回后：含 payload 的旧更正键重放 410（授权状态先于载荷重放）
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 撤回后：历史读取 410
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 2).andExpect(status().isGone());
        read("subj-a", "RESEARCH", "rec-1").andExpect(status().isGone());
        // 撤回后：元数据查询只返回代次和版本号，不返回 payload
        metadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // 第 2 代：同 recordKey 写 c，版本从 v1 独立开始，不混入旧链
        grant("g-2", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        write("w-2", "subj-a", "RESEARCH", "rec-1", "c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.recordVersion").value(1));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").value("c"));
        readHistory("subj-a", "RESEARCH", 2, "rec-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("c"));
        metadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.recordVersion").value(1));
        // 旧代历史读取仍 410，旧链不混入新代
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1).andExpect(status().isGone());
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE epoch = 2 AND record_key = 'rec-1'"))
                .isEqualTo(1);
    }

    @Test
    void rectifyInNewEpochStartsFromVersionOne() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "c").andExpect(status().isOk());

        rectify("c-2", "subj-a", "RESEARCH", 2, "rec-1", 1, "d")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.recordVersion").value(2));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("d"));
        readHistory("subj-a", "RESEARCH", 2, "rec-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("c"));
        readHistory("subj-a", "RESEARCH", 2, "rec-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("d"));
    }

    // ---------- 原写入 409 规则不变 ----------

    @Test
    void writeSameKeyDifferentPayloadStill409AfterRectify() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        rectify("c-1", "subj-a", "RESEARCH", 1, "rec-1", 1, "b").andExpect(status().isOk());

        // 与原写入 payload 相同：去重返回当前记录（最近版）
        write("w-2", "subj-a", "RESEARCH", "rec-1", "a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("b"));
        // 与原写入 payload 不同：仍 409
        write("w-3", "subj-a", "RESEARCH", "rec-1", "zzz")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    // ---------- 元数据查询 ----------

    @Test
    void metadataWithoutGrantOrRecordReturns404() throws Exception {
        metadata("subj-none", "RESEARCH", "rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));

        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        metadata("subj-a", "RESEARCH", "rec-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));

        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        metadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentRectifySameRecordProducesSingleNewVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "c-concurrent-" + i;
            String payload = "p-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    return consentService.rectify(new RectifyRequest(
                            requestId, "subj-a", Purpose.RESEARCH, 1, "rec-1", 1, payload));
                } catch (ApiException e) {
                    return e;
                }
            });
        }
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        int versionConflicts = 0;
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome instanceof com.example.starter.consent.dto.RecordResponse response) {
                successes++;
                assertThat(response.recordVersion()).isEqualTo(2);
            } else {
                ApiException error = (ApiException) outcome;
                assertThat(error.getCode()).isEqualTo("RECORD_VERSION_CONFLICT");
                versionConflicts++;
            }
        }
        pool.shutdown();
        // 同记录并发更正：恰好一个成功追加 v2，其余版本冲突，不泄露唯一键异常
        assertThat(successes).isEqualTo(1);
        assertThat(versionConflicts).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE record_key = 'rec-1'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'RECTIFY'")).isEqualTo(1);
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
    }

    @Test
    void concurrentRectifyAndRevokeAreAtomic() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> rectifyTask = () -> {
            ready.countDown();
            start.await();
            try {
                return consentService.rectify(new RectifyRequest(
                        "c-race", "subj-a", Purpose.RESEARCH, 1, "rec-1", 1, "b"));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> revokeTask = () -> {
            ready.countDown();
            start.await();
            return consentService.revoke(new RevokeRequest("r-race", "subj-a", Purpose.RESEARCH, 1));
        };
        Future<Object> rectifyFuture = pool.submit(rectifyTask);
        Future<Object> revokeFuture = pool.submit(revokeTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        Object rectifyOutcome = rectifyFuture.get(30, TimeUnit.SECONDS);
        Object revokeOutcome = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 撤回必然成功；更正要么先于撤回提交（成功追加 v2），要么判定为已撤回（410）
        assertThat(revokeOutcome).isInstanceOf(com.example.starter.consent.dto.GrantResponse.class);
        boolean rectifyWon = rectifyOutcome instanceof com.example.starter.consent.dto.RecordResponse;
        if (!rectifyWon) {
            assertThat(((ApiException) rectifyOutcome).getCode()).isEqualTo("CONSENT_REVOKED");
        }
        int expectedVersions = rectifyWon ? 2 : 1;
        assertThat(count("SELECT COUNT(*) FROM consent_record_version WHERE record_key = 'rec-1'"))
                .isEqualTo(expectedVersions);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'"))
                .isEqualTo(1);
        // 最终状态一致：撤回后元数据可查，payload 操作一律 410
        metadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(expectedVersions))
                .andExpect(jsonPath("$.payload").doesNotExist());
        read("subj-a", "RESEARCH", "rec-1").andExpect(status().isGone());
    }
}
