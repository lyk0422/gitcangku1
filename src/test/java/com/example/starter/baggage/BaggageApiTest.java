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
 * 联程行李装载交接 API 测试：覆盖登记、装载、封舱、到达主流程，
 * 失败分支（整批回滚、版本冲突、状态非法、清单不一致）与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageApiTest {

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
        jdbcTemplate.update("DELETE FROM container_occupancy");
        jdbcTemplate.update("DELETE FROM cutoff_exception");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void mainFlow_registerLoadSealArriveAndDeliver() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());

        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.itinerary", hasSize(1)));
        registerBag("BAG2", List.of("LEG1", "LEG2")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.itinerary", hasSize(2)));

        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.loaded", contains("BAG1", "BAG2")));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));

        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")));

        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")));

        arrive("LEG1", List.of("BAG2", "BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(4));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));

        load("LEG2", 1, List.of("BAG2")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void registerBag_rejectsInvalidItinerary() throws Exception {
        registerLeg("LEG_A", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG_B", "XIY", "CAN").andExpect(status().isCreated());

        // 相邻航段首尾站不衔接
        registerBag("BAG_X", List.of("LEG_A", "LEG_B")).andExpect(status().isUnprocessableEntity());
        // 行程航段重复
        registerBag("BAG_X", List.of("LEG_A", "LEG_A")).andExpect(status().isUnprocessableEntity());
        // 引用不存在的航段
        registerBag("BAG_X", List.of("LEG_A", "LEG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 航段数超出 1~5（参数校验 400）
        registerBag("BAG_X", List.of()).andExpect(status().isBadRequest());
        registerBag("BAG_X", List.of("LEG_A", "LEG_A", "LEG_A", "LEG_A", "LEG_A", "LEG_A"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_rejectsDuplicates() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isConflict());

        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isConflict());
    }

    @Test
    void load_batchIsAtomicWhenAnyBagInvalid() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG2")).andExpect(status().isCreated());

        // BAG2 的待乘航段是 LEG2，整批 422 且 BAG1 也不移动
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(loadCount).isZero();
    }

    @Test
    void load_rejectsInvalidRequests() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "PEK", "XIY").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        // 版本冲突 -> 409
        load("LEG1", 99, List.of("BAG1")).andExpect(status().isConflict());
        // 请求内 bagTag 重复 -> 422
        load("LEG1", 1, List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        load("LEG1", 1, List.of("BAG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 批量大小越界 -> 400
        load("LEG1", 1, List.of()).andExpect(status().isBadRequest());
        // 航段不存在 -> 404
        load("LEG_MISSING", 1, List.of("BAG1")).andExpect(status().isNotFound());

        // 已装载的行李不得再装入其他清单
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 封舱后不得加装
        seal("LEG1", 2).andExpect(status().isOk());
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void seal_allowsEmptyManifestAndBumpsVersion() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        seal("LEG1", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.manifest", hasSize(0)));

        // 重复封舱 -> 422（版本虽新但状态非法）
        seal("LEG1", 2).andExpect(status().isUnprocessableEntity());
        // 空清单到达确认：空集合匹配
        arrive("LEG1", List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    void arrive_mismatchKeepsLegAndBagsUnchanged() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());

        // 少一件 -> 422
        arrive("LEG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 多一件 -> 422
        arrive("LEG1", List.of("BAG1", "BAG2", "BAG3")).andExpect(status().isUnprocessableEntity());
        // 集合相同但有重复袋号 -> 422
        arrive("LEG1", List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());

        // 航段与全部行李均不变
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
    }

    @Test
    void arrive_rejectedWhenNotSealed() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        arrive("LEG1", List.of()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void idempotency_replaysSameKeySameParams() throws Exception {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "requestId", requestId, "legId", "LEG1", "origin", "PEK", "destination", "SHA",
                "departureAt", "2099-01-01T00:00:00Z");
        postJson("/api/legs", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        // 同键同参重放：返回原成功结果，不产生第二条航段
        postJson("/api/legs", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        Integer legCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(legCount).isEqualTo(1);

        // 装载重放：版本只推进一次
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());
        String loadRequestId = UUID.randomUUID().toString();
        Map<String, Object> loadBody = Map.of(
                "requestId", loadRequestId, "expectedVersion", 1,
                "operator", "tester", "containerId", "ULD-LEG1", "bagTags", List.of("BAG1"));
        postJson("/api/legs/LEG1/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/legs/LEG1/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void idempotency_sameKeyDifferentParamsReturns409() throws Exception {
        String requestId = UUID.randomUUID().toString();
        postJson("/api/legs", Map.of("requestId", requestId, "legId", "LEG1",
                "origin", "PEK", "destination", "SHA", "departureAt", "2099-01-01T00:00:00Z"))
                .andExpect(status().isCreated());
        postJson("/api/legs", Map.of("requestId", requestId, "legId", "LEG2",
                "origin", "PEK", "destination", "SHA", "departureAt", "2099-01-01T00:00:00Z"))
                .andExpect(status().isConflict());
    }

    @Test
    void idempotency_failureDoesNotOccupyKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1")).andExpect(status().isCreated());

        String requestId = UUID.randomUUID().toString();
        // 先以错误版本失败（409），不占键
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId,
                "expectedVersion", 99, "operator", "tester", "containerId", "ULD-LEG1",
                "bagTags", List.of("BAG1"))).andExpect(status().isConflict());
        // 再以业务失败（422），同样不占键
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId,
                "expectedVersion", 1, "operator", "tester", "containerId", "ULD-LEG1",
                "bagTags", List.of("BAG_MISSING")))
                .andExpect(status().isUnprocessableEntity());
        // 修正参数后同键成功
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId,
                "expectedVersion", 1, "operator", "tester", "containerId", "ULD-LEG1",
                "bagTags", List.of("BAG1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void queries_return404ForUnknownIds() throws Exception {
        mockMvc.perform(get("/api/bags/NOPE/trace")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/NOPE/manifest")).andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination,
                "departureAt", "2099-01-01T00:00:00Z"));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion,
                "operator", "tester", "containerId", "ULD-" + legId,
                "bagTags", bagTags));
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
