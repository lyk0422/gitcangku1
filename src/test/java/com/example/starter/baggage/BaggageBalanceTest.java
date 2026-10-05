package com.example.starter.baggage;

import java.math.BigDecimal;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双舱航段装载与纵向力矩平衡测试：覆盖装载带舱位、舱重限值、封舱重心闭区间、
 * 批量换舱最终位置核算、封舱冻结快照与历史不变、质量校验与幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageBalanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM seal_item");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void mainFlow_compartmentLoadSealAndFrozenBalance() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated())
                .andExpect(jsonPath("$.legType").value("COMPARTMENT"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        registerBag("BAG_F", "60", "LEG_C").andExpect(status().isCreated())
                .andExpect(jsonPath("$.massKg").value(60.0));
        registerBag("BAG_A", "40", "LEG_C").andExpect(status().isCreated());

        load("LEG_C", 1, List.of("BAG_F", "BAG_A"),
                List.of(comp("BAG_F", "FWD"), comp("BAG_A", "AFT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.assignments", hasSize(2)));

        // 实时平衡：前舱 60×(-2) + 后舱 40×3 = 0，重心 0 米
        balance("LEG_C")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.totalMomentKgm").value(0.0))
                .andExpect(jsonPath("$.centerOfGravityM").value(0.0))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG_A"))
                .andExpect(jsonPath("$.items[0].compartment").value("AFT"))
                .andExpect(jsonPath("$.items[0].positionM").value(3.0))
                .andExpect(jsonPath("$.items[0].momentKgm").value(120.0))
                .andExpect(jsonPath("$.items[1].bagTag").value("BAG_F"))
                .andExpect(jsonPath("$.items[1].compartment").value("FWD"))
                .andExpect(jsonPath("$.items[1].positionM").value(-2.0))
                .andExpect(jsonPath("$.items[1].momentKgm").value(-120.0));

        seal("LEG_C", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", contains("BAG_A", "BAG_F")));

        // 封舱后返回冻结快照
        balance("LEG_C")
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.totalMomentKgm").value(0.0))
                .andExpect(jsonPath("$.items", hasSize(2)));
        BigDecimal frozenMass = jdbcTemplate.queryForObject(
                "SELECT sealed_total_mass FROM leg WHERE leg_id = 'LEG_C'", BigDecimal.class);
        BigDecimal frozenMoment = jdbcTemplate.queryForObject(
                "SELECT sealed_total_moment FROM leg WHERE leg_id = 'LEG_C'", BigDecimal.class);
        assertThat(frozenMass).isEqualByComparingTo("100.000");
        assertThat(frozenMoment).isEqualByComparingTo("0.000");
        Integer frozenItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seal_item WHERE leg_id = 'LEG_C'", Integer.class);
        assertThat(frozenItems).isEqualTo(2);
    }

    @Test
    void seal_rejectsWhenCenterOfGravityOutOfRange() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_F", "60", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_A", "40", "LEG_C").andExpect(status().isCreated());
        load("LEG_C", 1, List.of("BAG_F", "BAG_A"),
                List.of(comp("BAG_F", "FWD"), comp("BAG_A", "AFT")))
                .andExpect(status().isOk());

        // 把 40 千克移到前舱：重心 = (100×-2)/100 = -2 米，超出闭区间
        move("LEG_C", 2, List.of(comp("BAG_A", "FWD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.moved", contains("BAG_A")));
        balance("LEG_C").andExpect(jsonPath("$.centerOfGravityM").value(-2.0));

        // 重心超限：422 且航段保持开放、版本不变
        seal("LEG_C", 3).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3));

        // 移回后舱后重心恢复 0 米，可封舱
        move("LEG_C", 3, List.of(comp("BAG_A", "AFT"))).andExpect(status().isOk());
        seal("LEG_C", 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void seal_centerOfGravityBoundaryIsInclusive() throws Exception {
        // 上限边界：前后各 50 千克，重心恰好 0.5 米，可封舱
        registerLeg("LEG_HI", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_H1", "50", "LEG_HI").andExpect(status().isCreated());
        registerBag("BAG_H2", "50", "LEG_HI").andExpect(status().isCreated());
        load("LEG_HI", 1, List.of("BAG_H1", "BAG_H2"),
                List.of(comp("BAG_H1", "FWD"), comp("BAG_H2", "AFT")))
                .andExpect(status().isOk());
        balance("LEG_HI").andExpect(jsonPath("$.centerOfGravityM").value(0.5));
        seal("LEG_HI", 2).andExpect(status().isOk());

        // 下限边界：前 70 后 30 千克，重心恰好 -0.5 米，可封舱
        registerLeg("LEG_LO", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_L1", "70", "LEG_LO").andExpect(status().isCreated());
        registerBag("BAG_L2", "30", "LEG_LO").andExpect(status().isCreated());
        load("LEG_LO", 1, List.of("BAG_L1", "BAG_L2"),
                List.of(comp("BAG_L1", "FWD"), comp("BAG_L2", "AFT")))
                .andExpect(status().isOk());
        balance("LEG_LO").andExpect(jsonPath("$.centerOfGravityM").value(-0.5));
        seal("LEG_LO", 2).andExpect(status().isOk());

        // 下限之外：前 70.001 后 30 千克，重心略低于 -0.5 米（交叉相乘精确判断），拒绝并保持开放
        registerLeg("LEG_OUT", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_O1", "70.001", "LEG_OUT").andExpect(status().isCreated());
        registerBag("BAG_O2", "30", "LEG_OUT").andExpect(status().isCreated());
        load("LEG_OUT", 1, List.of("BAG_O1", "BAG_O2"),
                List.of(comp("BAG_O1", "FWD"), comp("BAG_O2", "AFT")))
                .andExpect(status().isOk());
        seal("LEG_OUT", 2).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG_OUT/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void seal_compartmentLegRequiresPositiveTotalMass() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        // 空双舱航段总质量为 0，不可封舱；普通航段空清单仍可封舱（原有行为保留）
        seal("LEG_C", 1).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));

        registerLeg("LEG_S", "STANDARD").andExpect(status().isCreated());
        seal("LEG_S", 1).andExpect(status().isOk());
    }

    @Test
    void load_compartmentOverweightRollsBackWholeBatch() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_1", "600", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_2", "500", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_3", "400", "LEG_C").andExpect(status().isCreated());

        // 前舱 600+500=1100 千克超限：422 且整批回滚
        load("LEG_C", 1, List.of("BAG_1", "BAG_2"),
                List.of(comp("BAG_1", "FWD"), comp("BAG_2", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG_C'", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/bags/BAG_1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 恰好 1000 千克可以装载；再装 1 千克到同舱超限
        load("LEG_C", 1, List.of("BAG_1", "BAG_3"),
                List.of(comp("BAG_1", "FWD"), comp("BAG_3", "FWD")))
                .andExpect(status().isOk());
        registerBag("BAG_4", "1", "LEG_C").andExpect(status().isCreated());
        load("LEG_C", 2, List.of("BAG_4"), List.of(comp("BAG_4", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        load("LEG_C", 2, List.of("BAG_4"), List.of(comp("BAG_4", "AFT")))
                .andExpect(status().isOk());
    }

    @Test
    void load_compartmentValidationFailures() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerLeg("LEG_S", "STANDARD").andExpect(status().isCreated());
        registerBag("BAG_M", "10", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_N", null, "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_S", "10", "LEG_S").andExpect(status().isCreated());

        // 双舱航段缺少舱位指定 -> 422
        load("LEG_C", 1, List.of("BAG_M"), null).andExpect(status().isUnprocessableEntity());
        // 普通航段指定舱位 -> 422
        load("LEG_S", 1, List.of("BAG_S"), List.of(comp("BAG_S", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        // 未登记质量的行李装入双舱航段 -> 422
        load("LEG_C", 1, List.of("BAG_N"), List.of(comp("BAG_N", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        // 舱位指定的 bagTag 不在本批 -> 422
        load("LEG_C", 1, List.of("BAG_M"), List.of(comp("BAG_X", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        // 舱位取值非法 -> 400（参数校验）
        load("LEG_C", 1, List.of("BAG_M"), List.of(comp("BAG_M", "MID")))
                .andExpect(status().isBadRequest());

        // 全部失败均不改变航段与行李
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.version").value(1));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
    }

    @Test
    void registerBag_massValidation() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        // 非正质量 -> 400
        registerBag("BAG_Z", "0", "LEG_C").andExpect(status().isBadRequest());
        registerBag("BAG_Z", "-5", "LEG_C").andExpect(status().isBadRequest());
        // 超过 3 位小数 -> 400
        registerBag("BAG_Z", "1.0001", "LEG_C").andExpect(status().isBadRequest());
        // 合法：正值、3 位小数
        registerBag("BAG_Z", "0.001", "LEG_C").andExpect(status().isCreated())
                .andExpect(jsonPath("$.massKg").value(0.001));
        // 质量不可变：登记后无修改入口，轨迹中保持原值
        mockMvc.perform(get("/api/bags/BAG_Z/trace"))
                .andExpect(jsonPath("$.massKg").value(0.001));
    }

    @Test
    void moveCompartment_checksFinalPositionsAtomically() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_W1", "900", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_W2", "100", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_W3", "900", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_W4", "100", "LEG_C").andExpect(status().isCreated());
        load("LEG_C", 1, List.of("BAG_W1", "BAG_W2", "BAG_W3", "BAG_W4"),
                List.of(comp("BAG_W1", "FWD"), comp("BAG_W2", "FWD"),
                        comp("BAG_W3", "AFT"), comp("BAG_W4", "AFT")))
                .andExpect(status().isOk());

        // 交换两件 900 千克行李：逐件先搬必然中途超限，按最终全体位置核算通过
        move("LEG_C", 2, List.of(comp("BAG_W1", "AFT"), comp("BAG_W3", "FWD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.moved", contains("BAG_W1", "BAG_W3")));
        // 交换后两舱各 1000 千克，重心 = (-2000+3000)/2000 = 0.5 米，仍在闭区间内
        balance("LEG_C")
                .andExpect(jsonPath("$.totalMassKg").value(2000.0))
                .andExpect(jsonPath("$.centerOfGravityM").value(0.5));

        // 单件换舱使后舱 1100 千克超限：422 且不移动任何行李
        move("LEG_C", 3, List.of(comp("BAG_W2", "AFT")))
                .andExpect(status().isUnprocessableEntity());
        String w2Compartment = jdbcTemplate.queryForObject(
                "SELECT compartment FROM load_record WHERE bag_tag = 'BAG_W2'", String.class);
        assertThat(w2Compartment).isEqualTo("FWD");
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void moveCompartment_rejectsInvalidRequests() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerLeg("LEG_S", "STANDARD").andExpect(status().isCreated());
        registerBag("BAG_1", "60", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_2", "40", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_3", "10", "LEG_C").andExpect(status().isCreated());
        load("LEG_C", 1, List.of("BAG_1"), List.of(comp("BAG_1", "FWD")))
                .andExpect(status().isOk());

        // 版本冲突 -> 409
        move("LEG_C", 99, List.of(comp("BAG_1", "AFT"))).andExpect(status().isConflict());
        // 行李未装载在本航段 -> 422
        move("LEG_C", 2, List.of(comp("BAG_2", "AFT"))).andExpect(status().isUnprocessableEntity());
        // 同一批内 bagTag 重复 -> 422
        move("LEG_C", 2, List.of(comp("BAG_1", "AFT"), comp("BAG_1", "FWD")))
                .andExpect(status().isUnprocessableEntity());
        // 普通航段不支持换舱 -> 422
        move("LEG_S", 1, List.of(comp("BAG_1", "AFT"))).andExpect(status().isUnprocessableEntity());
        // 航段不存在 -> 404
        move("LEG_MISSING", 1, List.of(comp("BAG_1", "AFT"))).andExpect(status().isNotFound());

        // 装上 BAG_2 使重心合格（前 60 后 40，重心 0 米）后封舱
        load("LEG_C", 2, List.of("BAG_2"), List.of(comp("BAG_2", "AFT")))
                .andExpect(status().isOk());
        seal("LEG_C", 3).andExpect(status().isOk());

        // 封舱后禁止换舱、禁止加装
        move("LEG_C", 4, List.of(comp("BAG_1", "AFT"))).andExpect(status().isUnprocessableEntity());
        load("LEG_C", 4, List.of("BAG_3"), List.of(comp("BAG_3", "AFT")))
                .andExpect(status().isUnprocessableEntity());
        String compartment = jdbcTemplate.queryForObject(
                "SELECT compartment FROM load_record WHERE bag_tag = 'BAG_1'", String.class);
        assertThat(compartment).isEqualTo("FWD");
    }

    @Test
    void sealedBalance_isFrozenAndUnaffectedByShortUnload() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_F", "60", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_A", "40", "LEG_C").andExpect(status().isCreated());
        load("LEG_C", 1, List.of("BAG_F", "BAG_A"),
                List.of(comp("BAG_F", "FWD"), comp("BAG_A", "AFT")))
                .andExpect(status().isOk());
        seal("LEG_C", 2).andExpect(status().isOk());

        // 到达核对仍以原封舱集合为准：精确到达少一件 -> 422
        arrive("LEG_C", List.of("BAG_F")).andExpect(status().isUnprocessableEntity());

        // 差异到达：BAG_A 短卸
        arriveDifference("LEG_C", 3, List.of("BAG_F")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG_A")));

        // 短卸不改变历史重心：冻结快照保持总质量 100、总力矩 0
        balance("LEG_C")
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.totalMomentKgm").value(0.0))
                .andExpect(jsonPath("$.centerOfGravityM").value(0.0))
                .andExpect(jsonPath("$.items", hasSize(2)));
        Integer frozenItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seal_item WHERE leg_id = 'LEG_C'", Integer.class);
        assertThat(frozenItems).isEqualTo(2);
    }

    @Test
    void idempotency_compartmentWrites() throws Exception {
        registerLeg("LEG_C", "COMPARTMENT").andExpect(status().isCreated());
        registerBag("BAG_1", "60", "LEG_C").andExpect(status().isCreated());
        registerBag("BAG_2", "40", "LEG_C").andExpect(status().isCreated());

        // 装载同键同参重放：舱位顺序差异经结构化比较视为同参
        String loadKey = UUID.randomUUID().toString();
        Map<String, Object> loadBody = new HashMap<>();
        loadBody.put("requestId", loadKey);
        loadBody.put("expectedVersion", 1);
        loadBody.put("bagTags", List.of("BAG_1", "BAG_2"));
        loadBody.put("compartments", List.of(comp("BAG_1", "FWD"), comp("BAG_2", "AFT")));
        postJson("/api/legs/LEG_C/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        loadBody.put("compartments", List.of(comp("BAG_2", "AFT"), comp("BAG_1", "FWD")));
        postJson("/api/legs/LEG_C/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键异参 -> 409
        loadBody.put("compartments", List.of(comp("BAG_1", "AFT"), comp("BAG_2", "FWD")));
        postJson("/api/legs/LEG_C/load", loadBody).andExpect(status().isConflict());
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.version").value(2));

        // 换舱失败不占键：先以未装载行李失败，再同键修正成功
        String moveKey = UUID.randomUUID().toString();
        Map<String, Object> moveBody = new HashMap<>();
        moveBody.put("requestId", moveKey);
        moveBody.put("expectedVersion", 2);
        moveBody.put("moves", List.of(comp("BAG_X", "AFT")));
        postJson("/api/legs/LEG_C/move-compartment", moveBody)
                .andExpect(status().isUnprocessableEntity());
        moveBody.put("moves", List.of(comp("BAG_2", "FWD")));
        postJson("/api/legs/LEG_C/move-compartment", moveBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 同键同参重放：版本不再推进
        postJson("/api/legs/LEG_C/move-compartment", moveBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 同键异参 -> 409
        moveBody.put("moves", List.of(comp("BAG_2", "AFT")));
        postJson("/api/legs/LEG_C/move-compartment", moveBody).andExpect(status().isConflict());
        mockMvc.perform(get("/api/legs/LEG_C/manifest"))
                .andExpect(jsonPath("$.version").value(3));
    }

    private Map<String, String> comp(String bagTag, String compartment) {
        return Map.of("bagTag", bagTag, "compartment", compartment);
    }

    private ResultActions registerLeg(String legId, String legType) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", "PEK", "destination", "SHA", "legType", legType));
    }

    private ResultActions registerBag(String bagTag, String massKg, String... legIds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", List.of(legIds));
        if (massKg != null) {
            body.put("massKg", massKg);
        }
        return postJson("/api/bags", body);
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags,
                               List<Map<String, String>> compartments) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        if (compartments != null) {
            body.put("compartments", compartments);
        }
        return postJson("/api/legs/" + legId + "/load", body);
    }

    private ResultActions move(String legId, int expectedVersion,
                               List<Map<String, String>> moves) throws Exception {
        return postJson("/api/legs/" + legId + "/move-compartment", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "moves", moves));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions arriveDifference(String legId, int expectedVersion,
                                           List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions balance(String legId) throws Exception {
        return mockMvc.perform(get("/api/legs/" + legId + "/balance"));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}