package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.consent.ControllableClockConfig.ControllableClock;
import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权代理委托 API 集成测试：覆盖委托作用域、授权代次迁移、批次查询门禁、
 * 快照固化边界、批量续签冲突与并发幂等，全部基于真实 H2（MODE=MySQL）数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClockConfig.class)
class DelegateApiTest {

    private static final Instant FROM = Instant.parse("2025-12-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DelegateService delegateService;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void cleanTablesAndResetClock() {
        jdbc.update("DELETE FROM delegate_query_item");
        jdbc.update("DELETE FROM delegate_query");
        jdbc.update("DELETE FROM delegate_query_block");
        jdbc.update("DELETE FROM delegate_grant");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setInstant(ControllableClockConfig.BASE);
    }

    // ---------- 工具 ----------

    private void grant(String requestId, String subject, String purpose) {
        try {
            mockMvc.perform(post("/api/v1/consents/grants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                                    """.formatted(requestId, subject, purpose)))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void revokeGrant(String requestId, String subject, String purpose, int epoch) {
        try {
            mockMvc.perform(post("/api/v1/consents/revocations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                                    """.formatted(requestId, subject, purpose, epoch)))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeRecord(String requestId, String subject, String purpose, String key, String payload) {
        try {
            mockMvc.perform(post("/api/v1/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                                    """.formatted(requestId, subject, purpose, key, payload)))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MvcResult createDelegation(String subject, String delegate, List<String> purposes,
                                       Instant from, Instant to, int version) throws Exception {
        return mockMvc.perform(post("/api/v1/delegations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectKey", subject,
                                "delegateId", delegate,
                                "purposes", purposes,
                                "validFrom", from.toString(),
                                "validTo", to.toString(),
                                "delegateVersion", version))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String delegateKeyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("delegateKey").asText();
    }

    private MvcResult batchQuery(String requestId, String delegate, String purpose, List<String> subjects)
            throws Exception {
        return mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", requestId,
                                "delegateId", delegate,
                                "purpose", purpose,
                                "subjectKeys", subjects))))
                .andReturn();
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    // ---------- 委托创建：作用域与 422 ----------

    @Test
    void createDelegationSucceedsAndFingerprintIsStable() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        MvcResult first = createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        String key1 = delegateKeyOf(first);
        assertThat(key1).hasSize(64).matches("[0-9a-f]{64}");

        // 用途顺序与重复不影响规范化指纹：同键重放返回原委托
        MvcResult second = createDelegation("subj-a", "agent-1",
                List.of("RESEARCH", "RESEARCH"), FROM, TO, 1);
        assertThat(delegateKeyOf(second)).isEqualTo(key1);
        assertThat(count("SELECT COUNT(*) FROM delegate_grant")).isEqualTo(1);
    }

    @Test
    void createDelegationDifferentVersionOrWindowProducesDifferentKey() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        String keyV1 = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        String keyV2 = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, Instant.parse("2026-03-01T00:00:00Z"), 2));
        assertThat(keyV2).isNotEqualTo(keyV1);
    }

    @Test
    void createDelegationWithEmptyPurposesReturns422AndLeavesNothing() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        mockMvc.perform(post("/api/v1/delegations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectKey", "subj-a",
                                "delegateId", "agent-1",
                                "purposes", List.of(),
                                "validFrom", FROM.toString(),
                                "validTo", TO.toString(),
                                "delegateVersion", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATION_PURPOSES_EMPTY"));
        assertThat(count("SELECT COUNT(*) FROM delegate_grant")).isZero();
    }

    @Test
    void createDelegationToSelfReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        mockMvc.perform(post("/api/v1/delegations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectKey", "subj-a",
                                "delegateId", "subj-a",
                                "purposes", List.of("RESEARCH"),
                                "validFrom", FROM.toString(),
                                "validTo", TO.toString(),
                                "delegateVersion", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATION_SELF"));
    }

    @Test
    void createDelegationWithInvalidIntervalReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        mockMvc.perform(post("/api/v1/delegations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectKey", "subj-a",
                                "delegateId", "agent-1",
                                "purposes", List.of("RESEARCH"),
                                "validFrom", TO.toString(),
                                "validTo", FROM.toString(),
                                "delegateVersion", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATION_INTERVAL_INVALID"));
    }

    @Test
    void createDelegationWithoutActiveGrantReturns422() throws Exception {
        mockMvc.perform(post("/api/v1/delegations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectKey", "subj-none",
                                "delegateId", "agent-1",
                                "purposes", List.of("RESEARCH"),
                                "validFrom", FROM.toString(),
                                "validTo", TO.toString(),
                                "delegateVersion", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELEGATION_GRANT_UNAVAILABLE"));
    }

    // ---------- 批次查询主流程 ----------

    @Test
    void batchQueryReturnsSnapshotForAllSubjects() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-b", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        writeRecord("w-2", "subj-b", "RESEARCH", "rec-b", "payload-b");
        String keyA = delegateKeyOf(createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1));
        String keyB = delegateKeyOf(createDelegation("subj-b", "agent-1", List.of("RESEARCH"), FROM, TO, 1));

        MvcResult result = batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a", "subj-b"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("queryId").asText()).isEqualTo("q-1");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("subjectKey").asText()).isEqualTo("subj-a");
        assertThat(body.get("items").get(0).get("epoch").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("delegateKey").asText()).isEqualTo(keyA);
        assertThat(body.get("items").get(0).get("delegateVersion").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("records").get(0).get("payload").asText())
                .isEqualTo("payload-a");
        assertThat(body.get("items").get(1).get("delegateKey").asText()).isEqualTo(keyB);

        // 快照与明细均已固化
        assertThat(count("SELECT COUNT(*) FROM delegate_query WHERE query_id = 'q-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delegate_query_item WHERE query_id = 'q-1'")).isEqualTo(2);
    }

    @Test
    void batchQueryReplaySameRequestIdReturnsSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a"));
        MvcResult replay = batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a"));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM delegate_query")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delegate_query_item")).isEqualTo(1);
    }

    @Test
    void batchQuerySameRequestIdDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-b", "RESEARCH");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        createDelegation("subj-b", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a"));
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-1",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-b")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 批次阻断：逐主体稳定原因、无数据、无半成品 ----------

    @Test
    void batchQueryWithMissingDelegationIsBlockedForAllAndReturnsNoData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-b", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        writeRecord("w-2", "subj-b", "RESEARCH", "rec-b", "payload-b");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        // subj-b 未委托

        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-block",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a", "subj-b")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BATCH_QUERY_BLOCKED"))
                .andExpect(jsonPath("$.reasons[0].target").value("subj-b"))
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_NOT_FOUND"));

        // 无快照、无明细，阻断审计落库；失败不占用 requestId
        assertThat(count("SELECT COUNT(*) FROM delegate_query")).isZero();
        assertThat(count("SELECT COUNT(*) FROM delegate_query_item")).isZero();
        assertThat(count("SELECT COUNT(*) FROM delegate_query_block")).isEqualTo(1);

        // 补齐委托后同一 requestId 可成功
        createDelegation("subj-b", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-block",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a", "subj-b")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void batchQueryReasonsDistinguishGrantMissingAndRevoked() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-b", "RESEARCH");
        revokeGrant("r-1", "subj-b", "RESEARCH", 1);
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-block",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a", "subj-missing", "subj-b")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0].target").value("subj-b"))
                .andExpect(jsonPath("$.reasons[0].reason").value("CONSENT_REVOKED"))
                .andExpect(jsonPath("$.reasons[1].target").value("subj-missing"))
                .andExpect(jsonPath("$.reasons[1].reason").value("GRANT_NOT_FOUND"));
    }

    @Test
    void purposeNotCoveredIsBlocked() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-a", "PERSONALIZATION");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-block",
                                "delegateId", "agent-1",
                                "purpose", "PERSONALIZATION",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_PURPOSE_NOT_COVERED"));
    }

    // ---------- 授权代次迁移 ----------

    @Test
    void delegationBoundToOldEpochCannotQueryNewEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "epoch1-data");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a"));

        // 主体迁移用途代次：撤回旧代并重新授权
        revokeGrant("r-1", "subj-a", "RESEARCH", 1);
        grant("g-3", "subj-a", "RESEARCH");
        writeRecord("w-2", "subj-a", "RESEARCH", "rec-a2", "epoch2-data");

        // 旧代次委托（含续签产生的新指纹）不能用于新代次
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-2",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_EPOCH_MISMATCH"));

        // 为新代次创建委托后可查
        String keyEpoch2 = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-3",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(2))
                .andExpect(jsonPath("$.items[0].delegateKey").value(keyEpoch2))
                .andExpect(jsonPath("$.items[0].records.length()").value(1));
    }

    // ---------- 委托撤销 ----------

    @Test
    void revokeDelegationBlocksFutureQueriesButSnapshotRemains() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        String key = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        batchQuery("q-1", "agent-1", "RESEARCH", List.of("subj-a"));

        mockMvc.perform(post("/api/v1/delegations/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dr-1","delegateKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 撤销只影响后续查询
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-2",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_REVOKED"));

        // 已生成快照固化，仍可读取旧委托指纹与版本
        mockMvc.perform(get("/api/v1/delegations/queries/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].delegateKey").value(key))
                .andExpect(jsonPath("$.items[0].delegateVersion").value(1))
                .andExpect(jsonPath("$.items[0].records[0].payload").value("payload-a"));

        // 撤销幂等：同 requestId 重放返回原结果；新 requestId 再撤销冲突
        mockMvc.perform(post("/api/v1/delegations/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dr-1","delegateKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/delegations/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dr-2","delegateKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_ALREADY_REVOKED"));
    }

    @Test
    void revokeUnknownDelegationReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/delegations/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dr-x","delegateKey":"%s"}
                                """.formatted("0".repeat(64))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELEGATION_NOT_FOUND"));
    }

    // ---------- UTC 左闭右开有效期 ----------

    @Test
    void validityWindowIsHalfOpenAndClockDriven() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        // 起点时刻：有效（左闭）
        clock.setInstant(FROM);
        assertThat(batchQuery("q-from", "agent-1", "RESEARCH", List.of("subj-a")).getResponse().getStatus())
                .isEqualTo(200);

        // 终点时刻：过期（右开）
        clock.setInstant(TO);
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-to",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_EXPIRED"));

        // 起点之前：尚未生效
        clock.setInstant(FROM.minusSeconds(1));
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-before",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_NOT_YET_VALID"));
    }

    // ---------- 批量续签 ----------

    @Test
    void batchRenewValidatesEveryOldVersionAndAppliesAtomically() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-b", "RESEARCH");
        String keyA = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        String keyB = delegateKeyOf(createDelegation("subj-b", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));

        // keyB 当前版本为 1，却声称 3：整批冲突，keyA 也不得发生变化
        mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rn-bad",
                                "items", List.of(
                                        Map.of("delegateKey", keyA, "expectedVersion", 1,
                                                "validFrom", FROM.toString(),
                                                "validTo", Instant.parse("2026-04-01T00:00:00Z").toString()),
                                        Map.of("delegateKey", keyB, "expectedVersion", 3,
                                                "validFrom", FROM.toString(),
                                                "validTo", Instant.parse("2026-04-01T00:00:00Z").toString()))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_RENEWAL_CONFLICT"))
                .andExpect(jsonPath("$.reasons[0].target").value(keyB))
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_VERSION_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'REVOKED'")).isZero();

        // 修正版本后整批成功：旧委托撤销，新委托版本递增
        Instant newTo = Instant.parse("2026-04-01T00:00:00Z");
        MvcResult renewed = mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rn-ok",
                                "items", List.of(
                                        Map.of("delegateKey", keyA, "expectedVersion", 1,
                                                "validFrom", FROM.toString(), "validTo", newTo.toString()),
                                        Map.of("delegateKey", keyB, "expectedVersion", 1,
                                                "validFrom", FROM.toString(), "validTo", newTo.toString()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        JsonNode body = objectMapper.readTree(renewed.getResponse().getContentAsString());
        assertThat(body.get("items").get(0).get("delegateVersion").asInt()).isEqualTo(2);
        assertThat(body.get("items").get(1).get("delegateVersion").asInt()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'REVOKED'")).isEqualTo(2);

        // 时钟推进到旧区间终点之后：新委托仍有效，查询成功
        clock.setInstant(TO.plusSeconds(1));
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        assertThat(batchQuery("q-renewed", "agent-1", "RESEARCH", List.of("subj-a", "subj-b"))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void renewReplaySameRequestIdReturnsOriginalResult() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        String key = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        Instant newTo = Instant.parse("2026-04-01T00:00:00Z");
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "rn-1",
                "items", List.of(Map.of("delegateKey", key, "expectedVersion", 1,
                        "validFrom", FROM.toString(), "validTo", newTo.toString()))));
        MvcResult first = mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondNode = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondNode.get("items").get(0).get("newDelegateKey").asText())
                .isEqualTo(firstNode.get("items").get(0).get("newDelegateKey").asText());
        assertThat(count("SELECT COUNT(*) FROM delegate_grant")).isEqualTo(2);
    }

    @Test
    void renewUnknownDelegationIsReportedPerItem() throws Exception {
        String missing = "a".repeat(64);
        mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rn-missing",
                                "items", List.of(Map.of("delegateKey", missing, "expectedVersion", 1,
                                        "validFrom", FROM.toString(), "validTo", TO.toString()))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0].target").value(missing))
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_NOT_FOUND"));
    }

    // ---------- 历史与阻断查询 ----------

    @Test
    void historyAndBlocksAreQueryable() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);
        // 一次阻断
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-block",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-other")))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/delegations")
                        .param("subjectKey", "subj-a")
                        .param("delegateId", "agent-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].delegateVersion").value(1));

        mockMvc.perform(get("/api/v1/delegations/blocks")
                        .param("delegateId", "agent-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("q-block"))
                .andExpect(jsonPath("$[0].reasons[0].target").value("subj-other"));
    }

    @Test
    void unknownSnapshotReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/delegations/queries/q-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUERY_NOT_FOUND"));
    }

    // ---------- 多用途代次独立性与续签历史 ----------

    @Test
    void multiPurposeDelegationBindsIndependentEpochsPerPurpose() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        grant("g-2", "subj-a", "PERSONALIZATION");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-r", "research-data");
        writeRecord("w-2", "subj-a", "PERSONALIZATION", "rec-p", "personal-data");
        MvcResult created = createDelegation("subj-a", "agent-1",
                List.of("PERSONALIZATION", "RESEARCH"), FROM, TO, 1);
        JsonNode delegation = objectMapper.readTree(created.getResponse().getContentAsString());
        // 规范化用途升序，各用途分别绑定当前代次
        assertThat(delegation.get("purposes").get(0).asText()).isEqualTo("PERSONALIZATION");
        assertThat(delegation.get("purposes").get(1).asText()).isEqualTo("RESEARCH");
        assertThat(delegation.get("epochs").get("RESEARCH").asInt()).isEqualTo(1);
        assertThat(delegation.get("epochs").get("PERSONALIZATION").asInt()).isEqualTo(1);

        // 两个用途均可查询
        assertThat(batchQuery("q-r", "agent-1", "RESEARCH", List.of("subj-a")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(batchQuery("q-p", "agent-1", "PERSONALIZATION", List.of("subj-a")).getResponse().getStatus())
                .isEqualTo(200);

        // 仅迁移 RESEARCH 代次：RESEARCH 查询被代次不匹配阻断，PERSONALIZATION 不受影响
        revokeGrant("r-1", "subj-a", "RESEARCH", 1);
        grant("g-3", "subj-a", "RESEARCH");
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-r2",
                                "delegateId", "agent-1",
                                "purpose", "RESEARCH",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasons[0].reason").value("DELEGATION_EPOCH_MISMATCH"));
        mockMvc.perform(post("/api/v1/delegations/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "q-p2",
                                "delegateId", "agent-1",
                                "purpose", "PERSONALIZATION",
                                "subjectKeys", List.of("subj-a")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].records[0].payload").value("personal-data"));
    }

    @Test
    void renewKeepsHistoryChainWithOldVersionRevoked() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        String keyV1 = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        Instant newTo = Instant.parse("2026-04-01T00:00:00Z");
        mockMvc.perform(post("/api/v1/delegations/renewals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rn-1",
                                "items", List.of(Map.of("delegateKey", keyV1, "expectedVersion", 1,
                                        "validFrom", FROM.toString(), "validTo", newTo.toString()))))))
                .andExpect(status().isOk());

        // 历史包含旧版本（REVOKED）与新版本（ACTIVE）
        mockMvc.perform(get("/api/v1/delegations")
                        .param("subjectKey", "subj-a")
                        .param("delegateId", "agent-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.delegateKey == '" + keyV1 + "')].status").value("REVOKED"))
                .andExpect(jsonPath("$[?(@.delegateVersion == 2)].status").value("ACTIVE"));
    }

    // ---------- 并发幂等 ----------

    @Test
    void concurrentSameContentCreatesSingleDelegation() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return delegateService.create(new DelegateCreateRequest(
                        "subj-a", "agent-1", java.util.Set.of(Purpose.RESEARCH), FROM, TO, 1)).delegateKey();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String expected = futures.get(0).get(20, TimeUnit.SECONDS);
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(expected);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM delegate_grant")).isEqualTo(1);
    }

    @Test
    void concurrentRenewalsAreOrderedWithSingleWinner() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        String key = delegateKeyOf(createDelegation("subj-a", "agent-1",
                List.of("RESEARCH"), FROM, TO, 1));
        Instant newTo = Instant.parse("2026-04-01T00:00:00Z");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "rn-c-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    delegateService.renew(new DelegateRenewRequest(requestId, List.of(
                            new DelegateRenewRequest.RenewItem(key, 1, FROM, newTo))));
                    return 200;
                } catch (BatchRejectionException ex) {
                    return ex.getStatus().value();
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(20, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            } else {
                throw new AssertionError("意外状态: " + status);
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 最终状态一致：唯一有效委托版本为 2，历史链条完整
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'ACTIVE' AND delegate_version = 2"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delegate_grant WHERE status = 'REVOKED'")).isEqualTo(1);
    }

    @Test
    void concurrentBatchQueriesSameRequestIdCreateSingleSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH");
        writeRecord("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a");
        createDelegation("subj-a", "agent-1", List.of("RESEARCH"), FROM, TO, 1);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    delegateService.batchQuery(new com.example.starter.consent.dto.BatchQueryRequest(
                            "q-same", "agent-1", Purpose.RESEARCH, List.of("subj-a")));
                    return 200;
                } catch (Exception ex) {
                    return 500;
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM delegate_query WHERE query_id = 'q-same'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM delegate_query_item WHERE query_id = 'q-same'")).isEqualTo(1);
    }
}
