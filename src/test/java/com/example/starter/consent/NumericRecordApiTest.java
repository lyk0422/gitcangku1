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

import com.example.starter.consent.dto.NumericRecordWriteRequest;

/**
 * 数值记录 API 集成测试：元数据写入、授权读取、类型边界、幂等与并发。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NumericRecordApiTest {

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

    private ResultActions writeNumeric(String requestId, String subjectKey, String purpose,
                                       String recordKey, int value) throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","value":%d}
                        """.formatted(requestId, subjectKey, purpose, recordKey, value)));
    }

    private ResultActions writeNumericRaw(String requestId, String subjectKey, String recordKey, int value)
            throws Exception {
        return mockMvc.perform(post("/api/v1/numeric-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s","value":%d}
                        """.formatted(requestId, subjectKey, recordKey, value)));
    }

    private ResultActions readNumeric(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/numeric-records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
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

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 数值写入与读取主流程 ----------

    @Test
    void numericWriteReturnsMetadataOnly() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("metric"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void numericWriteThenReadRoundtrip() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 1000).andExpect(status().isOk());
        readNumeric("subj-a", "RESEARCH", "metric")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(1000));
    }

    @Test
    void numericWriteRejectsOutOfRangeValues() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumericRaw("n-neg", "subj-a", "metric", -1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        writeNumericRaw("n-big", "subj-a", "metric", 1001)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
    }

    @Test
    void numericWriteSameKeySameValueDeduplicates() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7).andExpect(status().isOk());
        writeNumeric("n-2", "subj-a", "RESEARCH", "metric", 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void numericWriteSameKeyDifferentValueReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7).andExpect(status().isOk());
        writeNumeric("n-2", "subj-a", "RESEARCH", "metric", 8)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
    }

    // ---------- 幂等与授权边界 ----------

    @Test
    void numericWriteReplaySameRequestIdReturnsOriginal() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7).andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("metric"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        // 同 requestId 异参 409
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void numericOperationsAfterRevokeReturn410() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7).andExpect(status().isOk());
        revoke("r-1", "subj-a", 1).andExpect(status().isOk());

        readNumeric("subj-a", "RESEARCH", "metric")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        writeNumeric("n-2", "subj-a", "RESEARCH", "metric-2", 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 重放原写入请求（同 requestId 同参数）也被拒绝
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void stringRecordIsNotTreatedAsNumeric() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeString("w-1", "subj-a", "metric", "42").andExpect(status().isOk());
        // 旧字符串记录不自动转数：数值读取 422
        readNumeric("subj-a", "RESEARCH", "metric")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_NUMERIC"));
        // 同键数值写入视为不同类型内容冲突 409
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 42)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
    }

    @Test
    void failedNumericWriteDoesNotConsumeRequestId() throws Exception {
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7)
                .andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeNumeric("n-1", "subj-a", "RESEARCH", "metric", 7)
                .andExpect(status().isOk());
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentNumericWritesSameKeySameValueProduceSingleRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "n-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.writeNumeric(new NumericRecordWriteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, "metric", 7)).recordKey();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("metric");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'NUMERIC_WRITE'"))
                .isEqualTo(threads);
    }
}
