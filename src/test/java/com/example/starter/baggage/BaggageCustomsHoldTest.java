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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 海关暂扣 API 测试：覆盖暂扣状态机（可暂扣/禁止暂扣分支）、OPEN 清单原子移除、
 * 暂扣期间装载与补到 409 阻断、解除双人确认、查询接口与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCustomsHoldTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM customs_hold");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void hold_transitionsToCustomsHoldAndBlocksLoadAndRecover() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());

        hold("BAG1", "HOLD1", "PEK", "疑似违禁品").andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.location").value("PEK"))
                .andExpect(jsonPath("$.removedFromLegId").doesNotExist());

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"));

        // 交接阻断原因查询：给出暂扣地点与原因
        mockMvc.perform(get("/api/bags/BAG1/transfer-block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.bagStatus").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.location").value("PEK"))
                .andExpect(jsonPath("$.reason").value("疑似违禁品"));

        // 暂扣后不得装载到任何后续航段：409 且消息携带暂扣地点
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("PEK")));
        // 暂扣后不得执行补到确认：409 且消息携带暂扣地点
        postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "SHA"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("PEK")));
    }

    @Test
    void hold_removesBagFromOpenManifestAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        hold("BAG1", "HOLD1", "PEK", "开箱查验").andExpect(status().isCreated())
                .andExpect(jsonPath("$.removedFromLegId").value("LEG1"));

        // 已从 OPEN 清单移除且航段版本推进，行李不再挂载任何航段
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void hold_rejectsDeliveredSealedLostAndAlreadyHeld() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        // 已到达最终目的地：DELIVERED
        registerBag("BAG_DELIVERED", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG_DELIVERED")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG_DELIVERED")).andExpect(status().isOk());
        hold("BAG_DELIVERED", "HOLD_D", "SHA", "查验").andExpect(status().isConflict());

        // 已处于 SEALED 航段
        registerBag("BAG_SEALED", List.of("LEG2")).andExpect(status().isCreated());
        // BAG_SEALED 登记在 SHA，可直接装载 LEG2
        load("LEG2", 1, List.of("BAG_SEALED")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        hold("BAG_SEALED", "HOLD_S", "SHA", "查验").andExpect(status().isConflict());

        // 已丢失（短卸未补到）
        registerLeg("LEG3", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG_LOST", List.of("LEG3")).andExpect(status().isCreated());
        registerBag("BAG_OK", List.of("LEG3", "LEG2")).andExpect(status().isCreated());
        load("LEG3", 1, List.of("BAG_LOST", "BAG_OK")).andExpect(status().isOk());
        seal("LEG3", 2).andExpect(status().isOk());
        postJson("/api/legs/LEG3/arrive-difference", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "bagTags", List.of("BAG_OK"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG_LOST/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));
        hold("BAG_LOST", "HOLD_L", "PEK", "查验").andExpect(status().isConflict());

        // 正常暂扣后不得重复暂扣
        hold("BAG_OK", "HOLD1", "SHA", "查验").andExpect(status().isCreated());
        hold("BAG_OK", "HOLD2", "SHA", "重复暂扣").andExpect(status().isConflict());

        // holdKey 全局唯一
        registerBag("BAG_OTHER", List.of("LEG3")).andExpect(status().isCreated());
        hold("BAG_OTHER", "HOLD1", "SHA", "占用同键").andExpect(status().isConflict());

        // 行李不存在
        hold("BAG_MISSING", "HOLD_X", "SHA", "查验").andExpect(status().isNotFound());
    }

    @Test
    void release_requiresTwoDistinctOperatorsAndRestoresBag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        hold("BAG1", "HOLD1", "PEK", "查验").andExpect(status().isCreated());

        // 第一人确认：仍处暂扣，进入待第二人确认清单
        confirmRelease("HOLD1", "OP_A").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SECOND_CONFIRM"))
                .andExpect(jsonPath("$.firstOperator").value("OP_A"))
                .andExpect(jsonPath("$.bagStatus").value("CUSTOMS_HOLD"));
        mockMvc.perform(get("/api/customs-holds/pending-second"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingSecondConfirm", hasSize(1)))
                .andExpect(jsonPath("$.pendingSecondConfirm[0].holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.pendingSecondConfirm[0].firstOperator").value("OP_A"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"));

        // 同一操作人重复确认 -> 422，且第一人确认不被替换
        confirmRelease("HOLD1", "OP_A").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/customs-holds/pending-second"))
                .andExpect(jsonPath("$.pendingSecondConfirm[0].firstOperator").value("OP_A"));

        // 第二人确认：原子解除，行李转回可交接状态
        confirmRelease("HOLD1", "OP_B").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.firstOperator").value("OP_A"))
                .andExpect(jsonPath("$.secondOperator").value("OP_B"))
                .andExpect(jsonPath("$.bagStatus").value("IN_TRANSIT"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        mockMvc.perform(get("/api/bags/BAG1/transfer-block"))
                .andExpect(jsonPath("$.blocked").value(false));
        mockMvc.perform(get("/api/customs-holds/pending-second"))
                .andExpect(jsonPath("$.pendingSecondConfirm", hasSize(0)));

        // 暂扣历史固化两名操作人与时刻
        mockMvc.perform(get("/api/bags/BAG1/customs-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.holds[0].firstOperator").value("OP_A"))
                .andExpect(jsonPath("$.holds[0].firstConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.holds[0].secondOperator").value("OP_B"))
                .andExpect(jsonPath("$.holds[0].secondConfirmedAt").isNotEmpty());

        // 已解除的暂扣不得再次确认
        confirmRelease("HOLD1", "OP_C").andExpect(status().isConflict());

        // 解除后装载须重新提交并按当前规则校验：重新提交成功
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded[0]").value("BAG1"));
    }

    @Test
    void release_unknownHoldKeyReturns404() throws Exception {
        confirmRelease("HOLD_MISSING", "OP_A").andExpect(status().isNotFound());
    }

    @Test
    void hold_idempotencyReplaySameKeyAndFailureDoesNotOccupyKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        // 同键同参重放：返回首次结果，只产生一条暂扣记录
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "bagTag", "BAG1",
                "holdKey", "HOLD1", "location", "PEK", "reason", "查验");
        postJson("/api/customs-holds", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("HOLD1"));
        postJson("/api/customs-holds", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdKey").value("HOLD1"));
        Integer holds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(holds).isEqualTo(1);

        // 同键异参 -> 409
        postJson("/api/customs-holds", Map.of("requestId", requestId, "bagTag", "BAG1",
                "holdKey", "HOLD1", "location", "SHA", "reason", "查验"))
                .andExpect(status().isConflict());

        // 失败不占键：重复暂扣 409 后，同键修正参数可成功
        String failedRequestId = UUID.randomUUID().toString();
        postJson("/api/customs-holds", Map.of("requestId", failedRequestId, "bagTag", "BAG1",
                "holdKey", "HOLD2", "location", "PEK", "reason", "重复暂扣"))
                .andExpect(status().isConflict());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());
        postJson("/api/customs-holds", Map.of("requestId", failedRequestId, "bagTag", "BAG2",
                "holdKey", "HOLD2", "location", "PEK", "reason", "查验"))
                .andExpect(status().isCreated());

        // 解除确认幂等：同键同参重放第一人确认，不推进状态
        String confirmRequestId = UUID.randomUUID().toString();
        Map<String, Object> confirmBody = Map.of("requestId", confirmRequestId, "operatorId", "OP_A");
        postJson("/api/customs-holds/HOLD1/confirm-release", confirmBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SECOND_CONFIRM"));
        postJson("/api/customs-holds/HOLD1/confirm-release", confirmBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SECOND_CONFIRM"));
        mockMvc.perform(get("/api/customs-holds/pending-second"))
                .andExpect(jsonPath("$.pendingSecondConfirm", hasSize(1)));
        // 解除确认同键异参 -> 409
        postJson("/api/customs-holds/HOLD1/confirm-release",
                Map.of("requestId", confirmRequestId, "operatorId", "OP_B"))
                .andExpect(status().isConflict());
    }

    @Test
    void queries_return404ForUnknownBag() throws Exception {
        mockMvc.perform(get("/api/bags/NOPE/customs-holds")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/transfer-block")).andExpect(status().isNotFound());
    }

    @Test
    void transferBlock_notHeldBagReturnsNotBlocked() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/bags/BAG1/transfer-block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.bagStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.holdKey").doesNotExist());
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

    private ResultActions hold(String bagTag, String holdKey, String location, String reason)
            throws Exception {
        return postJson("/api/customs-holds", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "holdKey", holdKey, "location", location, "reason", reason));
    }

    private ResultActions confirmRelease(String holdKey, String operatorId) throws Exception {
        return postJson("/api/customs-holds/" + holdKey + "/confirm-release",
                Map.of("requestId", UUID.randomUUID().toString(), "operatorId", operatorId));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
