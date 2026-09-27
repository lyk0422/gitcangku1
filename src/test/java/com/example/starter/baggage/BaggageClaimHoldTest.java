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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李认领冻结 API 测试：覆盖冻结登记/复核/解除主流程、OPEN 清单同事务移出、
 * 失败分支（SEALED/已交付/重复冻结/摘要不匹配/同人或顺序违规）、
 * 冻结期间装载与补到阻断、解除后旧请求不自动恢复、幂等重放与链历史稳定性。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
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
    void mainFlow_holdBlocksLoadThenReviewAndReleaseRestores() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        hold("BAG1", "CK1", "DIGEST-1", "旅客认领待核验", "agent-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.bagStatus").value("CLAIM_HOLD"))
                .andExpect(jsonPath("$.priorStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.removedLegId").doesNotExist())
                .andExpect(jsonPath("$.heldBy").value("agent-a"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CLAIM_HOLD"));

        // 冻结期间禁止装载（409），失败不占 requestId 键
        String loadRequestId = UUID.randomUUID().toString();
        load("LEG1", 1, List.of("BAG1"), loadRequestId).andExpect(status().isConflict());

        // 复核：摘要不匹配 422，且失败不占键，可用同 requestId 更正重提
        String reviewRequestId = UUID.randomUUID().toString();
        review("CK1", "WRONG", "agent-b", reviewRequestId).andExpect(status().isUnprocessableEntity());
        // 复核人不得与冻结登记人相同
        review("CK1", "DIGEST-1", "agent-a").andExpect(status().isConflict());
        // 未复核不得解除
        release("CK1", "agent-b").andExpect(status().isConflict());

        review("CK1", "DIGEST-1", "agent-b", reviewRequestId).andExpect(status().isOk())
                .andExpect(jsonPath("$.holdStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewedBy").value("agent-b"));

        // 解除确认须由复核人本人提交
        release("CK1", "agent-c").andExpect(status().isConflict());
        release("CK1", "agent-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.holdStatus").value("RELEASED"))
                .andExpect(jsonPath("$.bagStatus").value("IN_TRANSIT"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 冻结期间的旧装载请求不自动恢复：解除后行李未装载，须重提并按当前状态校验
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        load("LEG1", 1, List.of("BAG1"), loadRequestId).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG1")));
    }

    @Test
    void hold_removesBagFromOpenManifestInSameTransaction() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        hold("BAG1", "CK1", "D1", "认领核验", "agent-a").andExpect(status().isCreated())
                .andExpect(jsonPath("$.removedLegId").value("LEG1"));

        // 同一事务内移出清单：load_record 不再含 BAG1，航段版本递增
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(records).isZero();
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(3));

        // 链记录：先清单移出后冻结登记，字段与顺序稳定
        mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].seq").value(0))
                .andExpect(jsonPath("$.events[0].eventType").value("MANIFEST_REMOVED"))
                .andExpect(jsonPath("$.events[0].legId").value("LEG1"))
                .andExpect(jsonPath("$.events[0].actor").value("agent-a"))
                .andExpect(jsonPath("$.events[1].seq").value(1))
                .andExpect(jsonPath("$.events[1].eventType").value("HOLD"))
                .andExpect(jsonPath("$.events[1].reason").value("认领核验"))
                .andExpect(jsonPath("$.events[1].legId").doesNotExist());

        // 封舱继续，清单只剩 BAG2
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG2")));
    }

    @Test
    void hold_rejectsSealedDeliveredAndDuplicate() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        registerBag("BAG3", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2", "BAG3")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());

        // 封舱先提交：已装载在 SEALED 航段的行李不可冻结
        hold("BAG1", "CK-S", "D", "r", "agent-a").andExpect(status().isConflict());

        arrive("LEG1", List.of("BAG1", "BAG2", "BAG3")).andExpect(status().isOk());
        // 已最终交付不可冻结
        hold("BAG1", "CK-D", "D", "r", "agent-a").andExpect(status().isConflict());

        // 同一行李同时只能一个生效冻结
        hold("BAG2", "CK1", "D", "r", "agent-a").andExpect(status().isCreated());
        hold("BAG2", "CK2", "D", "r", "agent-b").andExpect(status().isConflict());
        // claimKey 全局唯一
        hold("BAG3", "CK1", "D", "r", "agent-b").andExpect(status().isConflict());
        // 行李不存在
        hold("BAG-X", "CK-X", "D", "r", "agent-a").andExpect(status().isNotFound());
    }

    @Test
    void hold_blocksRecoverForShortUnloadedBag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        // 差异到达：BAG1 缺失转短卸
        arriveDifference("LEG1", 3, List.of()).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        hold("BAG1", "CK1", "D1", "短卸行李认领", "agent-a").andExpect(status().isCreated())
                .andExpect(jsonPath("$.priorStatus").value("SHORT_UNLOADED"));

        // 冻结期间禁止补到确认
        recover("BAG1", "LEG1", "SHA").andExpect(status().isConflict());

        review("CK1", "D1", "agent-b").andExpect(status().isOk());
        release("CK1", "agent-b").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagStatus").value("SHORT_UNLOADED"));

        // 解除后恢复短卸状态，可正常补到
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void load_batchWithHeldBagFailsAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());
        hold("BAG1", "CK1", "D1", "r", "agent-a").andExpect(status().isCreated());

        // 批量装载含被冻结行李：整批 409，无任何一件移动
        load("LEG1", 1, List.of("BAG2", "BAG1")).andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(records).isZero();
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(version).isEqualTo(1);
    }

    @Test
    void idempotency_replayAndConflict() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        String holdRequestId = UUID.randomUUID().toString();
        hold("BAG1", "CK1", "D1", "r", "agent-a", holdRequestId).andExpect(status().isCreated());
        // 同键同参重放首次结果
        hold("BAG1", "CK1", "D1", "r", "agent-a", holdRequestId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimKey").value("CK1"));
        // 同键异参 409
        hold("BAG1", "CK1", "D1", "r2", "agent-a", holdRequestId).andExpect(status().isConflict());
        Integer holds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'CK1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(holds).isEqualTo(1);

        String reviewRequestId = UUID.randomUUID().toString();
        review("CK1", "D1", "agent-b", reviewRequestId).andExpect(status().isOk());
        review("CK1", "D1", "agent-b", reviewRequestId).andExpect(status().isOk());
        review("CK1", "D1", "agent-c", reviewRequestId).andExpect(status().isConflict());

        String releaseRequestId = UUID.randomUUID().toString();
        release("CK1", "agent-b", releaseRequestId).andExpect(status().isOk());
        release("CK1", "agent-b", releaseRequestId).andExpect(status().isOk());
        // 重放只占一次效果：冻结单只解除一次
        Integer released = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'CK1' AND status = 'RELEASED'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(released).isEqualTo(1);
    }

    @Test
    void queries_detailHistoryAndDiagnosticsAreStableAndReadOnly() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        hold("BAG1", "CK1", "D1", "认领原因", "agent-a").andExpect(status().isCreated());

        // 明细：生效冻结可按行李与单号查询
        mockMvc.perform(get("/api/bags/BAG1/claim-hold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimKey").value("CK1"))
                .andExpect(jsonPath("$.holdStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.heldBy").value("agent-a"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.releasedAt").doesNotExist());
        mockMvc.perform(get("/api/claim-holds/CK1"))
                .andExpect(jsonPath("$.bagTag").value("BAG1"));
        mockMvc.perform(get("/api/claim-holds/CK-X")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG-X/claim-hold")).andExpect(status().isNotFound());

        // 诊断清单：按状态过滤
        mockMvc.perform(get("/api/claim-holds?status=ACTIVE"))
                .andExpect(jsonPath("$.holds", hasSize(1)));
        mockMvc.perform(get("/api/claim-holds?status=RELEASED"))
                .andExpect(jsonPath("$.holds", hasSize(0)));

        review("CK1", "D1", "agent-b").andExpect(status().isOk());
        release("CK1", "agent-b").andExpect(status().isOk());

        // 解除后历史不改写：链记录完整保留两位操作人与时刻，重复读取结果一致
        String first = mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].eventType").value("HOLD"))
                .andExpect(jsonPath("$.events[0].actor").value("agent-a"))
                .andExpect(jsonPath("$.events[1].eventType").value("REVIEW"))
                .andExpect(jsonPath("$.events[1].actor").value("agent-b"))
                .andExpect(jsonPath("$.events[2].eventType").value("RELEASE"))
                .andExpect(jsonPath("$.events[2].actor").value("agent-b"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/bags/BAG1/claim-hold/history"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);

        // 解除后无生效冻结，明细仍可按单号查询（记录不删除）
        mockMvc.perform(get("/api/bags/BAG1/claim-hold")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/claim-holds/CK1"))
                .andExpect(jsonPath("$.holdStatus").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("agent-b"));
        mockMvc.perform(get("/api/claim-holds?status=RELEASED"))
                .andExpect(jsonPath("$.holds", hasSize(1)));

        // 行李轨迹包含冻结与解除事件
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.events[1].eventType").value("CLAIM_HELD"))
                .andExpect(jsonPath("$.events[2].eventType").value("CLAIM_RELEASED"));
    }

    @Test
    void reviewAndRelease_unknownClaimKeyReturns404() throws Exception {
        review("CK-X", "D", "agent-b").andExpect(status().isNotFound());
        release("CK-X", "agent-b").andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return mockMvc.perform(post("/api/legs").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "legId", legId, "origin", origin, "destination", destination))));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return mockMvc.perform(post("/api/bags").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "bagTag", bagTag, "legIds", legIds))));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return load(legId, expectedVersion, bagTags, UUID.randomUUID().toString());
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags, String requestId)
            throws Exception {
        return mockMvc.perform(post("/api/legs/" + legId + "/load")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId,
                        "expectedVersion", expectedVersion, "bagTags", bagTags))));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/legs/" + legId + "/seal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "expectedVersion", expectedVersion))));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return mockMvc.perform(post("/api/legs/" + legId + "/arrive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(), "bagTags", bagTags))));
    }

    private ResultActions arriveDifference(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return mockMvc.perform(post("/api/legs/" + legId + "/arrive-difference")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "expectedVersion", expectedVersion, "bagTags", bagTags))));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return mockMvc.perform(post("/api/bags/recover").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "bagTag", bagTag, "missingLegId", missingLegId,
                        "actualStation", actualStation))));
    }

    private ResultActions hold(String bagTag, String claimKey, String verifyDigest,
                               String reason, String operator) throws Exception {
        return hold(bagTag, claimKey, verifyDigest, reason, operator, UUID.randomUUID().toString());
    }

    private ResultActions hold(String bagTag, String claimKey, String verifyDigest,
                               String reason, String operator, String requestId) throws Exception {
        return mockMvc.perform(post("/api/bags/" + bagTag + "/claim-hold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "claimKey", claimKey,
                        "verifyDigest", verifyDigest, "reason", reason, "operator", operator))));
    }

    private ResultActions review(String claimKey, String verifyDigest, String operator) throws Exception {
        return review(claimKey, verifyDigest, operator, UUID.randomUUID().toString());
    }

    private ResultActions review(String claimKey, String verifyDigest, String operator, String requestId)
            throws Exception {
        return mockMvc.perform(post("/api/claim-holds/" + claimKey + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "verifyDigest", verifyDigest, "operator", operator))));
    }

    private ResultActions release(String claimKey, String operator) throws Exception {
        return release(claimKey, operator, UUID.randomUUID().toString());
    }

    private ResultActions release(String claimKey, String operator, String requestId) throws Exception {
        return mockMvc.perform(post("/api/claim-holds/" + claimKey + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId, "operator", operator))));
    }
}
