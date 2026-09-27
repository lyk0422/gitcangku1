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

import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李滞留监控测试：覆盖中转到达核对、超时判定（左闭右开边界）、复核解除、改派、
 * 明细/历史/诊断查询的主流程与 422/409 失败分支、批量整次回滚、幂等边界、
 * 超时判定与装载并发按提交顺序裁决。全部基于 H2 MySQL 兼容模式真实数据库，时钟可注入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageDwellMonitorTest {

    private static final Instant T0 = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM dwell_event");
        jdbcTemplate.update("DELETE FROM dwell_record");
        jdbcTemplate.update("DELETE FROM leg_ops_config");
        jdbcTemplate.update("DELETE FROM dwell_config");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        now.set(T0);
        baggageService.setClock(now::get);
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void mainFlow_arriveTimeoutReleaseLoadAndDiagnostics() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk())
                .andExpect(jsonPath("$.station").value("SHA"))
                .andExpect(jsonPath("$.maxDwellMinutes").value(60));
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());

        // 中转到达核对：开启滞留计时
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.station").value("SHA"))
                .andExpect(jsonPath("$.arrived[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.arrived[0].arrivedAt").value(T0.toString()));

        // 滞留 30 分钟 < 上限 60：判定不超时，返回实际值与要求值
        now.set(T0.plusSeconds(30 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].dwellMinutes").value(30))
                .andExpect(jsonPath("$.items[0].maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.items[0].exceedByMinutes").doesNotExist());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 滞留恰好 60 分钟：区间左闭右开，达到上限即超时，差额为 0
        now.set(T0.plusSeconds(60 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("EXCEEDED"))
                .andExpect(jsonPath("$.items[0].dwellMinutes").value(60))
                .andExpect(jsonPath("$.items[0].exceedByMinutes").value(0));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));

        // 阻断后续装载（409）与改派（422）
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isConflict());
        reroute("BAG1", "OP9", List.of("LEG2")).andExpect(status().isUnprocessableEntity());

        // 复核解除：指定下一航段，重新校验截止与容量
        configureLegOps("LEG2", T0.plusSeconds(2 * 3600).toString(), 10).andExpect(status().isOk());
        release("BAG1", "OP1", "人工复核放行", "LEG2").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.releasedAt").value(T0.plusSeconds(3600).toString()))
                .andExpect(jsonPath("$.reviewer").value("OP1"));

        // 解除后明细：证据字段完整，记录尚未关闭
        mockMvc.perform(get("/api/bags/BAG1/dwell"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.station").value("SHA"))
                .andExpect(jsonPath("$.arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.exceededAt").value(T0.plusSeconds(3600).toString()))
                .andExpect(jsonPath("$.releasedAt").value(T0.plusSeconds(3600).toString()))
                .andExpect(jsonPath("$.reviewer").value("OP1"))
                .andExpect(jsonPath("$.reviewConclusion").value("人工复核放行"))
                .andExpect(jsonPath("$.nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());

        // 解除后可装载，装载离开中转点关闭滞留记录
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/dwell"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.closedAt").value(T0.plusSeconds(3600).toString()));

        // 历史事件：到达/超时/解除/关闭，追加只写、顺序稳定
        mockMvc.perform(get("/api/bags/BAG1/dwell-history"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("ARRIVED", "EXCEEDED", "RELEASED", "CLOSED")))
                .andExpect(jsonPath("$.events[*].seq", contains(0, 1, 2, 3)))
                .andExpect(jsonPath("$.events[0].operator").value("REG1"))
                .andExpect(jsonPath("$.events[1].operator").value("MON1"))
                .andExpect(jsonPath("$.events[2].operator").value("OP1"))
                .andExpect(jsonPath("$.events[2].nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.events[2].detail").value("人工复核放行"))
                .andExpect(jsonPath("$.events[3].operator").doesNotExist());

        // 诊断：状态分布与上限配置
        mockMvc.perform(get("/api/stations/SHA/dwell-diagnostics"))
                .andExpect(jsonPath("$.maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.openCount").value(0))
                .andExpect(jsonPath("$.exceededCount").value(0))
                .andExpect(jsonPath("$.releasedCount").value(1))
                .andExpect(jsonPath("$.closedCount").value(0));
    }

    @Test
    void transitArrive_validationsAndBatchRollback() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("BAG3", List.of("LEG2")).andExpect(status().isCreated());

        // 中转点缺配置 -> 422
        transitArrive("PEK", "REG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        configureDwell("PEK", 30).andExpect(status().isOk());
        configureDwell("SHA", 60).andExpect(status().isOk());

        // 行李不在该中转点 -> 422
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 已装载在航段上 -> 422
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        transitArrive("PEK", "REG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        transitArrive("PEK", "REG1", List.of("BAG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 请求内重复 -> 422
        transitArrive("PEK", "REG1", List.of("BAG2", "BAG2")).andExpect(status().isUnprocessableEntity());

        // 批量到达核对：BAG3 不在 PEK，任一行李非法整次回滚，BAG2 也不产生记录
        transitArrive("PEK", "REG1", List.of("BAG2", "BAG3")).andExpect(status().isUnprocessableEntity());
        assertThat(countRows("dwell_record")).isZero();
        assertThat(countRows("dwell_event")).isZero();

        // 合法登记成功；重复登记（已有未关闭记录）-> 422
        transitArrive("PEK", "REG1", List.of("BAG2")).andExpect(status().isOk());
        transitArrive("PEK", "REG1", List.of("BAG2")).andExpect(status().isUnprocessableEntity());
        assertThat(countRows("dwell_record")).isEqualTo(1);

        // 已交付行李 -> 422（BAG2 装载离开 PEK 后记录关闭，送达 SHA 后不可再登记）
        load("LEG1", 2, List.of("BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/dwell"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        seal("LEG1", 3).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        transitArrive("SHA", "REG1", List.of("BAG2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dwellTimeoutCheck_validationsAndBatchRollback() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG2")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG2")).andExpect(status().isCreated());
        registerBag("BAG3", List.of("LEG2")).andExpect(status().isCreated());
        transitArrive("SHA", "REG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());

        // 缺配置的中转点 -> 422
        timeoutCheck("XIY", "MON1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 到达时刻缺失（无滞留记录）-> 422
        timeoutCheck("SHA", "MON1", List.of("BAG3")).andExpect(status().isUnprocessableEntity());
        // 请求内重复 -> 422
        timeoutCheck("SHA", "MON1", List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());

        // 批量判定：BAG3 无到达记录，整次回滚，BAG1/BAG2 不转超时
        now.set(T0.plusSeconds(120 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1", "BAG2", "BAG3"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        mockMvc.perform(get("/api/bags/BAG1/dwell"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.exceededAt").doesNotExist());

        // 合法判定：BAG1/BAG2 超时，超出差额 = 120 - 60 = 60
        timeoutCheck("SHA", "MON1", List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].exceedByMinutes").value(60))
                .andExpect(jsonPath("$.items[1].exceedByMinutes").value(60));

        // 已超时重复判定 -> 409
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isConflict());

        // 记录已关闭（装载离开）-> 422
        transitArrive("SHA", "REG1", List.of("BAG3")).andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG3")).andExpect(status().isOk());
        timeoutCheck("SHA", "MON1", List.of("BAG3")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void releaseDwell_validations() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerLeg("LEG3", "SHA", "XIY").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isOk());

        // 未超时不可解除 -> 422
        release("BAG1", "OP1", "提前解除", "LEG2").andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 404
        release("BAG_MISSING", "OP1", "复核", "LEG2").andExpect(status().isNotFound());
        // 无滞留记录 -> 422
        release("BAG2", "OP1", "复核", "LEG2").andExpect(status().isUnprocessableEntity());

        now.set(T0.plusSeconds(90 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk());

        // 下一航段不属于行程 -> 422
        release("BAG1", "OP1", "复核放行", "LEG3").andExpect(status().isUnprocessableEntity());
        // 下一航段缺截止与容量配置 -> 422
        release("BAG1", "OP1", "复核放行", "LEG2").andExpect(status().isUnprocessableEntity());
        // 超过装载截止时刻 -> 422（截止 T0+60min，当前 T0+90min）
        configureLegOps("LEG2", T0.plusSeconds(3600).toString(), 10).andExpect(status().isOk());
        release("BAG1", "OP1", "复核放行", "LEG2").andExpect(status().isUnprocessableEntity());
        // 容量不足 -> 422（容量 1，BAG2 已占 1 件）
        configureLegOps("LEG2", T0.plusSeconds(2 * 3600).toString(), 1).andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG2")).andExpect(status().isOk());
        release("BAG1", "OP1", "复核放行", "LEG2").andExpect(status().isUnprocessableEntity());

        // 扩容后解除成功
        configureLegOps("LEG2", T0.plusSeconds(2 * 3600).toString(), 10).andExpect(status().isOk());
        release("BAG1", "OP1", "复核放行", "LEG2").andExpect(status().isOk());
        // 重复解除 -> 409
        release("BAG1", "OP2", "再次解除", "LEG2").andExpect(status().isConflict());
    }

    @Test
    void reroute_replacesItineraryClosesDwellAndPreservesEvidence() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerLeg("LEG3", "SHA", "XIY").andExpect(status().isCreated());
        registerLeg("LEG4", "XIY", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        transitArrive("SHA", "REG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());

        // BAG1 未超时直接改派：行程整体替换，滞留记录关闭
        reroute("BAG1", "OP9", List.of("LEG3", "LEG4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("LEG1", "LEG3", "LEG4")));
        mockMvc.perform(get("/api/bags/BAG1/dwell"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(T0.toString()));
        mockMvc.perform(get("/api/bags/BAG1/dwell-history"))
                .andExpect(jsonPath("$.events[*].eventType", contains("ARRIVED", "CLOSED")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "REROUTED")));

        // BAG2 超时后解除再改派：超时与解除证据不被改派改写
        now.set(T0.plusSeconds(90 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG2")).andExpect(status().isOk());
        configureLegOps("LEG2", T0.plusSeconds(2 * 3600).toString(), 10).andExpect(status().isOk());
        release("BAG2", "OP1", "复核放行", "LEG2").andExpect(status().isOk());
        reroute("BAG2", "OP9", List.of("LEG3", "LEG4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary[*].legId", contains("LEG1", "LEG3", "LEG4")));
        mockMvc.perform(get("/api/bags/BAG2/dwell"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.exceededAt").value(T0.plusSeconds(90 * 60).toString()))
                .andExpect(jsonPath("$.releasedAt").value(T0.plusSeconds(90 * 60).toString()))
                .andExpect(jsonPath("$.reviewer").value("OP1"))
                .andExpect(jsonPath("$.reviewConclusion").value("复核放行"))
                .andExpect(jsonPath("$.nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.closedAt").value(T0.plusSeconds(90 * 60).toString()));
        mockMvc.perform(get("/api/bags/BAG2/dwell-history"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("ARRIVED", "EXCEEDED", "RELEASED", "CLOSED")));
    }

    @Test
    void reroute_rejectsInvalidRequests() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerLeg("LEG3", "SHA", "XIY").andExpect(status().isCreated());
        registerLeg("LEG4", "XIY", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());

        // 行李不存在 -> 404
        reroute("BAG_MISSING", "OP9", List.of("LEG3")).andExpect(status().isNotFound());
        // 引用不存在的航段 -> 422
        reroute("BAG1", "OP9", List.of("LEG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 首段不从当前站点出发 -> 422
        reroute("BAG1", "OP9", List.of("LEG4")).andExpect(status().isUnprocessableEntity());
        // 相邻航段不衔接 -> 422
        reroute("BAG1", "OP9", List.of("LEG3", "LEG2")).andExpect(status().isUnprocessableEntity());
        // 改派航段重复 -> 422
        reroute("BAG1", "OP9", List.of("LEG3", "LEG3")).andExpect(status().isUnprocessableEntity());

        // 改派后行程超过 5 段 -> 422（已完成 2 段，再补 4 段）
        registerLeg("LEGA", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEGB", "SHA", "CAN").andExpect(status().isCreated());
        registerLeg("LEGC", "CAN", "HKG").andExpect(status().isCreated());
        registerLeg("LEGD", "HKG", "XIY").andExpect(status().isCreated());
        registerLeg("LEGE", "XIY", "TPE").andExpect(status().isCreated());
        registerLeg("LEGF", "TPE", "SIN").andExpect(status().isCreated());
        registerBag("BAG6", List.of("LEGA", "LEGB", "LEGC", "LEGD", "LEGE")).andExpect(status().isCreated());
        load("LEGA", 1, List.of("BAG6")).andExpect(status().isOk());
        seal("LEGA", 2).andExpect(status().isOk());
        arriveExact("LEGA", List.of("BAG6")).andExpect(status().isOk());
        load("LEGB", 1, List.of("BAG6")).andExpect(status().isOk());
        seal("LEGB", 2).andExpect(status().isOk());
        arriveExact("LEGB", List.of("BAG6")).andExpect(status().isOk());
        reroute("BAG6", "OP9", List.of("LEGC", "LEGD", "LEGE", "LEGF"))
                .andExpect(status().isUnprocessableEntity());
        reroute("BAG6", "OP9", List.of("LEGC", "LEGD", "LEGE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary[*].legId",
                        contains("LEGA", "LEGB", "LEGC", "LEGD", "LEGE")));

        // 已装载行李不可改派 -> 422
        registerBag("BAG5", List.of("LEG2")).andExpect(status().isCreated());
        load("LEG2", 1, List.of("BAG5")).andExpect(status().isOk());
        reroute("BAG5", "OP9", List.of("LEG3")).andExpect(status().isUnprocessableEntity());

        // 已交付行李不可改派 -> 422
        registerBag("BAG4", List.of("LEG3")).andExpect(status().isCreated());
        load("LEG3", 1, List.of("BAG4")).andExpect(status().isOk());
        seal("LEG3", 2).andExpect(status().isOk());
        arriveExact("LEG3", List.of("BAG4")).andExpect(status().isOk());
        reroute("BAG4", "OP9", List.of("LEG4")).andExpect(status().isUnprocessableEntity());

        // 滞留超时阻断改派 -> 422
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(120 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk());
        reroute("BAG1", "OP9", List.of("LEG3")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void idempotency_replayConflictAndFailureNotOccupyingKey() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        registerBag("BAG2", List.of("LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());

        // 同键同参重放：返回首次成功响应快照，滞留记录只建一条
        String arriveKey = UUID.randomUUID().toString();
        Map<String, Object> arriveBody = Map.of("requestId", arriveKey,
                "operator", "REG1", "bagTags", List.of("BAG1"));
        postJson("/api/stations/SHA/transit-arrive", arriveBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.arrived[0].arrivedAt").value(T0.toString()));
        postJson("/api/stations/SHA/transit-arrive", arriveBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.arrived[0].arrivedAt").value(T0.toString()));
        assertThat(countRows("dwell_record")).isEqualTo(1);
        assertThat(countRows("dwell_event")).isEqualTo(1);

        // 同键异参 -> 409
        postJson("/api/stations/SHA/transit-arrive", Map.of("requestId", arriveKey,
                "operator", "REG1", "bagTags", List.of("BAG2"))).andExpect(status().isConflict());

        // 失败不占键：先以不存在的行李失败（422），再同键成功
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/stations/SHA/transit-arrive", Map.of("requestId", retryKey,
                "operator", "REG1", "bagTags", List.of("BAG_MISSING")))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/stations/SHA/transit-arrive", Map.of("requestId", retryKey,
                "operator", "REG1", "bagTags", List.of("BAG2"))).andExpect(status().isOk());

        // 解除重放：releasedAt 快照一致，RELEASED 事件只追加一次
        now.set(T0.plusSeconds(90 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk());
        configureLegOps("LEG2", T0.plusSeconds(2 * 3600).toString(), 10).andExpect(status().isOk());
        String releaseKey = UUID.randomUUID().toString();
        Map<String, Object> releaseBody = Map.of("requestId", releaseKey,
                "reviewer", "OP1", "conclusion", "复核放行", "nextLegId", "LEG2");
        postJson("/api/bags/BAG1/release", releaseBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").value(T0.plusSeconds(90 * 60).toString()));
        postJson("/api/bags/BAG1/release", releaseBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").value(T0.plusSeconds(90 * 60).toString()));
        Integer releasedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_event WHERE bag_tag = 'BAG1' AND event_type = 'RELEASED'",
                Integer.class);
        assertThat(releasedEvents).isEqualTo(1);
    }

    @Test
    void concurrentTimeoutCheckAndLoad_adjudicatedByCommitOrder() throws Exception {
        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(120 * 60));

        // 超时判定与装载真实并发：按提交顺序裁决，有且仅有一个成功
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.checkDwellTimeout("SHA", new DwellTimeoutCheckRequest(
                        UUID.randomUUID().toString(), "MON1", List.of("BAG1"))),
                () -> baggageService.load("LEG2",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        boolean timeoutWon = results.stream().anyMatch(DwellTimeoutCheckResponse.class::isInstance);
        boolean loadWon = results.stream().anyMatch(LoadResponse.class::isInstance);
        assertThat(timeoutWon).isNotEqualTo(loadWon);

        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        String recordStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM dwell_record WHERE bag_tag = 'BAG1'", String.class);
        if (timeoutWon) {
            // 超时先提交：装载 409，行李阻断，记录 EXCEEDED
            ApiException loadError = results.stream()
                    .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                    .findFirst().orElseThrow();
            assertThat(loadError.getStatus().value()).isEqualTo(409);
            assertThat(bagStatus).isEqualTo("DWELL_EXCEEDED");
            assertThat(recordStatus).isEqualTo("EXCEEDED");
            assertThat(countRows("load_record")).isZero();
        } else {
            // 装载先提交：记录已关闭，超时判定 422，行李正常装载
            ApiException checkError = results.stream()
                    .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                    .findFirst().orElseThrow();
            assertThat(checkError.getStatus().value()).isEqualTo(422);
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(recordStatus).isEqualTo("CLOSED");
            assertThat(countRows("load_record")).isEqualTo(1);
        }
    }

    @Test
    void dwellQueries_readOnlyAndStable() throws Exception {
        // 未配置站点的诊断：200 且上限为 null，读取不产生任何记录
        mockMvc.perform(get("/api/stations/XIY/dwell-diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDwellMinutes").doesNotExist())
                .andExpect(jsonPath("$.openCount").value(0));
        mockMvc.perform(get("/api/bags/NOPE/dwell")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/dwell-history")).andExpect(status().isNotFound());

        configureDwell("SHA", 60).andExpect(status().isOk());
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isOk());
        transitArrive("SHA", "REG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(90 * 60));
        timeoutCheck("SHA", "MON1", List.of("BAG1")).andExpect(status().isOk());

        long recordsBefore = countRows("dwell_record");
        long eventsBefore = countRows("dwell_event");
        // 重复读取明细、历史与诊断：结果稳定且不改变状态
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/bags/BAG1/dwell"))
                    .andExpect(jsonPath("$.status").value("EXCEEDED"))
                    .andExpect(jsonPath("$.releasedAt").doesNotExist())
                    .andExpect(jsonPath("$.reviewer").doesNotExist());
            mockMvc.perform(get("/api/bags/BAG1/dwell-history"))
                    .andExpect(jsonPath("$.events[*].eventType", contains("ARRIVED", "EXCEEDED")))
                    .andExpect(jsonPath("$.events", hasSize(2)));
            mockMvc.perform(get("/api/stations/SHA/dwell-diagnostics"))
                    .andExpect(jsonPath("$.openCount").value(0))
                    .andExpect(jsonPath("$.exceededCount").value(1));
        }
        assertThat(countRows("dwell_record")).isEqualTo(recordsBefore);
        assertThat(countRows("dwell_event")).isEqualTo(eventsBefore);
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));
    }

    private long countRows(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
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

    private ResultActions arriveExact(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions configureDwell(String station, long maxDwellMinutes) throws Exception {
        return postJson("/api/stations/" + station + "/dwell-config", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "operator", "ADMIN", "maxDwellMinutes", maxDwellMinutes));
    }

    private ResultActions configureLegOps(String legId, String loadDeadline, int maxCapacity)
            throws Exception {
        return postJson("/api/legs/" + legId + "/ops-config", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "ADMIN",
                "loadDeadline", loadDeadline, "maxCapacity", maxCapacity));
    }

    private ResultActions transitArrive(String station, String operator, List<String> bagTags)
            throws Exception {
        return postJson("/api/stations/" + station + "/transit-arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator,
                "bagTags", bagTags));
    }

    private ResultActions timeoutCheck(String station, String operator, List<String> bagTags)
            throws Exception {
        return postJson("/api/stations/" + station + "/dwell-timeout-check", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator,
                "bagTags", bagTags));
    }

    private ResultActions release(String bagTag, String reviewer, String conclusion, String nextLegId)
            throws Exception {
        return postJson("/api/bags/" + bagTag + "/release", Map.of(
                "requestId", UUID.randomUUID().toString(), "reviewer", reviewer,
                "conclusion", conclusion, "nextLegId", nextLegId));
    }

    private ResultActions reroute(String bagTag, String operator, List<String> newLegIds)
            throws Exception {
        return postJson("/api/bags/" + bagTag + "/reroute", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator,
                "newLegIds", newLegIds));
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
