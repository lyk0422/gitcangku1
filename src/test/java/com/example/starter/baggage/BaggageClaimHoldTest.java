package com.example.starter.baggage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李认领冻结测试：覆盖冻结登记（含 OPEN 清单同事务移出）、复核、解除确认主流程，
 * 失败分支（已交付/已封舱/重复冻结 409、摘要不匹配 422、越权复核/解除 409）、
 * 冻结期间装载与补到拦截、解除后重提装载、链历史/明细/诊断查询与幂等边界，
 * 以及 H2 真实库上的生效冻结唯一约束。全部基于 H2 MySQL 兼容模式，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageClaimHoldTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-26T01:02:03Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM claim_hold_event");
        jdbcTemplate.update("DELETE FROM claim_hold");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(() -> FIXED_NOW);
    }

    @Test
    void mainFlow_holdRemovesFromOpenManifestThenReviewAndRelease() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());

        // 冻结：同事务移出 OPEN 清单，航段版本递增，行李转 CLAIM_HOLD
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.prevStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.removedLegId").value("LEG1"))
                .andExpect(jsonPath("$.holdAgent").value("agent-a"))
                .andExpect(jsonPath("$.reviewAgent").doesNotExist())
                .andExpect(jsonPath("$.releaseAgent").doesNotExist())
                .andExpect(jsonPath("$.heldAt").value("2026-09-26T01:02:03Z"))
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.releasedAt").doesNotExist());

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CLAIM_HOLD"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        // 清单已移出且航段版本递增
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", hasSize(0)));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(loadCount).isZero();

        // 冻结期间禁止装载任何后续航段（409）
        String staleLoadRequestId = UUID.randomUUID().toString();
        Map<String, Object> staleLoadBody = Map.of("requestId", staleLoadRequestId,
                "expectedVersion", 3, "bagTags", List.of("BAG1"));
        postJson("/api/legs/LEG1/load", staleLoadBody).andExpect(status().isConflict());
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isConflict());

        // 复核：摘要不匹配 422，登记客服本人复核 409
        review("BAG1", "agent-b", "digest-x").andExpect(status().isUnprocessableEntity());
        review("BAG1", "agent-a", "digest-1").andExpect(status().isConflict());
        review("BAG1", "agent-b", "digest-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewAgent").value("agent-b"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-09-26T01:02:03Z"));

        // 解除：非复核客服确认 409，复核客服第二次确认原子恢复
        release("BAG1", "agent-a").andExpect(status().isConflict());
        release("BAG1", "agent-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.bagStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.releasedAt").value("2026-09-26T01:02:03Z"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 链历史：清单移出 -> 冻结 -> 复核 -> 解除，字段与 null 语义稳定
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(4)))
                .andExpect(jsonPath("$.events[0].seq").value(0))
                .andExpect(jsonPath("$.events[0].eventType").value("MANIFEST_REMOVE"))
                .andExpect(jsonPath("$.events[0].legId").value("LEG1"))
                .andExpect(jsonPath("$.events[0].reason").doesNotExist())
                .andExpect(jsonPath("$.events[0].agentId").value("agent-a"))
                .andExpect(jsonPath("$.events[0].eventTime").value("2026-09-26T01:02:03Z"))
                .andExpect(jsonPath("$.events[1].eventType").value("HOLD"))
                .andExpect(jsonPath("$.events[1].legId").value("LEG1"))
                .andExpect(jsonPath("$.events[1].reason").value("乘客认领争议"))
                .andExpect(jsonPath("$.events[2].eventType").value("REVIEW"))
                .andExpect(jsonPath("$.events[2].legId").doesNotExist())
                .andExpect(jsonPath("$.events[2].agentId").value("agent-b"))
                .andExpect(jsonPath("$.events[3].eventType").value("RELEASE"))
                .andExpect(jsonPath("$.events[3].agentId").value("agent-b"));

        // 解除后明细保留完整审计字段，历史不被改写
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.removedLegId").value("LEG1"))
                .andExpect(jsonPath("$.reviewAgent").value("agent-b"))
                .andExpect(jsonPath("$.releaseAgent").value("agent-b"));

        // 冻结期间失败的旧装载请求不占键、不自动恢复，解除后须重提并按当前版本校验
        postJson("/api/legs/LEG1/load", staleLoadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.loaded", contains("BAG1")));
    }

    @Test
    void hold_rejectsTerminalAndSealedStates() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());

        // 已封舱航段上的行李不可冻结：409 且无任何半成品
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(jsonPath("$.events", hasSize(0)));
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold", Integer.class);
        assertThat(holdRows).isZero();

        // 已最终交付行李不可冻结
        arrive("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isConflict());

        // 行李不存在 -> 404
        hold("BAG_MISSING", "agent-a", "digest-1").andExpect(status().isNotFound());
    }

    @Test
    void hold_rejectsSecondActiveHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated());
        // 同一行李同时只能一个生效冻结
        hold("BAG1", "agent-c", "digest-2").andExpect(status().isConflict());

        // 解除后可再次冻结（生效去重键解除后释放）
        review("BAG1", "agent-b", "digest-1").andExpect(status().isOk());
        release("BAG1", "agent-b").andExpect(status().isOk());
        hold("BAG1", "agent-c", "digest-2").andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void hold_shortUnloadedBagBlocksRecoverAndRestoresStatus() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        // 差异到达：BAG1 缺失转短卸
        postJson("/api/legs/LEG1/arrive-difference", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "bagTags", List.of())).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        // 短卸行李可冻结，冻结期间禁止补到确认
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.prevStatus").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.removedLegId").doesNotExist());
        recover("BAG1").andExpect(status().isConflict());

        // 解除后原子恢复为短卸状态，补到按正常流程重提
        review("BAG1", "agent-b", "digest-1").andExpect(status().isOk());
        release("BAG1", "agent-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagStatus").value("SHORT_UNLOADED"));
        recover("BAG1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
    }

    @Test
    void idempotency_holdReviewReleaseReplayAndConflict() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        // 失败请求不占键：先 404，再同键成功
        String holdRequestId = UUID.randomUUID().toString();
        Map<String, Object> holdBody = Map.of("requestId", holdRequestId, "claimKey", "CK-1",
                "agentId", "agent-a", "passengerDigest", "digest-1", "reason", "乘客认领争议");
        postJson("/api/bags/BAG_MISSING/claim-hold", holdBody).andExpect(status().isNotFound());
        String firstHoldJson = postJson("/api/bags/BAG1/claim-hold", holdBody)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstHoldId = objectMapper.readTree(firstHoldJson).get("holdId").asLong();
        // 同键同参重放：返回原结果，不产生第二条冻结与链记录
        postJson("/api/bags/BAG1/claim-hold", holdBody).andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value(firstHoldId));
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold", Integer.class);
        assertThat(holdRows).isEqualTo(1);
        Integer chainRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event", Integer.class);
        assertThat(chainRows).isEqualTo(1);
        // 同键异参 -> 409
        postJson("/api/bags/BAG1/claim-hold", Map.of("requestId", holdRequestId, "claimKey", "CK-2",
                "agentId", "agent-a", "passengerDigest", "digest-1", "reason", "乘客认领争议"))
                .andExpect(status().isConflict());

        // 复核与解除同样幂等
        String reviewRequestId = UUID.randomUUID().toString();
        Map<String, Object> reviewBody = Map.of("requestId", reviewRequestId,
                "agentId", "agent-b", "passengerDigest", "digest-1");
        postJson("/api/bags/BAG1/claim-hold/review", reviewBody).andExpect(status().isOk());
        postJson("/api/bags/BAG1/claim-hold/review", reviewBody).andExpect(status().isOk());
        Integer reviewedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE status = 'REVIEWED'", Integer.class);
        assertThat(reviewedRows).isEqualTo(1);

        String releaseRequestId = UUID.randomUUID().toString();
        Map<String, Object> releaseBody = Map.of("requestId", releaseRequestId, "agentId", "agent-b");
        postJson("/api/bags/BAG1/claim-hold/release", releaseBody).andExpect(status().isOk());
        postJson("/api/bags/BAG1/claim-hold/release", releaseBody).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        Integer chainAfterRelease = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event", Integer.class);
        assertThat(chainAfterRelease).isEqualTo(3);
    }

    @Test
    void reviewAndRelease_rejectInvalidSequences() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());

        // 无生效冻结时复核/解除 -> 409
        review("BAG1", "agent-b", "digest-1").andExpect(status().isConflict());
        release("BAG1", "agent-b").andExpect(status().isConflict());
        // 行李不存在 -> 404
        review("BAG_MISSING", "agent-b", "digest-1").andExpect(status().isNotFound());
        release("BAG_MISSING", "agent-b").andExpect(status().isNotFound());

        // 未复核不得解除；重复复核 409
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated());
        release("BAG1", "agent-b").andExpect(status().isConflict());
        review("BAG1", "agent-b", "digest-1").andExpect(status().isOk());
        review("BAG1", "agent-c", "digest-1").andExpect(status().isConflict());

        // 复核失败（422）不改变冻结状态，仍可用正确摘要复核
        hold("BAG2", "agent-a", "digest-2").andExpect(status().isCreated());
        review("BAG2", "agent-b", "digest-x").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BAG2/claim-hold"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reviewAgent").doesNotExist());
        review("BAG2", "agent-b", "digest-2").andExpect(status().isOk());
    }

    @Test
    void queries_detailHistoryDiagnosticsAndReadOnly() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());

        // 无冻结记录：明细 404，历史为空清单
        mockMvc.perform(get("/api/bags/BAG1/claim-hold")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG_MISSING/claim-hold")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG_MISSING/claim-hold/history"))
                .andExpect(status().isNotFound());

        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated());
        hold("BAG2", "agent-c", "digest-2").andExpect(status().isCreated());
        review("BAG2", "agent-b", "digest-2").andExpect(status().isOk());
        release("BAG2", "agent-b").andExpect(status().isOk());

        // 诊断查询：全部两条，按状态过滤
        mockMvc.perform(get("/api/claim-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(2)))
                .andExpect(jsonPath("$.holds[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.holds[1].bagTag").value("BAG2"));
        mockMvc.perform(get("/api/claim-holds?status=ACTIVE"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].bagTag").value("BAG1"));
        mockMvc.perform(get("/api/claim-holds?status=RELEASED"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].bagTag").value("BAG2"));
        mockMvc.perform(get("/api/claim-holds?status=BOGUS"))
                .andExpect(status().isUnprocessableEntity());

        // 读取不改变状态：重复查询后冻结与链记录保持不变
        mockMvc.perform(get("/api/bags/BAG1/claim-hold")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/claim-holds")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.heldAt").value("2026-09-26T01:02:03Z"));
        Integer chainRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(chainRows).isEqualTo(1);
    }

    @Test
    void database_enforcesSingleActiveHoldPerBag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        hold("BAG1", "agent-a", "digest-1").andExpect(status().isCreated());

        // H2 真实库唯一约束：同一行李第二条生效冻结被拒绝
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO claim_hold (bag_tag, claim_key, passenger_digest, reason, prev_status,"
                        + " status, hold_agent, active_bag_tag, held_at)"
                        + " VALUES ('BAG1', 'CK-9', 'digest-9', '重复冻结', 'IN_TRANSIT',"
                        + " 'ACTIVE', 'agent-c', 'BAG1', CURRENT_TIMESTAMP)"))
                .isInstanceOf(DuplicateKeyException.class);
        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE active_bag_tag = 'BAG1'", Integer.class);
        assertThat(activeRows).isEqualTo(1);
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag) throws Exception {
        return postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", "LEG1", "actualStation", "SHA"));
    }

    private ResultActions hold(String bagTag, String agentId, String digest) throws Exception {
        return postJson("/api/bags/" + bagTag + "/claim-hold", Map.of(
                "requestId", UUID.randomUUID().toString(), "claimKey", "CK-1",
                "agentId", agentId, "passengerDigest", digest, "reason", "乘客认领争议"));
    }

    private ResultActions review(String bagTag, String agentId, String digest) throws Exception {
        return postJson("/api/bags/" + bagTag + "/claim-hold/review", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "agentId", agentId, "passengerDigest", digest));
    }

    private ResultActions release(String bagTag, String agentId) throws Exception {
        return postJson("/api/bags/" + bagTag + "/claim-hold/release", Map.of(
                "requestId", UUID.randomUUID().toString(), "agentId", agentId));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
