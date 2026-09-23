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
 * 剩余行程改派 API 测试：覆盖未装载行李改派主流程、已完成前缀保留、二次改派、
 * 改派后按新后缀装载且旧航段拒收、改派历史与轨迹事件、
 * 失败分支（404/400/409/422）、整次回滚、短卸与补到边界以及幂等重放。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageRerouteTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-23T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM reroute_history");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
    }

    @Test
    void reroute_unloadedBagReplacesWholeSuffixAndKeepsPositionAndIndex() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        // 原行程 PEK->SHA->CAN
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        // 新后缀 PEK->XIY->CAN，最终目的地 CAN 不变
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "CAN");

        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.itinerary", hasSize(2)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("N1"))
                .andExpect(jsonPath("$.itinerary[1].legId").value("N2"));

        // 行李视图同步：旧航段不在行程中，位置与待乘索引不变，版本加一
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.itinerary[0].legId").value("N1"))
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "REROUTED")))
                .andExpect(jsonPath("$.events[1].legId").value("N1"))
                .andExpect(jsonPath("$.events[1].eventTime").value(FIXED_NOW.toString()));

        // 改派历史：前后完整行程、版本与 UTC 时刻
        mockMvc.perform(get("/api/bags/BAG1/reroutes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].versionFrom").value(1))
                .andExpect(jsonPath("$.history[0].versionTo").value(2))
                .andExpect(jsonPath("$.history[0].nextLegIndex").value(0))
                .andExpect(jsonPath("$.history[0].reroutedAt").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.history[0].beforeItinerary[*].legId", contains("L1", "L2")))
                .andExpect(jsonPath("$.history[0].afterItinerary[*].legId", contains("N1", "N2")));

        // 按新后缀执行原批量装载成功，航段版本正常推进
        load("N1", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.loaded", contains("BAG1")));
        // 原航段不再接受该行李：待乘航段已是 N2（到达前仍是 N1 装载中，这里验证装载旧航段被拒）
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("N1"));
    }

    @Test
    void reroute_keepsCompletedPrefixAndSupportsSecondReroute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("M1", "SHA", "KWL");
        registerLeg("M2", "KWL", "CAN");
        registerLeg("K1", "SHA", "CKG");
        registerLeg("K2", "CKG", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));

        // 飞完首段：行李在 SHA，待乘下标 1
        load("L1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("L1", 2).andExpect(status().isOk());
        arrive("L1", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));

        // 第一次改派：前缀 L1 保留，后缀替换为 M1,M2
        reroute("BAG1", 1, List.of("M1", "M2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "M1", "M2")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));

        // 第二次改派：版本再加一，历史两条且不可重写
        reroute("BAG1", 2, List.of("K1", "K2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "K1", "K2")));
        mockMvc.perform(get("/api/bags/BAG1/reroutes"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[0].versionFrom").value(1))
                .andExpect(jsonPath("$.history[0].versionTo").value(2))
                .andExpect(jsonPath("$.history[0].afterItinerary[*].legId",
                        contains("L1", "M1", "M2")))
                .andExpect(jsonPath("$.history[1].versionFrom").value(2))
                .andExpect(jsonPath("$.history[1].versionTo").value(3))
                .andExpect(jsonPath("$.history[1].beforeItinerary[*].legId",
                        contains("L1", "M1", "M2")))
                .andExpect(jsonPath("$.history[1].afterItinerary[*].legId",
                        contains("L1", "K1", "K2")));

        // 旧后缀航段 L2/M1 均不再接受该行李，新后缀 K1 接受
        load("L2", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        load("M1", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        load("K1", 1, List.of("BAG1")).andExpect(status().isOk());
    }

    @Test
    void reroute_rejectsInvalidParamsWith400() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerBag("BAG1", List.of("L1"));
        registerLeg("N1", "PEK", "SHA");

        // 新后缀为空 -> 400；超过 5 段 -> 400；缺字段 -> 400
        reroute("BAG1", 1, List.of()).andExpect(status().isBadRequest());
        reroute("BAG1", 1, List.of("N1", "N1", "N1", "N1", "N1", "N1"))
                .andExpect(status().isBadRequest());
        postJson("/api/bags/reroute", Map.of("bagTag", "BAG1", "newLegIds", List.of("N1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reroute_rejectsMissingResourcesWith404() throws Exception {
        registerLeg("N1", "PEK", "SHA");
        registerLeg("L1", "PEK", "CAN");
        registerBag("BAG1", List.of("L1"));

        // 行李不存在 -> 404
        reroute("BAG_MISSING", 1, List.of("N1")).andExpect(status().isNotFound());
        // 新航段不存在 -> 404
        reroute("BAG1", 1, List.of("LEG_MISSING")).andExpect(status().isNotFound());
        // 历史查询未知行李 -> 404
        mockMvc.perform(get("/api/bags/BAG_MISSING/reroutes")).andExpect(status().isNotFound());
    }

    @Test
    void reroute_rejectsVersionConflictLoadedDeliveredAndShort() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "CAN");
        registerLeg("S1", "PEK", "SHA");
        registerBag("BAG1", List.of("L1", "L2"));
        registerBag("BAG_DONE", List.of("S1"));

        // 版本不符 -> 409
        reroute("BAG1", 99, List.of("N1", "N2")).andExpect(status().isConflict());

        // 已装载 -> 409（状态冲突）
        load("L1", 1, List.of("BAG1")).andExpect(status().isOk());
        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isConflict());

        // 已送达 -> 409（状态冲突）
        load("S1", 1, List.of("BAG_DONE")).andExpect(status().isOk());
        seal("S1", 2).andExpect(status().isOk());
        arrive("S1", List.of("BAG_DONE")).andExpect(status().isOk());
        registerLeg("D1", "PEK", "SHA");
        reroute("BAG_DONE", 1, List.of("D1")).andExpect(status().isConflict());

        // 短卸中 -> 409，不能借改派绕过真实补到
        registerLeg("Q1", "PEK", "SHA");
        registerBag("BAG_SHORT", List.of("Q1"));
        load("Q1", 1, List.of("BAG_SHORT")).andExpect(status().isOk());
        seal("Q1", 2).andExpect(status().isOk());
        diffArrive("Q1", 3, List.of()).andExpect(status().isOk());
        registerLeg("R1", "PEK", "CAN");
        reroute("BAG_SHORT", 1, List.of("R1")).andExpect(status().isConflict());
    }

    @Test
    void reroute_afterRecoverCanBeRerouted() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        load("L1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("L1", 2).andExpect(status().isOk());
        diffArrive("L1", 3, List.of()).andExpect(status().isOk());

        // 短卸中改派 409
        registerLeg("M1", "SHA", "KWL");
        registerLeg("M2", "KWL", "CAN");
        reroute("BAG1", 1, List.of("M1", "M2")).andExpect(status().isConflict());

        // 真实补到后仍有待乘航段，可以改派
        recover("BAG1", "L1", "SHA").andExpect(status().isOk());
        reroute("BAG1", 1, List.of("M1", "M2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "M1", "M2")));
        load("M1", 1, List.of("BAG1")).andExpect(status().isOk());
    }

    @Test
    void reroute_rejectsDiscontinuousChangedDestinationDuplicateAndOverLimit() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));

        // 首段起点不等于当前位置 -> 422
        registerLeg("X1", "SHA", "KWL");
        registerLeg("X2", "KWL", "CAN");
        reroute("BAG1", 1, List.of("X1", "X2")).andExpect(status().isUnprocessableEntity());

        // 相邻不连续 -> 422
        registerLeg("Y1", "PEK", "KWL");
        reroute("BAG1", 1, List.of("Y1", "X1")).andExpect(status().isUnprocessableEntity());

        // 最终目的地改变 -> 422
        registerLeg("Z1", "PEK", "XIY");
        registerLeg("Z2", "XIY", "SHA");
        reroute("BAG1", 1, List.of("Z1", "Z2")).andExpect(status().isUnprocessableEntity());

        // 新后缀内部重复 -> 422
        reroute("BAG1", 1, List.of("Z1", "Z1")).andExpect(status().isUnprocessableEntity());

        // 与已完成前缀重复航段但目的地保持 CAN：先飞完 L1（变为 ARRIVED），行李在 SHA
        registerLeg("W1", "SHA", "PEK");
        load("L1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("L1", 2).andExpect(status().isOk());
        arrive("L1", List.of("BAG1")).andExpect(status().isOk());
        // 后缀 SHA->PEK->SHA->CAN 再次经过前缀航段 L1（L1 已 ARRIVED，非 OPEN）-> 409
        reroute("BAG1", 1, List.of("W1", "L1", "L2")).andExpect(status().isConflict());
        // 反向绕路但航段不重复、目的地不变：合法
        registerLeg("W2", "PEK", "CAN");
        reroute("BAG1", 1, List.of("W1", "W2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "W1", "W2")));

        // 合计超过 5 段 -> 422：飞完首段后前缀 1 段，提交 5 个新段（合计 6 段）
        registerLeg("P1", "PEK", "SHA");
        registerLeg("P2", "SHA", "CAN");
        registerBag("BAG2", List.of("P1", "P2"));
        load("P1", 1, List.of("BAG2")).andExpect(status().isOk());
        seal("P1", 2).andExpect(status().isOk());
        arrive("P1", List.of("BAG2")).andExpect(status().isOk());
        registerLeg("E1", "SHA", "CTU");
        registerLeg("E2", "CTU", "HGH");
        registerLeg("E3", "HGH", "SZX");
        registerLeg("E4", "SZX", "KWL");
        registerLeg("E5", "KWL", "CAN");
        reroute("BAG2", 1, List.of("E1", "E2", "E3", "E4", "E5"))
                .andExpect(status().isUnprocessableEntity());
        // 原行程保持不变
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("P1", "P2")));
    }

    @Test
    void reroute_totalFiveLegsIsLegalAndNewLegMustBeOpen() throws Exception {
        // 前缀 1 段（已完成 A1）+ 新后缀 3 段 = 4 段合法
        registerLeg("A1", "PEK", "SHA");
        registerLeg("A2", "SHA", "CAN");
        registerBag("BAG1", List.of("A1", "A2"));
        load("A1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("A1", 2).andExpect(status().isOk());
        arrive("A1", List.of("BAG1")).andExpect(status().isOk());
        // 行李在 SHA，新后缀 SHA->CTU->HGH->CAN，目的地 CAN 不变
        registerLeg("C1", "SHA", "CTU");
        registerLeg("C2", "CTU", "HGH");
        registerLeg("C3", "HGH", "CAN");
        reroute("BAG1", 1, List.of("C1", "C2", "C3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary", hasSize(4)))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("A1", "C1", "C2", "C3")))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.currentLocation").value("SHA"));

        // 新航段已封舱 -> 409（状态冲突）
        registerLeg("D1", "PEK", "SHA");
        registerBag("BAG2", List.of("D1"));
        registerLeg("F1", "PEK", "CAN");
        seal("F1", 1).andExpect(status().isOk());
        reroute("BAG2", 1, List.of("F1")).andExpect(status().isConflict());
    }

    @Test
    void reroute_failureRollsBackEverything() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "SHA"); // 终点错误

        // 目的地改变 -> 422，无部分后缀、无历史、无事件、版本不变
        reroute("BAG1", 1, List.of("N1", "N2")).andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.itinerary", hasSize(2)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("L1"))
                .andExpect(jsonPath("$.itinerary[1].legId").value("L2"))
                .andExpect(jsonPath("$.events", hasSize(1)));
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isZero();
        Integer seqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(seqCount).isEqualTo(2);
    }

    @Test
    void reroute_idempotentReplayConflictFailureNotOccupyingAndReplayDoesNotOverride() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "SHA");
        registerLeg("K1", "PEK", "CKG");
        registerLeg("K2", "CKG", "SHA");
        registerBag("BAG1", List.of("L1"));

        // 同键同参重放首次结果：版本只加一次、历史只一条
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "bagTag", "BAG1",
                "expectedRouteVersion", 1, "newLegIds", List.of("N1", "N2"));
        postJson("/api/bags/reroute", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));
        postJson("/api/bags/reroute", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("N1", "N2")));
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同键改参 -> 409
        postJson("/api/bags/reroute", Map.of("requestId", requestId, "bagTag", "BAG1",
                "expectedRouteVersion", 2, "newLegIds", List.of("K1", "K2")))
                .andExpect(status().isConflict());

        // 第二次改派成功（v2 -> v3）
        String secondKey = UUID.randomUUID().toString();
        postJson("/api/bags/reroute", Map.of("requestId", secondKey, "bagTag", "BAG1",
                "expectedRouteVersion", 2, "newLegIds", List.of("K1", "K2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3));

        // 旧键重放只返回首次结果（v2 路线），不覆盖当前 v3 路线
        postJson("/api/bags/reroute", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("N1", "N2")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("K1", "K2")));
        Integer historyAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyAfter).isEqualTo(2);

        // 失败不占键：先版本错误失败（409），再用同键正确改派成功
        registerLeg("V1", "PEK", "SHA");
        registerBag("BAG2", List.of("V1"));
        registerLeg("G1", "PEK", "SHA");
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/bags/reroute", Map.of("requestId", retryKey, "bagTag", "BAG2",
                "expectedRouteVersion", 99, "newLegIds", List.of("G1")))
                .andExpect(status().isConflict());
        postJson("/api/bags/reroute", Map.of("requestId", retryKey, "bagTag", "BAG2",
                "expectedRouteVersion", 1, "newLegIds", List.of("G1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));
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
