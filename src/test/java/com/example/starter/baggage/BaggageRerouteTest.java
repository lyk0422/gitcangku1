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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 剩余行程整体改派 API 测试：覆盖改派主流程（保留前缀、替换后缀、版本递增、历史与轨迹）、
 * 失败分支（404/400/409/422 与整次回滚无部分后缀）、补到后改派以及 requestId 幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageRerouteTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-23T08:09:10Z");

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
        jdbcTemplate.update("DELETE FROM bag_reroute_history");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
    }

    @Test
    void reroute_keepsCompletedPrefixReplacesSuffixAndVersionBumpsThenLoadsNewRoute() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        // 原行程 L1(PEK->SHA) -> L2(SHA->CAN)；新后缀 N1(SHA->KWL) -> N2(KWL->CAN)，最终目的地仍为 CAN
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));

        // 完成首段后到达 SHA，待乘索引推进到 1
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "N1", "N2")))
                .andExpect(jsonPath("$.itinerary[0].seq").value(0))
                .andExpect(jsonPath("$.itinerary[1].seq").value(1))
                .andExpect(jsonPath("$.itinerary[2].seq").value(2));

        // 轨迹追加 REROUTED 事件，位置不推进
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.registeredDestination").value("CAN"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "REROUTED")))
                .andExpect(jsonPath("$.events[3].location").value("SHA"))
                .andExpect(jsonPath("$.events[3].eventTime").value(FIXED_NOW.toString()));

        // 改派历史：前后完整行程、版本、前缀及 UTC 时刻
        mockMvc.perform(get("/api/bags/BAG1/reroutes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].seq").value(0))
                .andExpect(jsonPath("$.history[0].fromRouteVersion").value(1))
                .andExpect(jsonPath("$.history[0].toRouteVersion").value(2))
                .andExpect(jsonPath("$.history[0].prefixLegs", contains("L1")))
                .andExpect(jsonPath("$.history[0].beforeItinerary[*].legId",
                        contains("L1", "L2")))
                .andExpect(jsonPath("$.history[0].afterItinerary[*].legId",
                        contains("L1", "N1", "N2")))
                .andExpect(jsonPath("$.history[0].reroutedAt").value(FIXED_NOW.toString()));

        // 原待乘航段 L2 不再接受该行李
        load("L2", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        // 按新后缀执行原批量装载直至交付
        load("N1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("N1", 2);
        arriveExact("N1", List.of("BAG1"));
        load("N2", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("N2", 2);
        arriveExact("N2", List.of("BAG1"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "N1", "N2")));
    }

    @Test
    void reroute_multipleTimesKeepsFullHistoryAndIncrementsVersion() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));
        // 第二次改派：前缀仍为已完成的 L1，版本 2 -> 3
        reroute("BAG1", 2, List.of("L2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2")));

        mockMvc.perform(get("/api/bags/BAG1/reroutes"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[0].fromRouteVersion").value(1))
                .andExpect(jsonPath("$.history[0].toRouteVersion").value(2))
                .andExpect(jsonPath("$.history[1].fromRouteVersion").value(2))
                .andExpect(jsonPath("$.history[1].toRouteVersion").value(3))
                .andExpect(jsonPath("$.history[1].beforeItinerary[*].legId",
                        contains("L1", "N1", "N2")))
                .andExpect(jsonPath("$.history[1].afterItinerary[*].legId",
                        contains("L1", "L2")));
    }

    @Test
    void reroute_allowsFiveLegTotalWithEmptyPrefix() throws Exception {
        // 未出发行李前缀为空：0 段前缀 + 5 新段 = 5 合法；原始登记 1 段 PEK->CAN
        registerLeg("D1", "PEK", "CAN");
        registerLeg("R1", "PEK", "S1");
        registerLeg("R2", "S1", "S2");
        registerLeg("R3", "S2", "S3");
        registerLeg("R4", "S3", "S4");
        registerLeg("R5", "S4", "CAN");
        registerBag("BAG1", List.of("D1"));

        reroute("BAG1", 1, List.of("R1", "R2", "R3", "R4", "R5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary", hasSize(5)));
    }

    @Test
    void reroute_rejectsInvalidRouteWithFullRollback() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        // 目的地改变 -> 422
        registerLeg("B1", "SHA", "KWL");
        registerLeg("B2", "KWL", "PEK");
        reroute("BAG1", 1, List.of("B1", "B2")).andExpect(status().isUnprocessableEntity());
        // 新后缀内部不连续 -> 422
        registerLeg("M2", "CTU", "CAN");
        reroute("BAG1", 1, List.of("N1", "M2")).andExpect(status().isUnprocessableEntity());
        // 首段起点不等于当前位置 -> 422
        registerLeg("P1", "KWL", "CAN");
        reroute("BAG1", 1, List.of("P1")).andExpect(status().isUnprocessableEntity());
        // 新后缀内部重复 -> 422
        reroute("BAG1", 1, List.of("N1", "N1")).andExpect(status().isUnprocessableEntity());
        // 前缀 1 段 + 5 新段 = 6，超过 5 段 -> 422
        registerLeg("E1", "SHA", "A");
        registerLeg("E2", "A", "B");
        registerLeg("E3", "B", "C");
        registerLeg("E4", "C", "D");
        registerLeg("E5", "D", "CAN");
        reroute("BAG1", 1, List.of("E1", "E2", "E3", "E4", "E5"))
                .andExpect(status().isUnprocessableEntity());

        // 后缀引用已完成前缀航段 L1：该航段已 ARRIVED 非 OPEN，改派同样被拒绝（409）且不产生部分后缀
        registerLeg("Y1", "SHA", "KWL");
        registerLeg("Y2", "KWL", "PEK");
        registerLeg("Y3", "SHA", "KWL");
        reroute("BAG1", 1, List.of("Y1", "Y2", "L1", "Y3", "N2"))
                .andExpect(status().isConflict());

        // 行程版本错误 -> 409
        reroute("BAG1", 99, List.of("N1", "N2")).andExpect(status().isConflict());

        // 全部失败后无任何部分后缀、历史或事件，版本不变
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2")))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED")));
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history", Integer.class);
        assertThat(historyCount).isZero();
        Integer seqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(seqCount).isEqualTo(2);
    }

    @Test
    void reroute_rejectsIllegalStateAndNonOpenLeg() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));

        // 已装载 -> 409
        load("L1", 1, List.of("BAG1"));
        reroute("BAG1", 1, List.of("L2")).andExpect(status().isConflict());

        // 到达卸下后恢复在途
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        // 新航段先封舱：封舱先提交则改派失败 -> 409（状态校验先于目的地校验）
        seal("N1", 1);
        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isConflict());

        // 送达 -> 409
        load("L2", 1, List.of("BAG1"));
        seal("L2", 2);
        arriveExact("L2", List.of("BAG1"));
        reroute("BAG1", 2, List.of("L2")).andExpect(status().isConflict());
    }

    @Test
    void reroute_shortUnloadedRejectsButRecoveredBagCanReroute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        // 差异到达：BAG1 缺失，转短卸
        diffArrive("L1", 3, List.of());

        // 尚处 SHORT_UNLOADED -> 409，避免用改派绕过真实补到
        reroute("BAG1", 1, List.of("L1", "N1", "N2")).andExpect(status().isConflict());

        // 真实补到后（位于 SHA，待乘索引 1）可改派剩余后缀
        recover("BAG1", "L1", "SHA");
        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
    }

    @Test
    void reroute_returns404And400() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerBag("BAG1", List.of("L1"));

        // 行李不存在 -> 404
        reroute("NOPE", 1, List.of("L1")).andExpect(status().isNotFound());
        // 新航段不存在 -> 404
        reroute("BAG1", 1, List.of("NOPE")).andExpect(status().isNotFound());
        // 新后缀为空 -> 400
        reroute("BAG1", 1, List.of()).andExpect(status().isBadRequest());
        // 新后缀 6 段 -> 400
        reroute("BAG1", 1, List.of("L1", "L1", "L1", "L1", "L1", "L1"))
                .andExpect(status().isBadRequest());
        // 缺少 expectedRouteVersion -> 400
        postJson("/api/bags/reroute", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", "BAG1", "newLegIds", List.of("L1")))
                .andExpect(status().isBadRequest());

        // 历史查询未知行李 -> 404
        mockMvc.perform(get("/api/bags/NOPE/reroutes")).andExpect(status().isNotFound());
    }

    @Test
    void reroute_idempotentReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        // 同键同参重放首次结果：版本只递增一次、历史与事件只追加一条
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId,
                "bagTag", "BAG1", "expectedRouteVersion", 1,
                "newLegIds", List.of("N1", "N2"));
        postJson("/api/bags/reroute", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));
        postJson("/api/bags/reroute", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "N1", "N2")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG1' AND event_type = 'REROUTED'",
                Integer.class)).isEqualTo(1);

        // 同键改参 -> 409
        postJson("/api/bags/reroute", Map.of("requestId", requestId,
                "bagTag", "BAG1", "expectedRouteVersion", 1,
                "newLegIds", List.of("L2")))
                .andExpect(status().isConflict());

        // 失败不占键：先用该键以错误版本失败，再以正确参数成功
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/bags/reroute", Map.of("requestId", retryKey,
                "bagTag", "BAG1", "expectedRouteVersion", 99,
                "newLegIds", List.of("L2")))
                .andExpect(status().isConflict());
        postJson("/api/bags/reroute", Map.of("requestId", retryKey,
                "bagTag", "BAG1", "expectedRouteVersion", 2,
                "newLegIds", List.of("L2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3));
    }

    @Test
    void reroute_replayDoesNotOverwriteLaterRoute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1"));
        seal("L1", 2);
        arriveExact("L1", List.of("BAG1"));

        String firstKey = UUID.randomUUID().toString();
        postJson("/api/bags/reroute", Map.of("requestId", firstKey,
                "bagTag", "BAG1", "expectedRouteVersion", 1,
                "newLegIds", List.of("N1", "N2"))).andExpect(status().isOk());
        // 另一请求把路线改回 L2（版本 2 -> 3）
        reroute("BAG1", 2, List.of("L2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3));
        // 旧键重放返回首次结果（版本 2、后缀 N1/N2），但不覆盖当前路线
        postJson("/api/bags/reroute", Map.of("requestId", firstKey,
                "bagTag", "BAG1", "expectedRouteVersion", 1,
                "newLegIds", List.of("N1", "N2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "N1", "N2")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(2);
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(new com.example.starter.baggage.BaggageDtos.RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new com.example.starter.baggage.BaggageDtos.RegisterBagRequest(
                UUID.randomUUID().toString(), bagTag, legIds));
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

    private ResultActions arriveExact(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions reroute(String bagTag, int expectedRouteVersion, List<String> newLegIds)
            throws Exception {
        return postJson("/api/bags/reroute", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "expectedRouteVersion", expectedRouteVersion,
                "newLegIds", newLegIds));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
