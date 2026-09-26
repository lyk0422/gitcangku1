package com.example.starter.baggage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李认领冻结 API 测试：覆盖冻结-复核-解除主流程、OPEN 清单移出、
 * 失败分支（已交付/已封舱/重复冻结/摘要 mismatch/操作人约束）、
 * 短卸行李冻结恢复、幂等边界与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageClaimHoldTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    @Test
    void mainFlow_freezeRemovesFromOpenManifestAndBlocksUntilRelease() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());

        // 冻结：同事务移出 OPEN 清单，航段版本递增，行李转 CLAIM_HOLD
        freeze("BAG1", "CK1", "AGENT_A", "DIGEST-1", "乘客认领待核验")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimKey").value("CK1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").value("乘客认领待核验"))
                .andExpect(jsonPath("$.freezeAgent").value("AGENT_A"))
                .andExpect(jsonPath("$.reviewAgent").value(nullValue()))
                .andExpect(jsonPath("$.prevBagStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.removedLegId").value("LEG1"))
                .andExpect(jsonPath("$.frozenAt").value(notNullValue()))
                .andExpect(jsonPath("$.reviewedAt").value(nullValue()))
                .andExpect(jsonPath("$.releasedAt").value(nullValue()));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CLAIM_HOLD"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", hasSize(0)));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(loadCount).isZero();

        // 冻结期间禁止装载后续航段 -> 409
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isConflict());

        // 链记录：FROZEN + MANIFEST_REMOVED，固化航段、原因与操作人
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].eventType").value("FROZEN"))
                .andExpect(jsonPath("$.events[0].claimKey").value("CK1"))
                .andExpect(jsonPath("$.events[0].legId").value("LEG1"))
                .andExpect(jsonPath("$.events[0].reason").value("乘客认领待核验"))
                .andExpect(jsonPath("$.events[0].operatorId").value("AGENT_A"))
                .andExpect(jsonPath("$.events[0].counterpartId").value(nullValue()))
                .andExpect(jsonPath("$.events[1].eventType").value("MANIFEST_REMOVED"))
                .andExpect(jsonPath("$.events[1].legId").value("LEG1"))
                .andExpect(jsonPath("$.events[1].operatorId").value("AGENT_A"));

        // 复核：摘要不匹配 -> 422 且状态不变；同人复核 -> 409
        review("BAG1", "AGENT_B", "WRONG-DIGEST").andExpect(status().isUnprocessableEntity());
        review("BAG1", "AGENT_A", "DIGEST-1").andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 未复核直接解除 -> 409
        release("BAG1", "AGENT_B").andExpect(status().isConflict());

        // 不同客服复核成功 -> RELEASE_REVIEWED
        review("BAG1", "AGENT_B", "DIGEST-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_REVIEWED"))
                .andExpect(jsonPath("$.reviewAgent").value("AGENT_B"))
                .andExpect(jsonPath("$.reviewedAt").value(notNullValue()));

        // 解除确认须复核人本人 -> 他人 409
        release("BAG1", "AGENT_C").andExpect(status().isConflict());
        // 第二次确认原子恢复可交接状态
        release("BAG1", "AGENT_B").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").value(notNullValue()));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 冻结期间的旧装载不自动恢复：清单仍为空，须重提并按当前版本校验
        Integer loadCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(loadCountAfter).isZero();
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 历史链完整且解除后不回写冻结前后证据
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(jsonPath("$.events", hasSize(4)))
                .andExpect(jsonPath("$.events[2].eventType").value("REVIEWED"))
                .andExpect(jsonPath("$.events[2].operatorId").value("AGENT_B"))
                .andExpect(jsonPath("$.events[2].counterpartId").value("AGENT_A"))
                .andExpect(jsonPath("$.events[3].eventType").value("RELEASED"))
                .andExpect(jsonPath("$.events[3].operatorId").value("AGENT_B"))
                .andExpect(jsonPath("$.events[3].counterpartId").value("AGENT_A"));
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.prevBagStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.removedLegId").value("LEG1"));
    }

    @Test
    void freeze_rejectsInvalidStates() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG_SEALED", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG_DELIVERED", List.of("LEG2")).andExpect(status().isCreated());
        registerBag("BAG_ACTIVE", List.of("LEG1")).andExpect(status().isCreated());

        // 行李不存在 -> 404
        freeze("BAG_MISSING", "CK_M", "AGENT_A", "D", "R").andExpect(status().isNotFound());

        // 已 SEALED 航段清单内行李 -> 409
        load("LEG1", 1, List.of("BAG_SEALED")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        freeze("BAG_SEALED", "CK_S", "AGENT_A", "D", "R").andExpect(status().isConflict());

        // 已最终交付行李 -> 409
        load("LEG2", 1, List.of("BAG_DELIVERED")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG_DELIVERED")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG_DELIVERED/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        freeze("BAG_DELIVERED", "CK_D", "AGENT_A", "D", "R").andExpect(status().isConflict());

        // 同一行李同时只能一个生效冻结 -> 409
        freeze("BAG_ACTIVE", "CK_A1", "AGENT_A", "D", "R").andExpect(status().isCreated());
        freeze("BAG_ACTIVE", "CK_A2", "AGENT_B", "D", "R").andExpect(status().isConflict());

        // claimKey 全局唯一 -> 409
        registerBag("BAG_OTHER", List.of("LEG1")).andExpect(status().isCreated());
        freeze("BAG_OTHER", "CK_A1", "AGENT_A", "D", "R").andExpect(status().isConflict());
    }

    @Test
    void freeze_bagNotInManifest_hasNoRemovalEvent() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        freeze("BAG1", "CK1", "AGENT_A", "DIGEST-1", "认领核验")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.removedLegId").value(nullValue()));
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].eventType").value("FROZEN"))
                .andExpect(jsonPath("$.events[0].legId").value(nullValue()));

        review("BAG1", "AGENT_B", "DIGEST-1").andExpect(status().isOk());
        release("BAG1", "AGENT_B").andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        // 解除后可正常装载
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
    }

    @Test
    void freeze_shortUnloadedBag_blocksRecoverUntilRelease() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        // 差异到达：实际为空集，BAG1 转短卸
        postJson("/api/legs/LEG1/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", 3,
                "bagTags", List.of())).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        freeze("BAG1", "CK1", "AGENT_A", "DIGEST-1", "短卸行李认领")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prevBagStatus").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.removedLegId").value(nullValue()));

        // 冻结期间禁止补到确认 -> 409
        recover("BAG1", "LEG1", "SHA").andExpect(status().isConflict());

        review("BAG1", "AGENT_B", "DIGEST-1").andExpect(status().isOk());
        release("BAG1", "AGENT_B").andExpect(status().isOk());
        // 解除后恢复短卸状态，可正常补到
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void reviewAndRelease_requireExistingActiveHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        // 无生效冻结 -> 409；行李不存在 -> 404
        review("BAG1", "AGENT_B", "D").andExpect(status().isConflict());
        release("BAG1", "AGENT_B").andExpect(status().isConflict());
        review("BAG_MISSING", "AGENT_B", "D").andExpect(status().isNotFound());
        release("BAG_MISSING", "AGENT_B").andExpect(status().isNotFound());

        // 解除后的冻结不可再次复核/解除
        freeze("BAG1", "CK1", "AGENT_A", "D", "R").andExpect(status().isCreated());
        review("BAG1", "AGENT_B", "D").andExpect(status().isOk());
        review("BAG1", "AGENT_C", "D").andExpect(status().isConflict());
        release("BAG1", "AGENT_B").andExpect(status().isOk());
        review("BAG1", "AGENT_C", "D").andExpect(status().isConflict());
        release("BAG1", "AGENT_B").andExpect(status().isConflict());
    }

    @Test
    void idempotency_replayAndConflictAndFailureNotOccupying() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        // 同键同参重放首次结果，只产生一条冻结
        String freezeRequestId = UUID.randomUUID().toString();
        Map<String, Object> freezeBody = Map.of("requestId", freezeRequestId, "claimKey", "CK1",
                "agentId", "AGENT_A", "passengerDigest", "DIGEST-1", "reason", "认领核验");
        postJson("/api/bags/BAG1/claim-hold", freezeBody).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/bags/BAG1/claim-hold", freezeBody).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(holdCount).isEqualTo(1);

        // 同键异参 -> 409
        postJson("/api/bags/BAG1/claim-hold", Map.of("requestId", freezeRequestId,
                "claimKey", "CK2", "agentId", "AGENT_A", "passengerDigest", "DIGEST-1",
                "reason", "认领核验")).andExpect(status().isConflict());

        // 失败不占键：复核摘要 mismatch（422）后同键修正参数成功
        String reviewRequestId = UUID.randomUUID().toString();
        postJson("/api/bags/BAG1/claim-hold/review", Map.of("requestId", reviewRequestId,
                "agentId", "AGENT_B", "passengerDigest", "WRONG"))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/bags/BAG1/claim-hold/review", Map.of("requestId", reviewRequestId,
                "agentId", "AGENT_B", "passengerDigest", "DIGEST-1")).andExpect(status().isOk());

        // 解除同键重放
        String releaseRequestId = UUID.randomUUID().toString();
        Map<String, Object> releaseBody = Map.of("requestId", releaseRequestId, "agentId", "AGENT_B");
        postJson("/api/bags/BAG1/claim-hold/release", releaseBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        postJson("/api/bags/BAG1/claim-hold/release", releaseBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event WHERE claim_key = 'CK1'", Integer.class);
        assertThat(eventCount).isEqualTo(3);
    }

    @Test
    void queries_areReadOnlyAndStable() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());

        // 无冻结行李：明细 404，历史为空链；未知行李 404
        mockMvc.perform(get("/api/bags/BAG1/claim-hold")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(0)));
        mockMvc.perform(get("/api/bags/NOPE/claim-hold")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/claim-hold/history")).andExpect(status().isNotFound());

        freeze("BAG1", "CK1", "AGENT_A", "D1", "原因一").andExpect(status().isCreated());
        freeze("BAG2", "CK2", "AGENT_A", "D2", "原因二").andExpect(status().isCreated());

        // 诊断清单：全部与按状态过滤
        mockMvc.perform(get("/api/claim-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(2)));
        mockMvc.perform(get("/api/claim-holds?status=ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(2)));
        mockMvc.perform(get("/api/claim-holds?status=RELEASED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(0)));

        // 读取不改变状态：重复查询后冻结仍生效，链记录数不变
        mockMvc.perform(get("/api/bags/BAG1/claim-hold")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CLAIM_HOLD"));
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold_event", Integer.class);
        assertThat(eventCount).isEqualTo(2);
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

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions freeze(String bagTag, String claimKey, String agentId,
                                 String digest, String reason) throws Exception {
        return postJson("/api/bags/" + bagTag + "/claim-hold", Map.of(
                "requestId", UUID.randomUUID().toString(), "claimKey", claimKey,
                "agentId", agentId, "passengerDigest", digest, "reason", reason));
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
