package com.example.starter.baggage;

import java.time.LocalDateTime;
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
 * 复重纠偏与超重拦截 API 测试（真实 H2 MySQL 兼容库）：
 * 覆盖复重改重/同重事件、不可变历史、超重提醒生成与清除、装载提醒携带、
 * OPEN 可复重/SEALED 禁止 409、复重后装载总重按最新重量校验及幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReweighApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BusinessClock businessClock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM overweight_alert");
        jdbcTemplate.update("DELETE FROM reweigh_record");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        businessClock.useSystem();
    }

    @Test
    void reweigh_changedWeightUpdatesBagAndAppendsImmutableHistory() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 30);
        businessClock.useFixed(LocalDateTime.of(2026, 3, 1, 8, 30));

        reweigh("BAG1", "RW-1", 25, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(25))
                .andExpect(jsonPath("$.journeyWeightKg").value(25))
                .andExpect(jsonPath("$.seq").value(0))
                .andExpect(jsonPath("$.overweightActive").value(false))
                .andExpect(jsonPath("$.weighedAt").value("2026-03-01T08:30"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.weightKg").value(25));

        // 同值复重：不改重量，仅追加事件
        reweigh("BAG1", "RW-2", 25, "STN-B").andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(25))
                .andExpect(jsonPath("$.seq").value(1));

        // 再次改重
        businessClock.useFixed(LocalDateTime.of(2026, 3, 2, 9, 0));
        reweigh("BAG1", "RW-3", 18, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(2));

        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(18))
                .andExpect(jsonPath("$.history", hasSize(3)))
                .andExpect(jsonPath("$.history[0].seq").value(0))
                .andExpect(jsonPath("$.history[0].reweighKey").value("RW-1"))
                .andExpect(jsonPath("$.history[0].oldWeightKg").value(20))
                .andExpect(jsonPath("$.history[0].newWeightKg").value(25))
                .andExpect(jsonPath("$.history[0].weightChanged").value(true))
                .andExpect(jsonPath("$.history[0].stationId").value("STN-A"))
                .andExpect(jsonPath("$.history[0].weighedAt").value("2026-03-01T08:30"))
                .andExpect(jsonPath("$.history[0].overweightRaised").value(false))
                .andExpect(jsonPath("$.history[1].weightChanged").value(false))
                .andExpect(jsonPath("$.history[1].oldWeightKg").value(25))
                .andExpect(jsonPath("$.history[1].newWeightKg").value(25))
                .andExpect(jsonPath("$.history[2].weighedAt").value("2026-03-02T09:00"));

        // 历史不可变：行数与首条固化值不随后续复重改变
        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE bag_tag = 'BAG1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(historyRows).isEqualTo(3);
        Integer firstOld = jdbcTemplate.queryForObject(
                "SELECT old_weight_kg FROM reweigh_record WHERE bag_tag = 'BAG1' AND seq = 0",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(firstOld).isEqualTo(20);
    }

    @Test
    void reweigh_overweightRaisesAlertShownInTraceStatusHistoryAndLoad() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 100);
        registerBag("BAG1", List.of("LEG1"), 20, 23);

        // 25 > 免费限额 23：生成 OVERWEIGHT 提醒，不阻止操作
        reweigh("BAG1", "RW-1", 25, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(true));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.overweightActive").value(true));
        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(jsonPath("$.overweightActive").value(true))
                .andExpect(jsonPath("$.history[0].overweightRaised").value(true));
        mockMvc.perform(get("/api/bags/BAG1/overweight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(true))
                .andExpect(jsonPath("$.alerts", hasSize(1)))
                .andExpect(jsonPath("$.alerts[0].seq").value(0))
                .andExpect(jsonPath("$.alerts[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.alerts[0].journeyWeightKg").value(25))
                .andExpect(jsonPath("$.alerts[0].freeAllowanceKg").value(23))
                .andExpect(jsonPath("$.alerts[0].reweighKey").value("RW-1"))
                .andExpect(jsonPath("$.alerts[0].clearedExplanation").doesNotExist());

        // 装载不拦截，但响应必须携带提醒清单
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWeightKg").value(25))
                .andExpect(jsonPath("$.maxLoadWeightKg").value(100))
                .andExpect(jsonPath("$.overweightWarnings", hasSize(1)))
                .andExpect(jsonPath("$.overweightWarnings[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.overweightWarnings[0].journeyWeightKg").value(25))
                .andExpect(jsonPath("$.overweightWarnings[0].freeAllowanceKg").value(23));
    }

    @Test
    void reweigh_doesNotRaiseDuplicateActiveAlertUntilCleared() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 23);

        reweigh("BAG1", "RW-1", 25, "STN-A").andExpect(status().isOk());
        // 持续超重的后续复重不重复生成活动提醒
        reweigh("BAG1", "RW-2", 26, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(true));
        mockMvc.perform(get("/api/bags/BAG1/overweight"))
                .andExpect(jsonPath("$.alerts", hasSize(1)));

        // 重量降回限额内，活动提醒仍需人工说明清除
        reweigh("BAG1", "RW-3", 22, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(true));
    }

    @Test
    void clearOverweight_requiresExplanationAndIsIrreversible() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 23);
        reweigh("BAG1", "RW-1", 30, "STN-A").andExpect(status().isOk());

        // 说明为空 -> 400
        mockMvc.perform(post("/api/bags/BAG1/overweight/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(), "explanation", ""))))
                .andExpect(status().isBadRequest());

        businessClock.useFixed(LocalDateTime.of(2026, 3, 3, 12, 0));
        clearOverweight("BAG1", "旅客已补缴逾重行李费，单据 XYZ").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(false))
                .andExpect(jsonPath("$.explanation").value("旅客已补缴逾重行李费，单据 XYZ"))
                .andExpect(jsonPath("$.clearedAt").value("2026-03-03T12:00"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.overweightActive").value(false));
        mockMvc.perform(get("/api/bags/BAG1/overweight"))
                .andExpect(jsonPath("$.overweightActive").value(false))
                .andExpect(jsonPath("$.alerts[0].status").value("CLEARED"))
                .andExpect(jsonPath("$.alerts[0].clearedExplanation").value("旅客已补缴逾重行李费，单据 XYZ"))
                .andExpect(jsonPath("$.alerts[0].clearedAt").value("2026-03-03T12:00"));

        // 清除不可逆：再次清除 -> 422
        clearOverweight("BAG1", "再次说明").andExpect(status().isUnprocessableEntity());

        // 清除后再次超限生成新序号的提醒；历史提醒保留为 CLEARED
        reweigh("BAG1", "RW-2", 31, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightActive").value(true));
        mockMvc.perform(get("/api/bags/BAG1/overweight"))
                .andExpect(jsonPath("$.overweightActive").value(true))
                .andExpect(jsonPath("$.alerts", hasSize(2)))
                .andExpect(jsonPath("$.alerts[0].status").value("CLEARED"))
                .andExpect(jsonPath("$.alerts[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.alerts[1].seq").value(1))
                .andExpect(jsonPath("$.alerts[1].journeyWeightKg").value(31));
    }

    @Test
    void reweigh_allowedInOpenLegButBlockedAfterSeal() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 100);
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());

        // 已装入 OPEN 航段未封舱：允许复重
        reweigh("BAG1", "RW-1", 22, "STN-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(22));

        seal("LEG1", 2).andExpect(status().isOk());

        // 进入 SEALED 清单：禁止复重 -> 409，重量不变
        reweigh("BAG1", "RW-2", 25, "STN-A").andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.weightKg").value(22));

        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        // 已完成（到达过封舱航段）同样禁止复重 -> 409
        reweigh("BAG1", "RW-3", 25, "STN-A").andExpect(status().isConflict());

        // 历史只有封舱前那一条
        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(jsonPath("$.history", hasSize(1)));
    }

    @Test
    void load_capacityUsesLatestWeightAfterReweigh() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 40);
        registerBag("BAG1", List.of("LEG1"), 20, 100);
        registerBag("BAG2", List.of("LEG1"), 20, 100);

        // 装载时 20 千克
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.totalWeightKg").value(20));
        // OPEN 航段内复重到 35
        reweigh("BAG1", "RW-1", 35, "STN-A").andExpect(status().isOk());

        // 再装 BAG2：必须按 35 计合计 55 > 40，整批 422，BAG2 不动
        load("LEG1", 2, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 装一个 5 千克的行李：35+5=40 恰好不超限，放行且 totalWeightKg 为 40
        registerBag("BAG3", List.of("LEG1"), 5, 100);
        load("LEG1", 2, List.of("BAG3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.totalWeightKg").value(40))
                .andExpect(jsonPath("$.loaded", contains("BAG3")));
    }

    @Test
    void load_warningsOnlyForUnclearedAlerts() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 1000);
        registerBag("BAG1", List.of("LEG1"), 20, 23);
        registerBag("BAG2", List.of("LEG1"), 20, 23);

        reweigh("BAG1", "RW-1", 25, "STN-A").andExpect(status().isOk());
        clearOverweight("BAG1", "已补缴费用").andExpect(status().isOk());
        reweigh("BAG2", "RW-2", 26, "STN-A").andExpect(status().isOk());

        // BAG1 提醒已清除、BAG2 未清除：仅 BAG2 出现在提醒清单
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overweightWarnings", hasSize(1)))
                .andExpect(jsonPath("$.overweightWarnings[0].bagTag").value("BAG2"));
    }

    @Test
    void reweigh_validationAndMissingBag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 23);

        // 实测重量越界（0 / 51）-> 400
        reweighRaw("BAG1", "RW-X", 0, "STN-A").andExpect(status().isBadRequest());
        reweighRaw("BAG1", "RW-X", 51, "STN-A").andExpect(status().isBadRequest());
        // 称重站为空 -> 400
        reweighRaw("BAG1", "RW-X", 25, "").andExpect(status().isBadRequest());
        // 行李不存在 -> 404
        reweigh("BAG_MISSING", "RW-X", 25, "STN-A").andExpect(status().isNotFound());
        // 无活动提醒时清除 -> 422
        clearOverweight("BAG1", "无中生有").andExpect(status().isUnprocessableEntity());
        clearOverweight("BAG_MISSING", "无中生有").andExpect(status().isNotFound());

        // 全部失败，重量不变、无历史
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.weightKg").value(20));
        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(jsonPath("$.history", hasSize(0)));
    }

    @Test
    void reweigh_idempotencyReplaysAndRejectsDifferentParams() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 100);

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "reweighKey", "RW-1",
                "measuredWeightKg", 25, "stationId", "STN-A");
        postJson("/api/bags/BAG1/reweigh", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(0));
        // 同键同参重放：返回首次结果，不新增历史
        postJson("/api/bags/BAG1/reweigh", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(0))
                .andExpect(jsonPath("$.weightKg").value(25));
        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(jsonPath("$.history", hasSize(1)));

        // 同键异参 -> 409
        postJson("/api/bags/BAG1/reweigh", Map.of("requestId", requestId, "reweighKey", "RW-1",
                "measuredWeightKg", 26, "stationId", "STN-A")).andExpect(status().isConflict());

        // 失败不占键：先用不存在的行李失败，再以同键成功
        String retryId = UUID.randomUUID().toString();
        postJson("/api/bags/BAG_MISSING/reweigh", Map.of("requestId", retryId, "reweighKey", "RW-2",
                "measuredWeightKg", 25, "stationId", "STN-A")).andExpect(status().isNotFound());
        postJson("/api/bags/BAG1/reweigh", Map.of("requestId", retryId, "reweighKey", "RW-2",
                "measuredWeightKg", 24, "stationId", "STN-A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1));
        mockMvc.perform(get("/api/bags/BAG1/reweigh-history"))
                .andExpect(jsonPath("$.history", hasSize(2)));
    }

    @Test
    void clearOverweight_idempotencyReplaysAndRejectsDifferentExplanation() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"), 20, 23);
        reweigh("BAG1", "RW-1", 30, "STN-A").andExpect(status().isOk());

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "explanation", "首次说明");
        postJson("/api/bags/BAG1/overweight/clear", body).andExpect(status().isOk());
        // 同键同参重放首次结果
        postJson("/api/bags/BAG1/overweight/clear", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("首次说明"));
        // 同键异参 -> 409
        postJson("/api/bags/BAG1/overweight/clear",
                Map.of("requestId", requestId, "explanation", "换个说明"))
                .andExpect(status().isConflict());

        Integer clearedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM overweight_alert WHERE bag_tag = 'BAG1' AND status = 'CLEARED'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(clearedRows).isEqualTo(1);

        // 失败不占键：无活动提醒时先 422，重新超重后同键成功
        String retryId = UUID.randomUUID().toString();
        postJson("/api/bags/BAG1/overweight/clear",
                Map.of("requestId", retryId, "explanation", "二次说明"))
                .andExpect(status().isUnprocessableEntity());
        reweigh("BAG1", "RW-2", 31, "STN-A").andExpect(status().isOk());
        postJson("/api/bags/BAG1/overweight/clear",
                Map.of("requestId", retryId, "explanation", "二次说明"))
                .andExpect(status().isOk());
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      Integer maxLoadWeightKg) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
        if (maxLoadWeightKg != null) {
            body.put("maxLoadWeightKg", maxLoadWeightKg);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds, int weightKg,
                                      int freeAllowanceKg) throws Exception {
        return postJson("/api/bags", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds,
                "weightKg", weightKg, "freeAllowanceKg", freeAllowanceKg));
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

    private ResultActions reweigh(String bagTag, String reweighKey, int measuredWeightKg,
                                  String stationId) throws Exception {
        return reweighRaw(bagTag, reweighKey, measuredWeightKg, stationId);
    }

    private ResultActions reweighRaw(String bagTag, String reweighKey, int measuredWeightKg,
                                     String stationId) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("reweighKey", reweighKey);
        body.put("measuredWeightKg", measuredWeightKg);
        body.put("stationId", stationId);
        return postJson("/api/bags/" + bagTag + "/reweigh", body);
    }

    private ResultActions clearOverweight(String bagTag, String explanation) throws Exception {
        return postJson("/api/bags/" + bagTag + "/overweight/clear", Map.of(
                "requestId", UUID.randomUUID().toString(), "explanation", explanation));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
