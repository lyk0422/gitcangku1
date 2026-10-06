package com.example.starter.baggage;

import java.util.ArrayList;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 堆栈承压规则测试：题干算例（A 承压 1000，B 600 + C 500 整次拒绝，C 改 400 恰好可封舱）、
 * 完整后态原子核验（层号空洞/重复/超压 422 且原清单不变）、卸载换层、封舱冻结、
 * 重量档案版本与幂等边界。全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageStackPressureTest {

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
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void stemScenario_overCapacityRejectedThenExactlyFullSeals() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        // A 底层承压 1000 克，B 重 600，C 重 500（零承压只能顶层）
        registerBag("A", 800, 1000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.selfWeightGrams").value(800))
                .andExpect(jsonPath("$.maxLoadAboveGrams").value(1000))
                .andExpect(jsonPath("$.weightVersion").value(1));
        registerBag("B", 600, 2000).andExpect(status().isCreated());
        registerBag("C", 500, 0).andExpect(status().isCreated());

        // B+C=1100 克超过 A 承压 1000 克，整次拒绝，原清单不变
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 2), entry("C", 1, 3)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(0)))
                .andExpect(jsonPath("$.version").value(1));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(loadCount).isZero();

        // C 改 400 克后 A 上方恰好 1000 克，声明成功并可封舱
        updateWeight("C", 1, 400, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightVersion").value(2));
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 2), entry("C", 1, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].bagTag").value("A"))
                .andExpect(jsonPath("$.entries[0].loadAboveGrams").value(1000))
                .andExpect(jsonPath("$.entries[1].loadAboveGrams").value(400))
                .andExpect(jsonPath("$.entries[2].loadAboveGrams").value(0));

        seal("LEG1", 2).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));

        // 封舱冻结层序与各层载荷：快照不漂移
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].bagTag").value("A"))
                .andExpect(jsonPath("$.entries[0].selfWeightGrams").value(800))
                .andExpect(jsonPath("$.entries[0].loadAboveGrams").value(1000))
                .andExpect(jsonPath("$.entries[2].selfWeightGrams").value(400));

        // 已封舱禁止新排堆栈、禁止修改已装行李档案
        declare("LEG1", 3, List.of(entry("A", 1, 1))).andExpect(status().isUnprocessableEntity());
        updateWeight("A", 1, 900, 1000).andExpect(status().isConflict());
        // 封舱冻结后重量与布局仍未漂移
        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.selfWeightGrams").value(800));
    }

    @Test
    void declare_rejectsShapeViolations() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", 600, 2000).andExpect(status().isCreated());
        registerBag("C", 500, 0).andExpect(status().isCreated());

        // 层号空洞：1 之后直接 3
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 3)))
                .andExpect(status().isUnprocessableEntity());
        // 同堆栈层号重复
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        // bagTag 重复
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("A", 2, 1)))
                .andExpect(status().isUnprocessableEntity());
        // 零承压不在顶层
        declare("LEG1", 1, List.of(entry("C", 1, 1), entry("A", 1, 2)))
                .andExpect(status().isUnprocessableEntity());
        // 层号/堆栈号非正 -> 400（参数校验）
        declare("LEG1", 1, List.of(entry("A", 1, 0))).andExpect(status().isBadRequest());
        declare("LEG1", 1, List.of(entry("A", 0, 1))).andExpect(status().isBadRequest());

        // 全部失败不占版本、不落清单
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries", hasSize(0)));

        // 舱内多个独立堆栈：各自从 1 起编号，互不影响承压
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 2, 1), entry("C", 2, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].stackNo").value(1))
                .andExpect(jsonPath("$.entries[1].stackNo").value(2))
                .andExpect(jsonPath("$.entries[1].loadAboveGrams").value(500));
    }

    @Test
    void declare_isAtomicWhenAnyBagInvalid() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", List.of("LEG2"), 600, 2000).andExpect(status().isCreated());

        // B 待乘航段是 LEG2：整次 422，A 也不装入
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 2)))
                .andExpect(status().isUnprocessableEntity());
        // 不存在的行李：整次 422
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("MISSING", 1, 2)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(loadCount).isZero();
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void declare_requiresWeightProfile() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        // 无重量档案的行李不能参与堆栈声明
        postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "PLAIN", "legIds", List.of("LEG1"))).andExpect(status().isCreated());
        declare("LEG1", 1, List.of(entry("PLAIN", 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 通过重量修改建立首个档案（expectedVersion 0 -> 版本 1）后可声明
        updateWeight("PLAIN", 0, 300, 500).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightVersion").value(1));
        declare("LEG1", 1, List.of(entry("PLAIN", 1, 1))).andExpect(status().isOk());
    }

    @Test
    void declare_unloadAndRestackApplyAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", 600, 2000).andExpect(status().isCreated());

        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 完整后态只含 B 在底层：A 被卸载出舱，B 换到第 1 层
        declare("LEG1", 2, List.of(entry("B", 1, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].bagTag").value("B"))
                .andExpect(jsonPath("$.entries[0].layer").value(1));

        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
        Integer aRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'A'", Integer.class);
        assertThat(aRecords).isZero();

        // A 仍在始发站且待乘 LEG1，可再次声明装入（与 B 同栈，B 承压 2000 可承 A 的 800）
        declare("LEG1", 3, List.of(entry("B", 1, 1), entry("A", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)));
        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
    }

    @Test
    void weightProfile_validationAndVersionChecks() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        // 自重必须为正 -> 400；承压非负 -> 400；须成对提供 -> 422
        postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "X1", "legIds", List.of("LEG1"),
                "selfWeightGrams", 0, "maxLoadAboveGrams", 100))
                .andExpect(status().isBadRequest());
        postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "X1", "legIds", List.of("LEG1"),
                "selfWeightGrams", 100, "maxLoadAboveGrams", -1))
                .andExpect(status().isBadRequest());
        postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "X1", "legIds", List.of("LEG1"), "selfWeightGrams", 100))
                .andExpect(status().isUnprocessableEntity());

        registerBag("A", 800, 1000).andExpect(status().isCreated());
        // 版本从 1 起：错误的 expectedVersion -> 409
        updateWeight("A", 0, 900, 1000).andExpect(status().isConflict());
        updateWeight("A", 2, 900, 1000).andExpect(status().isConflict());
        // 正确版本修改成功，版本递增
        updateWeight("A", 1, 900, 1200).andExpect(status().isOk())
                .andExpect(jsonPath("$.selfWeightGrams").value(900))
                .andExpect(jsonPath("$.maxLoadAboveGrams").value(1200))
                .andExpect(jsonPath("$.weightVersion").value(2));
        // 不存在的行李 -> 404
        updateWeight("MISSING", 0, 100, 100).andExpect(status().isNotFound());
    }

    @Test
    void updateWeight_loadedBagOverPressureReturns409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", 600, 2000).andExpect(status().isCreated());
        declare("LEG1", 1, List.of(entry("A", 1, 1), entry("B", 1, 2)))
                .andExpect(status().isOk());

        // B 增重到 1500 克令 A 上方载荷 1500 > 1000，超压 409，档案不变
        updateWeight("B", 1, 1500, 2000).andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.selfWeightGrams").value(600))
                .andExpect(jsonPath("$.weightVersion").value(1));
        // A 承压调低到 500 同样令当前堆栈超压 -> 409
        updateWeight("A", 1, 800, 500).andExpect(status().isConflict());
        // 不超压的修改放行：B 减到 400，A 上方 400 <= 1000
        updateWeight("B", 1, 400, 2000).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightVersion").value(2));
        // 当前布局随新重量重算载荷
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.entries[0].loadAboveGrams").value(400));
    }

    @Test
    void declare_idempotencyReplayAndConflict() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", 600, 2000).andExpect(status().isCreated());

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "operator", "loader-1",
                "expectedVersion", 1,
                "entries", List.of(entry("A", 1, 1), entry("B", 1, 2)));
        postJson("/api/legs/LEG1/stacks/declare", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键同参重放首次完整响应，版本只推进一次
        postJson("/api/legs/LEG1/stacks/declare", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.version").value(2));
        // 同键异参 -> 409
        postJson("/api/legs/LEG1/stacks/declare", Map.of("requestId", requestId,
                "operator", "loader-1", "expectedVersion", 1,
                "entries", List.of(entry("A", 1, 1)))).andExpect(status().isConflict());
        // 操作者属于身份：同键换操作者 -> 409
        postJson("/api/legs/LEG1/stacks/declare", Map.of("requestId", requestId,
                "operator", "loader-2", "expectedVersion", 1,
                "entries", List.of(entry("A", 1, 1), entry("B", 1, 2))))
                .andExpect(status().isConflict());
    }

    @Test
    void declare_failureDoesNotOccupyIdempotencyKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", 800, 1000).andExpect(status().isCreated());
        registerBag("B", 600, 2000).andExpect(status().isCreated());
        registerBag("C", 500, 0).andExpect(status().isCreated());

        String requestId = UUID.randomUUID().toString();
        // 先以超压失败（422），不占键
        postJson("/api/legs/LEG1/stacks/declare", Map.of("requestId", requestId,
                "operator", "loader-1", "expectedVersion", 1,
                "entries", List.of(entry("A", 1, 1), entry("B", 1, 2), entry("C", 1, 3))))
                .andExpect(status().isUnprocessableEntity());
        // 修正参数后同键成功
        postJson("/api/legs/LEG1/stacks/declare", Map.of("requestId", requestId,
                "operator", "loader-1", "expectedVersion", 1,
                "entries", List.of(entry("A", 1, 1), entry("B", 1, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, int selfWeightGrams, int maxLoadAboveGrams)
            throws Exception {
        return registerBag(bagTag, List.of("LEG1"), selfWeightGrams, maxLoadAboveGrams);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds,
                                      int selfWeightGrams, int maxLoadAboveGrams) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds,
                "selfWeightGrams", selfWeightGrams, "maxLoadAboveGrams", maxLoadAboveGrams));
    }

    private ResultActions declare(String legId, int expectedVersion, List<Map<String, Object>> entries)
            throws Exception {
        return postJson("/api/legs/" + legId + "/stacks/declare", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "loader-1",
                "expectedVersion", expectedVersion, "entries", entries));
    }

    private ResultActions updateWeight(String bagTag, int expectedVersion,
                                       int selfWeightGrams, int maxLoadAboveGrams) throws Exception {
        return postJson("/api/bags/" + bagTag + "/weight", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "loader-1",
                "expectedVersion", expectedVersion,
                "selfWeightGrams", selfWeightGrams, "maxLoadAboveGrams", maxLoadAboveGrams));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private static Map<String, Object> entry(String bagTag, int stackNo, int layer) {
        return Map.of("bagTag", bagTag, "stackNo", stackNo, "layer", layer);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}