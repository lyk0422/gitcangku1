package com.example.starter.baggage;

import java.time.Instant;
import java.util.ArrayList;
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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.OffloadRequest;
import com.example.starter.baggage.BaggageDtos.OffloadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 航段容量与优先级卸载测试：覆盖装载容量上限校验（422 及超出维度）、
 * 卸载决策顺序（BASIC→STANDARD→PREMIUM、同级重量降序/bagTag 升序）、待乘索引冻结、
 * 改派重新装载、封舱/到达交互（409 与清单排除）、载量/卸载明细/轨迹查询、
 * offloadKey 幂等以及卸载/装载/封舱真实并发的提交顺序裁决。全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCapacityOffloadTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-24T08:30:00Z");

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
        jdbcTemplate.update("DELETE FROM offload_item");
        jdbcTemplate.update("DELETE FROM offload_record");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
        executor = Executors.newFixedThreadPool(12);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void register_validationRangesAndCabin() throws Exception {
        // 件数上限越界 -> 400
        postJson("/api/legs", legBody("L0", 0, 100)).andExpect(status().isBadRequest());
        postJson("/api/legs", legBody("L0", 501, 100)).andExpect(status().isBadRequest());
        // 总重上限越界 -> 400
        postJson("/api/legs", legBody("L0", 10, 0)).andExpect(status().isBadRequest());
        postJson("/api/legs", legBody("L0", 10, 50001)).andExpect(status().isBadRequest());
        registerLeg("LEG1", "PEK", "SHA", 2, 30).andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxBags").value(2))
                .andExpect(jsonPath("$.maxWeight").value(30));

        // 重量越界 -> 400
        registerBagHttp("B0", List.of("LEG1"), 0, "BASIC").andExpect(status().isBadRequest());
        registerBagHttp("B0", List.of("LEG1"), 51, "BASIC").andExpect(status().isBadRequest());
        // 舱位非法 -> 422
        registerBagHttp("B0", List.of("LEG1"), 10, "ECONOMY").andExpect(status().isUnprocessableEntity());
        registerBagHttp("B1", List.of("LEG1"), 10, "basic").andExpect(status().isUnprocessableEntity());
        registerBagHttp("B1", List.of("LEG1"), 10, "BASIC").andExpect(status().isCreated())
                .andExpect(jsonPath("$.weight").value(10))
                .andExpect(jsonPath("$.cabin").value("BASIC"));
    }

    @Test
    void load_rejectsBatchWhenPostLoadCapacityExceededAndReportsDimensions() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 2, 30);
        registerBag("BAG1", List.of("LEG1"), 20, "BASIC");
        registerBag("BAG2", List.of("LEG1"), 15, "STANDARD");
        registerBag("BAG3", List.of("LEG1"), 5, "BASIC");

        // 先装 2 件共 25 千克：件数 2、总重 25 均不超限
        load("LEG1", 1, List.of("BAG1", "BAG3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 再装 BAG2：件数 3>2、总重 40>30，双维度超限整批 422，无一件移动
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 2, "bagTags", List.of("BAG2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("BAGS")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("WEIGHT")));

        mockMvc.perform(get("/api/legs/LEG1/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.occupiedBags").value(2))
                .andExpect(jsonPath("$.occupiedWeight").value(25))
                .andExpect(jsonPath("$.remainingBags").value(0))
                .andExpect(jsonPath("$.remainingWeight").value(5));
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
    }

    @Test
    void load_rejectsBatchWhenOnlyWeightExceeded() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 30);
        registerBag("BAG1", List.of("LEG1"), 20, "BASIC");
        registerBag("BAG2", List.of("LEG1"), 15, "STANDARD");

        // 件数不超、总重 35>30：422 且只报 WEIGHT 维度
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("BAG1", "BAG2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("WEIGHT")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BAGS"))));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();

        // 拆成不超重的批次后可装
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        load("LEG1", 2, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void offload_followsCabinThenWeightThenBagTagOrderAndFreezesIndex() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        // BASIC: 10,20(B20A),20(B20B)；STANDARD:30；PREMIUM:40，共 5 件 120 千克
        registerBag("B10", List.of("LEG1"), 10, "BASIC");
        registerBag("B20A", List.of("LEG1"), 20, "BASIC");
        registerBag("B20B", List.of("LEG1"), 20, "BASIC");
        registerBag("S30", List.of("LEG1"), 30, "STANDARD");
        registerBag("P40", List.of("LEG1"), 40, "PREMIUM");
        load("LEG1", 1, List.of("B10", "B20A", "B20B", "S30", "P40")).andExpect(status().isOk());

        // 目标保留 2 件且总重不超过 100：依次卸 B20A、B20B、B10，保留 P40+S30=70
        offload("OFF-1", "LEG1", 2, 2, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.retainedBags").value(2))
                .andExpect(jsonPath("$.retainedWeight").value(70))
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("B20A"))
                .andExpect(jsonPath("$.offloaded[0].cabin").value("BASIC"))
                .andExpect(jsonPath("$.offloaded[0].weight").value(20))
                .andExpect(jsonPath("$.offloaded[0].originNextLegIndex").value(0))
                .andExpect(jsonPath("$.offloaded[1].bagTag").value("B20B"))
                .andExpect(jsonPath("$.offloaded[2].bagTag").value("B10"))
                .andExpect(jsonPath("$.retained", contains("P40", "S30")));

        // 被卸行李：OFFLOADED、移出清单、位置与待乘索引不变（不推进）
        for (String tag : List.of("B20A", "B20B", "B10")) {
            mockMvc.perform(get("/api/bags/" + tag + "/trace"))
                    .andExpect(jsonPath("$.status").value("OFFLOADED"))
                    .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                    .andExpect(jsonPath("$.currentLocation").value("PEK"))
                    .andExpect(jsonPath("$.nextLegIndex").value(0))
                    .andExpect(jsonPath("$.events[-1].eventType").value("CAPACITY_OFFLOADED"))
                    .andExpect(jsonPath("$.events[-1].legId").value("LEG1"))
                    .andExpect(jsonPath("$.events[-1].eventTime").value(FIXED_NOW.toString()));
        }
        // 保留行李不受影响
        mockMvc.perform(get("/api/bags/P40/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));

        // 清单中只剩保留行李，无行李同时出现在保留与卸载集合
        assertThat(queryStringList("SELECT bag_tag FROM load_record WHERE leg_id = 'LEG1' ORDER BY bag_tag"))
                .containsExactly("P40", "S30");
        Integer overlap = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record lr JOIN offload_item oi ON oi.bag_tag = lr.bag_tag"
                        + " WHERE oi.offload_key = 'OFF-1'", Integer.class);
        assertThat(overlap).isZero();

        // 卸载明细查询
        mockMvc.perform(get("/api/legs/LEG1/offloads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offloads", hasSize(1)))
                .andExpect(jsonPath("$.offloads[0].offloadKey").value("OFF-1"))
                .andExpect(jsonPath("$.offloads[0].offloadedCount").value(3))
                .andExpect(jsonPath("$.offloads[0].offloadedWeight").value(50))
                .andExpect(jsonPath("$.offloads[0].retainedCount").value(2))
                .andExpect(jsonPath("$.offloads[0].retainedWeight").value(70))
                .andExpect(jsonPath("$.offloads[0].offloadedAt").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.offloads[0].items[0].bagTag").value("B20A"))
                .andExpect(jsonPath("$.offloads[0].items[2].originNextLegIndex").value(0));

        mockMvc.perform(get("/api/legs/LEG1/capacity"))
                .andExpect(jsonPath("$.occupiedBags").value(2))
                .andExpect(jsonPath("$.occupiedWeight").value(70));
    }

    @Test
    void offload_premiumLastAndHigherWeightFirstWithinSameCabin() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        registerBag("BASIC_HEAVY", List.of("LEG1"), 50, "BASIC");
        registerBag("STANDARD_LIGHT", List.of("LEG1"), 1, "STANDARD");
        registerBag("PREMIUM_MID", List.of("LEG1"), 30, "PREMIUM");
        load("LEG1", 1, List.of("BASIC_HEAVY", "STANDARD_LIGHT", "PREMIUM_MID")).andExpect(status().isOk());

        // 目标保留 2 件：BASIC 最先卸，STANDARD/PREMIUM 保留
        offload("OFF-2", "LEG1", 2, 2, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded", hasSize(1)))
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("BASIC_HEAVY"))
                .andExpect(jsonPath("$.retained", contains("PREMIUM_MID", "STANDARD_LIGHT")));
        // 目标保留 1 件：STANDARD 先于 PREMIUM 被卸，PREMIUM 最后保留
        offload("OFF-3", "LEG1", 3, 1, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("STANDARD_LIGHT"))
                .andExpect(jsonPath("$.retained", contains("PREMIUM_MID")));
    }

    @Test
    void offload_alreadyWithinTargetReturnsEmptyListWithoutVersionChange() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 100);
        registerBag("BAG1", List.of("LEG1"), 20, "BASIC");
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());

        offload("OFF-EMPTY", "LEG1", 2, 5, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.offloaded", hasSize(0)))
                .andExpect(jsonPath("$.retained", contains("BAG1")))
                .andExpect(jsonPath("$.retainedBags").value(1))
                .andExpect(jsonPath("$.retainedWeight").value(20));

        // 空卸载不产生执行记录，行李与版本不变
        Integer offloadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_record", Integer.class);
        assertThat(offloadRecords).isZero();
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
    }

    @Test
    void offload_targetAboveRegisteredLimitReturns422() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 100);
        registerBag("BAG1", List.of("LEG1"), 90, "BASIC");
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());

        offload("OFF-HI-1", "LEG1", 2, 11, 100).andExpect(status().isUnprocessableEntity());
        offload("OFF-HI-2", "LEG1", 2, 10, 101).andExpect(status().isUnprocessableEntity());
        // 失败不占键：同键改合法参数可成功
        offload("OFF-HI-2", "LEG1", 2, 0, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("BAG1"));
    }

    @Test
    void offload_sealedOrArrivedReturns409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        registerBag("BAG1", List.of("LEG1"), 20, "BASIC");
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2);

        // SEALED 卸载 409
        offload("OFF-S1", "LEG1", 3, 0, 0).andExpect(status().isConflict());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        // ARRIVED 卸载 409
        offload("OFF-S2", "LEG1", 4, 0, 0).andExpect(status().isConflict());

        // 封舱后清单未被卸载改变
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void offloadedBag_excludedFromSealManifestAndReloadedOnlyAfterReassignment() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 100);
        registerLeg("LEG2", "SHA", "CAN", 500, 50000);
        registerBag("BAG_KEEP", List.of("LEG1"), 10, "PREMIUM");
        registerBag("BAG_OFF", List.of("LEG1", "LEG2"), 30, "BASIC");
        load("LEG1", 1, List.of("BAG_KEEP", "BAG_OFF")).andExpect(status().isOk());

        // 目标总重 10：BASIC 重件 BAG_OFF 被卸，PREMIUM 保留
        offload("OFF-EX", "LEG1", 2, 5, 10).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("BAG_OFF"));

        // 改派前封舱：清单不含被卸行李
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG_KEEP")));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.manifest", contains("BAG_KEEP")));
        arrive("LEG1", List.of("BAG_KEEP")).andExpect(status().isOk());

        // 被卸行李滞留始发站、索引冻结，未进入任何封舱清单
        mockMvc.perform(get("/api/bags/BAG_OFF/trace"))
                .andExpect(jsonPath("$.status").value("OFFLOADED"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
    }

    @Test
    void offloadedBag_canReloadViaExistingLoadEntryWhileLegOpenAndContinueItinerary() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 100);
        registerLeg("LEG2", "SHA", "CAN", 500, 50000);
        registerBag("BAG1", List.of("LEG1", "LEG2"), 30, "BASIC");
        registerBag("BAG2", List.of("LEG1"), 10, "PREMIUM");
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        offload("OFF-RE", "LEG1", 2, 5, 10).andExpect(status().isOk());

        // 航段仍 OPEN：OFFLOADED 行李沿用既有批量装载入口改派重新装回同一待乘航段
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "CAPACITY_OFFLOADED", "LOADED")));

        // 随后正常封舱到达并续乘后续航段直至交付
        seal("LEG1", 4).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"));
    }

    @Test
    void offload_idempotentReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        registerBag("BAG1", List.of("LEG1"), 30, "BASIC");
        registerBag("BAG2", List.of("LEG1"), 20, "STANDARD");
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());

        // 错误版本先失败（409），不占 offloadKey
        offload("OFF-IDEM", "LEG1", 99, 1, 100).andExpect(status().isConflict());
        // 同键正确参数成功：目标保留 1 件，仅 BASIC 的 BAG1 被卸，STANDARD 的 BAG2 保留
        offload("OFF-IDEM", "LEG1", 2, 1, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.offloaded[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.retained", contains("BAG2")));
        // 同参重放：原结果（含 version=3），不再次执行
        offload("OFF-IDEM", "LEG1", 2, 1, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'OFF-IDEM'", Integer.class);
        assertThat(logs).isEqualTo(1);
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_item WHERE offload_key = 'OFF-IDEM'", Integer.class);
        assertThat(itemCount).isEqualTo(1);

        // 同键异参 -> 409
        offload("OFF-IDEM", "LEG1", 2, 0, 100).andExpect(status().isConflict());
    }

    @Test
    void offload_unknownLeg404AndVersionConflict409() throws Exception {
        offload("OFF-X", "LEG_MISSING", 1, 1, 1).andExpect(status().isNotFound());

        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        registerBag("BAG1", List.of("LEG1"), 10, "BASIC");
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        offload("OFF-V", "LEG1", 99, 0, 0).andExpect(status().isConflict());
        // 失败不占键：同键以当前版本可成功（空卸载也算首次成功结果）
        offload("OFF-V", "LEG1", 2, 5, 100).andExpect(status().isOk());
    }

    @Test
    void concurrentOffloadSealAndLoad_onlyOneWinsAndStateStaysConsistent() throws Exception {
        for (int iteration = 0; iteration < 6; iteration++) {
            String suffix = "-" + iteration;
            registerLeg("LEG1" + suffix, "PEK", "SHA", 500, 50000);
            List<String> loaded = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String tag = "B" + i + suffix;
                registerBag(tag, List.of("LEG1" + suffix), 10, "BASIC");
                loaded.add(tag);
            }
            baggageService.load("LEG1" + suffix,
                    new LoadRequest(UUID.randomUUID().toString(), 1, loaded));
            registerBag("BNEW" + suffix, List.of("LEG1" + suffix), 10, "STANDARD");

            int baseVersion = 2;
            List<Callable<Object>> tasks = List.of(
                    () -> baggageService.offload("LEG1" + suffix, new OffloadRequest(
                            UUID.randomUUID().toString(), baseVersion, 3, 50000)),
                    () -> baggageService.seal("LEG1" + suffix,
                            new SealRequest(UUID.randomUUID().toString(), baseVersion)),
                    () -> baggageService.load("LEG1" + suffix, new LoadRequest(
                            UUID.randomUUID().toString(), baseVersion, List.of("BNEW" + suffix))));
            List<Object> results = runConcurrently(tasks);

            long successes = results.stream()
                    .filter(r -> !(r instanceof ApiException))
                    .count();
            long conflicts = results.stream()
                    .filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .filter(ex -> ex.getStatus().value() == 409)
                    .count();
            assertThat(successes).as("迭代 %d 仅一个提交者成功", iteration).isEqualTo(1);
            assertThat(conflicts).as("迭代 %d 其余提交者版本冲突", iteration).isEqualTo(2);
            assertConsistentState("LEG1" + suffix, 500, 50000);
        }
    }

    @Test
    void concurrentSameOffloadKey_singleEffectWithReplay() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            registerBag("B" + i, List.of("LEG1"), 10, "BASIC");
            tags.add("B" + i);
        }
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, tags));

        String key = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> baggageService.offload("LEG1", new OffloadRequest(key, 2, 1, 50000)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(OffloadResponse.class);
            OffloadResponse response = (OffloadResponse) result;
            assertThat(response.version()).isEqualTo(3);
            assertThat(response.offloaded()).hasSize(3);
            assertThat(response.offloaded().get(0).bagTag()).isEqualTo("B0");
            assertThat(response.retained()).containsExactly("B3");
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        assertThat(queryStringList("SELECT bag_tag FROM load_record WHERE leg_id = 'LEG1'"))
                .containsExactly("B3");
        Integer heads = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_record WHERE offload_key = ?", Integer.class, key);
        assertThat(heads).isEqualTo(1);
        Integer items = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_item WHERE offload_key = ?", Integer.class, key);
        assertThat(items).isEqualTo(3);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentDistinctOffloadKeys_serializedByLegLock() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 500, 50000);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            registerBag("B" + i, List.of("LEG1"), 10, "BASIC");
            tags.add("B" + i);
        }
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1, tags));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> baggageService.offload("LEG1", new OffloadRequest(
                    UUID.randomUUID().toString(), 2, 1, 50000)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(OffloadResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        assertConsistentState("LEG1", 500, 50000);
    }

    /** 校验任何并发结局下：载量不超限、清单与行李状态一致、封舱清单等于清单快照、无保留/卸载重叠。 */
    private void assertConsistentState(String legId, int maxBags, int maxWeight) {
        Integer occupiedBags = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
        Integer occupiedWeight = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(b.weight),0) FROM load_record lr JOIN bag b ON b.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ?", Integer.class, legId);
        assertThat(occupiedBags).isLessThanOrEqualTo(maxBags);
        assertThat(occupiedWeight).isLessThanOrEqualTo(maxWeight);

        // 清单内行李必须仍标记装载到本航段；OFFLOADED 行李不得残留在清单
        Integer stale = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record lr JOIN bag b ON b.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ? AND (b.status <> 'IN_TRANSIT' OR b.loaded_leg_id <> ?)",
                Integer.class, legId, legId);
        assertThat(stale).isZero();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = ?", String.class, legId);
        if ("SEALED".equals(status) || "ARRIVED".equals(status)) {
            // 封舱后清单不得再变化：只读快照袋号集合必须与封舱时刻的装载清单一致，且不含任何本航段被卸行李。
            List<String> manifest = baggageService.getManifest(legId).manifest();
            List<String> offloadedForLeg = jdbcTemplate.queryForList(
                    "SELECT bag_tag FROM offload_item WHERE leg_id = ?", String.class, legId);
            if (!offloadedForLeg.isEmpty()) {
                assertThat(manifest).doesNotContainAnyElementsOf(offloadedForLeg);
            }
            if ("SEALED".equals(status)) {
                assertThat(manifest).containsExactlyInAnyOrderElementsOf(
                        jdbcTemplate.queryForList(
                                "SELECT bag_tag FROM load_record WHERE leg_id = ?", String.class, legId));
            }
        }
    }

    private List<String> queryStringList(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      int maxBags, int maxWeight) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination,
                "maxBags", maxBags, "maxWeight", maxWeight));
    }

    private Map<String, Object> legBody(String legId, int maxBags, int maxWeight) {
        return Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", "PEK", "destination", "SHA",
                "maxBags", maxBags, "maxWeight", maxWeight);
    }

    private ResultActions registerBagHttp(String bagTag, List<String> legIds, int weight, String cabin)
            throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds, "weight", weight, "cabin", cabin));
    }

    private void registerBag(String bagTag, List<String> legIds, int weight, String cabin) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, legIds, weight, cabin));
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

    private ResultActions offload(String key, String legId, int expectedVersion,
                                  int targetBags, int targetWeight) throws Exception {
        return postJson("/api/legs/" + legId + "/offload", Map.of(
                "offloadKey", key, "expectedVersion", expectedVersion,
                "targetMaxBags", targetBags, "targetMaxWeight", targetWeight));
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
