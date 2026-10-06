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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.CohortAggregateRequest;
import com.example.starter.consent.dto.CohortAggregateResponse;
import com.example.starter.consent.dto.NumericRecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 数值记录与最小群体汇总 API 集成测试：覆盖主流程、失败分支、幂等重放与并发边界。
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
        jdbc.update("DELETE FROM cohort_aggregate_snapshot");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private ResultActions grant(String requestId, String subjectKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH"}
                        """.formatted(requestId, subjectKey)));
    }

    private ResultActions writeNumeric(String requestId, String subjectKey, String recordKey, int value)
            throws Exception {
        return writeNumericRaw(requestId, subjectKey, recordKey, String.valueOf(value));
    }

    private ResultActions writeNumericRaw(String requestId, String subjectKey, String recordKey, String valueJson)
            throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s","value":%s}
                        """.formatted(requestId, subjectKey, recordKey, valueJson)));
    }

    private ResultActions writeString(String requestId, String subjectKey, String recordKey, String payload)
            throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, recordKey, payload)));
    }

    private ResultActions revoke(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    /** 成员格式："subjectKey:epoch:recordKey"。 */
    private ResultActions aggregate(String requestId, String purpose, String... members) throws Exception {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < members.length; i++) {
            String[] parts = members[i].split(":");
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"subjectKey\":\"%s\",\"epoch\":%s,\"recordKey\":\"%s\"}"
                    .formatted(parts[0], parts[1], parts[2]));
        }
        return mockMvc.perform(post("/api/v1/cohort-aggregates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","purpose":"%s","members":[%s]}
                        """.formatted(requestId, purpose, json)));
    }

    /** 创建主体授权并写入数值记录（epoch 1，recordKey rec-1）。 */
    private void subjectWithValue(String subjectKey, int value) throws Exception {
        grant("g-" + subjectKey, subjectKey).andExpect(status().isOk());
        writeNumeric("n-" + subjectKey, subjectKey, "rec-1", value).andExpect(status().isOk());
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 数值写入 ----------

    @Test
    void numericWriteReturnsMetadataOnly() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("num-a"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.recordType").value("NUMBER"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void numericWriteBoundaryValuesAccepted() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-0", "num-a", "rec-min", 0).andExpect(status().isOk());
        writeNumeric("n-1000", "num-a", "rec-max", 1000).andExpect(status().isOk());
    }

    @Test
    void numericWriteOutOfRangeOrNonIntegerReturns400() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumericRaw("n-neg", "num-a", "rec-1", "-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        writeNumericRaw("n-big", "num-a", "rec-1", "1001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        writeNumericRaw("n-dec", "num-a", "rec-1", "1.5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        writeNumericRaw("n-str", "num-a", "rec-1", "\"abc\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
    }

    @Test
    void numericWriteReplayThenGoneAfterRevoke() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 5).andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'n-1'")).isEqualTo(1);

        revoke("r-1", "num-a", 1).andExpect(status().isOk());
        // 撤回后载荷操作（含原键重放）一律 410
        writeNumeric("n-1", "num-a", "rec-1", 5)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        writeNumeric("n-2", "num-a", "rec-2", 6)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void numericWriteSameRequestIdDifferentValueReturns409() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 5).andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void numericWriteSameKeySameValueDeduplicates() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 5).andExpect(status().isOk());
        writeNumeric("n-2", "num-a", "rec-1", 5).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        writeNumeric("n-3", "num-a", "rec-1", 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_VALUE_CONFLICT"));
    }

    @Test
    void numericAndStringRecordsConflictOnSameKey() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeString("w-1", "num-a", "rec-1", "5").andExpect(status().isOk());
        // 同键已存在字符串记录：数值写入不覆盖、旧字符串记录不自动转数
        writeNumeric("n-1", "num-a", "rec-1", 5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_TYPE_CONFLICT"));
        writeNumeric("n-2", "num-a", "rec-2", 5).andExpect(status().isOk());
        writeString("w-2", "num-a", "rec-2", "5")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_TYPE_CONFLICT"));
    }

    @Test
    void numericPayloadReadRequiresActiveGrant() throws Exception {
        grant("g-1", "num-a").andExpect(status().isOk());
        writeNumeric("n-1", "num-a", "rec-1", 7).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "num-a").param("purpose", "RESEARCH").param("recordKey", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("7"));
        revoke("r-1", "num-a", 1).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "num-a").param("purpose", "RESEARCH").param("recordKey", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void numericWriteFailureDoesNotConsumeRequestId() throws Exception {
        writeNumeric("n-1", "num-none", "rec-1", 5).andExpect(status().isNotFound());
        grant("g-1", "num-none").andExpect(status().isOk());
        writeNumeric("n-1", "num-none", "rec-1", 5).andExpect(status().isOk());
    }

    // ---------- 群体汇总主流程 ----------

    @Test
    void aggregateThreeSubjectsReturnsCountAndSum() throws Exception {
        subjectWithValue("agg-a", 2);
        subjectWithValue("agg-b", 3);
        subjectWithValue("agg-c", 4);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.members").doesNotExist());
        // 完整依据（含逐主体值）仅保存在服务端快照中，公开响应不含个人值
        String membersJson = jdbc.queryForObject(
                "SELECT members_json FROM cohort_aggregate_snapshot WHERE request_id = 'agg-1'", String.class);
        assertThat(membersJson).contains("\"value\":2").contains("\"value\":3").contains("\"value\":4");
    }

    @Test
    void aggregateReplaySameRequestIdOrderInsensitive() throws Exception {
        subjectWithValue("agg-a", 2);
        subjectWithValue("agg-b", 3);
        subjectWithValue("agg-c", 4);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isOk());
        // 集合换序同参：返回首次完整响应，不重复汇总
        aggregate("agg-1", "RESEARCH", "agg-c:1:rec-1", "agg-a:1:rec-1", "agg-b:1:rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.total").value(9));
        assertThat(count("SELECT COUNT(*) FROM cohort_aggregate_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'agg-1'")).isEqualTo(1);
    }

    @Test
    void aggregateReplayAfterMemberRevokeReturns410() throws Exception {
        subjectWithValue("agg-a", 2);
        subjectWithValue("agg-b", 3);
        subjectWithValue("agg-c", 4);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isOk());
        revoke("r-1", "agg-b", 1).andExpect(status().isOk());
        // 撤回任一个后原键重放 410，不能返回人数 2 或总和 7
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 新键同参同样 410：不偷偷删成员继续汇总
        aggregate("agg-2", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void aggregateOldEpochStaysGoneAfterRegrant() throws Exception {
        grant("g-1", "sup-a").andExpect(status().isOk());
        writeNumeric("n-1", "sup-a", "rec-1", 5).andExpect(status().isOk());
        revoke("r-1", "sup-a", 1).andExpect(status().isOk());
        grant("g-2", "sup-a").andExpect(status().isOk());
        writeNumeric("n-2", "sup-a", "rec-1", 50).andExpect(status().isOk());
        subjectWithValue("sup-b", 1);
        subjectWithValue("sup-c", 1);
        // 旧代已撤回：新代记录（值 50）不能填补旧代
        aggregate("agg-1", "RESEARCH", "sup-a:1:rec-1", "sup-b:1:rec-1", "sup-c:1:rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 指定当前新代则正常汇总
        aggregate("agg-2", "RESEARCH", "sup-a:2:rec-1", "sup-b:1:rec-1", "sup-c:1:rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.total").value(52));
    }

    // ---------- 群体汇总 400 / 404 / 409 / 422 ----------

    @Test
    void aggregateTooFewOrTooManyMembersReturns400() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        String[] eleven = new String[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = "agg-x" + i + ":1:rec-1";
        }
        aggregate("agg-2", "RESEARCH", eleven)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void aggregateDuplicateSubjectReturns400() throws Exception {
        subjectWithValue("agg-a", 1);
        writeNumeric("n-a2", "agg-a", "rec-2", 2).andExpect(status().isOk());
        subjectWithValue("agg-b", 3);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-a:1:rec-2", "agg-b:1:rec-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUBJECT"));
    }

    @Test
    void aggregateNonResearchPurposeReturns400() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        subjectWithValue("agg-c", 3);
        aggregate("agg-1", "PERSONALIZATION", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ALLOWED"));
    }

    @Test
    void aggregateUnknownRecordOrSubjectReturns404() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        subjectWithValue("agg-c", 3);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-none", "agg-c:1:rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        aggregate("agg-2", "RESEARCH", "agg-a:1:rec-1", "agg-none:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void aggregateStringRecordReturns422() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        grant("g-c", "agg-c").andExpect(status().isOk());
        writeString("w-c", "agg-c", "rec-1", "3").andExpect(status().isOk());
        // 旧字符串记录不自动转数：参数合法但记录不是数值类型 → 422
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_NUMERIC"));
    }

    @Test
    void aggregateSameRequestIdDifferentParamsReturns409() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        subjectWithValue("agg-c", 3);
        subjectWithValue("agg-d", 4);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-1", "agg-c:1:rec-1")
                .andExpect(status().isOk());
        aggregate("agg-1", "RESEARCH", "agg-b:1:rec-1", "agg-c:1:rec-1", "agg-d:1:rec-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void aggregateFailureDoesNotConsumeRequestId() throws Exception {
        subjectWithValue("agg-a", 1);
        subjectWithValue("agg-b", 2);
        subjectWithValue("agg-c", 3);
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-none", "agg-c:1:rec-1")
                .andExpect(status().isNotFound());
        writeNumeric("n-fix", "agg-b", "rec-none", 9).andExpect(status().isOk());
        aggregate("agg-1", "RESEARCH", "agg-a:1:rec-1", "agg-b:1:rec-none", "agg-c:1:rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.total").value(13));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentAggregateSameRequestIdProducesSingleSnapshot() throws Exception {
        subjectWithValue("cc-a", 2);
        subjectWithValue("cc-b", 3);
        subjectWithValue("cc-c", 4);
        List<CohortAggregateRequest.Member> members = List.of(
                new CohortAggregateRequest.Member("cc-a", 1, "rec-1"),
                new CohortAggregateRequest.Member("cc-b", 1, "rec-1"),
                new CohortAggregateRequest.Member("cc-c", 1, "rec-1"));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CohortAggregateResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return consentService.aggregate(
                        new CohortAggregateRequest("cc-agg-1", Purpose.RESEARCH, members));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<CohortAggregateResponse> future : futures) {
            CohortAggregateResponse response = future.get(20, TimeUnit.SECONDS);
            assertThat(response.memberCount()).isEqualTo(3);
            assertThat(response.total()).isEqualTo(9);
        }
        pool.shutdown();
        // 竞争不泄露唯一键异常：幂等记录与快照各一行
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cc-agg-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM cohort_aggregate_snapshot")).isEqualTo(1);
    }

    @Test
    void concurrentNumericWritesSameKeySameValueProduceSingleRecord() throws Exception {
        grant("g-1", "cw-a").andExpect(status().isOk());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "cw-n-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return consentService.writeNumeric(new NumericRecordWriteRequest(
                        requestId, "cw-a", Purpose.RESEARCH, "rec-1", 5)).epoch();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'WRITE_NUMERIC'"))
                .isEqualTo(threads);
    }

    @Test
    void concurrentAggregateAndRevokeYieldConsistentOutcomes() throws Exception {
        subjectWithValue("cr-a", 2);
        subjectWithValue("cr-b", 3);
        subjectWithValue("cr-c", 4);
        List<CohortAggregateRequest.Member> members = List.of(
                new CohortAggregateRequest.Member("cr-a", 1, "rec-1"),
                new CohortAggregateRequest.Member("cr-b", 1, "rec-1"),
                new CohortAggregateRequest.Member("cr-c", 1, "rec-1"));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "cr-agg-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    CohortAggregateResponse response = consentService.aggregate(
                            new CohortAggregateRequest(requestId, Purpose.RESEARCH, members));
                    assertThat(response.memberCount()).isEqualTo(3);
                    assertThat(response.total()).isEqualTo(9);
                    return 200;
                } catch (ApiException gone) {
                    assertThat(gone.getStatus()).isEqualTo(HttpStatus.GONE);
                    return 410;
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        // 与汇总并发撤回其中一个主体
        consentService.revoke(new RevokeRequest("cr-r-1", "cr-b", Purpose.RESEARCH, 1));
        for (Future<Integer> future : futures) {
            // 每个并发汇总要么完整成功（3 人 / 总和 9），要么整体 410，无部分结果、无异常泄露
            assertThat(future.get(20, TimeUnit.SECONDS)).isIn(200, 410);
        }
        pool.shutdown();
        // 撤回提交后，新的汇总必须整体 410
        aggregate("cr-after", "RESEARCH", "cr-a:1:rec-1", "cr-b:1:rec-1", "cr-c:1:rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }
}
