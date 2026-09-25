package com.example.starter.baggage;

import java.util.HashMap;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 优先舱行李复重纠偏与超重拦截测试：覆盖复重主流程（纠偏/相同仅记录/完整历史）、
 * 超重提醒标记与人工清除、SEALED 禁复重、装载前置总重校验使用最新重量、
 * 装载响应携带提醒清单以及幂等边界。
 * 测试环境批量装载总重上限为 60 千克（src/test/resources/application.yaml）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageReweighTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM reweigh_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void reweigh_mainFlowCorrectsWeightAndKeepsFullHistory() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 18, 20).andExpect(status().isCreated())
                .andExpect(jsonPath("$.weightKg").value(18))
                .andExpect(jsonPath("$.freeAllowanceKg").value(20))
                .andExpect(jsonPath("$.overweightReminder").value("NONE"));

        // 实测不同：原子纠偏 18 -> 25，超过限额 20，标记 ACTIVE
        reweigh("BAG1", "RW-1", 25, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousWeightKg").value(18))
                .andExpect(jsonPath("$.weightKg").value(25))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.stationId").value("ST-A"))
                .andExpect(jsonPath("$.weighedAt", notNullValue()))
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.weightKg").value(25))
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));

        // 实测相同：仅记录事件，不改变重量，仍超重
        reweigh("BAG1", "RW-2", 25, "ST-B").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousWeightKg").value(25))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));

        // 再次复重回到限额内：提醒解除
        reweigh("BAG1", "RW-3", 15, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.overweightReminder").value("NONE"));

        // 完整历史：三次复重全部保留，固化原重量/新重量/称重站
        mockMvc.perform(get("/api/bags/BAG1/reweighs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(15))
                .andExpect(jsonPath("$.freeAllowanceKg").value(20))
                .andExpect(jsonPath("$.overweightReminder").value("NONE"))
                .andExpect(jsonPath("$.history", hasSize(3)))
                .andExpect(jsonPath("$.history[0].reweighKey").value("RW-1"))
                .andExpect(jsonPath("$.history[0].oldWeightKg").value(18))
                .andExpect(jsonPath("$.history[0].newWeightKg").value(25))
                .andExpect(jsonPath("$.history[0].weightChanged").value(true))
                .andExpect(jsonPath("$.history[0].stationId").value("ST-A"))
                .andExpect(jsonPath("$.history[1].reweighKey").value("RW-2"))
                .andExpect(jsonPath("$.history[1].weightChanged").value(false))
                .andExpect(jsonPath("$.history[2].oldWeightKg").value(25))
                .andExpect(jsonPath("$.history[2].newWeightKg").value(15));
    }

    @Test
    void reweigh_firstWeighingHasNullPreviousWeight() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.weightKg", nullValue()))
                .andExpect(jsonPath("$.freeAllowanceKg").value(20));

        reweigh("BAG1", "RW-1", 12, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousWeightKg", nullValue()))
                .andExpect(jsonPath("$.weightKg").value(12))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.overweightReminder").value("NONE"));
    }

    @Test
    void reweigh_rejectsInvalidRequests() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());

        // 实测重量越界或缺失 -> 400
        reweigh("BAG1", "RW-Z", 0, "ST-A").andExpect(status().isBadRequest());
        reweigh("BAG1", "RW-Z", 51, "ST-A").andExpect(status().isBadRequest());
        postJson("/api/bags/BAG1/reweigh", Map.of("requestId", UUID.randomUUID().toString(),
                "reweighKey", "RW-Z", "stationId", "ST-A")).andExpect(status().isBadRequest());
        // 称重站缺失 -> 400
        postJson("/api/bags/BAG1/reweigh", Map.of("requestId", UUID.randomUUID().toString(),
                "reweighKey", "RW-Z", "measuredWeightKg", 10)).andExpect(status().isBadRequest());
        // 行李不存在 -> 404
        reweigh("BAG_MISSING", "RW-Z", 10, "ST-A").andExpect(status().isNotFound());
        // 复重历史查询：行李不存在 -> 404
        mockMvc.perform(get("/api/bags/BAG_MISSING/reweighs")).andExpect(status().isNotFound());

        // 上述失败均不产生复重记录
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(records).isZero();
    }

    @Test
    void reweigh_duplicateReweighKeyReturns409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), 10, 20).andExpect(status().isCreated());

        reweigh("BAG1", "RW-DUP", 12, "ST-A").andExpect(status().isOk());
        // 同一 reweighKey 即使换 requestId、换行李也被拒绝
        reweigh("BAG1", "RW-DUP", 12, "ST-A").andExpect(status().isConflict());
        reweigh("BAG2", "RW-DUP", 12, "ST-A").andExpect(status().isConflict());
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(records).isEqualTo(1);
    }

    @Test
    void reweigh_forbiddenOnceSealedAndAllowedWhileOpen() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), 10, 20).andExpect(status().isCreated());

        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        // 已装入 OPEN 航段但未封舱：允许复重
        reweigh("BAG1", "RW-OPEN", 22, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(22))
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));

        seal("LEG1", 2).andExpect(status().isOk());
        // 进入 SEALED 清单：禁止复重 -> 409
        reweigh("BAG1", "RW-SEALED", 23, "ST-A").andExpect(status().isConflict());
        // 未入清单的 BAG2 不受影响
        reweigh("BAG2", "RW-OTHER", 12, "ST-A").andExpect(status().isOk());

        // 到达后清单仍为只读历史，依旧禁止复重
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        reweigh("BAG1", "RW-ARRIVED", 24, "ST-A").andExpect(status().isConflict());
    }

    @Test
    void overweight_clearRequiresNoteAndIsIrreversible() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());

        // 无提醒时清除 -> 422
        clearOverweight("BAG1", "提前清除").andExpect(status().isUnprocessableEntity());

        reweigh("BAG1", "RW-1", 30, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));

        // 说明缺失 -> 400
        postJson("/api/bags/BAG1/overweight/clear", Map.of(
                "requestId", UUID.randomUUID().toString())).andExpect(status().isBadRequest());

        // 携带说明清除成功，状态与说明可查
        clearOverweight("BAG1", "旅客已现场补缴超重费").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminder").value("CLEARED"))
                .andExpect(jsonPath("$.note").value("旅客已现场补缴超重费"))
                .andExpect(jsonPath("$.clearedAt", notNullValue()));
        mockMvc.perform(get("/api/bags/BAG1/reweighs"))
                .andExpect(jsonPath("$.overweightReminder").value("CLEARED"))
                .andExpect(jsonPath("$.overweightClearNote").value("旅客已现场补缴超重费"));

        // 清除不可逆：重复清除 -> 422
        clearOverweight("BAG1", "再次清除").andExpect(status().isUnprocessableEntity());

        // 新的复重仍超重：产生新的 ACTIVE 提醒
        reweigh("BAG1", "RW-2", 35, "ST-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.overweightReminder").value("ACTIVE"));
    }

    @Test
    void load_carriesOverweightRemindersButDoesNotBlock() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1", "LEG2"), 10, 20).andExpect(status().isCreated());

        reweigh("BAG2", "RW-1", 25, "ST-A").andExpect(status().isOk());

        // 装载不被超重提醒阻止，但响应携带提醒清单
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.totalWeightKg").value(35))
                .andExpect(jsonPath("$.overweightReminders", contains("BAG2")));

        // 清除提醒后再次装载（下一航段），提醒清单为空
        clearOverweight("BAG2", "已补缴").andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminders", hasSize(0)));
    }

    @Test
    void load_totalWeightLimitUsesLatestReweighedWeight() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 40, 50).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), 10, 50).andExpect(status().isCreated());

        // 复重把 BAG2 从 10 纠偏到 30：批总重 70 超过上限 60，装载被拒绝（不能用装载时旧值 10）
        reweigh("BAG2", "RW-UP", 30, "ST-A").andExpect(status().isOk());
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(loadCount).isZero();

        // 复重把 BAG1 从 40 纠偏到 25：批总重 55 回到上限内，装载成功且按最新重量计总重
        reweigh("BAG1", "RW-DOWN", 25, "ST-A").andExpect(status().isOk());
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWeightKg").value(55));
    }

    @Test
    void reweigh_idempotencyReplayConflictAndFailure() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());

        // 失败不占键：先用不存在的行李失败（404），同键修正后成功
        String requestId = UUID.randomUUID().toString();
        postJson("/api/bags/BAG_MISSING/reweigh", reweighBody(requestId, "RW-1", 12, "ST-A"))
                .andExpect(status().isNotFound());
        postJson("/api/bags/BAG1/reweigh", reweighBody(requestId, "RW-1", 12, "ST-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(12));

        // 同键同参重放首次结果，不产生新复重记录
        postJson("/api/bags/BAG1/reweigh", reweighBody(requestId, "RW-1", 12, "ST-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(12));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(records).isEqualTo(1);

        // 同键异参 -> 409
        postJson("/api/bags/BAG1/reweigh", reweighBody(requestId, "RW-1", 13, "ST-A"))
                .andExpect(status().isConflict());
    }

    @Test
    void clearOverweight_idempotencyReplay() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), 10, 20).andExpect(status().isCreated());
        reweigh("BAG1", "RW-1", 30, "ST-A").andExpect(status().isOk());

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "note", "已补缴");
        postJson("/api/bags/BAG1/overweight/clear", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminder").value("CLEARED"));
        // 同键同参重放成功结果（而非 422）
        postJson("/api/bags/BAG1/overweight/clear", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightReminder").value("CLEARED"));
        // 同键异参 -> 409
        postJson("/api/bags/BAG1/overweight/clear", Map.of("requestId", requestId, "note", "其他说明"))
                .andExpect(status().isConflict());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds,
                                      Integer weightKg, Integer freeAllowanceKg) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", legIds);
        if (weightKg != null) {
            body.put("weightKg", weightKg);
        }
        if (freeAllowanceKg != null) {
            body.put("freeAllowanceKg", freeAllowanceKg);
        }
        return postJson("/api/bags", body);
    }

    private ResultActions reweigh(String bagTag, String reweighKey, int measuredWeightKg,
                                  String stationId) throws Exception {
        return postJson("/api/bags/" + bagTag + "/reweigh",
                reweighBody(UUID.randomUUID().toString(), reweighKey, measuredWeightKg, stationId));
    }

    private Map<String, Object> reweighBody(String requestId, String reweighKey,
                                            int measuredWeightKg, String stationId) {
        return Map.of("requestId", requestId, "reweighKey", reweighKey,
                "measuredWeightKg", measuredWeightKg, "stationId", stationId);
    }

    private ResultActions clearOverweight(String bagTag, String note) throws Exception {
        return postJson("/api/bags/" + bagTag + "/overweight/clear",
                Map.of("requestId", UUID.randomUUID().toString(), "note", note));
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

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
