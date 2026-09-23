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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.BatchQueryItem;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 固定授权代次原子批量查询集成测试：覆盖主流程、失败整批拒绝、幂等重放与并发边界。
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

    private ResultActions batch(String requestId, String purpose, String itemsJson) throws Exception {
        return mockMvc.perform(post("/api/v1/records/batch-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","purpose":"%s","items":[%s]}
                        """.formatted(requestId, purpose, itemsJson)));
    }

    private String item(String subjectKey, int epoch, String recordKey) {
        return """
                {"subjectKey":"%s","expectedEpoch":%d,"recordKey":"%s"}
                """.formatted(subjectKey, epoch, recordKey);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程 ----------

    @Test
    void batchQueryReturnsResultsInInputOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-a2").andExpect(status().isOk());
        write("w-3", "subj-b", "RESEARCH", "rec-1", "payload-b1").andExpect(status().isOk());

        batch("b-1", "RESEARCH",
                item("subj-b", 1, "rec-1") + "," + item("subj-a", 1, "rec-2") + "," + item("subj-a", 1, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.results[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.results[0].payload").value("payload-b1"))
                .andExpect(jsonPath("$.results[1].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.results[1].recordKey").value("rec-2"))
                .andExpect(jsonPath("$.results[1].payload").value("payload-a2"))
                .andExpect(jsonPath("$.results[2].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.results[2].payload").value("payload-a1"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'"
                + " AND operation = 'BATCH_QUERY'")).isEqualTo(1);
    }

    @Test
    void batchQueryPurposesAreIsolated() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "research-data").andExpect(status().isOk());

        // 同一主体另一用途无授权：整批 404，失败项索引指向该项
        batch("b-1", "PERSONALIZATION", item("subj-a", 1, "rec-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[0].code").value("GRANT_NOT_FOUND"));
    }

    // ---------- 失败分支：整批拒绝 ----------

    @Test
    void unknownSubjectFailsWholeBatchWith404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH",
                item("subj-a", 1, "rec-1") + "," + item("subj-none", 1, "rec-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("GRANT_NOT_FOUND"));
        // 失败不占键
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'")).isZero();
    }

    @Test
    void missingRecordFailsWholeBatchWith404() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH",
                item("subj-a", 1, "rec-1") + "," + item("subj-a", 1, "rec-none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void revokedEpochFailsWholeBatchWith410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void oldEpochAfterRegrantFailsWholeBatchWith410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "new-payload").andExpect(status().isOk());

        // 指定旧代：整批 410，不自动转读新代
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void activeButSupersededEpochFailsWholeBatchWith410() throws Exception {
        // 构造防御性数据状态：旧代仍 ACTIVE，但已存在更新的当前代（正常 API 流程中旧代会先被撤回）
        jdbc.update("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                + " VALUES ('subj-s', 'RESEARCH', 1, 'ACTIVE', 'seed-1')");
        jdbc.update("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                + " VALUES ('subj-s', 'RESEARCH', 2, 'ACTIVE', 'seed-2')");
        jdbc.update("INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                + " VALUES ('subj-s', 'RESEARCH', 1, 'rec-1', 'old', 'seed-w-1')");

        batch("b-1", "RESEARCH", item("subj-s", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[0].code").value("EPOCH_SUPERSEDED"));
    }

    @Test
    void epochAheadOfLatestFailsWholeBatchWith409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH", item("subj-a", 5, "rec-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[0].code").value("EPOCH_AHEAD"));
    }

    @Test
    void firstFailingItemDeterminesHttpStatus() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-b", "RESEARCH", "rec-1", "payload-b1").andExpect(status().isOk());
        revoke("r-1", "subj-b", "RESEARCH", 1).andExpect(status().isOk());

        // 首个失败项（索引 0）是 410，即使后续项是 404，整体仍取 410
        batch("b-1", "RESEARCH",
                item("subj-b", 1, "rec-1") + "," + item("subj-none", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures.length()").value(2))
                .andExpect(jsonPath("$.failures[0].index").value(0))
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"))
                .andExpect(jsonPath("$.failures[1].index").value(1))
                .andExpect(jsonPath("$.failures[1].code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void failureResponseContainsNoPayload() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "secret-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        MvcResult result = batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-payload");
    }

    // ---------- 参数错误 400 ----------

    @Test
    void sameSubjectWithTwoEpochsReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH",
                item("subj-a", 1, "rec-1") + "," + item("subj-a", 2, "rec-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("SUBJECT_EPOCH_CONFLICT"));
    }

    @Test
    void duplicateTripleReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH",
                item("subj-a", 1, "rec-1") + "," + item("subj-a", 1, "rec-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("DUPLICATE_QUERY_ITEM"));
    }

    @Test
    void emptyItemsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b-1\",\"purpose\":\"RESEARCH\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void moreThanFiftyItemsReturns400() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append(item("subj-" + i, 1, "rec-1"));
        }
        batch("b-1", "RESEARCH", items.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void missingPurposeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/records/batch-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b-1\",\"items\":[{\"subjectKey\":\"s\",\"expectedEpoch\":1,\"recordKey\":\"r\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidItemFieldsReturn400() throws Exception {
        // expectedEpoch = 0
        batch("b-1", "RESEARCH", item("subj-a", 0, "rec-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 空 recordKey
        mockMvc.perform(post("/api/v1/records/batch-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b-2\",\"purpose\":\"RESEARCH\","
                                + "\"items\":[{\"subjectKey\":\"subj-a\",\"expectedEpoch\":1,\"recordKey\":\"\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 空 requestId
        mockMvc.perform(post("/api/v1/records/batch-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"purpose\":\"RESEARCH\","
                                + "\"items\":[{\"subjectKey\":\"subj-a\",\"expectedEpoch\":1,\"recordKey\":\"r\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 非法 purpose
        mockMvc.perform(post("/api/v1/records/batch-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b-3\",\"purpose\":\"MARKETING\","
                                + "\"items\":[{\"subjectKey\":\"subj-a\",\"expectedEpoch\":1,\"recordKey\":\"r\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectedBatchRollsBackAndLeavesAllDataUntouched() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        write("w-2", "subj-b", "RESEARCH", "rec-1", "payload-b1").andExpect(status().isOk());

        int grantsBefore = count("SELECT COUNT(*) FROM consent_grant");
        int recordsBefore = count("SELECT COUNT(*) FROM consent_record");
        int idemBefore = count("SELECT COUNT(*) FROM idempotency_request");

        // subj-a 命中、subj-b 的记录缺失：整批回滚，任何部分结果都不落库
        batch("b-rollback", "RESEARCH",
                item("subj-a", 1, "rec-1") + "," + item("subj-b", 1, "rec-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failures[0].index").value(1))
                .andExpect(jsonPath("$.failures[0].code").value("RECORD_NOT_FOUND"));

        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(grantsBefore);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(recordsBefore);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request")).isEqualTo(idemBefore);
    }

    @Test
    void batchWithFiftyItemsSucceedsInOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            write("w-" + i, "subj-a", "RESEARCH", "rec-" + i, "payload-" + i).andExpect(status().isOk());
            if (i > 0) {
                items.append(',');
            }
            items.append(item("subj-a", 1, "rec-" + i));
        }
        batch("b-fifty", "RESEARCH", items.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(50))
                .andExpect(jsonPath("$.results[0].recordKey").value("rec-0"))
                .andExpect(jsonPath("$.results[49].recordKey").value("rec-49"))
                .andExpect(jsonPath("$.results[49].payload").value("payload-49"));
    }

    // ---------- 幂等与重放 ----------

    @Test
    void replaySameRequestIdSameParamsReturnsIdenticalSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-a2").andExpect(status().isOk());

        String items = item("subj-a", 1, "rec-2") + "," + item("subj-a", 1, "rec-1");
        MvcResult first = batch("b-1", "RESEARCH", items).andExpect(status().isOk()).andReturn();
        MvcResult second = batch("b-1", "RESEARCH", items).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-a2").andExpect(status().isOk());

        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1")).andExpect(status().isOk());
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedBatchDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-none"))
                .andExpect(status().isNotFound());
        // 同 requestId 修正参数后可成功
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].payload").value("payload-a1"));
    }

    @Test
    void replayAfterRevokeIsRejectedWith410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1")).andExpect(status().isOk());

        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 重放必须重新核对授权：已撤回则整批 410，不能返回缓存内容
        MvcResult result = batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("payload-a1");
    }

    @Test
    void replayAfterSupersededByNewEpochIsRejectedWith410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1")).andExpect(status().isOk());

        // 重新授权产生新代后，旧代重放必须 410，不能自动转读新代
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "payload-new").andExpect(status().isOk());

        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void regrantRequiresNewEpochAndNewRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-1", "new-payload").andExpect(status().isOk());

        // 复用 recordKey 须以新 epoch 和新 requestId 读取
        batch("b-new", "RESEARCH", item("subj-a", 2, "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].epoch").value(2))
                .andExpect(jsonPath("$.results[0].payload").value("new-payload"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentSameRequestIdSameParamsStoresSingleSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<BatchQueryResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.batchQuery(new BatchQueryRequest("b-concurrent",
                        Purpose.RESEARCH, List.of(new BatchQueryItem("subj-a", 1, "rec-1"))));
            });
        }
        List<Future<BatchQueryResponse>> futures = new ArrayList<>();
        for (Callable<BatchQueryResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<BatchQueryResponse> future : futures) {
            BatchQueryResponse response = future.get(20, TimeUnit.SECONDS);
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().get(0).payload()).isEqualTo("payload-a1");
        }
        pool.shutdown();
        // 并发相同 requestId 同参至多保存一个成功快照
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-concurrent'")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentParamsOnlyOneSucceeds() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-a2").andExpect(status().isOk());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String recordKey = "rec-" + (i % 2 + 1);
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.batchQuery(new BatchQueryRequest("b-conflict",
                            Purpose.RESEARCH, List.of(new BatchQueryItem("subj-a", 1, recordKey))));
                    return "OK";
                } catch (ApiException conflict) {
                    return conflict.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int ok = 0;
        int conflicts = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(20, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                ok++;
            } else {
                assertThat(outcome).isEqualTo("REQUEST_ID_CONFLICT");
                conflicts++;
            }
        }
        pool.shutdown();
        // 同键不同参只能一方成功
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(conflicts).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-conflict'")).isEqualTo(1);
    }

    @Test
    void revokeCommittedBeforeBatchQueryFailsTheBatch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        // 撤回先提交：包含该代的整批失败
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void batchQueryCommittedBeforeRevokeSucceedsThenReplayIsRejected() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());
        // 查询先提交可以成功
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1")).andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 之后重放必须拒绝
        batch("b-1", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failures[0].code").value("CONSENT_REVOKED"));
    }

    @Test
    void concurrentBatchQueryAndRevokeResolvedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a1").andExpect(status().isOk());

        int pairs = 6;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String batchRequestId = "b-race-" + i;
            String revokeRequestId = "r-race-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.batchQuery(new BatchQueryRequest(batchRequestId,
                            Purpose.RESEARCH, List.of(new BatchQueryItem("subj-a", 1, "rec-1"))));
                    return "BATCH_OK";
                } catch (BatchRejectedException rejected) {
                    return "BATCH_REJECTED:" + rejected.getFailures().get(0).code();
                }
            });
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.revoke(new RevokeRequest(revokeRequestId, "subj-a", Purpose.RESEARCH, 1));
                    return "REVOKE_OK";
                } catch (ApiException alreadyRevoked) {
                    return "REVOKE_" + alreadyRevoked.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int batchOk = 0;
        int batchRejected = 0;
        int revokeOk = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(30, TimeUnit.SECONDS);
            if ("BATCH_OK".equals(outcome)) {
                batchOk++;
            } else if (outcome.startsWith("BATCH_REJECTED:")) {
                assertThat(outcome).isEqualTo("BATCH_REJECTED:CONSENT_REVOKED");
                batchRejected++;
            } else if ("REVOKE_OK".equals(outcome)) {
                revokeOk++;
            } else {
                assertThat(outcome).isEqualTo("REVOKE_GRANT_ALREADY_REVOKED");
            }
        }
        pool.shutdown();
        // 撤回只生效一次；每个批量查询要么在撤回前成功，要么整批 410
        assertThat(revokeOk).isEqualTo(1);
        assertThat(batchOk + batchRejected).isEqualTo(pairs);
        // 最终状态：代次已撤回
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a'"
                + " AND status = 'REVOKED'")).isEqualTo(1);
        // 撤回后新的批量查询必须 410
        batch("b-after", "RESEARCH", item("subj-a", 1, "rec-1"))
                .andExpect(status().isGone());
    }
}
