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
 * 航段载量上限与优先级卸载测试：覆盖登记上限校验、批量装载超限整批 422（含超出维度与数值）、
 * 卸载确定顺序（BASIC -> STANDARD -> PREMIUM、同级重量降序、同重 bagTag 升序）、
 * 待乘索引冻结、OFFLOADED 改派前后封舱交互、目标上限边界、SEALED/ARRIVED 409、
 * 载量占用/卸载明细/轨迹查询，以及卸载与装载/封舱并发裁决和 offloadKey 幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageOffloadTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-24T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM offload_record");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void register_validatesCapacityWeightAndCabinRanges() throws Exception {
        // 航段件数/总重上限越界 -> 400
        registerLeg("L0", "PEK", "SHA", 0, 1000).andExpect(status().isBadRequest());
        registerLeg("L0", "PEK", "SHA", 501, 1000).andExpect(status().isBadRequest());
        registerLeg("L0", "PEK", "SHA", 10, 0).andExpect(status().isBadRequest());
        registerLeg("L0", "PEK", "SHA", 10, 50001).andExpect(status().isBadRequest());
        // 缺省载量字段时规范化为最宽松上限，可正常登记
        registerLeg("LDEF", "PEK", "SHA", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxPieces").value(500))
                .andExpect(jsonPath("$.maxWeightKg").value(50000));

        registerLeg("LEG1", "PEK", "SHA", 3, 100).andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxPieces").value(3))
                .andExpect(jsonPath("$.maxWeightKg").value(100));

        // 行李重量/舱位越界 -> 400
        registerBag("BX", List.of("LEG1"), 0, "BASIC").andExpect(status().isBadRequest());
        registerBag("BX", List.of("LEG1"), 51, "BASIC").andExpect(status().isBadRequest());
        registerBag("BX", List.of("LEG1"), 10, "ECONOMY").andExpect(status().isBadRequest());
        // 缺省重量/舱位时规范化为 10 千克 STANDARD
        registerBag("BDEF", List.of("LEG1"), null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.weightKg").value(10))
                .andExpect(jsonPath("$.cabinClass").value("STANDARD"));

        registerBag("B1", List.of("LEG1"), 25, "PREMIUM").andExpect(status().isCreated())
                .andExpect(jsonPath("$.weightKg").value(25))
                .andExpect(jsonPath("$.cabinClass").value("PREMIUM"));
    }

    @Test
    void load_exceedingPieceOrWeightLimitRejectsWholeBatchWithDimensions() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 2, 100);
        registerBag("B1", List.of("LEG1"), 10, "BASIC");
        registerBag("B2", List.of("LEG1"), 10, "BASIC");
        registerBag("B3", List.of("LEG1"), 10, "BASIC");

        // 件数超限：3 件 > 2 件，422 响应包含超出维度与数值，行李不移动
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("B1", "B2", "B3")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("件数")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3")));

        // 总重超限：两件各 10 千克先装入（20 ≤ 100），再装 25 千克将达 45 不超；
        // 改用更严上限航段验证总重维度
        registerLeg("LEG2", "PEK", "XIY", 10, 20);
        registerBag("W1", List.of("LEG2"), 15, "BASIC");
        registerBag("W2", List.of("LEG2"), 10, "BASIC");
        postJson("/api/legs/LEG2/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("W1", "W2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("总重")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("25")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("20")));

        // 整批回滚：无装载记录、无版本推进、行李未移动
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 未超限批次正常成功，占用查询反映件数与总重
        load("LEG1", 1, List.of("B1", "B2"));
        mockMvc.perform(get("/api/legs/LEG1/occupancy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.occupiedPieces").value(2))
                .andExpect(jsonPath("$.occupiedWeightKg").value(20))
                .andExpect(jsonPath("$.remainingPieces").value(0))
                .andExpect(jsonPath("$.remainingWeightKg").value(80));
    }

    @Test
    void offload_followsDeterministicCabinWeightBagTagOrder() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        // PREMIUM 30/20，STANDARD 30/30/10（S1/S2 同重按 bagTag），BASIC 40/20
        registerBag("P1", List.of("LEG1"), 30, "PREMIUM");
        registerBag("P2", List.of("LEG1"), 20, "PREMIUM");
        registerBag("S1", List.of("LEG1"), 30, "STANDARD");
        registerBag("S2", List.of("LEG1"), 30, "STANDARD");
        registerBag("S3", List.of("LEG1"), 10, "STANDARD");
        registerBag("B1", List.of("LEG1"), 40, "BASIC");
        registerBag("B2", List.of("LEG1"), 20, "BASIC");
        load("LEG1", 1, List.of("P1", "P2", "S1", "S2", "S3", "B1", "B2"));

        // 目标件数 3：依次卸 B1(40)、B2(20)、S1(30)、S2(30) 后余 3 件、总重 60
        offload("OFFKEY-1", "LEG1", 2, 3, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.occupiedPieces").value(3))
                .andExpect(jsonPath("$.occupiedWeightKg").value(60))
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("B1", "B2", "S1", "S2")))
                .andExpect(jsonPath("$.offloaded[0].weightKg").value(40))
                .andExpect(jsonPath("$.offloaded[0].cabinClass").value("BASIC"))
                .andExpect(jsonPath("$.retained[*].bagTag", contains("P1", "P2", "S3")));

        // 被卸行李：OFFLOADED、移出清单、待乘索引冻结为 0、记录卸载航段/原索引/UTC 时刻
        for (String tag : List.of("B1", "B2", "S1", "S2")) {
            mockMvc.perform(get("/api/bags/" + tag + "/trace"))
                    .andExpect(jsonPath("$.status").value("OFFLOADED"))
                    .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                    .andExpect(jsonPath("$.nextLegIndex").value(0))
                    .andExpect(jsonPath("$.currentLocation").value("PEK"))
                    .andExpect(jsonPath("$.offloadLegId").value("LEG1"))
                    .andExpect(jsonPath("$.offloadNextIndex").value(0))
                    .andExpect(jsonPath("$.offloadedAt").value(FIXED_NOW.toString()))
                    .andExpect(jsonPath("$.events[-1].eventType").value("OFFLOADED"));
        }
        // 保留行李不受影响
        mockMvc.perform(get("/api/bags/S3/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.events[-1].eventType").value("LOADED"));

        // 卸载明细查询：按时刻与写入顺序
        mockMvc.perform(get("/api/legs/LEG1/offloads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offloads", hasSize(4)))
                .andExpect(jsonPath("$.offloads[0].offloadKey").value("OFFKEY-1"))
                .andExpect(jsonPath("$.offloads[0].bagTag").value("B1"))
                .andExpect(jsonPath("$.offloads[0].originIndex").value(0))
                .andExpect(jsonPath("$.offloads[0].targetMaxPieces").value(3))
                .andExpect(jsonPath("$.offloads[0].targetMaxWeightKg").value(1000));
    }

    @Test
    void offload_weightDimensionContinuesBeyondPieceTarget() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerBag("P1", List.of("LEG1"), 30, "PREMIUM");
        registerBag("P2", List.of("LEG1"), 20, "PREMIUM");
        registerBag("S3", List.of("LEG1"), 10, "STANDARD");
        registerBag("S1", List.of("LEG1"), 30, "STANDARD");
        registerBag("S2", List.of("LEG1"), 30, "STANDARD");
        registerBag("B1", List.of("LEG1"), 40, "BASIC");
        registerBag("B2", List.of("LEG1"), 20, "BASIC");
        load("LEG1", 1, List.of("P1", "P2", "S1", "S2", "S3", "B1", "B2"));

        // 目标件数 3、总重 50：件数达标但余重 60 > 50，继续卸 S3，保留 P1+P2=50
        offload("OFFKEY-W", "LEG1", 2, 3, 50).andExpect(status().isOk())
                .andExpect(jsonPath("$.occupiedPieces").value(2))
                .andExpect(jsonPath("$.occupiedWeightKg").value(50))
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("B1", "B2", "S1", "S2", "S3")))
                .andExpect(jsonPath("$.retained[*].bagTag", contains("P1", "P2")));
    }

    @Test
    void offload_targetAlreadySatisfiedReturnsEmptyListAndIsSuccess() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerBag("B1", List.of("LEG1"), 10, "BASIC");
        registerBag("B2", List.of("LEG1"), 10, "BASIC");
        load("LEG1", 1, List.of("B1", "B2"));

        offload("OFFKEY-EMPTY", "LEG1", 2, 2, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded", hasSize(0)))
                .andExpect(jsonPath("$.retained[*].bagTag", contains("B1", "B2")))
                .andExpect(jsonPath("$.version").value(3));

        // 清单确实未变化，无 OFFLOADED 行李与卸载明细
        Integer offloadedBags = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE status = 'OFFLOADED'", Integer.class);
        assertThat(offloadedBags).isZero();
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_record", Integer.class);
        assertThat(records).isZero();
        Integer onboard = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(onboard).isEqualTo(2);
    }

    @Test
    void offload_rejectsTargetAboveRegisteredLimitWrongStateAndVersion() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 5, 100);
        registerBag("B1", List.of("LEG1"), 10, "BASIC");
        load("LEG1", 1, List.of("B1"));

        // 目标高于登记上限 -> 422
        offload("K1", "LEG1", 2, 6, 100).andExpect(status().isUnprocessableEntity());
        offload("K1", "LEG1", 2, 5, 101).andExpect(status().isUnprocessableEntity());
        // 版本不符 -> 409
        offload("K1", "LEG1", 99, 5, 100).andExpect(status().isConflict());
        // 失败不占键：同键合法参数随后成功
        offload("K1", "LEG1", 2, 5, 100).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded", hasSize(0)));

        // 封舱后卸载 -> 409，航段不存在 -> 404
        seal("LEG1", 3);
        offload("K2", "LEG1", 4, 5, 100).andExpect(status().isConflict());
        offload("K3", "LEG_MISSING", 1, 5, 100).andExpect(status().isNotFound());

        // ARRIVED 航段卸载 -> 409
        registerLeg("LEG2", "PEK", "SHA", 5, 100);
        seal("LEG2", 1);
        mockMvc.perform(post("/api/legs/LEG2/arrive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(), "bagTags", List.of()))))
                .andExpect(status().isOk());
        offload("K4", "LEG2", 2, 5, 100).andExpect(status().isConflict());
    }

    @Test
    void offloadedBag_excludedFromManifestUntilReroutedThenReloads() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerLeg("LEG2", "SHA", "CAN", 10, 1000);
        registerBag("KEEP", List.of("LEG1"), 10, "PREMIUM");
        registerBag("DROP", List.of("LEG1", "LEG2"), 40, "BASIC");
        load("LEG1", 1, List.of("KEEP", "DROP"));
        offload("OFFKEY-R", "LEG1", 2, 10, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("DROP")));

        // 改派前不得进入封舱清单
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("KEEP")));

        // 封舱后清单不再变化：卸载 409、装载 422
        offload("OFFKEY-LATE", "LEG1", 4, 10, 10).andExpect(status().isConflict());
        registerBag("LATE", List.of("LEG1"), 1, "BASIC");
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 4, "bagTags", List.of("LATE")))
                .andExpect(status().isUnprocessableEntity());

        // 改派站点错误 -> 422（行李仍滞留 PEK）；改派航段不符 -> 409
        recover("DROP", "LEG1", "SHA").andExpect(status().isUnprocessableEntity());
        recover("DROP", "LEG2", "PEK").andExpect(status().isConflict());

        // 用新 OPEN 航段走改派后重新装载：另建一套（LEG1 已封舱无法重装）
        registerLeg("L1", "PEK", "SHA", 10, 1000);
        registerLeg("L2", "SHA", "CAN", 10, 1000);
        registerBag("BAGX", List.of("L1", "L2"), 40, "BASIC");
        registerBag("BAGY", List.of("L1"), 50, "PREMIUM");
        load("L1", 1, List.of("BAGX", "BAGY"));
        offload("OFFKEY-X", "L1", 2, 10, 60).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("BAGX")));

        // 改派前装载被拒
        postJson("/api/legs/L1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "bagTags", List.of("BAGX")))
                .andExpect(status().isUnprocessableEntity());

        // 沿用既有补到入口改派：索引不推进、位置仍是 PEK、恢复 IN_TRANSIT
        recover("BAGX", "L1", "PEK").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.recoveredLegId").value("L1"));
        mockMvc.perform(get("/api/bags/BAGX/trace"))
                .andExpect(jsonPath("$.offloadLegId").doesNotExist())
                .andExpect(jsonPath("$.offloadNextIndex").doesNotExist())
                .andExpect(jsonPath("$.offloadedAt").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "OFFLOADED", "RECOVERED")));

        // 改派后可重新装载剩余行程并一路交付
        load("L1", 3, List.of("BAGX"));
        seal("L1", 4).andExpect(jsonPath("$.manifest", contains("BAGX", "BAGY")));
        mockMvc.perform(post("/api/legs/L1/arrive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "bagTags", List.of("BAGX", "BAGY")))))
                .andExpect(status().isOk());
        load("L2", 1, List.of("BAGX"));
        seal("L2", 2);
        mockMvc.perform(post("/api/legs/L2/arrive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(), "bagTags", List.of("BAGX")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAGX/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"));
    }

    @Test
    void offloadKey_sameKeyReplaysDifferentParamsConflicts() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerBag("B1", List.of("LEG1"), 40, "BASIC");
        registerBag("B2", List.of("LEG1"), 40, "BASIC");
        registerBag("B3", List.of("LEG1"), 40, "BASIC");
        load("LEG1", 1, List.of("B1", "B2", "B3"));

        String key = UUID.randomUUID().toString();
        offload(key, "LEG1", 2, 1, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("B1", "B2")))
                .andExpect(jsonPath("$.version").value(3));
        // 同键同参重放首次结果：版本仍为 3、卸载明细只有 2 行
        offload(key, "LEG1", 2, 1, 1000).andExpect(status().isOk())
                .andExpect(jsonPath("$.offloaded[*].bagTag", contains("B1", "B2")))
                .andExpect(jsonPath("$.version").value(3));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_record WHERE offload_key = ?", Integer.class, key);
        assertThat(records).isEqualTo(2);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);

        // 同键异参 -> 409（目标不同 / 版本不同均属异参）
        offload(key, "LEG1", 2, 2, 1000).andExpect(status().isConflict());
        offload(key, "LEG1", 3, 1, 1000).andExpect(status().isConflict());
    }

    @Test
    void concurrentOffloadAndSeal_onlyOneWinsAndManifestFreezes() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerBag("B1", List.of("LEG1"), 40, "BASIC");
        registerBag("B2", List.of("LEG1"), 10, "PREMIUM");
        load("LEG1", 1, List.of("B1", "B2"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.offload("LEG1",
                        new OffloadRequest(UUID.randomUUID().toString(), 2, 10, 20)),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof OffloadResponse
                        || r instanceof com.example.starter.baggage.BaggageDtos.SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        if (results.get(0) instanceof OffloadResponse offloadResponse) {
            // 卸载先提交：B1 被卸，随后封舱清单只含 B2 且永不再变
            assertThat(offloadResponse.offloaded()).extracting("bagTag").containsExactly("B1");
            SealResponse sealed = baggageService.seal("LEG1",
                    new com.example.starter.baggage.BaggageDtos.SealRequest(
                            UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).containsExactly("B2");
        } else {
            // 封舱先提交：清单冻结为 B1,B2，卸载 409 且无行李被卸
            assertThat(status).isEqualTo("SEALED");
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("B1", "B2");
            Integer offloaded = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag WHERE status = 'OFFLOADED'", Integer.class);
            assertThat(offloaded).isZero();
        }
        // 封舱后任何卸载都被拒
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> baggageService.offload("LEG1",
                new OffloadRequest(UUID.randomUUID().toString(), 3, 10, 20)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(409));
    }

    @Test
    void concurrentLoadAndOffload_capacityNeverBreachedAndBagLivesInOneListOnly() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 5, 1000);
        registerBag("A1", List.of("LEG1"), 10, "STANDARD");
        registerBag("A2", List.of("LEG1"), 10, "STANDARD");
        registerBag("A3", List.of("LEG1"), 10, "STANDARD");
        registerBag("D1", List.of("LEG1"), 10, "STANDARD");
        registerBag("D2", List.of("LEG1"), 10, "STANDARD");
        load("LEG1", 1, List.of("A1", "A2", "A3"));

        // 装载 D1,D2 与卸载到 3 件并发，均基于版本 2，恰好一个成功另一个 409
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 2, List.of("D1", "D2"))),
                () -> baggageService.offload("LEG1",
                        new OffloadRequest(UUID.randomUUID().toString(), 2, 3, 1000)));
        List<Object> results = runConcurrently(tasks);

        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(conflicts).isEqualTo(1);

        // 无论谁先提交，补齐到“5 件在舱且无卸载”的确定终态：先失败方用新版本串行完成
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        Integer onboard = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        if (onboard < 5) {
            // 卸载先提交且为无操作（3 件已达标），装载以新版本成功
            baggageService.load("LEG1", new LoadRequest(
                    UUID.randomUUID().toString(), version, List.of("D1", "D2")));
        }

        // 最终：5 件在舱、占用不超登记上限；没有任何行李同时处于 OFFLOADED 与装载清单
        Integer finalOnboard = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        Integer finalWeight = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(b.weight_kg),0) FROM load_record lr JOIN bag b"
                        + " ON b.bag_tag = lr.bag_tag WHERE lr.leg_id = 'LEG1'", Integer.class);
        assertThat(finalOnboard).isLessThanOrEqualTo(5);
        assertThat(finalWeight).isLessThanOrEqualTo(1000);
        Integer both = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag b JOIN load_record lr ON lr.bag_tag = b.bag_tag"
                        + " WHERE b.status = 'OFFLOADED'", Integer.class);
        assertThat(both).isZero();
    }

    @Test
    void concurrentSameOffloadKey_singleEffectAndSameResult() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 1000);
        registerBag("B1", List.of("LEG1"), 40, "BASIC");
        registerBag("B2", List.of("LEG1"), 40, "BASIC");
        registerBag("B3", List.of("LEG1"), 10, "PREMIUM");
        load("LEG1", 1, List.of("B1", "B2", "B3"));

        String key = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.offload("LEG1",
                    new OffloadRequest(key, 2, 10, 20)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(OffloadResponse.class);
            OffloadResponse response = (OffloadResponse) result;
            assertThat(response.version()).isEqualTo(3);
            assertThat(response.offloaded()).extracting("bagTag").containsExactly("B1", "B2");
            assertThat(response.retained()).extracting("bagTag").containsExactly("B3");
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM offload_record WHERE offload_key = ?", Integer.class, key);
        assertThat(records).isEqualTo(2);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      Integer maxPieces, Integer maxWeightKg) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        body.put("maxPieces", maxPieces);
        body.put("maxWeightKg", maxWeightKg);
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds,
                                      Integer weightKg, String cabinClass) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", legIds);
        body.put("weightKg", weightKg);
        body.put("cabinClass", cabinClass);
        return postJson("/api/bags", body);
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

    private ResultActions offload(String offloadKey, String legId, int expectedVersion,
                                  int targetMaxPieces, int targetMaxWeightKg) throws Exception {
        return postJson("/api/legs/" + legId + "/offload", Map.of(
                "offloadKey", offloadKey,
                "expectedVersion", expectedVersion,
                "targetMaxPieces", targetMaxPieces,
                "targetMaxWeightKg", targetMaxWeightKg));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
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
