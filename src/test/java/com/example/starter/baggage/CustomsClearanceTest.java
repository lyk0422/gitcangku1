package com.example.starter.baggage;

import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李海关放行与持续门禁 API 测试：覆盖放行/拦截登记、检查版本规则、
 * 装载/补到/改派门禁、批量整批回滚、拦截标记快照与解除、clearanceKey 幂等重放。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsClearanceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-26T01:02:03Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomsService customsService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM customs_hold");
        jdbcTemplate.update("DELETE FROM customs_clearance");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        customsService.setClock(() -> FIXED_NOW);
    }

    @Test
    void mainFlow_releaseThenLoadInternationalLeg() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.originCountry").value("CN"))
                .andExpect(jsonPath("$.destinationCountry").value("KR"));
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());

        // 无放行版本 -> 422 海关放行缺失
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关放行缺失")));

        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.country").value("KR"))
                .andExpect(jsonPath("$.registeredAt").value(FIXED_NOW.toString()));

        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 检查链查询
        mockMvc.perform(get("/api/bags/BAG1/customs/chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain", hasSize(1)))
                .andExpect(jsonPath("$.chain[0].inspectionVersion").value(1))
                .andExpect(jsonPath("$.chain[0].status").value("RELEASED"));

        // 航段门禁查询：国际航段，已装载行李门禁通过
        mockMvc.perform(get("/api/legs/LEG_CN_KR/customs/gate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.international").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.items[0].gateStatus").value("CLEARED"))
                .andExpect(jsonPath("$.items[0].latestVersion").value(1));
    }

    @Test
    void load_batchRollbackWhenAnyBagMissingClearance() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG_CN_KR")).andExpect(status().isCreated());
        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());

        // BAG2 缺失放行 -> 整批 422，BAG1 也不移动，容器占用与版本全部回滚
        load("LEG_CN_KR", 1, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关放行缺失")));

        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(loadCount).isZero();
        mockMvc.perform(get("/api/legs/LEG_CN_KR/manifest"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
    }

    @Test
    void holdAfterRelease_blocksLoadUntilHigherVersionRelease() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());
        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());

        // 新拦截：不删除历史放行，但未起飞的后续航段被标记 CUSTOMS_HOLD
        clearance("BAG1", 2, "HELD", "KR", "开箱查验未过").andExpect(status().isCreated());
        mockMvc.perform(get("/api/bags/BAG1/customs/chain"))
                .andExpect(jsonPath("$.chain", hasSize(2)))
                .andExpect(jsonPath("$.chain[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.chain[1].status").value("HELD"))
                .andExpect(jsonPath("$.chain[1].reason").value("开箱查验未过"));
        mockMvc.perform(get("/api/bags/BAG1/customs/holds"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].legId").value("LEG_CN_KR"))
                .andExpect(jsonPath("$.holds[0].status").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.holds[0].inspectionVersion").value(2));

        // 拦截后装载 -> 422 海关拦截
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关拦截")));

        // 解除拦截必须以更高检查版本放行，原终态保留
        clearance("BAG1", 3, "RELEASED", "KR", "复核放行").andExpect(status().isCreated());
        mockMvc.perform(get("/api/bags/BAG1/customs/chain"))
                .andExpect(jsonPath("$.chain", hasSize(3)));
        mockMvc.perform(get("/api/bags/BAG1/customs/holds"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].status").value("CLEARED"))
                .andExpect(jsonPath("$.holds[0].resolvedVersion").value(3));

        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk());
    }

    @Test
    void holdMarking_skipsDepartedLegsAndKeepsLoadedSnapshot() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerLeg("LEG_KR_JP", "ICN", "NRT", "KR", "JP").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR", "LEG_KR_JP")).andExpect(status().isCreated());
        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());
        clearance("BAG1", 2, "RELEASED", "JP", null).andExpect(status().isCreated());

        // 装载并封舱首段（视为已截载/起飞），随后登记新拦截
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_CN_KR", 2).andExpect(status().isOk());
        clearance("BAG1", 3, "HELD", "JP", "目的国风险布控").andExpect(status().isCreated());

        // 已起飞的 LEG_CN_KR 不改写；仅未起飞的 LEG_KR_JP 被标记
        mockMvc.perform(get("/api/bags/BAG1/customs/holds"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].legId").value("LEG_KR_JP"))
                .andExpect(jsonPath("$.holds[0].seq").value(1))
                .andExpect(jsonPath("$.holds[0].status").value("CUSTOMS_HOLD"));
        mockMvc.perform(get("/api/legs/LEG_CN_KR/manifest"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.manifest", contains("BAG1")));
        // 已装载且已起飞记录不改写：行李仍在已封舱首段上
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG_CN_KR"));

        // 快照包含标记时刻的行李与航段状态
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT snapshot FROM customs_hold WHERE bag_tag = 'BAG1'", String.class);
        assertThat(snapshot).contains("\"legId\":\"LEG_KR_JP\"")
                .contains("\"legStatus\":\"OPEN\"")
                .contains("\"inspectionVersion\":3")
                .contains("\"heldAt\":\"" + FIXED_NOW + "\"");
    }

    @Test
    void clearance_versionAndValidationRules() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());

        // 行李不存在 -> 404
        clearance("BAG_NOPE", 1, "RELEASED", "KR", null).andExpect(status().isNotFound());
        // 拦截原因不能为空 -> 422
        clearance("BAG1", 1, "HELD", "KR", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("拦截原因不能为空")));
        clearance("BAG1", 1, "HELD", "KR", "  ").andExpect(status().isUnprocessableEntity());
        // 非法状态 -> 422
        clearance("BAG1", 1, "PENDING", "KR", null).andExpect(status().isUnprocessableEntity());

        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());
        clearance("BAG1", 3, "HELD", "KR", "查验").andExpect(status().isCreated());
        // 同一检查版本只能一个终态：同版本异内容 -> 409
        clearance("BAG1", 3, "RELEASED", "KR", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("同一检查版本只能一个终态")));
        // 低于已有最高版本 -> 422
        clearance("BAG1", 2, "RELEASED", "KR", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("检查版本必须高于已有最高版本")));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_clearance WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void clearanceKey_sameKeyReplaysAndFailureDoesNotOccupyKey() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());

        // 同内容不同 requestId：按 clearanceKey 重放原记录，不产生第二条
        String firstKey = clearance("BAG1", 1, "RELEASED", "KR", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replayed = clearance("BAG1", 1, "RELEASED", "KR", null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayed).isEqualTo(firstKey);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_clearance WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(count).isEqualTo(1);

        // 失败不占键：同 requestId 先失败后成功
        String requestId = UUID.randomUUID().toString();
        clearance(requestId, "BAG1", 2, "HELD", "KR", null)
                .andExpect(status().isUnprocessableEntity());
        clearance(requestId, "BAG1", 2, "HELD", "KR", "补充原因")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("补充原因"));
    }

    @Test
    void recover_internationalMissingLegRequiresClearance() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerLeg("LEG_KR_JP", "ICN", "NRT", "KR", "JP").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR", "LEG_KR_JP")).andExpect(status().isCreated());
        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());

        // 首段差异到达：BAG1 短卸在 LEG_CN_KR
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_CN_KR", 2).andExpect(status().isOk());
        diffArrive("LEG_CN_KR", 3, List.of()).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        // 缺失航段为国际航段：最新检查被拦截时补到 -> 422
        clearance("BAG1", 2, "HELD", "KR", "布控拦截").andExpect(status().isCreated());
        recover("BAG1", "LEG_CN_KR", "ICN").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关拦截")));
        // 更高版本放行后补到成功
        clearance("BAG1", 3, "RELEASED", "KR", "复核放行").andExpect(status().isCreated());
        recover("BAG1", "LEG_CN_KR", "ICN").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.currentLocation").value("ICN"));
    }

    @Test
    void reroute_internationalNewLegRequiresClearance() throws Exception {
        registerLeg("LEG_DOM", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerLeg("LEG_SEALED", "PEK", "HKG", "CN", "HK").andExpect(status().isCreated());
        seal("LEG_SEALED", 1).andExpect(status().isOk());
        registerBag("BAG1", List.of("LEG_DOM")).andExpect(status().isCreated());

        // 改派到国际航段且无放行 -> 422
        reroute("BAG1", "LEG_CN_KR").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关放行缺失")));
        // 改派到已截载航段 -> 422
        reroute("BAG1", "LEG_SEALED").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("已截载不可改派")));

        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());
        reroute("BAG1", "LEG_CN_KR").andExpect(status().isOk())
                .andExpect(jsonPath("$.newLegId").value("LEG_CN_KR"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.itinerary[0].legId").value("LEG_CN_KR"))
                .andExpect(jsonPath("$.itinerary[0].destination").value("ICN"));

        // 改派后可用原放行装载新航段
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk());
        // 已装载不可再改派
        reroute("BAG1", "LEG_DOM").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gateQuery_reflectsHoldAfterLoadAndClearsAfterRelease() throws Exception {
        registerLeg("LEG_CN_KR", "PEK", "ICN", "CN", "KR").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_CN_KR")).andExpect(status().isCreated());
        clearance("BAG1", 1, "RELEASED", "KR", null).andExpect(status().isCreated());
        load("LEG_CN_KR", 1, List.of("BAG1")).andExpect(status().isOk());

        // 已装载未起飞时登记新拦截：门禁转 HELD，航段被标记
        clearance("BAG1", 2, "HELD", "KR", "复查验扣").andExpect(status().isCreated());
        mockMvc.perform(get("/api/legs/LEG_CN_KR/customs/gate"))
                .andExpect(jsonPath("$.items[0].gateStatus").value("HELD"))
                .andExpect(jsonPath("$.items[0].latestVersion").value(2));
        mockMvc.perform(get("/api/bags/BAG1/customs/holds"))
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].legId").value("LEG_CN_KR"));

        clearance("BAG1", 3, "RELEASED", "KR", "复核放行").andExpect(status().isCreated());
        mockMvc.perform(get("/api/legs/LEG_CN_KR/customs/gate"))
                .andExpect(jsonPath("$.items[0].gateStatus").value("CLEARED"))
                .andExpect(jsonPath("$.items[0].latestVersion").value(3));
    }

    @Test
    void domesticLeg_hasNoCustomsGate() throws Exception {
        registerLeg("LEG_DOM", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG_DOM")).andExpect(status().isCreated());

        // 国内航段无需任何放行即可装载
        load("LEG_DOM", 1, List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG_DOM/customs/gate"))
                .andExpect(jsonPath("$.international").value(false))
                .andExpect(jsonPath("$.items[0].gateStatus").value("NOT_REQUIRED"));
    }

    @Test
    void customsQueries_return404ForUnknownIds() throws Exception {
        mockMvc.perform(get("/api/bags/NOPE/customs/chain")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/customs/holds")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/NOPE/customs/gate")).andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      String originCountry, String destinationCountry) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination,
                "originCountry", originCountry, "destinationCountry", destinationCountry));
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

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation)
            throws Exception {
        return postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions reroute(String bagTag, String newLegId) throws Exception {
        return postJson("/api/bags/reroute", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "newLegId", newLegId));
    }

    private ResultActions clearance(String bagTag, int version, String clearanceStatus,
                                    String country, String reason) throws Exception {
        return clearance(UUID.randomUUID().toString(), bagTag, version, clearanceStatus,
                country, reason);
    }

    private ResultActions clearance(String requestId, String bagTag, int version,
                                    String clearanceStatus, String country, String reason)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("bagTag", bagTag);
        body.put("inspectionVersion", version);
        body.put("status", clearanceStatus);
        body.put("country", country);
        body.put("reason", reason);
        return postJson("/api/customs/clearances", body);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
