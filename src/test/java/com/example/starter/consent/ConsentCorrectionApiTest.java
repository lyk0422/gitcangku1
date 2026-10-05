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

import com.example.starter.consent.dto.CorrectRecordRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 记录更正 API 集成测试：覆盖版本链主流程、409/410 失败分支、幂等重放与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentCorrectionApiTest {

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

    private ResultActions correct(String requestId, String subjectKey, String purpose, int epoch,
                                  String recordKey, String payload, int expectedRecordVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/records/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,\
                        "recordKey":"%s","payload":"%s","expectedRecordVersion":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch, recordKey, payload,
                        expectedRecordVersion)));
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

    private ResultActions readMetadata(String subjectKey, String purpose, String recordKey) throws Exception {
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
    void correctAppendsNewVersionAndReadReturnsLatest() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("b"));

        // 普通读取返回当前有效代次的最近版
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("b"));
        // 历史读取需明确同一 epoch 及版本，旧版本保留
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("a"));
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("b"));
        // 元数据只返回代次与版本号，不返回 payload
        readMetadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM consent_record_version")).isEqualTo(2);
    }

    @Test
    void correctWithStaleExpectedVersionReturns409AndDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1).andExpect(status().isOk());

        // 旧版本不能作为下一次更正基准
        correct("c-2", "subj-a", "RESEARCH", 1, "rec-1", "x", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_VERSION_CONFLICT"));
        // 失败不占请求键
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c-2'")).isZero();
        correct("c-2", "subj-a", "RESEARCH", 1, "rec-1", "x", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(3));
    }

    @Test
    void correctWithSamePayloadReturnsCurrentSnapshotWithoutNewVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "a", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(1))
                .andExpect(jsonPath("$.payload").value("a"));
        assertThat(count("SELECT COUNT(*) FROM consent_record_version")).isEqualTo(1);
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("a"));
    }

    // ---------- 幂等与失败分支 ----------

    @Test
    void correctReplaySameRequestIdReturnsOriginalResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1).andExpect(status().isOk());

        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").value("b"));
        assertThat(count("SELECT COUNT(*) FROM consent_record_version")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'c-1'")).isEqualTo(1);
    }

    @Test
    void correctSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1).andExpect(status().isOk());

        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "d", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void correctNonexistentRecordReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-none", "b", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void correctNonexistentEpochReturns404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 9, "rec-1", "b", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void correctPayloadOver1000CharsReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "x".repeat(1001), 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 撤回后的 410 语义 ----------

    @Test
    void revokedEpochRejectsCorrectHistoryAndPayloadReplay() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1).andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 撤回后新更正 410
        correct("c-2", "subj-a", "RESEARCH", 1, "rec-1", "x", 2)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 撤回后历史读取 410
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 授权状态先于载荷重放：含 payload 的旧更正键重放也 410
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 撤回后元数据查询只返回代次和版本号，不返回 payload
        readMetadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordVersion").value(2))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    // ---------- 合成场景：跨代次隔离 ----------

    @Test
    void syntheticScenarioEpochChainIsolation() throws Exception {
        // 第 1 代：v1=a，更正为 v2=b
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
        // 撤回后旧更正键 410
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        correct("c-1", "subj-a", "RESEARCH", 1, "rec-1", "b", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        // 第 2 代：同 recordKey 写 c，仅返回 c 及 v1，不混入旧链
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("c"));
        readMetadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.recordVersion").value(1));
        readHistory("subj-a", "RESEARCH", 2, "rec-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("c"));
        // 旧代历史不再可读
        readHistory("subj-a", "RESEARCH", 1, "rec-1", 1).andExpect(status().isGone());
        // 新代从独立记录版本开始：期望版本 1 可更正
        correct("c-9", "subj-a", "RESEARCH", 2, "rec-1", "d", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentCorrectionsSameRecordProduceSingleWinner() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "c-concurrent-" + i;
            String payload = "payload-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.correct(new CorrectRecordRequest(
                            requestId, "subj-a", Purpose.RESEARCH, 1, "rec-1", payload, 1));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 同一期望版本并发更正：恰好一个成功，其余 409，不泄露唯一键异常
        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("RECORD_VERSION_CONFLICT"::equals).count())
                .isEqualTo(threads - 1L);
        assertThat(count("SELECT COUNT(*) FROM consent_record_version")).isEqualTo(2);
        readMetadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
    }

    @Test
    void concurrentCorrectAndRevokeAreAtomic() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "a").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> correctTask = () -> {
            ready.countDown();
            start.await();
            try {
                consentService.correct(new CorrectRecordRequest(
                        "c-race", "subj-a", Purpose.RESEARCH, 1, "rec-1", "b", 1));
                return "OK";
            } catch (ApiException e) {
                return e.getCode();
            }
        };
        Callable<String> revokeTask = () -> {
            ready.countDown();
            start.await();
            try {
                consentService.revoke(new RevokeRequest("r-race", "subj-a", Purpose.RESEARCH, 1));
                return "OK";
            } catch (ApiException e) {
                return e.getCode();
            }
        };
        Future<String> correctFuture = pool.submit(correctTask);
        Future<String> revokeFuture = pool.submit(revokeTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String correctOutcome = correctFuture.get(20, TimeUnit.SECONDS);
        String revokeOutcome = revokeFuture.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        // 撤回必然成功；更正要么整体提交要么 410，不允许中间态或唯一键异常泄露
        assertThat(revokeOutcome).isEqualTo("OK");
        assertThat(correctOutcome).isIn("OK", "CONSENT_REVOKED");
        int expectedVersions = "OK".equals(correctOutcome) ? 2 : 1;
        assertThat(count("SELECT COUNT(*) FROM consent_record_version")).isEqualTo(expectedVersions);
        // 最终状态一致：撤回后普通读取 410，元数据可见版本号
        read("subj-a", "RESEARCH", "rec-1").andExpect(status().isGone());
        readMetadata("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(expectedVersions));
    }
}
