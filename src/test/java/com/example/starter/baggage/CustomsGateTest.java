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

import com.example.starter.baggage.BaggageDtos.CustomsCheckRequest;
import com.example.starter.baggage.BaggageDtos.CustomsCheckResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李海关放行与持续门禁测试：覆盖放行/拦截版本链、国际航段装载门控、批量整批回滚、
 * 拦截对未起飞后续航段的 CUSTOMS_HOLD 快照与已起飞不改写、更高版本放行解除、
 * 补到/改派/起飞门控、检查链与门禁查询，以及并发裁决与 clearanceKey/requestId 幂等。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsGateTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private CustomsService customsService;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_gate");
        jdbcTemplate.update("DELETE FROM customs_inspection");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
        customsService.setClock(Instant::now);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void internationalLoad_requiresReleaseAndDomesticLegIsUngated() throws Exception {
        // 国内航段不受门禁；国际航段 PEK(CN) -> NRT(JP)
        registerLeg("DOM", "PEK", "SHA", "CN", "CN");
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG_D", List.of("DOM"));
        registerBag("BAG_I", List.of("INTL"));

        // 无放行版本装载国际航段 -> 422，且原因可区分
        postJson("/api/legs/INTL/load", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("BAG_I")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("海关放行版本")));
        // 容器占用与事件无半成品
        assertThat(count("load_record")).isZero();
        assertThat(countWhere("bag_event", "event_type = 'LOADED'")).isZero();

        // 国内航段无门禁可正常装载
        load("DOM", 1, List.of("BAG_D")).andExpect(status().isOk());

        // 登记日本方向放行后可装载
        customsCheck("BAG_I", 1, "RELEASED", "JP", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.checkVersion").value(1))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.clearanceKey").isNotEmpty())
                .andExpect(jsonPath("$.heldLegs", hasSize(0)));
        load("INTL", 1, List.of("BAG_I")).andExpect(status().isOk());

        mockMvc.perform(get("/api/legs/INTL/gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationCountry").value("JP"))
                .andExpect(jsonPath("$.gates", hasSize(1)))
                .andExpect(jsonPath("$.gates[0].bagTag").value("BAG_I"))
                .andExpect(jsonPath("$.gates[0].gateStatus").value("RELEASED"))
                .andExpect(jsonPath("$.gates[0].checkVersion").value(1));
    }

    @Test
    void hold_marksOnlyFutureLegsWithSnapshotAndDepartedLegIsUntouched() throws Exception {
        customsService.setClock(() -> FIXED_NOW);
        // LEG1 国际 PEK->NRT，LEG2 国际 NRT->LAX
        registerLeg("LEG1", "PEK", "NRT", "CN", "JP");
        registerLeg("LEG2", "NRT", "LAX", "JP", "US");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2);
        // 起飞后航段固化
        depart("LEG1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"))
                .andExpect(jsonPath("$.version").value(4));

        // 登记 JP 拦截（版本 2）：LEG1 已 DEPARTED 不改写，LEG2 目的国是 US 不匹配 -> 无航段被标记
        customsCheck("BAG1", 2, "HELD", "JP", "JP hold after departure").andExpect(status().isOk())
                .andExpect(jsonPath("$.heldLegs", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG1/customs-chain"))
                .andExpect(jsonPath("$.inspections", hasSize(2)))
                .andExpect(jsonPath("$.gates[?(@.legId == 'LEG1')].gateStatus").value(contains("RELEASED")));

        // 登记 US 拦截（版本 3）：LEG2 未起飞被标记 CUSTOMS_HOLD 并写快照
        customsCheck("BAG1", 3, "HELD", "US", "US paperwork missing").andExpect(status().isOk())
                .andExpect(jsonPath("$.heldLegs", contains("LEG2")));
        mockMvc.perform(get("/api/bags/BAG1/hold-impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].legId").value("LEG2"))
                .andExpect(jsonPath("$.holds[0].gateStatus").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.holds[0].checkVersion").value(3))
                .andExpect(jsonPath("$.holds[0].snapshot", containsString("US paperwork missing")))
                .andExpect(jsonPath("$.holds[0].snapshot", containsString("\"legStatus\":\"OPEN\"")))
                .andExpect(jsonPath("$.holds[0].updatedAt").value(FIXED_NOW.toString()));

        // 拦截事件已追加；已起飞 LEG1 的门禁未被改写
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "DEPARTED", "CUSTOMS_HELD")));
        Integer leg1Gate = jdbcTemplate.queryForObject(
                "SELECT check_version FROM bag_gate WHERE bag_tag = 'BAG1' AND leg_id = 'LEG1'",
                Integer.class);
        assertThat(leg1Gate).isEqualTo(1);

        // 更高版本 US 放行解除拦截，历史拦截行保留
        customsCheck("BAG1", 4, "RELEASED", "US", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.heldLegs", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG1/hold-impact"))
                .andExpect(jsonPath("$.holds", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG1/customs-chain"))
                .andExpect(jsonPath("$.inspections", hasSize(4)));
        // 放行解除事件
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "DEPARTED", "CUSTOMS_HELD", "CUSTOMS_RELEASED")));
    }

    @Test
    void load_batchFullyRollsBackWhenOneBagHeldOrMissingClearance() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG_OK", List.of("INTL"));
        registerBag("BAG_HELD", List.of("INTL"));
        // BAG_OK 放行；BAG_HELD 先放行再以更高版本拦截
        customsCheck("BAG_OK", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        customsCheck("BAG_HELD", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        customsCheck("BAG_HELD", 2, "HELD", "JP", "blocked").andExpect(status().is2xxSuccessful());

        // 整批含被拦截行李 -> 422，放行行李也不动
        load("INTL", 1, List.of("BAG_OK", "BAG_HELD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("被海关拦截")));
        assertThat(count("load_record")).isZero();
        mockMvc.perform(get("/api/bags/BAG_OK/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/legs/INTL/manifest"))
                .andExpect(jsonPath("$.version").value(1));

        // 缺放行的新行李同样整批 422
        registerBag("BAG_NOCLEAR", List.of("INTL"));
        load("INTL", 1, List.of("BAG_OK", "BAG_NOCLEAR"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("load_record")).isZero();

        // 解除拦截后整批成功
        customsCheck("BAG_HELD", 3, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        load("INTL", 1, List.of("BAG_OK", "BAG_HELD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG_HELD", "BAG_OK")));
    }

    @Test
    void depart_blockedByHoldAndAllowedAfterHigherRelease() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        load("INTL", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("INTL", 2);

        // 封舱后新拦截：航段未起飞被 CUSTOMS_HOLD，起飞 422
        customsCheck("BAG1", 2, "HELD", "JP", "late intercept").andExpect(status().is2xxSuccessful());
        depart("INTL").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("被海关拦截")));
        // 航段仍 SEALED，无 DEPARTED 事件
        mockMvc.perform(get("/api/legs/INTL/manifest"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3));
        assertThat(countWhere("bag_event", "event_type = 'DEPARTED'")).isZero();

        // 必须更高版本放行才能起飞；同版本不能覆盖原终态（同版本改判拦截 -> 409）
        customsCheck("BAG1", 1, "HELD", "JP", "override attempt").andExpect(status().isConflict());
        customsCheck("BAG1", 3, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        depart("INTL").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"))
                .andExpect(jsonPath("$.departed", contains("BAG1")));

        // 已起飞后到达确认正常
        postJson("/api/legs/INTL/arrive", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTags", List.of("BAG1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    void depart_rejectsNonSealedLegAndUnknownLeg() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));
        // 装载门控保证：未放行无法装载，因此已装载行李必然存在最新放行终态；这里验证起飞状态前提
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        load("INTL", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("INTL", 2);
        // 未封舱航段不能起飞 -> 422
        registerLeg("INTL2", "PEK", "NRT", "CN", "JP");
        depart("INTL2").andExpect(status().isUnprocessableEntity());
        // 不存在航段 -> 404
        postJson("/api/legs/NOPE/depart", Map.of("requestId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkVersionRules_blankReasonRejectedAndHistoryKept() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));

        // 拦截原因为空 -> 422（@NotBlank 无法按状态条件生效，业务层统一兜底，原因可区分）
        customsCheck("BAG1", 1, "HELD", "JP", "   ").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("拦截原因不能为空")));
        postJson("/api/customs/checks", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", "BAG1", "checkVersion", 1, "status", "HELD", "country", "JP"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("拦截原因不能为空")));
        // 非法状态 -> 422
        customsCheck("BAG1", 1, "MAYBE", "JP", "x").andExpect(status().isUnprocessableEntity());

        // 正常放行
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        // 同版本再登记拦截 -> 409（同一检查版本只能一个终态）
        customsCheck("BAG1", 1, "HELD", "JP", "dup").andExpect(status().isConflict());
        // 同指纹重放原结果 -> 200 且不新增；同版本异指纹（拦截不同原因）-> 409，不能覆盖原终态
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        customsCheck("BAG1", 1, "HELD", "JP", "changed").andExpect(status().isConflict());
        // 更小版本 -> 422，必须严格递增
        customsCheck("BAG1", 0, "RELEASED", "JP", null).andExpect(status().isUnprocessableEntity());

        // 失败不占 clearanceKey：版本 2 先因行李不存在失败，同键对存在行李可成功
        customsCheck("GHOST", 2, "HELD", "JP", "r").andExpect(status().isNotFound());

        // 历史放行不删除：放行 -> 拦截 -> 放行后检查链仍有 3 条
        customsCheck("BAG1", 2, "HELD", "JP", "hold").andExpect(status().is2xxSuccessful());
        customsCheck("BAG1", 3, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_inspection WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(rows).isEqualTo(3);
        mockMvc.perform(get("/api/bags/BAG1/customs-chain"))
                .andExpect(jsonPath("$.inspections[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.inspections[1].status").value("HELD"))
                .andExpect(jsonPath("$.inspections[1].reason").value("hold"))
                .andExpect(jsonPath("$.inspections[2].status").value("RELEASED"));
    }

    @Test
    void reroute_rebuildsItineraryAndRequiresClearanceForNewInternationalLeg() throws Exception {
        registerLeg("OLD", "PEK", "NRT", "CN", "JP");
        registerLeg("NEW_DOM", "PEK", "SHA", "CN", "CN");
        registerLeg("NEW_INTL", "SHA", "FRA", "CN", "DE");
        registerBag("BAG1", List.of("OLD"));
        customsCheck("BAG1", 1, "RELEASED", "JP", null).andExpect(status().is2xxSuccessful());
        // 未装载时改派：新国际段缺 DE 放行 -> 422，旧行程不变
        reroute("BAG1", List.of("NEW_DOM", "NEW_INTL")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("DE")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.itinerary", hasSize(1)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("OLD"));

        // 补 DE 放行后改派成功：旧 JP 门禁删除，新 DE 门禁建立
        customsCheck("BAG1", 2, "RELEASED", "DE", null).andExpect(status().is2xxSuccessful());
        reroute("BAG1", List.of("NEW_DOM", "NEW_INTL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedLegId").doesNotExist())
                .andExpect(jsonPath("$.itinerary", hasSize(2)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("NEW_DOM"))
                .andExpect(jsonPath("$.itinerary[1].legId").value("NEW_INTL"));
        List<String> gatedLegs = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_gate WHERE bag_tag = 'BAG1'", String.class);
        assertThat(gatedLegs).containsExactly("NEW_INTL");

        // 已装载到 OPEN 航段后改派：从原航段卸下并推进原航段版本
        load("NEW_DOM", 1, List.of("BAG1")).andExpect(status().isOk());
        registerLeg("ALT", "PEK", "ICN", "CN", "KR");
        // 当前在 PEK（装载不改位置），首段始发站必须为 PEK
        customsCheck("BAG1", 3, "RELEASED", "KR", null).andExpect(status().is2xxSuccessful());
        reroute("BAG1", List.of("ALT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedLegId").value("NEW_DOM"));
        assertThat(countWhere("load_record", "bag_tag = 'BAG1'")).isZero();
        Integer domVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'NEW_DOM'", Integer.class);
        assertThat(domVersion).isEqualTo(3);

        // 封舱后不可改派：NEW_DOM 在 BAG1 装载(v2)与卸下(v3)后当前版本为 3
        registerBag("BAG2", List.of("NEW_DOM"));
        load("NEW_DOM", 3, List.of("BAG2")).andExpect(status().isOk());
        seal("NEW_DOM", 4);
        reroute("BAG2", List.of("NEW_DOM")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recover_internationalNextLegRequiresClearance() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "CN", "CN");
        registerLeg("LEG2", "SHA", "FRA", "CN", "DE");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 短卸补到后下一航段为国际段且无 DE 放行 -> 补到 422，短卸状态保持
        recover("BAG1", "LEG1", "SHA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("DE")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        customsCheck("BAG1", 1, "RELEASED", "DE", null).andExpect(status().is2xxSuccessful());
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
    }

    @Test
    void idempotency_clearanceKeyReplaysAndRequestIdConflictAndFailureNotOccupying() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));

        // 同 clearanceKey（同指纹）跨 requestId 重放原结果，只插入一条检查记录
        postJson("/api/customs/checks", checkBody(UUID.randomUUID().toString(),
                "BAG1", 1, "RELEASED", "JP", null)).andExpect(status().isOk());
        postJson("/api/customs/checks", checkBody(UUID.randomUUID().toString(),
                "BAG1", 1, "RELEASED", "JP", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkVersion").value(1));
        assertThat(countWhere("customs_inspection", "bag_tag = 'BAG1'")).isEqualTo(1);

        // 同 requestId 异参数 -> 409
        String requestId = UUID.randomUUID().toString();
        postJson("/api/customs/checks", checkBody(requestId, "BAG1", 2, "HELD", "JP", "r1"))
                .andExpect(status().isOk());
        postJson("/api/customs/checks", checkBody(requestId, "BAG1", 2, "HELD", "JP", "r2"))
                .andExpect(status().isConflict());

        // 拦截重放固化的 heldLegs 快照：先拦截 LEG（未起飞），再更高版本放行，重放拦截仍返回原 heldLegs
        registerLeg("INTL2", "PEK", "NRT", "CN", "JP");
        registerBag("BAG2", List.of("INTL2"));
        String holdKey = UUID.randomUUID().toString();
        postJson("/api/customs/checks", checkBody(holdKey, "BAG2", 1, "HELD", "JP", "hold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldLegs", contains("INTL2")));
        postJson("/api/customs/checks", checkBody(UUID.randomUUID().toString(),
                "BAG2", 2, "RELEASED", "JP", null)).andExpect(status().isOk());
        postJson("/api/customs/checks", checkBody(UUID.randomUUID().toString(),
                "BAG2", 1, "HELD", "JP", "hold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldLegs", contains("INTL2")));
        assertThat(countWhere("customs_inspection", "bag_tag = 'BAG2'")).isEqualTo(2);

        // 失败不占 requestId：同键先 422（拦截无原因），再成功
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/customs/checks", checkBody(retryKey, "BAG1", 3, "HELD", "JP", " "))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/customs/checks", checkBody(retryKey, "BAG1", 3, "RELEASED", "JP", null))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentCheckAndLoad_commitOrderDecidesAndNoDeadlock() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));
        // 预置 v1 放行，使装载具备成功前提；并发竞争的是装载与 v2 新拦截的提交顺序
        customsService.registerCheck(new CustomsCheckRequest(
                UUID.randomUUID().toString(), "BAG1", 1, "RELEASED", "JP", null));

        // 放行装载 与 拦截检查 真实并发：两种裁决都合法且状态自洽
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("INTL",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> customsService.registerCheck(new CustomsCheckRequest(
                        UUID.randomUUID().toString(), "BAG1", 2, "HELD", "JP", "race hold")));
        List<Object> results = runConcurrently(tasks);

        boolean loaded = results.stream().anyMatch(r -> r instanceof com.example.starter.baggage.BaggageDtos.LoadResponse);
        boolean held = results.stream().anyMatch(r -> r instanceof CustomsCheckResponse);
        assertThat(held).isTrue();
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        if (loaded) {
            // 装载先提交：拦截随后把未起飞的 INTL 标记 CUSTOMS_HOLD
            assertThat(loadRecords).isEqualTo(1);
            String gate = jdbcTemplate.queryForObject(
                    "SELECT gate_status FROM bag_gate WHERE bag_tag = 'BAG1' AND leg_id = 'INTL'",
                    String.class);
            assertThat(gate).isEqualTo("CUSTOMS_HOLD");
        } else {
            // 拦截先提交：装载 422，无容器占用
            assertThat(loadRecords).isZero();
            boolean loadRejected = results.stream()
                    .filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .anyMatch(ex -> ex.getStatus().value() == 422);
            assertThat(loadRejected).isTrue();
        }
    }

    @Test
    void concurrentSameClearanceKey_singleInsertAndSameResponse() throws Exception {
        registerLeg("INTL", "PEK", "NRT", "CN", "JP");
        registerBag("BAG1", List.of("INTL"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> customsService.registerCheck(new CustomsCheckRequest(
                    UUID.randomUUID().toString(), "BAG1", 1, "RELEASED", "JP", null)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(CustomsCheckResponse.class);
            assertThat(((CustomsCheckResponse) result).checkVersion()).isEqualTo(1);
        });
        assertThat(countWhere("customs_inspection", "bag_tag = 'BAG1'")).isEqualTo(1);
    }

    @Test
    void queries_404ForUnknownBagOrLeg() throws Exception {
        mockMvc.perform(get("/api/bags/NOPE/customs-chain")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/hold-impact")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/NOPE/gates")).andExpect(status().isNotFound());
    }

    // ---------- 辅助方法 ----------

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String where) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
    }

    private void registerLeg(String legId, String origin, String destination,
                             String originCountry, String destinationCountry) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination,
                originCountry, destinationCountry));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(
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

    private ResultActions depart(String legId) throws Exception {
        return postJson("/api/legs/" + legId + "/depart",
                Map.of("requestId", UUID.randomUUID().toString()));
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions reroute(String bagTag, List<String> newLegIds) throws Exception {
        return postJson("/api/bags/reroute", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTag", bagTag, "newLegIds", newLegIds));
    }

    private ResultActions customsCheck(String bagTag, int version, String status,
                                       String country, String reason) throws Exception {
        return postJson("/api/customs/checks",
                checkBody(UUID.randomUUID().toString(), bagTag, version, status, country, reason));
    }

    private Map<String, Object> checkBody(String requestId, String bagTag, int version,
                                          String status, String country, String reason) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("bagTag", bagTag);
        body.put("checkVersion", version);
        body.put("status", status);
        body.put("country", country);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
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
