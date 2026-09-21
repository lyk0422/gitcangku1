package com.example.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权与数据隔离 API 端到端测试：主流程、失败分支、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM consent_record");
        jdbcTemplate.update("DELETE FROM consent_grant");
        jdbcTemplate.update("DELETE FROM idempotency_request");
    }

    // ---------- 主流程 ----------

    @Test
    void grant_firstTimeReturnsEpoch1_repeatReturnsSameEpoch() throws Exception {
        grant("g1", "subject-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 当前授权仍有效时重复授权（新 requestId）返回原 epoch，不新增代次。
        grant("g2", "subject-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Integer grantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subject-a' AND purpose = 'RESEARCH'",
                Integer.class);
        assertThat(grantCount).isEqualTo(1);
    }

    @Test
    void writeAndRead_afterGrant_roundTrips() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());

        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("key-1"))
                .andExpect(jsonPath("$.payload").value("payload-1"));

        read("subject-a", "RESEARCH", "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }

    @Test
    void write_sameKeySamePayload_returnsOriginalRecord() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());

        // 同代相同 recordKey、相同 payload 重复提交（新 requestId）返回原记录。
        write("w2", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.payload").value("payload-1"));

        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE record_key = 'key-1'", Integer.class);
        assertThat(recordCount).isEqualTo(1);
    }

    // ---------- 失败分支：400 / 404 / 409 ----------

    @Test
    void validation_missingOrInvalidParams_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("subjectKey", "subject-a");
        body.put("purpose", "RESEARCH");
        // 缺 requestId。
        mockMvc.perform(post("/api/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 非法用途。
        grant("g1", "subject-a", "MARKETING")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // 查询缺少参数。
        mockMvc.perform(get("/api/records").param("subjectKey", "subject-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void writeAndRead_withoutGrant_returns404() throws Exception {
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));

        read("subject-a", "RESEARCH", "key-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void read_missingRecord_returns404() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        read("subject-a", "RESEARCH", "no-such-key")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    void write_sameKeyDifferentPayload_returns409() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());

        write("w2", "subject-a", "RESEARCH", "key-1", "payload-other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYLOAD_CONFLICT"));
    }

    @Test
    void revoke_nonExistingEpoch_returns404() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        revoke("r1", "subject-a", "RESEARCH", 9)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void revoke_twiceWithDifferentRequestId_returns409() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        revoke("r1", "subject-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        revoke("r2", "subject-a", "RESEARCH", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));
    }

    // ---------- 撤回与数据隔离 ----------

    @Test
    void revoke_afterCommit_readReturns410AndWriteRejected() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());
        revoke("r1", "subject-a", "RESEARCH", 1).andExpect(status().isOk());

        read("subject-a", "RESEARCH", "key-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        write("w2", "subject-a", "RESEARCH", "key-2", "payload-2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        // 数据不物理删除。
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE record_key = 'key-1'", Integer.class);
        assertThat(recordCount).isEqualTo(1);
    }

    @Test
    void revoke_replayOfOriginalWriteRequest_stillRejected() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());
        revoke("r1", "subject-a", "RESEARCH", 1).andExpect(status().isOk());

        // 撤回后即使重放已成功过的原写入请求（同 requestId 同参数）也必须拒绝，
        // 不能用幂等结果绕过授权状态。
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void regrant_afterRevoke_generatesNextEpochAndIsolatesOldData() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "old-payload").andExpect(status().isOk());
        revoke("r1", "subject-a", "RESEARCH", 1).andExpect(status().isOk());

        // 撤回后重新授权生成下一代。
        grant("g2", "subject-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 新 epoch 可复用 recordKey。
        write("w2", "subject-a", "RESEARCH", "key-1", "new-payload")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("new-payload"));

        // 查询只返回新代数据，不混入旧代。
        read("subject-a", "RESEARCH", "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("new-payload"));

        // 旧代数据仍在库中但不可见。
        Integer oldCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE epoch = 1 AND record_key = 'key-1'",
                Integer.class);
        assertThat(oldCount).isEqualTo(1);
    }

    @Test
    void purposes_areIndependent() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        grant("g2", "subject-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "research-data").andExpect(status().isOk());
        write("w2", "subject-a", "PERSONALIZATION", "key-1", "personal-data").andExpect(status().isOk());

        // 撤回研究用途不影响个性化用途。
        revoke("r1", "subject-a", "RESEARCH", 1).andExpect(status().isOk());

        read("subject-a", "RESEARCH", "key-1").andExpect(status().isGone());
        read("subject-a", "PERSONALIZATION", "key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("personal-data"));
    }

    // ---------- 幂等语义 ----------

    @Test
    void idempotency_sameRequestIdSameParams_returnsOriginalWithoutSideEffects() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());

        // 相同 requestId、相同参数重试返回原结果，不重复增 epoch。
        grant("g1", "subject-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));

        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));

        Integer grantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_grant", Integer.class);
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_record", Integer.class);
        assertThat(grantCount).isEqualTo(1);
        assertThat(recordCount).isEqualTo(1);

        revoke("r1", "subject-a", "RESEARCH", 1).andExpect(status().isOk());
        // 撤回请求同样幂等：同 requestId 重试返回原结果而非 409。
        revoke("r1", "subject-a", "RESEARCH", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void idempotency_sameRequestIdDifferentParams_returns409() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        // 同一 requestId 改变参数返回 409。
        grant("g1", "subject-b", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-changed")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void idempotency_failedRequestDoesNotConsumeRequestId() throws Exception {
        // 未授权时写入失败（404），requestId 不应被占用。
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isNotFound());

        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());

        // 相同 requestId 再次写入成功。
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }

    @Test
    void idempotency_recordsPersistedForRestartSemantics() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());
        write("w1", "subject-a", "RESEARCH", "key-1", "payload-1").andExpect(status().isOk());

        // 幂等记录持久化在数据库中，应用重启后仍从库中恢复语义。
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id IN ('g1', 'w1')",
                Integer.class);
        assertThat(idemCount).isEqualTo(2);
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrent_writeAndRevoke_finalStateConsistent() throws Exception {
        grant("g1", "subject-a", "RESEARCH").andExpect(status().isOk());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writeStatus = new AtomicInteger();
        AtomicInteger revokeStatus = new AtomicInteger();

        Thread writer = new Thread(() -> {
            ready.countDown();
            await(start);
            try {
                MvcResult result = mockMvc.perform(post("/api/records")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "requestId", "w1", "subjectKey", "subject-a",
                                        "purpose", "RESEARCH", "recordKey", "key-1",
                                        "payload", "payload-1"))))
                        .andReturn();
                writeStatus.set(result.getResponse().getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread revoker = new Thread(() -> {
            ready.countDown();
            await(start);
            try {
                MvcResult result = mockMvc.perform(post("/api/consents/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "requestId", "r1", "subjectKey", "subject-a",
                                        "purpose", "RESEARCH", "epoch", 1))))
                        .andReturn();
                revokeStatus.set(result.getResponse().getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        revoker.start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        writer.join(30000);
        revoker.join(30000);

        // 撤回最终生效；旧代查询立即返回 410。
        assertThat(revokeStatus.get()).isEqualTo(200);
        read("subject-a", "RESEARCH", "key-1").andExpect(status().isGone());

        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE record_key = 'key-1'", Integer.class);
        if (writeStatus.get() == 200) {
            // 写入先提交：记录属于旧代但随后不可见。
            assertThat(recordCount).isEqualTo(1);
        } else {
            // 撤回先提交：写入失败且不留记录。
            assertThat(writeStatus.get()).isEqualTo(410);
            assertThat(recordCount).isZero();
        }
    }

    // ---------- 请求辅助 ----------

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/consents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "subjectKey", subjectKey, "purpose", purpose))));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch)
            throws Exception {
        return mockMvc.perform(post("/api/consents/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "subjectKey", subjectKey,
                        "purpose", purpose, "epoch", epoch))));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "subjectKey", subjectKey, "purpose", purpose,
                        "recordKey", recordKey, "payload", payload))));
    }

    private ResultActions read(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
