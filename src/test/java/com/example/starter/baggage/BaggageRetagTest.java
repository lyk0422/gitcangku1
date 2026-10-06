package com.example.starter.baggage;

import java.util.LinkedHashMap;
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
 * 标签代次与别名测试（真实 H2）：换标主流程、旧标签写入门禁、历史标签追溯、
 * 封舱冻结快照不被换标倒改、到达按冻结稳定ID处理、各拒绝分支与注册占号约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageRetagTest {

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
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_tag_alias");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void retag_thenStaleTagRejectedAndCurrentTagLoads() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1", List.of("LEG1"));

        retag("ID-1", 1, "B2").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagId").value("ID-1"))
                .andExpect(jsonPath("$.oldBagTag").value("B1"))
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));

        // 旧标签写入：既不是未知（别名可查）也不是当前号 -> 409
        load("LEG1", 1, Map.of("B1", 2)).andExpect(status().isConflict());
        // 新号但代次错误 -> 409
        load("LEG1", 1, Map.of("B2", 1)).andExpect(status().isConflict());
        // 当前号 + 当前代次 -> 可装
        load("LEG1", 1, Map.of("B2", 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("B2")));

        // B1 作为历史别名仍追溯到同一稳定行李
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bagId").value("ID-1"))
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2))
                .andExpect(jsonPath("$.aliases[*].bagTag", contains("B1", "B2")))
                .andExpect(jsonPath("$.aliases[0].generation").value(1));
    }

    @Test
    void retag_betweenLegs_oldManifestKeepsFrozenTagAndId() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerLeg("LEG3", "CAN", "SZX");
        registerBag("ID-1", "B1", List.of("LEG1", "LEG2", "LEG3"));

        // 首段用 B1 完成
        load("LEG1", 1, Map.of("B1", 1));
        seal("LEG1", 2);
        arrive("LEG1", List.of("B1"));

        // 中转站 SHA 尚有后续航段：换 B2
        retag("ID-1", 1, "B2").andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(2));

        // LEG2 必须按 B2#2 装载，封舱清单冻结 B2
        load("LEG2", 1, Map.of("B2", 2));
        seal("LEG2", 2).andExpect(jsonPath("$.manifest", hasSize(1)))
                .andExpect(jsonPath("$.manifest[0].bagId").value("ID-1"))
                .andExpect(jsonPath("$.manifest[0].bagTag").value("B2"))
                .andExpect(jsonPath("$.manifest[0].tagGeneration").value(2));
        // 到达按冻结清单的稳定ID处理，标签仍用冻结的 B2
        arrive("LEG2", List.of("B2")).andExpect(status().isOk());

        // 到达 CAN 后再换 B3：旧封舱清单保持原号 B2，不倒改
        retag("ID-1", 2, "B3").andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(3));
        mockMvc.perform(get("/api/legs/LEG2/manifest"))
                .andExpect(jsonPath("$.manifest[0].bagTag").value("B2"))
                .andExpect(jsonPath("$.manifest[0].tagGeneration").value(2));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.manifest[0].bagTag").value("B1"));

        // B1/B2/B3 查询都追溯同一 bagId，当前号为 B3
        for (String tag : List.of("B1", "B2", "B3")) {
            mockMvc.perform(get("/api/bags/" + tag + "/trace"))
                    .andExpect(jsonPath("$.bagId").value("ID-1"))
                    .andExpect(jsonPath("$.bagTag").value("B3"))
                    .andExpect(jsonPath("$.tagGeneration").value(3));
        }
        // 既有行程不重置：仍待乘第 3 段，位于 CAN
        mockMvc.perform(get("/api/bags/B3/trace"))
                .andExpect(jsonPath("$.nextLegIndex").value(2))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.itinerary", hasSize(3)));

        // 后续航段只能按 B3#3 装载
        load("LEG3", 1, Map.of("B2", 3)).andExpect(status().isConflict());
        load("LEG3", 1, Map.of("B3", 3)).andExpect(status().isOk());
    }

    @Test
    void retag_rejectsLoadedShortAndDelivered() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1", List.of("LEG1"));

        // 已装舱（未封舱/未到达）禁止换标
        load("LEG1", 1, Map.of("B1", 1));
        retag("ID-1", 1, "B2").andExpect(status().isUnprocessableEntity());
        seal("LEG1", 2);
        // 封舱在舱仍然禁止
        retag("ID-1", 1, "B2").andExpect(status().isUnprocessableEntity());
        arrive("LEG1", List.of("B1"));
        // 已交付禁止
        retag("ID-1", 1, "B2").andExpect(status().isUnprocessableEntity());

        // 短卸状态禁止换标
        registerLeg("LEG2", "PEK", "SHA");
        registerLeg("LEG3", "SHA", "CAN");
        registerBag("ID-2", "S1", List.of("LEG2", "LEG3"));
        load("LEG2", 1, Map.of("S1", 1));
        seal("LEG2", 2);
        diffArrive("LEG2", 3, List.of());
        retag("ID-2", 1, "S2").andExpect(status().isUnprocessableEntity());
    }
    @Test
    void retag_rejectsWrongGenerationUnknownIdAndOccupiedTags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1", List.of("LEG1"));
        registerBag("ID-2", "C1", List.of("LEG1"));

        // 期望代次错误 -> 409
        retag("ID-1", 9, "B2").andExpect(status().isConflict());
        // 稳定ID未知 -> 404
        retag("ID-MISSING", 1, "B2").andExpect(status().isNotFound());
        // 新标签与当前标签相同 -> 409
        retag("ID-1", 1, "B1").andExpect(status().isConflict());
        // 新标签是另一实物的当前标签 -> 409
        retag("ID-1", 1, "C1").andExpect(status().isConflict());

        // 成功换为 B2
        retag("ID-1", 1, "B2").andExpect(status().isOk());
        // 新标签复用自己的旧标签 -> 409
        retag("ID-1", 2, "B1").andExpect(status().isConflict());
        // 旧标签永不分配给另一实物：以 B1 注册新行李 -> 409
        registerBag("ID-3", "B1", List.of("LEG1")).andExpect(status().isConflict());
        // 换标失败不推进代次
        mockMvc.perform(get("/api/bags/B2/trace"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
    }

    @Test
    void retag_idempotentReplayKeepsCurrentTagAndDoesNotReoccupy() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("ID-1", "B1", List.of("LEG1", "LEG2"));

        // 首次换标 B1 -> B2
        String firstKey = UUID.randomUUID().toString();
        retagBody(firstKey, "ID-1", 1, "B2", "op-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        // 同键同参重放：返回首次完整响应，但数据不再变化
        retagBody(firstKey, "ID-1", 1, "B2", "op-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.oldBagTag").value("B1"))
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        Integer gen = jdbcTemplate.queryForObject(
                "SELECT tag_generation FROM bag WHERE bag_id = 'ID-1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(gen).isEqualTo(2);
        Integer aliasCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_id = 'ID-1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(aliasCount).isEqualTo(2);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, firstKey);
        org.assertj.core.api.Assertions.assertThat(logCount).isEqualTo(1);

        // 继续换到 B3 后，重放旧响应 B1->B2 不会把当前标签退回 B2、也不重新占号
        retag("ID-1", 2, "B3").andExpect(status().isOk());
        retagBody(firstKey, "ID-1", 1, "B2", "op-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.bagTag").value("B3"))
                .andExpect(jsonPath("$.tagGeneration").value(3));
        // 同键异参（改新号或操作者）-> 409
        retagBody(firstKey, "ID-1", 1, "B9", "op-1").andExpect(status().isConflict());
        retagBody(firstKey, "ID-1", 1, "B2", "op-2").andExpect(status().isConflict());
    }

    @Test
    void retag_failureDoesNotOccupyKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1", List.of("LEG1"));

        String key = UUID.randomUUID().toString();
        // 先以错误代次失败（409），不占键
        retagBody(key, "ID-1", 9, "B2", "op-1").andExpect(status().isConflict());
        // 再以占用标签失败（409），同样不占键
        retagBody(key, "ID-1", 1, "B1", "op-1").andExpect(status().isConflict());
        // 同键修正参数后成功
        retagBody(key, "ID-1", 1, "B2", "op-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(2));
    }

    @Test
    void arrive_afterSeal_processesFrozenManifestByBagIdAndRetagWaitsForUnload() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerLeg("LEG3", "CAN", "SZX");
        registerBag("ID-1", "B1", List.of("LEG1", "LEG2", "LEG3"));

        // LEG1 用 B1 封舱后到达 SHA
        load("LEG1", 1, Map.of("B1", 1));
        seal("LEG1", 2);
        arrive("LEG1", List.of("B1"));

        // LEG2 用 B2 封舱；封舱后仍在舱内，换标被拒绝（不允许在途换号）
        retag("ID-1", 1, "B2");
        load("LEG2", 1, Map.of("B2", 2));
        seal("LEG2", 2);
        retag("ID-1", 2, "B3").andExpect(status().isUnprocessableEntity());

        // 到达按原冻结清单的标签 B2 与稳定 ID 处理，不形成清单外物品
        arrive("LEG2", List.of("B3")).andExpect(status().isUnprocessableEntity());
        arrive("LEG2", List.of("B2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.arrived[0].bagId").value("ID-1"))
                .andExpect(jsonPath("$.arrived[0].bagTag").value("B2"))
                .andExpect(jsonPath("$.arrived[0].tagGeneration").value(2));

        // 卸下后（仍在途、尚有后续航段）才可换到 B3；旧封舱清单保持冻结的 B2
        retag("ID-1", 2, "B3").andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B2/trace"))
                .andExpect(jsonPath("$.bagTag").value("B3"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegIndex").value(2))
                .andExpect(jsonPath("$.currentLocation").value("CAN"));
        mockMvc.perform(get("/api/legs/LEG2/manifest"))
                .andExpect(jsonPath("$.manifest[0].bagTag").value("B2"))
                .andExpect(jsonPath("$.manifest[0].bagId").value("ID-1"));
    }

    @Test
    void retag_historyQueryUnknownTag404AndEventAppended() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1", List.of("LEG1"));
        retag("ID-1", 1, "B2").andExpect(status().isOk());

        mockMvc.perform(get("/api/bags/UNKNOWN/trace")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "TAG_REPLACED")));
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "operator", "op-1", "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagId, String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "operator", "op-1", "bagId", bagId, "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions retag(String bagId, int expectedGeneration, String newBagTag) throws Exception {
        return retagBody(UUID.randomUUID().toString(), bagId, expectedGeneration, newBagTag, "op-1");
    }

    private ResultActions retagBody(String requestId, String bagId, int expectedGeneration,
                                    String newBagTag, String operator) throws Exception {
        return postJson("/api/bags/retag", Map.of(
                "requestId", requestId, "operator", operator, "bagId", bagId,
                "expectedTagGeneration", expectedGeneration, "newBagTag", newBagTag));
    }

    private ResultActions load(String legId, int expectedVersion,
                               Map<String, Integer> generations) throws Exception {
        List<String> bagTags = new java.util.ArrayList<>(generations.keySet());
        java.util.Collections.sort(bagTags);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("operator", "op-1");
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        body.put("expectedTagGenerations", generations);
        return postJson("/api/legs/" + legId + "/load", body);
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "op-1",
                "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "op-1", "bagTags", bagTags));
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "op-1",
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
// __APPEND__
}
