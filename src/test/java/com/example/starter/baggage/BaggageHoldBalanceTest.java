package com.example.starter.baggage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.baggage.BaggageDtos.HoldAssignment;
import com.example.starter.baggage.BaggageDtos.ReholdRequest;
import com.example.starter.baggage.BaggageDtos.ReholdResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固定舱位航段（HOLD）装载限重、纵向力矩平衡与批量换舱测试：
 * 覆盖主流程（合成前舱 60 千克、后舱 40 千克，重心 0 可封舱）、
 * 失败分支（舱超重整批回滚、重心超限保持开放、封舱后禁止换舱）、
 * 换舱与封舱的版本竞争、幂等重放与异参 409、短卸不改变历史重心。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageHoldBalanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void mainFlow_holdLegLoadSealAndFreezeBalance() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("HOLD"));
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated())
                .andExpect(jsonPath("$.massKg").value(60.0));
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());

        // 合成场景：前舱 60 千克、后舱 40 千克，力矩 -120+120=0，重心 0 可封舱
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        String fwdHold = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG1'", String.class);
        assertThat(fwdHold).isEqualTo("FWD");

        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.momentKgm").value(0.0));

        // 封舱冻结：每件质量、位置、总重与精确力矩可查询
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.momentKgm").value(0.0))
                .andExpect(jsonPath("$.detail", hasSize(2)))
                .andExpect(jsonPath("$.detail[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.detail[0].massKg").value(60.0))
                .andExpect(jsonPath("$.detail[0].holdCode").value("FWD"))
                .andExpect(jsonPath("$.detail[0].positionM").value(-2))
                .andExpect(jsonPath("$.detail[1].holdCode").value("AFT"))
                .andExpect(jsonPath("$.detail[1].positionM").value(3));

        BigDecimal frozenMass = jdbcTemplate.queryForObject(
                "SELECT sealed_total_mass FROM leg WHERE leg_id = 'LEG1'", BigDecimal.class);
        BigDecimal frozenMoment = jdbcTemplate.queryForObject(
                "SELECT sealed_moment FROM leg WHERE leg_id = 'LEG1'", BigDecimal.class);
        assertThat(frozenMass).isEqualByComparingTo("100.000");
        assertThat(frozenMoment).isEqualByComparingTo("0.000");
    }

    @Test
    void seal_cgOutOfRangeStaysOpenAfterRehold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk());

        // 合成场景：把 40 千克移到前舱后重心 -2，不能封舱
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 2, Map.of("BAG2", "FWD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.changed", contains("BAG2")));

        seal("LEG1", 3).andExpect(status().isUnprocessableEntity());
        // 保持开放：状态与版本不变，可继续换舱恢复平衡后封舱
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3));
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 3, Map.of("BAG2", "AFT"))
                .andExpect(status().isOk());
        seal("LEG1", 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.momentKgm").value(0.0));
    }

    @Test
    void seal_cgBoundaryIsInclusiveAndCrossMultiplied() throws Exception {
        // 重心恰好 +0.5 米：前舱 50、后舱 50，力矩 50，可封舱
        registerLeg("LEG_HI", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG_H1", List.of("LEG_HI"), "50").andExpect(status().isCreated());
        registerBag("BAG_H2", List.of("LEG_HI"), "50").andExpect(status().isCreated());
        load("LEG_HI", 1, List.of("BAG_H1", "BAG_H2"), Map.of("BAG_H1", "FWD", "BAG_H2", "AFT"))
                .andExpect(status().isOk());
        seal("LEG_HI", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.momentKgm").value(50.0));

        // 重心恰好 -0.5 米：前舱 70、后舱 30，力矩 -50，可封舱
        registerLeg("LEG_LO", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG_L1", List.of("LEG_LO"), "70").andExpect(status().isCreated());
        registerBag("BAG_L2", List.of("LEG_LO"), "30").andExpect(status().isCreated());
        load("LEG_LO", 1, List.of("BAG_L1", "BAG_L2"), Map.of("BAG_L1", "FWD", "BAG_L2", "AFT"))
                .andExpect(status().isOk());
        seal("LEG_LO", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.momentKgm").value(-50.0));

        // 重心 -0.50005 米（3 位小数质量）：前舱 70.001、后舱 29.999，力矩 -50.005，拒绝
        registerLeg("LEG_OUT", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG_O1", List.of("LEG_OUT"), "70.001").andExpect(status().isCreated());
        registerBag("BAG_O2", List.of("LEG_OUT"), "29.999").andExpect(status().isCreated());
        load("LEG_OUT", 1, List.of("BAG_O1", "BAG_O2"), Map.of("BAG_O1", "FWD", "BAG_O2", "AFT"))
                .andExpect(status().isOk());
        seal("LEG_OUT", 2).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG_OUT/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void seal_holdLegRequiresPositiveTotalMass() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        // 空舱总质量为 0，不满足正值要求；旧航段空清单封舱行为不变（由既有用例覆盖）
        seal("LEG1", 1).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void registerBag_massMustBePositiveWithAtMostThreeDecimals() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        // 负质量、零质量、超过 3 位小数 -> 400
        registerBag("BAG_NEG", List.of("LEG1"), "-1").andExpect(status().isBadRequest());
        registerBag("BAG_ZERO", List.of("LEG1"), "0").andExpect(status().isBadRequest());
        registerBag("BAG_4DP", List.of("LEG1"), "1.0001").andExpect(status().isBadRequest());
        // 3 位小数正质量合法
        registerBag("BAG_OK", List.of("LEG1"), "0.001").andExpect(status().isCreated())
                .andExpect(jsonPath("$.massKg").value(0.001));
        Integer bagCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bag", Integer.class);
        assertThat(bagCount).isEqualTo(1);
    }

    @Test
    void load_holdLegRequiresHoldsAndMass() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "10").andExpect(status().isCreated());
        registerBag("BAG_NOMASS", List.of("LEG1"), null).andExpect(status().isCreated());

        // 缺舱位指派 -> 422
        load("LEG1", 1, List.of("BAG1"), null).andExpect(status().isUnprocessableEntity());
        // 指派未覆盖全部 bagTag -> 422
        load("LEG1", 1, List.of("BAG1", "BAG_NOMASS"), Map.of("BAG1", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        // 未知舱位 -> 422
        load("LEG1", 1, List.of("BAG1"), Map.of("BAG1", "MID"))
                .andExpect(status().isUnprocessableEntity());
        // 未登记质量的行李不能装上固定舱位航段 -> 422
        load("LEG1", 1, List.of("BAG_NOMASS"), Map.of("BAG_NOMASS", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        // 版本竞争 -> 409
        load("LEG1", 99, List.of("BAG1"), Map.of("BAG1", "FWD"))
                .andExpect(status().isConflict());

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(1);
    }

    @Test
    void load_classicLegRejectsHoldAssignments() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("CLASSIC"));
        registerBag("BAG1", List.of("LEG1"), "10").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1"), Map.of("BAG1", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        // 旧航段无舱位装载不受影响
        load("LEG1", 1, List.of("BAG1"), null).andExpect(status().isOk());
        String holdCode = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG1'", String.class);
        assertThat(holdCode).isNull();
    }

    @Test
    void load_overweightHoldRollsBackWholeBatch() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG_HEAVY", List.of("LEG1"), "800").andExpect(status().isCreated());
        registerBag("BAG_OVER", List.of("LEG1"), "300").andExpect(status().isCreated());
        registerBag("BAG_FINE", List.of("LEG1"), "50").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG_HEAVY"), Map.of("BAG_HEAVY", "FWD"))
                .andExpect(status().isOk());

        // 前舱 800+300=1100 超 1000 限重：整批 422，同批合法的后舱行李也不移动
        load("LEG1", 2, List.of("BAG_OVER", "BAG_FINE"),
                Map.of("BAG_OVER", "FWD", "BAG_FINE", "AFT"))
                .andExpect(status().isUnprocessableEntity());

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isEqualTo(1);
        mockMvc.perform(get("/api/bags/BAG_FINE/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2));

        // 恰好装满 1000 千克合法
        load("LEG1", 2, List.of("BAG_FINE"), Map.of("BAG_FINE", "AFT"))
                .andExpect(status().isOk());
        registerBag("BAG_TOP", List.of("LEG1"), "200").andExpect(status().isCreated());
        load("LEG1", 3, List.of("BAG_TOP"), Map.of("BAG_TOP", "FWD"))
                .andExpect(status().isOk());
    }

    @Test
    void rehold_checksFinalPositionsNotPiecewise() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG_A", List.of("LEG1"), "900").andExpect(status().isCreated());
        registerBag("BAG_B", List.of("LEG1"), "900").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG_A", "BAG_B"), Map.of("BAG_A", "FWD", "BAG_B", "AFT"))
                .andExpect(status().isOk());

        // 两件 900 千克互换舱位：逐件先搬必中途超重，按最终全体位置检查应成功
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 2,
                Map.of("BAG_A", "AFT", "BAG_B", "FWD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed", contains("BAG_A", "BAG_B")));
        String bagAHold = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG_A'", String.class);
        assertThat(bagAHold).isEqualTo("AFT");

        // 换舱后超重：前舱 900+900=1800，整批 422 且位置不变
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 3, Map.of("BAG_A", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        String bagAHoldAfter = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG_A'", String.class);
        assertThat(bagAHoldAfter).isEqualTo("AFT");
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
    }

    @Test
    void rehold_rejectsInvalidRequests() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerLeg("LEG_C", "PEK", "SHA", null).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());
        registerBag("BAG3", List.of("LEG1"), "20").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk());

        // 未装载到本航段的行李不能换舱 -> 422
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 2, Map.of("BAG3", "AFT"))
                .andExpect(status().isUnprocessableEntity());
        // 未知舱位 -> 422
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 2, Map.of("BAG1", "MID"))
                .andExpect(status().isUnprocessableEntity());
        // 版本冲突 -> 409
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 99, Map.of("BAG1", "AFT"))
                .andExpect(status().isConflict());
        // 旧航段禁止换舱 -> 422
        rehold(UUID.randomUUID().toString(), "LEG_C", "ops-1", 1, Map.of("BAG1", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        // 航段不存在 -> 404
        rehold(UUID.randomUUID().toString(), "LEG_MISSING", "ops-1", 1, Map.of("BAG1", "FWD"))
                .andExpect(status().isNotFound());

        // 封舱后禁止换舱，舱位冻结不变
        seal("LEG1", 2).andExpect(status().isOk());
        rehold(UUID.randomUUID().toString(), "LEG1", "ops-1", 3, Map.of("BAG1", "AFT"))
                .andExpect(status().isUnprocessableEntity());
        String holdCode = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG1'", String.class);
        assertThat(holdCode).isEqualTo("FWD");
    }

    @Test
    void concurrentReholdAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk());

        // 换舱（40 千克移到前舱）与封舱真实并发，版本决定唯一先后，失败方不动任何行李
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.rehold("LEG1", new ReholdRequest(UUID.randomUUID().toString(),
                        "ops-1", 2, List.of(new HoldAssignment("BAG2", "FWD")))),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), "ops-2", 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof ReholdResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        String bag2Hold = jdbcTemplate.queryForObject(
                "SELECT hold_code FROM load_record WHERE bag_tag = 'BAG2'", String.class);
        if ("SEALED".equals(status)) {
            // 封舱先成功：换舱失败且未搬动，冻结重心 0
            assertThat(bag2Hold).isEqualTo("AFT");
            BigDecimal moment = jdbcTemplate.queryForObject(
                    "SELECT sealed_moment FROM leg WHERE leg_id = 'LEG1'", BigDecimal.class);
            assertThat(moment).isEqualByComparingTo("0.000");
        } else {
            // 换舱先成功：封舱失败，航段开放且重心 -2 不可封舱
            assertThat(status).isEqualTo("OPEN");
            assertThat(bag2Hold).isEqualTo("FWD");
            seal("LEG1", 3).andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void rehold_idempotentReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk());

        // 同键同参重放首次完整响应，版本只推进一次
        String requestId = UUID.randomUUID().toString();
        rehold(requestId, "LEG1", "ops-1", 2, Map.of("BAG2", "FWD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.changed", contains("BAG2")));
        rehold(requestId, "LEG1", "ops-1", 2, Map.of("BAG2", "FWD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同键异参（操作者不同）-> 409
        rehold(requestId, "LEG1", "ops-2", 2, Map.of("BAG2", "FWD"))
                .andExpect(status().isConflict());
        // 同键异参（舱位不同）-> 409
        rehold(requestId, "LEG1", "ops-1", 2, Map.of("BAG2", "AFT"))
                .andExpect(status().isConflict());

        // 失败不占键：先以未装载行李失败（422），再同键修正参数成功
        String retryKey = UUID.randomUUID().toString();
        rehold(retryKey, "LEG1", "ops-1", 3, Map.of("BAG_MISSING", "FWD"))
                .andExpect(status().isUnprocessableEntity());
        rehold(retryKey, "LEG1", "ops-1", 3, Map.of("BAG2", "AFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void shortUnloadDoesNotChangeFrozenBalance() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "HOLD").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"), "60").andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1"), "40").andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2"), Map.of("BAG1", "FWD", "BAG2", "AFT"))
                .andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());

        // 差异到达：BAG2 短卸，历史重心不变
        diffArrive("LEG1", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG2")));

        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.totalMassKg").value(100.0))
                .andExpect(jsonPath("$.momentKgm").value(0.0))
                .andExpect(jsonPath("$.detail", hasSize(2)));
        BigDecimal frozenMass = jdbcTemplate.queryForObject(
                "SELECT sealed_total_mass FROM leg WHERE leg_id = 'LEG1'", BigDecimal.class);
        BigDecimal frozenMoment = jdbcTemplate.queryForObject(
                "SELECT sealed_moment FROM leg WHERE leg_id = 'LEG1'", BigDecimal.class);
        assertThat(frozenMass).isEqualByComparingTo("100.000");
        assertThat(frozenMoment).isEqualByComparingTo("0.000");
    }

    private ResultActions registerLeg(String legId, String origin, String destination, String kind)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        if (kind != null) {
            body.put("kind", kind);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds, String massKg)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", legIds);
        if (massKg != null) {
            body.put("massKg", new BigDecimal(massKg));
        }
        return postJson("/api/bags", body);
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags,
                               Map<String, String> holds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("operator", "loader-1");
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        if (holds != null) {
            body.put("holds", holdList(holds));
        }
        return postJson("/api/legs/" + legId + "/load", body);
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("operator", "sealer-1");
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/legs/" + legId + "/seal", body);
    }

    private ResultActions rehold(String requestId, String legId, String operator,
                                 int expectedVersion, Map<String, String> assignments)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("expectedVersion", expectedVersion);
        body.put("assignments", holdList(assignments));
        return postJson("/api/legs/" + legId + "/rehold", body);
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private static List<Map<String, String>> holdList(Map<String, String> holds) {
        return holds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of("bagTag", entry.getKey(), "holdCode", entry.getValue()))
                .toList();
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
