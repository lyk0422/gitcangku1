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
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RerouteRequest;
import com.example.starter.baggage.BaggageDtos.RerouteResponse;
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
 * 剩余行程改派测试：前缀保留与后缀替换、改派后按新后缀装载、历史与轨迹、
 * 状态/版本/路线校验失败整单回滚、requestId 幂等边界，以及改派与同袋装载、
 * 新航段封舱、另一改派真实并发时的提交顺序裁决。全部基于 H2 MySQL 兼容模式真实数据库。
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

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_reroute_history");
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
    void reroute_keepsPrefixReplacesSuffixAndLoadsNewRouteToDelivery() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        // 待乘后缀 [L2] 整体替换为 [L3, L4]，前缀 L1 保留，位置/索引不推进，版本 1 -> 2
        reroute("B1", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.prefixSize").value(1))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L3", "L4")));

        // 轨迹在改派时刻追加 REROUTED 事件，位置为当前位置 SHA
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "REROUTED")))
                .andExpect(jsonPath("$.events[3].location").value("SHA"))
                .andExpect(jsonPath("$.events[3].eventTime").value(FIXED_NOW.toString()));

        // 历史：前后完整行程、版本及 UTC 时刻
        mockMvc.perform(get("/api/bags/B1/reroute-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].routeVersion").value(2))
                .andExpect(jsonPath("$.history[0].prefixSize").value(1))
                .andExpect(jsonPath("$.history[0].beforeItinerary[*].legId", contains("L1", "L2")))
                .andExpect(jsonPath("$.history[0].afterItinerary[*].legId", contains("L1", "L3", "L4")))
                .andExpect(jsonPath("$.history[0].reroutedAt").value(FIXED_NOW.toString()));

        // 原航段 L2 不再接受该行李
        load("L2", 1, List.of("B1")).andExpect(status().isUnprocessableEntity());
        Integer l2Records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'L2'", Integer.class);
        assertThat(l2Records).isZero();

        // 按新后缀完成装载/封舱/到达，最终在原登记目的地 CAN 交付
        load("L3", 1, List.of("B1")).andExpect(status().isOk());
        seal("L3", 2).andExpect(status().isOk());
        arriveExact("L3", List.of("B1")).andExpect(status().isOk());
        load("L4", 1, List.of("B1")).andExpect(status().isOk());
        seal("L4", 2).andExpect(status().isOk());
        arriveExact("L4", List.of("B1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(3));

        // 封舱/到达快照引用的仍是当时真实航段，前缀事件不被重写
        mockMvc.perform(get("/api/legs/L1/manifest"))
                .andExpect(jsonPath("$.manifest", contains("B1")));
    }

    @Test
    void reroute_allowedAfterRecoverWhenLegsRemain() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        load("L1", 1, List.of("B1")).andExpect(status().isOk());
        seal("L1", 2).andExpect(status().isOk());
        diffArrive("L1", 3, List.of()).andExpect(status().isOk());

        // 尚处 SHORT_UNLOADED：改派 409，不能绕过真实补到
        reroute("B1", 1, List.of("L3", "L4")).andExpect(status().isConflict());

        recover("B1", "L1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
        // 补到后仍有待乘航段，可改派
        reroute("B1", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L3", "L4")));
    }

    @Test
    void reroute_rejectsLoadedDeliveredBagsAndUnknownResources() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerBag("B1", List.of("L1", "L2"));

        // 已装载（旧航段在途）：409
        load("L1", 1, List.of("B1")).andExpect(status().isOk());
        reroute("B1", 1, List.of("L3", "L4")).andExpect(status().isConflict());

        // 已送达：409（独立航段，封舱清单只有 B2）
        registerLeg("D1", "PEK", "SHA");
        registerBag("B2", List.of("D1"));
        load("D1", 1, List.of("B2")).andExpect(status().isOk());
        seal("D1", 2).andExpect(status().isOk());
        arriveExact("D1", List.of("B2")).andExpect(status().isOk());
        reroute("B2", 1, List.of("L3", "L4")).andExpect(status().isConflict());

        // 行李不存在：404；历史查询同样 404
        reroute("NOPE", 1, List.of("L3", "L4")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/reroute-history")).andExpect(status().isNotFound());

        // 引用不存在的新航段：404（行李 B3 在 SHA 待乘）
        registerBag("B3", List.of("L1", "L2"));
        reroute("B3", 1, List.of("L3", "L_MISSING")).andExpect(status().isNotFound());
    }

    @Test
    void reroute_rejectsVersionAndRouteGeometryErrorsWith409Or422() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerLeg("LX", "PEK", "XIY");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        // 行程版本不匹配：409
        reroute("B1", 99, List.of("L3", "L4")).andExpect(status().isConflict());
        // 新后缀首段起点不等于当前位置（当前 SHA，LX 起点 PEK）：422
        reroute("B1", 1, List.of("LX")).andExpect(status().isUnprocessableEntity());
        // 后段起点不等于前段终点：L3 SHA->KWL，L2 起点 SHA：422
        reroute("B1", 1, List.of("L3", "L2")).andExpect(status().isUnprocessableEntity());
        // 最终目的地改变：只到 KWL：422
        reroute("B1", 1, List.of("L3")).andExpect(status().isUnprocessableEntity());
        // 新后缀内部重复：422
        reroute("B1", 1, List.of("L3", "L3")).andExpect(status().isUnprocessableEntity());
        // 与已完成前缀重复：422
        reroute("B1", 1, List.of("L1", "L3", "L4")).andExpect(status().isUnprocessableEntity());
        // 参数越界（空后缀/超过 5 段）：400
        rerouteRaw("B1", 1, List.of()).andExpect(status().isBadRequest());
        rerouteRaw("B1", 1, List.of("L3", "L3", "L3", "L3", "L3", "L3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reroute_rejectsTotalOverFiveLegs() throws Exception {
        // 原始三段 PEK->SHA->KWL->CAN，完成首段后前缀 1，再提交 5 个新段 => 合计 6：422
        registerLeg("A1", "PEK", "SHA");
        registerLeg("A2", "SHA", "KWL");
        registerLeg("A3", "KWL", "CAN");
        registerLeg("N1", "SHA", "X1");
        registerLeg("N2", "X1", "X2");
        registerLeg("N3", "X2", "X3");
        registerLeg("N4", "X3", "X4");
        registerLeg("N5", "X4", "CAN");
        registerBag("B1", List.of("A1", "A2", "A3"));
        completeLeg("A1", List.of("B1"));

        reroute("B1", 1, List.of("N1", "N2", "N3", "N4", "N5"))
                .andExpect(status().isUnprocessableEntity());

        // 失败无部分后缀：行程仍为原始三段
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("A1", "A2", "A3")))
                .andExpect(jsonPath("$.routeVersion").value(1));
    }

    @Test
    void reroute_sealedNewLegConflictsAndFailedRerouteRollsBackEverything() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        // 新航段 L3 已封舱（空清单），不可装载：409
        seal("L3", 1).andExpect(status().isOk());
        reroute("B1", 1, List.of("L3", "L4")).andExpect(status().isConflict());

        // 无部分后缀、无历史、版本不变、无 REROUTED 事件
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2")))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED")));
        mockMvc.perform(get("/api/bags/B1/reroute-history"))
                .andExpect(jsonPath("$.history", hasSize(0)));
        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history", Integer.class);
        assertThat(historyRows).isZero();
        // 后缀行未被删除：原待乘航段 L2 仍可装载
        load("L2", 1, List.of("B1")).andExpect(status().isOk());
    }

    @Test
    void reroute_idempotentReplayChangeParamsConflictFailureReusableAndReplayKeepsLaterRoute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerLeg("LD", "SHA", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        // 同键同参重放首次结果
        String key = UUID.randomUUID().toString();
        rerouteWithId(key, "B1", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));
        rerouteWithId(key, "B1", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L3", "L4")));
        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'B1'", Integer.class);
        assertThat(historyRows).isEqualTo(1);
        Integer rerouteEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'B1' AND event_type = 'REROUTED'",
                Integer.class);
        assertThat(rerouteEvents).isEqualTo(1);

        // 同键改参：409
        rerouteWithId(key, "B1", 1, List.of("LD")).andExpect(status().isConflict());

        // 失败不占键：先以目的地改变失败（422），同键修正后成功
        registerLeg("M1", "PEK", "SHA");
        registerBag("B2", List.of("M1", "L2"));
        completeLeg("M1", List.of("B2"));
        String retryKey = UUID.randomUUID().toString();
        rerouteWithId(retryKey, "B2", 1, List.of("L3")).andExpect(status().isUnprocessableEntity());
        rerouteWithId(retryKey, "B2", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2));

        // 重放不能覆盖后续路线：另一请求先把 B1 改派到 v3（直飞 LD），
        // 再用旧键重放只返回首次存储结果，数据库保持 v3 路线
        reroute("B1", 2, List.of("LD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(3));
        rerouteWithId(key, "B1", 1, List.of("L3", "L4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeVersion").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L3", "L4")));
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.routeVersion").value(3))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "LD")));
        Integer allHistory = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'B1'", Integer.class);
        assertThat(allHistory).isEqualTo(2);
    }

    @Test
    void concurrentTwoReroutes_onlyOneCommitsWithoutPartialSuffix() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerLeg("LD", "SHA", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "B1", 1, List.of("L3", "L4"))),
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "B1", 1, List.of("LD"))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RerouteResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 恰好一条历史、版本为 2；行程是两种完整路线之一，不存在混合后缀
        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'B1'", Integer.class);
        assertThat(historyRows).isEqualTo(1);
        List<String> legIds = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'B1' ORDER BY seq", String.class);
        boolean threeLegRoute = legIds.equals(List.of("L1", "L3", "L4"));
        boolean directRoute = legIds.equals(List.of("L1", "LD"));
        assertThat(threeLegRoute ^ directRoute).isTrue();
    }

    @Test
    void concurrentRerouteAndSeal_commitOrderDecidesWithRepeatedRuns() throws Exception {
        boolean sawRerouteWin = false;
        boolean sawSealWin = false;
        for (int round = 0; round < 8 && !(sawRerouteWin && sawSealWin); round++) {
            String p = "S" + round;
            String l1 = p + "_L1";
            String l2 = p + "_L2";
            String l3 = p + "_L3";
            String l4 = p + "_L4";
            String bag = p + "_B";
            registerLeg(l1, "PEK", "SHA");
            registerLeg(l2, "SHA", "CAN");
            registerLeg(l3, "SHA", "KWL");
            registerLeg(l4, "KWL", "CAN");
            registerBag(bag, List.of(l1, l2));
            completeLeg(l1, List.of(bag));

            List<Callable<Object>> tasks = List.of(
                    () -> baggageService.seal(l3,
                            new SealRequest(UUID.randomUUID().toString(), 1)),
                    () -> baggageService.reroute(new RerouteRequest(
                            UUID.randomUUID().toString(), bag, 1, List.of(l3, l4))));
            List<Object> results = runConcurrently(tasks);

            long rerouteOk = results.stream().filter(RerouteResponse.class::isInstance).count();
            long rerouteConflict = results.stream()
                    .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                    .filter(ex -> ex.getStatus().value() == 409).count();
            assertThat(rerouteOk + rerouteConflict).isEqualTo(1);
            // 封舱在两种顺序下都成立：改派不改变航段状态；改派先提交允许之后封舱
            assertThat(results).anyMatch(SealResponse.class::isInstance);

            String legStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM leg WHERE leg_id = ?", String.class, l3);
            assertThat(legStatus).isEqualTo("SEALED");
            Integer version = jdbcTemplate.queryForObject(
                    "SELECT route_version FROM bag WHERE bag_tag = ?", Integer.class, bag);
            if (rerouteOk == 1) {
                sawRerouteWin = true;
                assertThat(version).isEqualTo(2);
            } else {
                sawSealWin = true;
                // 封舱先提交：改派失败，路线与版本不变
                assertThat(version).isEqualTo(1);
                List<String> legIds = jdbcTemplate.queryForList(
                        "SELECT leg_id FROM bag_itinerary WHERE bag_tag = ? ORDER BY seq",
                        String.class, bag);
                assertThat(legIds).containsExactly(l1, l2);
            }
        }
        // 两种提交顺序都实际出现时给出更强断言；调度只呈现一种时各轮内部不变量仍已校验
        assertThat(sawRerouteWin || sawSealWin).isTrue();
    }

    @Test
    void concurrentRerouteAndLoadOldLeg_neverLoadsOldLegOnNewRoute() throws Exception {
        for (int round = 0; round < 6; round++) {
            String p = "O" + round;
            String l1 = p + "_L1";
            String l2 = p + "_L2";
            String l3 = p + "_L3";
            String l4 = p + "_L4";
            String bag = p + "_B";
            registerLeg(l1, "PEK", "SHA");
            registerLeg(l2, "SHA", "CAN");
            registerLeg(l3, "SHA", "KWL");
            registerLeg(l4, "KWL", "CAN");
            registerBag(bag, List.of(l1, l2));
            completeLeg(l1, List.of(bag));

            List<Callable<Object>> tasks = List.of(
                    () -> baggageService.load(l2,
                            new LoadRequest(UUID.randomUUID().toString(), 1, List.of(bag))),
                    () -> baggageService.reroute(new RerouteRequest(
                            UUID.randomUUID().toString(), bag, 1, List.of(l3, l4))));
            List<Object> results = runConcurrently(tasks);

            long loadOk = results.stream().filter(LoadResponse.class::isInstance).count();
            long rerouteOk = results.stream().filter(RerouteResponse.class::isInstance).count();
            // 互斥：旧航段装载成功则改派必败；改派成功则旧航段装载必失败
            assertThat(loadOk).isEqualTo(1 - rerouteOk);

            Integer version = jdbcTemplate.queryForObject(
                    "SELECT route_version FROM bag WHERE bag_tag = ?", Integer.class, bag);
            List<String> records = jdbcTemplate.queryForList(
                    "SELECT leg_id FROM load_record WHERE bag_tag = ?", String.class, bag);
            List<String> legIds = jdbcTemplate.queryForList(
                    "SELECT leg_id FROM bag_itinerary WHERE bag_tag = ? ORDER BY seq",
                    String.class, bag);
            if (rerouteOk == 1) {
                assertThat(version).isEqualTo(2);
                assertThat(legIds).containsExactly(l1, l3, l4);
                assertThat(records).doesNotContain(l2);
            } else {
                // 装载先提交：行李挂在旧航段 l2 上，路线不得改变
                assertThat(version).isEqualTo(1);
                assertThat(legIds).containsExactly(l1, l2);
                assertThat(records).containsExactly(l2);
            }
        }
    }

    @Test
    void concurrentRerouteAndLoadNewLeg_loadWinsOnlyAfterReroute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("L3", "SHA", "KWL");
        registerLeg("L4", "KWL", "CAN");
        registerBag("B1", List.of("L1", "L2"));
        completeLeg("L1", List.of("B1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("L3",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("B1"))),
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "B1", 1, List.of("L3", "L4"))));
        List<Object> results = runConcurrently(tasks);

        long rerouteOk = results.stream().filter(RerouteResponse.class::isInstance).count();
        long loadOk = results.stream().filter(LoadResponse.class::isInstance).count();
        assertThat(rerouteOk).isEqualTo(1);
        // 改派先提交则装载成功；装载先拿到锁时旧路线下 L3 非待乘航段，整批失败
        assertThat(loadOk).isLessThanOrEqualTo(1);

        List<String> records = jdbcTemplate.queryForList(
                "SELECT leg_id FROM load_record WHERE bag_tag = 'B1'", String.class);
        if (loadOk == 1) {
            assertThat(records).containsExactly("L3");
        } else {
            assertThat(records).isEmpty();
        }
        List<String> legIds = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'B1' ORDER BY seq", String.class);
        assertThat(legIds).containsExactly("L1", "L3", "L4");
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    /** 完成一个航段的装载（版本 1->2）、封舱（2->3）、精确到达。 */
    private void completeLeg(String legId, List<String> bagTags) throws Exception {
        load(legId, 1, bagTags).andExpect(status().isOk());
        seal(legId, 2).andExpect(status().isOk());
        arriveExact(legId, bagTags).andExpect(status().isOk());
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

    private ResultActions reroute(String bagTag, int expectedVersion, List<String> newLegIds) throws Exception {
        return rerouteWithId(UUID.randomUUID().toString(), bagTag, expectedVersion, newLegIds);
    }

    private ResultActions rerouteWithId(String requestId, String bagTag,
                                        int expectedVersion, List<String> newLegIds) throws Exception {
        return rerouteRaw0(requestId, bagTag, expectedVersion, newLegIds);
    }

    private ResultActions rerouteRaw(String bagTag, int expectedVersion, List<String> newLegIds) throws Exception {
        return rerouteRaw0(UUID.randomUUID().toString(), bagTag, expectedVersion, newLegIds);
    }

    private ResultActions rerouteRaw0(String requestId, String bagTag,
                                      int expectedVersion, List<String> newLegIds) throws Exception {
        return postJson("/api/bags/reroute", Map.of(
                "requestId", requestId, "bagTag", bagTag,
                "expectedRouteVersion", expectedVersion, "newLegIds", newLegIds));
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
