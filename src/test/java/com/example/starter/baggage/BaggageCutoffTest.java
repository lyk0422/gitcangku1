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

import com.example.starter.baggage.BaggageDtos.CutoffResponse;
import com.example.starter.baggage.BaggageDtos.CutoffUpdateRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
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
 * 航段截载控制测试：覆盖截载时刻边界（等于/晚于截载时刻 422）、批量原子装载回滚、
 * 容器占用与释放、超截载例外清单（不可变、不伪造原装载时刻）、剩余行程改派口径、
 * 补到截载判定、截载修改与装载并发裁决以及幂等指纹边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库与可注入时钟，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCutoffTest {

    private static final Instant BASE_NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final Instant FAR_DEPARTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-09-26T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private final AtomicReference<Instant> now = new AtomicReference<>(BASE_NOW);

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM container_occupancy");
        jdbcTemplate.update("DELETE FROM cutoff_exception");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        now.set(BASE_NOW);
        baggageService.setClock(now::get);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        baggageService.setClock(Instant::now);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void updateCutoff_validatesVersionDepartureAndUnknownLeg() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", Instant.parse("2026-09-27T00:00:00Z"));

        // 版本冲突 -> 409
        updateCutoff("LEG1", 99, CUTOFF).andExpect(status().isConflict());
        // 截载时刻等于起飞时刻 -> 422
        updateCutoff("LEG1", 1, Instant.parse("2026-09-27T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
        // 截载时刻晚于起飞时刻 -> 422
        updateCutoff("LEG1", 1, Instant.parse("2026-09-27T00:00:01Z"))
                .andExpect(status().isUnprocessableEntity());
        // 航段不存在 -> 404
        updateCutoff("LEG_MISSING", 1, CUTOFF).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/LEG_MISSING/cutoff")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/LEG_MISSING/cutoff-exceptions")).andExpect(status().isNotFound());

        // 合法配置成功并递增版本
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk())
                .andExpect(jsonPath("$.legId").value("LEG1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.departureAt").value("2026-09-27T00:00:00Z"))
                .andExpect(jsonPath("$.cutoffAt").value(CUTOFF.toString()));

        mockMvc.perform(get("/api/legs/LEG1/cutoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffAt").value(CUTOFF.toString()))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions", hasSize(0)));
    }

    @Test
    void load_cutoffBoundaryRejectsAtAndAfterCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", Instant.parse("2026-09-27T00:00:00Z"));
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());

        // 截载前一刻：可装载
        now.set(CUTOFF.minusSeconds(1));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 当前时刻等于截载时刻：422 且给出截载时刻
        now.set(CUTOFF);
        load("LEG1", 3, List.of("BAG2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(CUTOFF.toString())));
        // 当前时刻晚于截载时刻：422
        now.set(CUTOFF.plusSeconds(1));
        load("LEG1", 3, List.of("BAG2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(CUTOFF.toString())));

        // 回到截载前：可装载
        now.set(CUTOFF.minusSeconds(1));
        load("LEG1", 3, List.of("BAG2")).andExpect(status().isOk());
    }

    @Test
    void recover_cutoffBoundaryAppliesToMissingLeg() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", Instant.parse("2026-09-27T00:00:00Z"));
        registerLeg("LEG2", "SHA", "CAN", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());

        now.set(CUTOFF.minusSeconds(1));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 3).andExpect(status().isOk());
        diffArrive("LEG1", 4, List.of()).andExpect(status().isOk());

        // 当前时刻等于缺失航段截载时刻：补到 422 且给出截载时刻
        now.set(CUTOFF);
        recover("BAG1", "LEG1", "SHA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(CUTOFF.toString())));
        // 截载前：补到成功
        now.set(CUTOFF.minusSeconds(1));
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
    }

    @Test
    void load_batchAtomicWhenCutoffPassed_noRecordNoOccupancyNoVersionBump() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());

        now.set(CUTOFF.plusSeconds(1));
        load("LEG1", 2, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());

        // 整单回滚：交接记录、容器占用、航段版本均不变
        assertThat(countRows("load_record")).isZero();
        assertThat(countRows("container_occupancy")).isZero();
        assertThat(legVersion("LEG1")).isEqualTo(2);
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
    }

    @Test
    void container_occupiedByOtherLegRejectsAndReleasedAfterArrival() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerLeg("LEG2", "PEK", "XIY", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG2"));

        load("LEG1", 1, "ULD-9", List.of("BAG1")).andExpect(status().isOk());
        // 同一容器被未到达航段 LEG1 占用：LEG2 整批 422 且不移动
        load("LEG2", 1, "ULD-9", List.of("BAG2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("ULD-9")));
        assertThat(legVersion("LEG2")).isEqualTo(1);
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // LEG1 到达后容器释放，LEG2 可复用
        seal("LEG1", 2).andExpect(status().isOk());
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        assertThat(countRows("container_occupancy")).isZero();
        load("LEG2", 1, "ULD-9", List.of("BAG2")).andExpect(status().isOk());
    }

    @Test
    void cutoffException_recordedForOverCutoffLoadsAndLoadTimeUntouched() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        Instant t1 = Instant.parse("2026-09-26T01:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T02:00:00Z");
        now.set(t1);
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        now.set(t2);
        load("LEG1", 2, List.of("BAG2")).andExpect(status().isOk());

        // 截载时刻介于两次装载之间：仅 BAG2 超截载，写入例外清单
        Instant cutoffMid = Instant.parse("2026-09-26T01:30:00Z");
        updateCutoff("LEG1", 3, cutoffMid).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andExpect(jsonPath("$.exceptions[0].bagTag").value("BAG2"))
                .andExpect(jsonPath("$.exceptions[0].loadedAt").value(t2.toString()))
                .andExpect(jsonPath("$.exceptions[0].cutoffAt").value(cutoffMid.toString()));

        // 原装载时刻不可变：交接记录仍是 t1/t2
        assertThat(loadedAtOf("BAG1")).isEqualTo(t1);
        assertThat(loadedAtOf("BAG2")).isEqualTo(t2);

        // 截载时刻再提早到两次装载之前：两件都登记例外
        Instant cutoffEarly = Instant.parse("2026-09-26T00:30:00Z");
        updateCutoff("LEG1", 4, cutoffEarly).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(jsonPath("$.exceptions", hasSize(3)));
        // 相同截载时刻再次保存（新 requestId）：例外清单不重复登记
        updateCutoff("LEG1", 5, cutoffEarly).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(jsonPath("$.exceptions", hasSize(3)));
        // 原装载时刻始终未被伪造
        assertThat(loadedAtOf("BAG1")).isEqualTo(t1);
        assertThat(loadedAtOf("BAG2")).isEqualTo(t2);
    }

    @Test
    void loadedBagBeforeCutoff_queryableAfterCutoffButNotReloadable() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        Instant t1 = Instant.parse("2026-09-26T01:00:00Z");
        now.set(t1);
        load("LEG1", 1, "ULD-7", List.of("BAG1")).andExpect(status().isOk());

        // 截载时刻早于已装载时刻：允许保存（登记例外），既有装载记录不变
        Instant cutoffPast = Instant.parse("2026-09-26T00:30:00Z");
        updateCutoff("LEG1", 2, cutoffPast).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andExpect(jsonPath("$.exceptions[0].bagTag").value("BAG1"));

        // 截载后已装载行李仍可查询
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/bags/BAG1/load-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.containerId").value("ULD-7"))
                .andExpect(jsonPath("$.operator").value("tester"))
                .andExpect(jsonPath("$.loadedAt").value(t1.toString()))
                .andExpect(jsonPath("$.loadedLegCutoffAt").value(cutoffPast.toString()));

        // 但不得通过普通装载接口重复写入
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        assertThat(countRows("load_record")).isEqualTo(1);
    }

    @Test
    void reroute_replacesItineraryAndUsesNewLegCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerLeg("LEG_ALT", "PEK", "CAN", Instant.parse("2026-12-01T00:00:00Z"));
        registerBag("BAG1", List.of("LEG1"));

        // 新航段截载时刻已过：改派后装载被新航段截载拦截
        Instant pastCutoff = Instant.parse("2026-06-01T00:00:00Z");
        updateCutoff("LEG_ALT", 1, pastCutoff).andExpect(status().isOk());
        reroute("BAG1", List.of("LEG_ALT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary", hasSize(1)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("LEG_ALT"))
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "REROUTED")));
        load("LEG_ALT", 2, List.of("BAG1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(pastCutoff.toString())));

        // 新航段截载时刻推迟到当前之后：装载成功（与旧航段 LEG1 无关）
        updateCutoff("LEG_ALT", 2, Instant.parse("2026-11-30T00:00:00Z")).andExpect(status().isOk());
        load("LEG_ALT", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 改派幂等：同键同参重放，REROUTED 事件只追加一次
        registerLeg("LEG2", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG2", List.of("LEG2"));
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "legIds", List.of("LEG_ALT"));
        postJson("/api/bags/BAG2/reroute", body).andExpect(status().isOk());
        postJson("/api/bags/BAG2/reroute", body).andExpect(status().isOk());
        Integer reroutedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG2' AND event_type = 'REROUTED'",
                Integer.class);
        assertThat(reroutedEvents).isEqualTo(1);
        // 同键异参（不同航段集合）-> 409
        postJson("/api/bags/BAG2/reroute", Map.of("requestId", requestId, "legIds", List.of("LEG2")))
                .andExpect(status().isConflict());
    }

    @Test
    void reroute_rejectsInvalidStatesAndItineraries() throws Exception {
        registerLeg("LEG_A", "PEK", "SHA", FAR_DEPARTURE);
        registerLeg("LEG_B", "PEK", "SHA", FAR_DEPARTURE);
        registerLeg("LEG_C", "PEK", "SHA", FAR_DEPARTURE);
        registerLeg("LEG_X", "XIY", "CAN", FAR_DEPARTURE);
        registerLeg("LEG_R", "PEK", "CAN", FAR_DEPARTURE);

        // 已装载行李不得改派
        registerBag("BAG_LOAD", List.of("LEG_A"));
        load("LEG_A", 1, List.of("BAG_LOAD")).andExpect(status().isOk());
        reroute("BAG_LOAD", List.of("LEG_R")).andExpect(status().isUnprocessableEntity());

        // 短卸行李须先补到才能改派
        registerBag("BAG_SHORT", List.of("LEG_B"));
        load("LEG_B", 1, List.of("BAG_SHORT")).andExpect(status().isOk());
        seal("LEG_B", 2).andExpect(status().isOk());
        diffArrive("LEG_B", 3, List.of()).andExpect(status().isOk());
        reroute("BAG_SHORT", List.of("LEG_R")).andExpect(status().isUnprocessableEntity());

        // 已交付行李不得改派
        registerBag("BAG_DONE", List.of("LEG_C"));
        load("LEG_C", 1, List.of("BAG_DONE")).andExpect(status().isOk());
        seal("LEG_C", 2).andExpect(status().isOk());
        arrive("LEG_C", List.of("BAG_DONE")).andExpect(status().isOk());
        reroute("BAG_DONE", List.of("LEG_R")).andExpect(status().isUnprocessableEntity());

        // 首段始发站须等于当前所在站 / 航段须存在 / 不得重复
        registerBag("BAG_OK", List.of("LEG_A"));
        reroute("BAG_OK", List.of("LEG_X")).andExpect(status().isUnprocessableEntity());
        reroute("BAG_OK", List.of("LEG_MISSING")).andExpect(status().isUnprocessableEntity());
        reroute("BAG_OK", List.of("LEG_R", "LEG_R")).andExpect(status().isUnprocessableEntity());
        reroute("BAG_MISSING", List.of("LEG_R")).andExpect(status().isNotFound());

        // 合法改派成功，剩余行程被替换
        reroute("BAG_OK", List.of("LEG_R")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary", hasSize(1)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("LEG_R"));
    }

    @Test
    void concurrentCutoffUpdateAndLoad_singleWinnerByVersion() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        Instant cutoff = Instant.parse("2098-06-01T00:00:00Z");

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.updateCutoff("LEG1",
                        new CutoffUpdateRequest(UUID.randomUUID().toString(), 1, cutoff)),
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1,
                                "tester", "ULD-C", List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof CutoffResponse || r instanceof LoadResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        // 按事务提交顺序裁决：恰有一个成功，另一个版本冲突
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(legVersion("LEG1")).isEqualTo(2);

        if (results.stream().anyMatch(LoadResponse.class::isInstance)) {
            // 装载先提交：截载未配置，交接记录存在
            assertThat(countRows("load_record")).isEqualTo(1);
            assertThat(baggageService.getCutoff("LEG1").cutoffAt()).isNull();
        } else {
            // 截载先提交：装载未发生
            assertThat(countRows("load_record")).isZero();
            assertThat(baggageService.getCutoff("LEG1").cutoffAt()).isEqualTo(cutoff.toString());
        }
    }

    @Test
    void concurrentSameRequestIdCutoffUpdate_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        Instant cutoff = Instant.parse("2098-06-01T00:00:00Z");
        String requestId = UUID.randomUUID().toString();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.updateCutoff("LEG1",
                    new CutoffUpdateRequest(requestId, 1, cutoff)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(CutoffResponse.class);
            assertThat(((CutoffResponse) result).version()).isEqualTo(2);
            assertThat(((CutoffResponse) result).cutoffAt()).isEqualTo(cutoff.toString());
        });
        assertThat(legVersion("LEG1")).isEqualTo(2);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void idempotency_loadFingerprintAndCutoffFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));

        // loadKey 指纹含操作者与容器：同键改容器或操作者均 409
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> loadBody = Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "alice", "containerId", "ULD-1", "bagTags", List.of("BAG1"));
        postJson("/api/legs/LEG1/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "alice", "containerId", "ULD-2", "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "bob", "containerId", "ULD-1", "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());
        // 同键同参重放首次完整结果
        postJson("/api/legs/LEG1/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(legVersion("LEG1")).isEqualTo(2);

        // 截载配置失败不占键：先以不合法截载时刻 422，再同键合法参数成功
        String cutoffKey = UUID.randomUUID().toString();
        postJson("/api/legs/LEG1/cutoff", Map.of("requestId", cutoffKey,
                "expectedVersion", 2, "cutoffAt", "2099-06-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/legs/LEG1/cutoff", Map.of("requestId", cutoffKey,
                "expectedVersion", 2, "cutoffAt", "2098-06-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.cutoffAt").value("2098-06-01T00:00:00Z"));
    }

    @Test
    void loadStatus_returnsHandoverDetailsAndHandlesUnloadedAndUnknown() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", FAR_DEPARTURE);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        updateCutoff("LEG1", 1, Instant.parse("2098-06-01T00:00:00Z")).andExpect(status().isOk());

        Instant t1 = Instant.parse("2026-09-26T01:00:00Z");
        now.set(t1);
        load("LEG1", 2, "ULD-7", List.of("BAG1")).andExpect(status().isOk());

        mockMvc.perform(get("/api/bags/BAG1/load-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("BAG1"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.containerId").value("ULD-7"))
                .andExpect(jsonPath("$.operator").value("tester"))
                .andExpect(jsonPath("$.loadedAt").value(t1.toString()))
                .andExpect(jsonPath("$.loadedLegCutoffAt").value("2098-06-01T00:00:00Z"));

        // 未装载行李：装载字段均为空
        mockMvc.perform(get("/api/bags/BAG2/load-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.containerId").doesNotExist())
                .andExpect(jsonPath("$.loadedAt").doesNotExist());
        // 行李不存在 -> 404
        mockMvc.perform(get("/api/bags/NOPE/load-status")).andExpect(status().isNotFound());
    }

    private void registerLeg(String legId, String origin, String destination, Instant departureAt) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination,
                        departureAt));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return load(legId, expectedVersion, "ULD-" + legId, bagTags);
    }

    private ResultActions load(String legId, int expectedVersion, String containerId,
                               List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion,
                "operator", "tester", "containerId", containerId,
                "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation)
            throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions updateCutoff(String legId, int expectedVersion, Instant cutoffAt)
            throws Exception {
        return postJson("/api/legs/" + legId + "/cutoff", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "cutoffAt", cutoffAt.toString()));
    }

    private ResultActions reroute(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags/" + bagTag + "/reroute", Map.of(
                "requestId", UUID.randomUUID().toString(), "legIds", legIds));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int legVersion(String legId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
        return version == null ? -1 : version;
    }

    private Instant loadedAtOf(String bagTag) {
        java.time.OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT loaded_at FROM load_record WHERE bag_tag = ?",
                java.time.OffsetDateTime.class, bagTag);
        return value == null ? null : value.toInstant();
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
