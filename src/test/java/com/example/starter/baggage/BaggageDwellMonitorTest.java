package com.example.starter.baggage;

import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;

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

import com.example.starter.baggage.BaggageDtos.ArrivalItem;
import com.example.starter.baggage.BaggageDtos.DwellCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellReleaseRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RegisterTransitPointRequest;
import com.example.starter.baggage.BaggageDtos.TransitArrivalRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联程行李滞留时限与自动交接阻断测试：
 * 覆盖中转点配置、批量到达核对、按可控时钟的超时判定（左闭右开）、装载阻断、
 * 处理人复核解除并重校截止与容量、不可变超时证据、明细/历史/诊断查询，
 * 以及事务整批回滚、超时与装载并发裁决、requestId 幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageDwellMonitorTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM dwell_event");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM transit_point");
        jdbcTemplate.update("DELETE FROM request_log");
        clock.set(T0);
        baggageService.setClock(clock::get);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        baggageService.setClock(Instant::now);
    }

    @Test
    void mainFlow_arriveDwellTimeoutBlockReleaseAndDeliver() throws Exception {
        // LEG2 配置容量 2 与截载时刻，供解除时重新校验
        registerLeg("LEG1", "PEK", "SHA", null, null);
        registerLeg("LEG2", "SHA", "CAN", 2, "2026-09-27T03:00:00Z");
        registerTransitPoint("SHA", 30);
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        flyLeg("LEG1", 1, 2, List.of("BAG1"));

        // 到达中转点 SHA 后批量到达核对
        clock.set(Instant.parse("2026-09-27T01:00:00Z"));
        transitArrival(List.of(Map.of("bagTag", "BAG1", "station", "SHA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.arrived", contains("BAG1")));

        // 诊断：ACTIVE，实际滞留 0、剩余 30
        mockMvc.perform(get("/api/transit/dwell").param("station", "SHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].limitMinutes").value(30))
                .andExpect(jsonPath("$.items[0].deadline").value("2026-09-27T01:30:00Z"))
                .andExpect(jsonPath("$.items[0].elapsedMinutes").value(0))
                .andExpect(jsonPath("$.items[0].remainingMinutes").value(30))
                .andExpect(jsonPath("$.items[0].exceededAt").doesNotExist());

        // 左闭右开：01:29:59 未超时；01:30:00 恰好截止即超时
        clock.set(Instant.parse("2026-09-27T01:29:59Z"));
        assertThat(baggageService.dwellCheck(
                new DwellCheckRequest(rid(), "op1", null, List.of("BAG1"))).exceeded()).isEmpty();

        clock.set(Instant.parse("2026-09-27T01:30:00Z"));
        postDwellCheck(List.of("BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.exceededCount").value(1))
                .andExpect(jsonPath("$.exceeded", contains("BAG1")));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED",
                                "DWELL_ARRIVED", "DWELL_EXCEEDED")));

        // 诊断：EXCEEDED，实际滞留 30、差额 0
        mockMvc.perform(get("/api/transit/dwell"))
                .andExpect(jsonPath("$.items[0].state").value("EXCEEDED"))
                .andExpect(jsonPath("$.items[0].elapsedMinutes").value(30))
                .andExpect(jsonPath("$.items[0].excessMinutes").value(0))
                .andExpect(jsonPath("$.items[0].exceededAt").value("2026-09-27T01:30:00Z"));

        // 超时阻断装载
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isConflict());

        // 未解除前再次装载仍 409，状态未被读取改变
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));

        // 01:45 复核解除：实际 45 分钟、要求 30、差额 15
        clock.set(Instant.parse("2026-09-27T01:45:00Z"));
        release("BAG1", "LEG2", "REVIEWER-7", "人工复核，允许续运")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.reviewer").value("REVIEWER-7"))
                .andExpect(jsonPath("$.nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.elapsedMinutes").value(45))
                .andExpect(jsonPath("$.limitMinutes").value(30))
                .andExpect(jsonPath("$.excessMinutes").value(15))
                .andExpect(jsonPath("$.releasedAt").value("2026-09-27T01:45:00Z"));

        // 解除后只能装载指定的下一航段
        registerLeg("LEG3", "SHA", "CKG", null, null);
        load("LEG3", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        load("LEG2", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG2", 2);
        arriveExact("LEG2", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED",
                                "DWELL_ARRIVED", "DWELL_EXCEEDED", "DWELL_RELEASED",
                                "LOADED", "UNLOADED", "DELIVERED")));

        // 离站后当前滞留态清空
        Integer activeVisits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE dwell_state IS NOT NULL", Integer.class);
        assertThat(activeVisits).isZero();

        // 历史证据不可变：到达/超时/解除三条，字段与排序稳定，后续航段变更不改写
        mockMvc.perform(get("/api/bags/BAG1/dwell-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].eventType").value("ARRIVED"))
                .andExpect(jsonPath("$.events[0].station").value("SHA"))
                .andExpect(jsonPath("$.events[0].arrivedAt").value("2026-09-27T01:00:00Z"))
                .andExpect(jsonPath("$.events[0].deadline").value("2026-09-27T01:30:00Z"))
                .andExpect(jsonPath("$.events[0].elapsedMinutes").doesNotExist())
                .andExpect(jsonPath("$.events[0].nextLegId").doesNotExist())
                .andExpect(jsonPath("$.events[0].reviewer").doesNotExist())
                .andExpect(jsonPath("$.events[1].eventType").value("TIMEOUT"))
                .andExpect(jsonPath("$.events[1].eventTime").value("2026-09-27T01:30:00Z"))
                .andExpect(jsonPath("$.events[1].elapsedMinutes").value(30))
                .andExpect(jsonPath("$.events[1].excessMinutes").value(0))
                .andExpect(jsonPath("$.events[1].arrivedAt").value("2026-09-27T01:00:00Z"))
                .andExpect(jsonPath("$.events[2].eventType").value("RELEASED"))
                .andExpect(jsonPath("$.events[2].nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.events[2].reviewer").value("REVIEWER-7"))
                .andExpect(jsonPath("$.events[2].reviewConclusion").value("人工复核，允许续运"))
                .andExpect(jsonPath("$.events[2].elapsedMinutes").value(45))
                .andExpect(jsonPath("$.events[2].excessMinutes").value(15));
    }

    @Test
    void stationWideDwellCheck_findsAllActiveBagsStableSorted() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", null, null);
        registerTransitPoint("SHA", 10);
        registerBag("BAG_B", List.of("L1", "L2"));
        registerBag("BAG_A", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG_A", "BAG_B"));

        clock.set(Instant.parse("2026-09-27T01:00:00Z"));
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG_A", "SHA"), arrival("BAG_B", "SHA"))));

        // bagTags 缺省：按 station 全量判定，超时后 exceeded 按袋号稳定排序
        clock.set(Instant.parse("2026-09-27T01:10:00Z"));
        var resp = baggageService.dwellCheck(new DwellCheckRequest(rid(), "op", "SHA", null));
        assertThat(resp.checked()).isEqualTo(2);
        assertThat(resp.exceeded()).containsExactly("BAG_A", "BAG_B");

        // 诊断列表稳定排序：station, bagTag
        mockMvc.perform(get("/api/transit/dwell"))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG_A"))
                .andExpect(jsonPath("$.items[1].bagTag").value("BAG_B"));

        // 已全部超时后再全量判定：checked 为 0
        var again = baggageService.dwellCheck(new DwellCheckRequest(rid(), "op", "SHA", null));
        assertThat(again.checked()).isZero();
        assertThat(again.exceeded()).isEmpty();
    }

    @Test
    void transitArrival_missingConfigOrIllegalStateRollsBackWholeBatch() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", null, null);
        registerTransitPoint("SHA", 10);
        registerBag("BAG_OK", List.of("L1", "L2"));
        registerBag("BAG_NO_CFG", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG_OK", "BAG_NO_CFG"));

        // 第二项到达未配置站点 XIY：422，整批回滚，BAG_OK 也不产生到达证据
        jdbcTemplate.update("DELETE FROM transit_point WHERE station = 'SHA'");
        transitArrival(List.of(
                Map.of("bagTag", "BAG_OK", "station", "SHA"),
                Map.of("bagTag", "BAG_NO_CFG", "station", "SHA")))
                .andExpect(status().isUnprocessableEntity());

        // 重新加上 SHA 配置；站点缺配置单独验证
        registerTransitPoint("SHA", 10);
        // 到达时刻缺失语义：未登记过中转到达的行李不能判定超时（dwell_started_at 为空）
        postDwellCheck(List.of("BAG_OK")).andExpect(status().isUnprocessableEntity());

        // 站点与行李当前位置不一致 -> 422 整批回滚
        transitArrival(List.of(
                Map.of("bagTag", "BAG_OK", "station", "SHA"),
                Map.of("bagTag", "BAG_NO_CFG", "station", "XIY")))
                .andExpect(status().isUnprocessableEntity());
        Integer visits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE dwell_state IS NOT NULL", Integer.class);
        assertThat(visits).isZero();
        Integer evidence = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_event", Integer.class);
        assertThat(evidence).isZero();

        // 合法首项 + 重复袋号次项：整批回滚
        transitArrival(List.of(
                Map.of("bagTag", "BAG_OK", "station", "SHA"),
                Map.of("bagTag", "BAG_OK", "station", "SHA")))
                .andExpect(status().isUnprocessableEntity());
        evidence = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dwell_event", Integer.class);
        assertThat(evidence).isZero();

        // 正常登记 BAG_OK 后，重复到达被拒；已在航段上（loaded）的行李到达被拒
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG_OK", "SHA"))));
        transitArrival(List.of(Map.of("bagTag", "BAG_OK", "station", "SHA")))
                .andExpect(status().isUnprocessableEntity());
        registerLeg("L3", "PEK", "SHA", null, null);
        registerBag("BAG_LOADED", List.of("L3"));
        baggageService.load("L3", new LoadRequest(rid(), 1, List.of("BAG_LOADED")));
        transitArrival(List.of(Map.of("bagTag", "BAG_LOADED", "station", "PEK")))
                .andExpect(status().isUnprocessableEntity());

        // 首段尚未飞（PEK 是始发站而非中转点）即便位置一致也拒绝
        registerBag("BAG_FIRST", List.of("L3"));
        transitArrival(List.of(Map.of("bagTag", "BAG_FIRST", "station", "PEK")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void release_requiresNextLegInItineraryAndRechecksCutoffAndCapacity() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", 1, "2026-09-27T02:00:00Z");
        registerLeg("L_OTHER", "SHA", "CKG", null, null);
        registerTransitPoint("SHA", 30);
        registerBag("BAG1", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG1"));
        clock.set(Instant.parse("2026-09-27T01:00:00Z"));
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG1", "SHA"))));

        // 未超时不能解除
        release("BAG1", "L2", "r", "ok").andExpect(status().isUnprocessableEntity());

        clock.set(Instant.parse("2026-09-27T01:30:00Z"));
        baggageService.dwellCheck(new DwellCheckRequest(rid(), "op", null, List.of("BAG1")));

        // 下一航段不属于当前待乘行程 -> 422
        release("BAG1", "L_OTHER", "r", "ok").andExpect(status().isUnprocessableEntity());
        // 不存在的航段 -> 422
        release("BAG1", "L_MISSING", "r", "ok").andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 404
        release("BAG_MISSING", "L2", "r", "ok").andExpect(status().isNotFound());

        // 容量占用：另一件已占满 L2 的 1 件容量，解除重校容量失败 -> 422（差额 1）
        registerBag("BAG_CAP", List.of("L2"));
        baggageService.load("L2", new LoadRequest(rid(), 1, List.of("BAG_CAP")));
        release("BAG1", "L2", "r", "容量复核")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超出 1 件")));
        // 容量满时超时阻断优先于容量校验：直接装载返回 409
        load("L2", 2, List.of("BAG1")).andExpect(status().isConflict());

        // 腾空容量后，越过截载时刻：解除重校截止失败 -> 422
        jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = 'BAG_CAP'");
        jdbcTemplate.update("UPDATE bag SET loaded_leg_id = NULL WHERE bag_tag = 'BAG_CAP'");
        clock.set(Instant.parse("2026-09-27T02:00:00Z"));
        release("BAG1", "L2", "r", "截止复核")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("截载时刻")));

        // 截止前一秒解除成功（左闭右开边界）
        clock.set(Instant.parse("2026-09-27T01:59:59Z"));
        release("BAG1", "L2", "REVIEWER-1", "边界复核").andExpect(status().isOk());
    }

    @Test
    void load_enforcesCutoffBoundaryAndCapacity() throws Exception {
        registerLeg("L_CAP", "PEK", "SHA", 2, "2026-09-27T00:30:00Z");
        registerBag("BAG1", List.of("L_CAP"));
        registerBag("BAG2", List.of("L_CAP"));
        registerBag("BAG3", List.of("L_CAP"));

        // 容量：2 件上限，装第 3 件整批拒绝并给出实际/要求/差额
        load("L_CAP", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        load("L_CAP", 2, List.of("BAG3"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超出 1 件")));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'L_CAP'", Integer.class);
        assertThat(count).isEqualTo(2);

        // 截载左闭右开：恰好 00:30 拒绝装载（失败不推进版本，仍为 2）
        clock.set(Instant.parse("2026-09-27T00:30:00Z"));
        load("L_CAP", 2, List.of("BAG3")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void idempotency_dwellOperationsReplaySnapshotAndDetectDifferentOperator() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", null, null);
        registerTransitPoint("SHA", 30);
        registerBag("BAG1", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG1"));
        clock.set(Instant.parse("2026-09-27T01:00:00Z"));

        // 到达核对同参重放：证据只写一次
        String arrivalKey = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            baggageService.transitArrival(new TransitArrivalRequest(
                    arrivalKey, "op-a", List.of(arrival("BAG1", "SHA"))));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_event", Integer.class)).isEqualTo(1);
        // 同键换操作者 -> 指纹不同 -> 409
        ApiException ex = catchApi(() -> baggageService.transitArrival(new TransitArrivalRequest(
                arrivalKey, "op-b", List.of(arrival("BAG1", "SHA")))));
        assertThat(ex.getStatus().value()).isEqualTo(409);

        // 超时判定同键重放：只追加一次 TIMEOUT
        clock.set(Instant.parse("2026-09-27T01:30:00Z"));
        String checkKey = UUID.randomUUID().toString();
        var check1 = baggageService.dwellCheck(
                new DwellCheckRequest(checkKey, "op-a", null, List.of("BAG1")));
        var checkReplay = baggageService.dwellCheck(
                new DwellCheckRequest(checkKey, "op-a", null, List.of("BAG1")));
        assertThat(checkReplay.exceeded()).containsExactlyElementsOf(check1.exceeded());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_event WHERE event_type = 'TIMEOUT'", Integer.class))
                .isEqualTo(1);

        // 解除：首次在 01:45 提交（实际 45、差额 15），时钟推进后重放必须返回首次快照
        clock.set(Instant.parse("2026-09-27T01:45:00Z"));
        String releaseKey = UUID.randomUUID().toString();
        var released = baggageService.dwellRelease(new DwellReleaseRequest(
                releaseKey, "REVIEWER-1", "BAG1", "L2", "复核通过"));
        assertThat(released.elapsedMinutes()).isEqualTo(45);
        assertThat(released.excessMinutes()).isEqualTo(15);
        clock.set(Instant.parse("2026-09-27T03:00:00Z"));
        var replay = baggageService.dwellRelease(new DwellReleaseRequest(
                releaseKey, "REVIEWER-1", "BAG1", "L2", "复核通过"));
        assertThat(replay.elapsedMinutes()).isEqualTo(45);
        assertThat(replay.excessMinutes()).isEqualTo(15);
        assertThat(replay.releasedAt()).isEqualTo("2026-09-27T01:45:00Z");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_event WHERE event_type = 'RELEASED'", Integer.class))
                .isEqualTo(1);

        // 同键换下一航段 -> 409
        ApiException wrongLeg = catchApi(() -> baggageService.dwellRelease(new DwellReleaseRequest(
                releaseKey, "REVIEWER-1", "BAG1", "L1", "复核通过")));
        assertThat(wrongLeg.getStatus().value()).isEqualTo(409);

        // 失败不占键：先以非法航段失败，再同键成功（新一件行李走完整链路）
        registerLeg("L4", "PEK", "SHA", null, null);
        registerLeg("L5", "SHA", "CAN", null, null);
        registerBag("BAG2", List.of("L4", "L5"));
        flyLeg("L4", 1, 2, List.of("BAG2"));
        clock.set(Instant.parse("2026-09-27T04:00:00Z"));
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG2", "SHA"))));
        clock.set(Instant.parse("2026-09-27T04:40:00Z"));
        baggageService.dwellCheck(new DwellCheckRequest(rid(), "op", null, List.of("BAG2")));
        String retryKey = UUID.randomUUID().toString();
        ApiException failed = catchApi(() -> baggageService.dwellRelease(new DwellReleaseRequest(
                retryKey, "r", "BAG2", "L_MISSING", "x")));
        assertThat(failed.getStatus().value()).isEqualTo(422);
        var retried = baggageService.dwellRelease(new DwellReleaseRequest(
                retryKey, "r", "BAG2", "L5", "x"));
        assertThat(retried.nextLegId()).isEqualTo("L5");
    }

    @Test
    void concurrentTimeoutAndLoad_firstCommitWins() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", null, null);
        registerTransitPoint("SHA", 30);
        registerBag("BAG1", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG1"));
        clock.set(Instant.parse("2026-09-27T01:00:00Z"));
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG1", "SHA"))));
        clock.set(Instant.parse("2026-09-27T01:30:00Z"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.dwellCheck(
                        new DwellCheckRequest(UUID.randomUUID().toString(),
                                "op", null, List.of("BAG1"))),
                () -> baggageService.load("L2",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        long checksOk = results.stream()
                .filter(r -> r instanceof BaggageDtos.DwellCheckResponse cr && cr.exceededCount() == 1)
                .count();
        long loadsOk = results.stream().filter(r -> r instanceof BaggageDtos.LoadResponse).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        long unprocessable = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 422).count();
        // 唯一先后：超时先提交则装载 409；装载先提交则判定时行李已离站（422）
        assertThat(checksOk + loadsOk).isEqualTo(1);
        assertThat(conflicts).isEqualTo(checksOk);
        assertThat(unprocessable).isEqualTo(loadsOk);

        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        if (loadsOk == 1) {
            assertThat(loadRecords).isEqualTo(1);
        } else {
            // 超时先提交：行李被阻断，无装载半成品
            assertThat(bagStatus).isEqualTo("DWELL_EXCEEDED");
            assertThat(loadRecords).isZero();
        }
    }

    @Test
    void concurrentTimeoutAndRelease_thenLoadIsConsistent() throws Exception {
        registerLeg("L1", "PEK", "SHA", null, null);
        registerLeg("L2", "SHA", "CAN", null, null);
        registerTransitPoint("SHA", 30);
        registerBag("BAG1", List.of("L1", "L2"));
        flyLeg("L1", 1, 2, List.of("BAG1"));
        clock.set(Instant.parse("2026-09-27T01:00:00Z"));
        baggageService.transitArrival(new TransitArrivalRequest(
                rid(), "op", List.of(arrival("BAG1", "SHA"))));
        clock.set(Instant.parse("2026-09-27T01:30:00Z"));

        // 01:30:00 判定超时 与 01:30:00 解除并发：解除只在超时提交后合法
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.dwellCheck(new DwellCheckRequest(
                        UUID.randomUUID().toString(), "op", null, List.of("BAG1"))),
                () -> baggageService.dwellRelease(new DwellReleaseRequest(
                        UUID.randomUUID().toString(), "REVIEWER-9", "BAG1", "L2", "并发复核")));
        List<Object> results = runConcurrently(tasks);

        boolean released = results.stream().anyMatch(r -> r instanceof BaggageDtos.DwellReleaseResponse);
        boolean timedOut = results.stream()
                .anyMatch(r -> r instanceof BaggageDtos.DwellCheckResponse cr && cr.exceededCount() == 1);
        assertThat(timedOut)
                .as("并发结果: %s", results.stream().map(r -> r instanceof Throwable t
                        ? t.getClass().getSimpleName() + ":" + t.getMessage()
                        : r.getClass().getSimpleName()).toList())
                .isTrue();
        // 裁决后状态自洽：要么已解除可直接装载，要么仍阻断（装载 409），解除后再装载成功
        if (released) {
            assertThat(baggageService.load("L2",
                    new LoadRequest(rid(), 1, List.of("BAG1"))).loaded()).contains("BAG1");
        } else {
            ApiException blocked = catchApi(() -> baggageService.load("L2",
                    new LoadRequest(rid(), 1, List.of("BAG1"))));
            assertThat(blocked.getStatus().value()).isEqualTo(409);
            baggageService.dwellRelease(new DwellReleaseRequest(
                    rid(), "REVIEWER-9", "BAG1", "L2", "补复核"));
            assertThat(baggageService.load("L2",
                    new LoadRequest(rid(), 1, List.of("BAG1"))).loaded()).contains("BAG1");
        }
    }

    @Test
    void transitPoint_duplicateConfigConflictsAndInvalidPayloadRejected() throws Exception {
        baggageService.registerTransitPoint(
                new RegisterTransitPointRequest(rid(), "op", "SHA", 30));
        ApiException dup = catchApi(() -> baggageService.registerTransitPoint(
                new RegisterTransitPointRequest(rid(), "op", "SHA", 45)));
        assertThat(dup.getStatus().value()).isEqualTo(409);

        // 非正分钟数 -> 400
        mockMvc.perform(post("/api/transit-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"operator\":\"op\",\"station\":\"CAN\",\"maxDwellMinutes\":0}"))
                .andExpect(status().isBadRequest());
        // 历史查询未知行李 -> 404
        mockMvc.perform(get("/api/bags/NOPE/dwell-history"))
                .andExpect(status().isNotFound());
    }

    // ===== 辅助方法 =====

    private void registerLeg(String legId, String origin, String destination,
                             Integer capacity, String cutoff) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination, capacity, cutoff));
    }

    private void registerTransitPoint(String station, int maxDwellMinutes) {
        baggageService.registerTransitPoint(new RegisterTransitPointRequest(
                UUID.randomUUID().toString(), "op", station, maxDwellMinutes));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(
                UUID.randomUUID().toString(), bagTag, legIds));
    }

    /** 装载、封舱、精确到达一整条航段。 */
    private void flyLeg(String legId, int loadVersion, int sealVersion, List<String> bagTags)
            throws Exception {
        load(legId, loadVersion, bagTags).andExpect(status().isOk());
        seal(legId, sealVersion).andExpect(status().isOk());
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

    private ResultActions transitArrival(List<?> arrivals) throws Exception {
        return postJson("/api/transit/arrivals", new HashMap<>(Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "op", "arrivals", arrivals)));
    }

    private ResultActions postDwellCheck(List<String> bagTags) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("operator", "op");
        body.put("bagTags", bagTags);
        return postJson("/api/transit/dwell-checks", body);
    }

    private ResultActions release(String bagTag, String nextLegId, String reviewer, String conclusion)
            throws Exception {
        return postJson("/api/bags/dwell/release", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", reviewer,
                "bagTag", bagTag, "nextLegId", nextLegId, "reviewConclusion", conclusion));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static ArrivalItem arrival(String bagTag, String station) {
        return new ArrivalItem(bagTag, station);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static ApiException catchApi(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException ex) {
            return ex;
        }
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
