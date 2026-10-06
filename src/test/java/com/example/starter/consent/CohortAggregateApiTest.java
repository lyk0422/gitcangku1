package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.CohortAggregateRequest;
import com.example.starter.consent.dto.CohortAggregateResponse;
import com.example.starter.consent.dto.CohortMember;

/**
 * 最小群体汇总 API 集成测试：主流程、400/404/410/422 分支、重放核验与并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CohortAggregateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
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

    private ResultActions writeNumeric(String requestId, String subjectKey, String recordKey, int value)
            throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s","value":%d}
                        """.formatted(requestId, subjectKey, recordKey, value)));
    }

    private ResultActions writeString(String requestId, String subjectKey, String purpose,
                                      String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions revoke(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    private static String member(String subjectKey, int epoch, String recordKey) {
        return "{\"subjectKey\":\"%s\",\"epoch\":%d,\"recordKey\":\"%s\"}"
                .formatted(subjectKey, epoch, recordKey);
    }

    private ResultActions aggregate(String requestId, String purpose, String membersJson) throws Exception {
        return mockMvc.perform(post("/api/v1/cohort-aggregates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","purpose":"%s","members":[%s]}
                        """.formatted(requestId, purpose, membersJson)));
    }

    /** 授权并写入数值记录，返回所属代次（固定为 1）。 */
    private int setupNumericSubject(String idPrefix, String subjectKey, int value) throws Exception {
        grant(idPrefix + "-g", subjectKey, "RESEARCH").andExpect(status().isOk());
        writeNumeric(idPrefix + "-n", subjectKey, "metric", value).andExpect(status().isOk());
        return 1;
    }

    /** 合成三主体 2、3、4 的标准场景。 */
    private String setupStandardCohort(String idPrefix) throws Exception {
        setupNumericSubject(idPrefix + "-1", "subj-1", 2);
        setupNumericSubject(idPrefix + "-2", "subj-2", 3);
        setupNumericSubject(idPrefix + "-3", "subj-3", 4);
        return member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程 ----------

    @Test
    void aggregateThreeSubjectsReturnsCountAndSum() throws Exception {
        String members = setupStandardCohort("s");
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.totalValue").value(9))
                // 公开响应不含逐主体值或名单
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(jsonPath("$.values").doesNotExist());
    }

    @Test
    void aggregateMemberOrderDoesNotMatter() throws Exception {
        setupStandardCohort("s");
        String ordered = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
        String shuffled = member("subj-3", 1, "metric") + "," + member("subj-1", 1, "metric") + ","
                + member("subj-2", 1, "metric");

        aggregate("a-1", "RESEARCH", ordered).andExpect(status().isOk());
        // 同 requestId 换序重放：视为同参，返回首次结果
        aggregate("a-1", "RESEARCH", shuffled)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.totalValue").value(9));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isEqualTo(1);
        // 新 requestId 换序同参：同样结果
        aggregate("a-2", "RESEARCH", shuffled)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.totalValue").value(9));
    }

    // ---------- 400 分支 ----------

    @Test
    void aggregateFewerThanThreeMembersReturns400() throws Exception {
        setupStandardCohort("s");
        String two = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric");
        aggregate("a-1", "RESEARCH", two)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void aggregateMoreThanTenMembersReturns400() throws Exception {
        String eleven = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> member("subj-x" + i, 1, "metric"))
                .collect(Collectors.joining(","));
        aggregate("a-1", "RESEARCH", eleven)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void aggregateDuplicateSubjectsReturns400() throws Exception {
        setupStandardCohort("s");
        String duplicated = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-1", 1, "metric");
        aggregate("a-1", "RESEARCH", duplicated)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUBJECT"));
    }

    @Test
    void aggregateNonResearchPurposeReturns400() throws Exception {
        setupStandardCohort("s");
        String members = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
        aggregate("a-1", "PERSONALIZATION", members)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ALLOWED"));
    }

    // ---------- 404 / 410 / 422 分支 ----------

    @Test
    void aggregateUnknownRecordReturns404() throws Exception {
        setupStandardCohort("s");
        String members = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "no-such-key");
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void aggregateRevokedMemberReturns410WithoutPartialResult() throws Exception {
        String members = setupStandardCohort("s");
        revoke("r-1", "subj-2", 1).andExpect(status().isOk());
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"))
                // 不给部分人数或总和
                .andExpect(jsonPath("$.memberCount").doesNotExist())
                .andExpect(jsonPath("$.totalValue").doesNotExist());
    }

    @Test
    void aggregateSupersededEpochReturns410AndNewEpochCannotFill() throws Exception {
        String members = setupStandardCohort("s");
        // subj-2 撤回后重新授权进入 epoch 2，并在新代写入同名数值记录
        revoke("r-1", "subj-2", 1).andExpect(status().isOk());
        grant("g-2b", "subj-2", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-2b", "subj-2", "metric", 30).andExpect(status().isOk());
        // 汇总仍指定旧代 epoch 1：被新代替代，整体 410，新代不能填补旧代
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EPOCH_SUPERSEDED"));
    }

    @Test
    void aggregateStringRecordReturns422() throws Exception {
        setupNumericSubject("s-1", "subj-1", 2);
        setupNumericSubject("s-3", "subj-3", 4);
        grant("s-2-g", "subj-2", "RESEARCH").andExpect(status().isOk());
        writeString("s-2-w", "subj-2", "RESEARCH", "metric", "3").andExpect(status().isOk());
        String members = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_NUMERIC"));
    }

    @Test
    void aggregateDifferentPurposeDataDoesNotFill() throws Exception {
        setupNumericSubject("s-1", "subj-1", 2);
        setupNumericSubject("s-3", "subj-3", 4);
        // subj-2 只有个性化用途下的记录，研究用途无记录
        grant("s-2-g", "subj-2", "PERSONALIZATION").andExpect(status().isOk());
        writeString("s-2-w", "subj-2", "PERSONALIZATION", "metric", "3").andExpect(status().isOk());
        String members = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    // ---------- 重放与幂等边界 ----------

    @Test
    void aggregateReplayAfterRevokeReturns410NotPartialResult() throws Exception {
        String members = setupStandardCohort("s");
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.totalValue").value(9));

        revoke("r-1", "subj-2", 1).andExpect(status().isOk());
        // 原键重放：先核验原成员指定代仍有效，失效即 410，不能返回人数 2 或总和 7
        aggregate("a-1", "RESEARCH", members)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"))
                .andExpect(jsonPath("$.memberCount").doesNotExist())
                .andExpect(jsonPath("$.totalValue").doesNotExist());
        // 新 requestId 同参同样 410，不删减成员继续汇总
        aggregate("a-2", "RESEARCH", members)
                .andExpect(status().isGone());
    }

    @Test
    void aggregateSameRequestIdWithDifferentParamsReturns409() throws Exception {
        String members = setupStandardCohort("s");
        aggregate("a-1", "RESEARCH", members).andExpect(status().isOk());
        String other = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "other-key");
        aggregate("a-1", "RESEARCH", other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedAggregateDoesNotConsumeRequestId() throws Exception {
        setupStandardCohort("s");
        String missing = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "no-such-key");
        aggregate("a-1", "RESEARCH", missing).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isZero();
        // 修正参数后同一 requestId 可成功
        String fixed = member("subj-1", 1, "metric") + "," + member("subj-2", 1, "metric") + ","
                + member("subj-3", 1, "metric");
        aggregate("a-1", "RESEARCH", fixed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValue").value(9));
    }

    @Test
    void aggregateSuccessStoresParamsAndResultSnapshot() throws Exception {
        String members = setupStandardCohort("s");
        aggregate("a-1", "RESEARCH", members).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request"
                + " WHERE request_id = 'a-1' AND operation = 'COHORT_AGGREGATE'")).isEqualTo(1);
        String fingerprint = jdbc.queryForObject(
                "SELECT params_fingerprint FROM idempotency_request WHERE request_id = 'a-1'", String.class);
        String snapshot = jdbc.queryForObject(
                "SELECT response_body FROM idempotency_request WHERE request_id = 'a-1'", String.class);
        // 完整依据：结构化参数含全部成员及其代次与记录键，结果快照含人数与总和
        assertThat(fingerprint).contains("COHORT_AGGREGATE", "subj-1", "subj-2", "subj-3", "metric");
        assertThat(snapshot).contains("\"memberCount\":3", "\"totalValue\":9");
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentAggregatesSameRequestIdReturnSameSnapshot() throws Exception {
        setupStandardCohort("s");
        List<CohortMember> members = List.of(
                new CohortMember("subj-1", 1, "metric"),
                new CohortMember("subj-2", 1, "metric"),
                new CohortMember("subj-3", 1, "metric"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<CohortAggregateResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.aggregate(
                        new CohortAggregateRequest("a-concurrent", Purpose.RESEARCH, members));
            });
        }
        List<Future<CohortAggregateResponse>> futures = new ArrayList<>();
        for (Callable<CohortAggregateResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<CohortAggregateResponse> future : futures) {
            CohortAggregateResponse response = future.get(20, TimeUnit.SECONDS);
            assertThat(response.memberCount()).isEqualTo(3);
            assertThat(response.totalValue()).isEqualTo(9);
        }
        pool.shutdown();
        // 竞争不泄露唯一键异常：同一 requestId 只落一行
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-concurrent'"))
                .isEqualTo(1);
    }
}
