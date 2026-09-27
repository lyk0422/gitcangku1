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

import com.example.starter.baggage.BaggageDtos.DwellCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellCheckResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李滞留监控测试：覆盖配置、到达证据、超时判定（区间左闭右开）、解除阻断主流程，
 * 缺配置/到达时刻缺失/下一航段非法等 422 分支，批量核对整次回滚，解除时截止与容量重新校验，
 * 证据不可变、幂等重放与并发提交顺序。全部基于 H2 MySQL 兼容模式真实数据库，时钟可注入。
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

    /** 可控时钟：测试按分钟推进，避免真实等待。 */
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM dwell_record");
        jdbcTemplate.update("DELETE FROM dwell_config");
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
        baggageService.setClock(Instant::now);
    }

    @Test
    void mainFlow_arriveCheckExceedReleaseThenLoadAndDeliver() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated())
                .andExpect(jsonPath("$.station").value("SHA"))
                .andExpect(jsonPath("$.maxDwellMinutes").value(60));
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());

        // 到达中转点：记录滞留起点与到达证据
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.dwellStation").value("SHA"))
                .andExpect(jsonPath("$.dwellArrivedAt").value(T0.toString()));
        mockMvc.perform(get("/api/bags/BAG1/dwell"))
                .andExpect(jsonPath("$.station").value("SHA"))
                .andExpect(jsonPath("$.arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.dwellMinutes").value(0))
                .andExpect(jsonPath("$.maxDwellMinutes").value(60));
        mockMvc.perform(get("/api/bags/BAG1/dwell-records"))
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].recordType").value("ARRIVAL"))
                .andExpect(jsonPath("$.records[0].station").value("SHA"))
                .andExpect(jsonPath("$.records[0].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.records[0].recordedAt").value(T0.toString()))
                .andExpect(jsonPath("$.records[0].dwellMinutes").doesNotExist())
                .andExpect(jsonPath("$.records[0].reviewer").doesNotExist());

        // 区间左闭右开：59 分钟未超时
        now.set(T0.plusSeconds(59 * 60));
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.results[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.results[0].dwellMinutes").value(59))
                .andExpect(jsonPath("$.results[0].maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.results[0].exceeded").value(false));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 达到上限即超时：转 DWELL_EXCEEDED 并追加超时证据
        now.set(T0.plusSeconds(60 * 60));
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").value(now.get().toString()))
                .andExpect(jsonPath("$.results[0].dwellMinutes").value(60))
                .andExpect(jsonPath("$.results[0].exceeded").value(true));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "DWELL_EXCEEDED")));
        mockMvc.perform(get("/api/stations/SHA/dwell-exceeded"))
                .andExpect(jsonPath("$.exceeded", hasSize(1)))
                .andExpect(jsonPath("$.exceeded[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.exceeded[0].arrivedAt").value(T0.toString()));

        // 阻断后续装载与改派：均 409
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isConflict());
        postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "SHA"))
                .andExpect(status().isConflict());

        // 登记复核结论并指定下一航段后解除
        dwellRelease("BAG1", "OP1", "复核正常，可继续装运", "LEG2").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.reviewer").value("OP1"))
                .andExpect(jsonPath("$.releasedAt").value(now.get().toString()));
        mockMvc.perform(get("/api/stations/SHA/dwell-exceeded"))
                .andExpect(jsonPath("$.exceeded", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG1/dwell-records"))
                .andExpect(jsonPath("$.records[*].recordType",
                        contains("ARRIVAL", "EXCEEDED", "RELEASED")))
                .andExpect(jsonPath("$.records[1].dwellMinutes").value(60))
                .andExpect(jsonPath("$.records[1].maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.records[1].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.records[2].nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.records[2].reviewer").value("OP1"))
                .andExpect(jsonPath("$.records[2].reviewNote").value("复核正常，可继续装运"));

        // 解除后可正常装载、封舱、到达并交付，滞留信息清空
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.dwellStation").doesNotExist())
                .andExpect(jsonPath("$.dwellArrivedAt").doesNotExist());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(recordCount).isEqualTo(3);
    }

    @Test
    void dwellCheck_missingConfigMissingArrivalOrUnknownBagReturns422() throws Exception {
        // 中转点缺配置 -> 422
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        configDwell("PEK", 30).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        // 行李登记在始发站、从未到达中转点：到达时刻缺失 -> 422
        dwellCheck("PEK", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        dwellCheck("PEK", List.of("BAG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 请求内袋号重复 -> 422
        dwellCheck("PEK", List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());
        // 配置上限非正数 -> 400（参数校验）
        postJson("/api/dwell-configs", Map.of("requestId", UUID.randomUUID().toString(),
                "station", "XIY", "maxDwellMinutes", 0)).andExpect(status().isBadRequest());

        // 全部失败均不产生证据与状态变化
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class);
        assertThat(recordCount).isZero();
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    @Test
    void dwellCheck_batchRollsBackWhenAnyBagIllegal() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        // BAG1 在中转点等待，BAG2 已交付
        now.set(T0.plusSeconds(60 * 60));

        // 批量含已交付行李：整次 422，合法行李也不转超时
        dwellCheck("SHA", List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());
        // 行李不在该中转点：同样整次回滚
        dwellCheck("CAN", List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        Integer exceededCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type = 'EXCEEDED'", Integer.class);
        assertThat(exceededCount).isZero();
        // 仅有 BAG1 的一条到达证据（BAG2 到达即交付不产生滞留证据），无半成品
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class);
        assertThat(recordCount).isEqualTo(1);

        // 单独核对合法行李可成功
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].exceeded").value(true));
    }

    @Test
    void dwellRelease_requiresItineraryNextLegAndRevalidatesCutoffAndCapacity() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerLeg("LEG3", "SHA", "XIY");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        registerBag("BAG2", List.of("LEG1", "LEG3"));
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isOk());
        now.set(T0.plusSeconds(60 * 60));
        dwellCheck("SHA", List.of("BAG1", "BAG2")).andExpect(status().isOk());

        // 下一航段不属于行程待乘航段 -> 422
        dwellRelease("BAG1", "OP1", "复核", "LEG1").andExpect(status().isUnprocessableEntity());
        dwellRelease("BAG1", "OP1", "复核", "LEG3").andExpect(status().isUnprocessableEntity());
        dwellRelease("BAG1", "OP1", "复核", "LEG_MISSING").andExpect(status().isUnprocessableEntity());
        // 非阻断状态行李不可解除 -> 422；行李不存在 -> 404
        registerBag("BAG9", List.of("LEG2"));
        dwellRelease("BAG9", "OP1", "复核", "LEG2").andExpect(status().isUnprocessableEntity());
        dwellRelease("BAG_MISSING", "OP1", "复核", "LEG2").andExpect(status().isNotFound());

        // 截止校验：下一航段已封舱 -> 422
        seal("LEG2", 1).andExpect(status().isOk());
        dwellRelease("BAG1", "OP1", "复核", "LEG2").andExpect(status().isUnprocessableEntity());

        // 容量校验：下一航段已装满 20 件（达上限）-> 422，报文含实际值与要求值
        List<String> fillers = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String tag = String.format("FILL%02d", i);
            registerBag(tag, List.of("LEG3"));
            fillers.add(tag);
        }
        load("LEG3", 1, fillers).andExpect(status().isOk());
        dwellRelease("BAG2", "OP1", "复核", "LEG3").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已装载 20 件，上限 20 件")));

        // 全部失败不改变阻断状态
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));
        Integer releasedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type = 'RELEASED'", Integer.class);
        assertThat(releasedCount).isZero();
    }

    @Test
    void idempotency_replayConflictAndFailureNotOccupyingKey() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(60 * 60));

        // 核对同键同参重放：返回首次成功响应快照，证据不重复
        String checkId = UUID.randomUUID().toString();
        Map<String, Object> checkBody = Map.of(
                "requestId", checkId, "bagTags", List.of("BAG1"));
        String firstBody = postJson("/api/stations/SHA/dwell-check", checkBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].exceeded").value(true))
                .andReturn().getResponse().getContentAsString();
        postJson("/api/stations/SHA/dwell-check", checkBody)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(firstBody));
        Integer exceededCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type = 'EXCEEDED'", Integer.class);
        assertThat(exceededCount).isEqualTo(1);

        // 同键异参（集合元素不同）-> 409：指纹覆盖规范化集合
        postJson("/api/stations/SHA/dwell-check", Map.of(
                "requestId", checkId, "bagTags", List.of("BAG1", "BAG2")))
                .andExpect(status().isConflict());

        // 解除失败不占键：先以错误航段失败，再同键正确参数成功
        String releaseId = UUID.randomUUID().toString();
        postJson("/api/bags/BAG1/dwell-release", Map.of("requestId", releaseId,
                "reviewer", "OP1", "reviewNote", "复核", "nextLegId", "LEG1"))
                .andExpect(status().isUnprocessableEntity());
        Map<String, Object> releaseBody = Map.of("requestId", releaseId,
                "reviewer", "OP1", "reviewNote", "复核正常", "nextLegId", "LEG2");
        postJson("/api/bags/BAG1/dwell-release", releaseBody).andExpect(status().isOk());
        // 同键同参重放原结果，解除证据只追加一次
        postJson("/api/bags/BAG1/dwell-release", releaseBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewer").value("OP1"));
        Integer releasedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type = 'RELEASED'", Integer.class);
        assertThat(releasedCount).isEqualTo(1);
        // 同键异参（改复核人）-> 409
        postJson("/api/bags/BAG1/dwell-release", Map.of("requestId", releaseId,
                "reviewer", "OP2", "reviewNote", "复核正常", "nextLegId", "LEG2"))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentCheckAndLoad_submissionOrderDecides() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(60 * 60));

        // 超时判定与装载真实并发：按提交顺序裁决，只允许两种一致结果
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.checkDwell("SHA", new DwellCheckRequest(
                        UUID.randomUUID().toString(), List.of("BAG1"))),
                () -> baggageService.load("LEG2", new LoadRequest(
                        UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        boolean checkSucceeded = results.stream().anyMatch(DwellCheckResponse.class::isInstance);
        boolean loadSucceeded = results.stream().anyMatch(LoadResponse.class::isInstance);
        assertThat(checkSucceeded).isNotEqualTo(loadSucceeded);

        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        Integer exceededRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type = 'EXCEEDED'", Integer.class);
        if (checkSucceeded) {
            // 超时先提交：装载 409，行李阻断且无装载记录
            assertThat(bagStatus).isEqualTo("DWELL_EXCEEDED");
            assertThat(loadRecords).isZero();
            assertThat(exceededRecords).isEqualTo(1);
            results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .forEach(ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
        } else {
            // 装载先提交：行李已离站，核对 422（不在中转点等待）
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(loadRecords).isEqualTo(1);
            assertThat(exceededRecords).isZero();
            results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .forEach(ex -> assertThat(ex.getStatus().value()).isEqualTo(422));
        }
    }

    @Test
    void history_immutableAcrossReleaseReExceedAndSubsequentLegs() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());

        now.set(T0.plusSeconds(60 * 60));
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk());
        dwellRelease("BAG1", "OP1", "首次复核放行", "LEG2").andExpect(status().isOk());

        // 解除后仍未离站：再次超时追加新证据，历史不被覆盖
        now.set(T0.plusSeconds(120 * 60));
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].dwellMinutes").value(120))
                .andExpect(jsonPath("$.results[0].exceeded").value(true));
        mockMvc.perform(get("/api/bags/BAG1/dwell-records"))
                .andExpect(jsonPath("$.records[*].recordType",
                        contains("ARRIVAL", "EXCEEDED", "RELEASED", "EXCEEDED")))
                .andExpect(jsonPath("$.records[0].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.records[1].dwellMinutes").value(60))
                .andExpect(jsonPath("$.records[1].maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.records[2].reviewer").value("OP1"))
                .andExpect(jsonPath("$.records[2].reviewNote").value("首次复核放行"))
                .andExpect(jsonPath("$.records[3].dwellMinutes").value(120))
                .andExpect(jsonPath("$.records[3].arrivedAt").value(T0.toString()));

        // 再次解除并完成后续航段：后续航段变更不改写既有超时证据
        dwellRelease("BAG1", "OP2", "二次复核放行", "LEG2").andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(get("/api/bags/BAG1/dwell-records"))
                .andExpect(jsonPath("$.records[*].recordType",
                        contains("ARRIVAL", "EXCEEDED", "RELEASED", "EXCEEDED", "RELEASED")))
                .andExpect(jsonPath("$.records[1].dwellMinutes").value(60))
                .andExpect(jsonPath("$.records[1].maxDwellMinutes").value(60))
                .andExpect(jsonPath("$.records[1].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.records[2].reviewer").value("OP1"))
                .andExpect(jsonPath("$.records[4].reviewer").value("OP2"))
                .andExpect(jsonPath("$.records[4].nextLegId").value("LEG2"));
    }

    @Test
    void reads_doNotChangeState() throws Exception {
        configDwell("SHA", 60).andExpect(status().isCreated());
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        now.set(T0.plusSeconds(60 * 60));
        dwellCheck("SHA", List.of("BAG1")).andExpect(status().isOk());

        Integer logsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log", Integer.class);
        Integer recordsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class);

        // 重复读取诊断、历史与清单：结果一致且不改状态
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/bags/BAG1/dwell"))
                    .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"))
                    .andExpect(jsonPath("$.station").value("SHA"))
                    .andExpect(jsonPath("$.dwellMinutes").value(60))
                    .andExpect(jsonPath("$.maxDwellMinutes").value(60));
            mockMvc.perform(get("/api/bags/BAG1/dwell-records"))
                    .andExpect(jsonPath("$.records", hasSize(2)));
            mockMvc.perform(get("/api/stations/SHA/dwell-exceeded"))
                    .andExpect(jsonPath("$.exceeded", hasSize(1)));
        }
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DWELL_EXCEEDED"));
        Integer logsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log", Integer.class);
        Integer recordsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class);
        assertThat(logsAfter).isEqualTo(logsBefore);
        assertThat(recordsAfter).isEqualTo(recordsBefore);

        // 未知行李的读取 -> 404
        mockMvc.perform(get("/api/bags/NOPE/dwell")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/dwell-records")).andExpect(status().isNotFound());
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private ResultActions configDwell(String station, int maxDwellMinutes) throws Exception {
        return postJson("/api/dwell-configs", Map.of("requestId", UUID.randomUUID().toString(),
                "station", station, "maxDwellMinutes", maxDwellMinutes));
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

    private ResultActions dwellCheck(String station, List<String> bagTags) throws Exception {
        return postJson("/api/stations/" + station + "/dwell-check", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions dwellRelease(String bagTag, String reviewer, String reviewNote,
                                       String nextLegId) throws Exception {
        return postJson("/api/bags/" + bagTag + "/dwell-release", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "reviewer", reviewer, "reviewNote", reviewNote, "nextLegId", nextLegId));
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
