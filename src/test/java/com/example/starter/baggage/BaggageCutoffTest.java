package com.example.starter.baggage;

import java.time.Instant;
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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.UpdateCutoffRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 航段截载控制测试：覆盖截载时间边界（早于/等于/晚于截载时刻）、批量原子装载、
 * 超截载历史例外清单、改派后新航段截载口径、补到截载判定、并发裁决与幂等指纹。
 * 全部基于 H2 MySQL 兼容模式真实数据库与可注入时钟，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCutoffTest {

    private static final String DEPARTURE = "2026-09-25T12:00:00Z";
    private static final String CUTOFF = "2026-09-25T10:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    /** 可推进的测试时钟。 */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T08:00:00Z"));

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM cutoff_exception");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        now.set(Instant.parse("2026-09-25T08:00:00Z"));
        baggageService.setClock(now::get);
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        baggageService.setClock(Instant::now);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cutoffBoundary_loadAllowedBeforeRejectedAtAndAfterCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF))
                .andExpect(jsonPath("$.newExceptions", hasSize(0)));
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        // 截载前一刻：允许装载
        now.set(Instant.parse("2026-09-25T09:59:59Z"));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 等于截载时刻：拒绝并给出截载时刻
        now.set(Instant.parse(CUTOFF));
        load("LEG1", 3, List.of("BAG2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString(CUTOFF)));
        // 晚于截载时刻：同样拒绝
        now.set(Instant.parse("2026-09-25T10:00:01Z"));
        load("LEG1", 3, List.of("BAG2")).andExpect(status().isUnprocessableEntity());

        // 截载前已装载的行李截载后可查询，但不得重复装载
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        // 被拒绝的行李未移动，容器占用（装载记录）不变
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
    }

    @Test
    void cutoffConfig_requiresDepartureAndCutoffBeforeDeparture() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        // 截载等于起飞 -> 422
        updateCutoff("LEG1", 1, DEPARTURE).andExpect(status().isUnprocessableEntity());
        // 截载晚于起飞 -> 422
        updateCutoff("LEG1", 1, "2026-09-25T13:00:00Z").andExpect(status().isUnprocessableEntity());
        // 时刻格式非法 -> 422
        updateCutoff("LEG1", 1, "not-a-time").andExpect(status().isUnprocessableEntity());
        // 版本冲突 -> 409
        updateCutoff("LEG1", 99, CUTOFF).andExpect(status().isConflict());
        // 航段不存在 -> 404
        updateCutoff("LEG_MISSING", 1, CUTOFF).andExpect(status().isNotFound());
        // 未配置起飞时刻的航段不可设置截载 -> 422
        registerLeg("LEG2", "PEK", "CAN", null).andExpect(status().isCreated());
        updateCutoff("LEG2", 1, CUTOFF).andExpect(status().isUnprocessableEntity());

        // 合法配置成功并推进版本
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG1/cutoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departureTime").value(DEPARTURE))
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG2/cutoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departureTime").doesNotExist())
                .andExpect(jsonPath("$.cutoffTime").doesNotExist());
        mockMvc.perform(get("/api/legs/LEG_MISSING/cutoff")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/LEG_MISSING/cutoff-exceptions")).andExpect(status().isNotFound());
    }

    @Test
    void cutoffUpdate_earlierThanLoadedAtRecordsImmutableExceptions() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        now.set(Instant.parse("2026-09-25T08:00:00Z"));
        load("LEG1", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk());

        // 截载时刻修改为早于既有装载时刻：允许保存并登记例外，原装载记录不变
        updateCutoff("LEG1", 2, "2026-09-25T07:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.newExceptions", hasSize(2)))
                .andExpect(jsonPath("$.newExceptions[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.newExceptions[0].loadedAt").value("2026-09-25T08:00:00Z"))
                .andExpect(jsonPath("$.newExceptions[0].cutoffTime").value("2026-09-25T07:00:00Z"));

        mockMvc.perform(get("/api/legs/LEG1/cutoff-exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions", hasSize(2)))
                .andExpect(jsonPath("$.exceptions[*].bagTag", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.exceptions[*].loadedAt",
                        contains("2026-09-25T08:00:00Z", "2026-09-25T08:00:00Z")));

        // 原装载时刻不得被删除或伪造
        java.time.OffsetDateTime loadedAt = jdbcTemplate.queryForObject(
                "SELECT loaded_at FROM load_record WHERE bag_tag = 'BAG1'",
                java.time.OffsetDateTime.class);
        assertThat(loadedAt.toInstant()).isEqualTo(Instant.parse("2026-09-25T08:00:00Z"));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));

        // 再次提前截载：追加新例外，旧例外保留
        updateCutoff("LEG1", 3, "2026-09-25T06:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.newExceptions", hasSize(2)));
        Integer exceptionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cutoff_exception WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(exceptionCount).isEqualTo(4);

        // 以不同请求重复相同截载时刻：例外清单不重复登记
        updateCutoff("LEG1", 4, "2026-09-25T07:00:00Z").andExpect(status().isOk());
        exceptionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cutoff_exception WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(exceptionCount).isEqualTo(4);
    }

    @Test
    void batchLoad_validatesAllBagsAndCutoffBeforeAnyWrite() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN", null).andExpect(status().isCreated());
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG2"));

        // 批内一件行李待乘航段不符：整批 422，容器占用与交接记录不变
        now.set(Instant.parse("2026-09-25T09:00:00Z"));
        load("LEG1", 2, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);

        // 截载后整批（即便全部合法）也被拒绝且无任何写入
        now.set(Instant.parse("2026-09-25T10:30:00Z"));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString(CUTOFF)));
        records = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
    }

    @Test
    void reassign_thenLoadUsesNewLegCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerLeg("LEG2", "PEK", "CAN", "2026-09-25T20:00:00Z").andExpect(status().isCreated());
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());
        updateCutoff("LEG2", 1, "2026-09-25T18:00:00Z").andExpect(status().isOk());
        registerBag("BAG1", List.of("LEG1"));

        // 当前时刻晚于 LEG1 截载、早于 LEG2 截载：原航段不可装载
        now.set(Instant.parse("2026-09-25T11:00:00Z"));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        // 改派到新航段后，后续装载使用新航段截载时刻
        reassign("BAG1", List.of("LEG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary", hasSize(1)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("LEG2"))
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "REASSIGNED")));
        load("LEG2", 2, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 改派请求同键重放：返回原结果，事件不重复
        String requestId = UUID.randomUUID().toString();
        registerBag("BAG2", List.of("LEG1"));
        Map<String, Object> body = Map.of("requestId", requestId, "legIds", List.of("LEG2"));
        postJson("/api/bags/BAG2/reassign", body).andExpect(status().isOk());
        postJson("/api/bags/BAG2/reassign", body).andExpect(status().isOk());
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG2' AND event_type = 'REASSIGNED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
        // 同键异参 -> 409
        postJson("/api/bags/BAG2/reassign", Map.of("requestId", requestId, "legIds", List.of("LEG1")))
                .andExpect(status().isConflict());
    }

    @Test
    void reassign_rejectsInvalidRequests() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN", null).andExpect(status().isCreated());
        registerLeg("LEG3", "XIY", "CAN", null).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        // 首段始发站与当前所在站不一致 -> 422
        reassign("BAG1", List.of("LEG3")).andExpect(status().isUnprocessableEntity());
        // 引用不存在的航段 -> 422
        reassign("BAG1", List.of("LEG1", "LEG_MISSING")).andExpect(status().isUnprocessableEntity());
        // 相邻航段不衔接 -> 422
        reassign("BAG1", List.of("LEG1", "LEG3")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 404
        reassign("BAG_MISSING", List.of("LEG1")).andExpect(status().isNotFound());

        // 已装载行李禁止改派
        now.set(Instant.parse("2026-09-25T09:00:00Z"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        reassign("BAG1", List.of("LEG1", "LEG2")).andExpect(status().isUnprocessableEntity());

        // 短卸行李须先补到才能改派
        registerBag("BAG2", List.of("LEG1", "LEG2"));
        load("LEG1", 2, List.of("BAG2")).andExpect(status().isOk());
        seal("LEG1", 3).andExpect(status().isOk());
        postJson("/api/legs/LEG1/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", 4,
                "bagTags", List.of("BAG1"))).andExpect(status().isOk());
        reassign("BAG2", List.of("LEG2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recover_checksNextLegCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN", "2026-09-25T23:00:00Z").andExpect(status().isCreated());
        updateCutoff("LEG2", 1, "2026-09-25T20:00:00Z").andExpect(status().isOk());
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        now.set(Instant.parse("2026-09-25T08:00:00Z"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        postJson("/api/legs/LEG1/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", 3,
                "bagTags", List.of())).andExpect(status().isOk());

        // 补到时刻晚于下一待乘航段截载时刻：拒绝并给出截载时刻
        now.set(Instant.parse("2026-09-25T21:00:00Z"));
        recover("BAG1", "LEG1", "SHA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("2026-09-25T20:00:00Z")));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"));

        // 截载前补到成功
        now.set(Instant.parse("2026-09-25T19:00:00Z"));
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
    }

    @Test
    void concurrent_cutoffUpdateAndLoad_versionDecidesSingleOrder() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"));
        now.set(Instant.parse("2026-09-25T09:00:00Z"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, "OP1", "ULD1", List.of("BAG1"))),
                () -> baggageService.updateCutoff("LEG1",
                        new UpdateCutoffRequest(UUID.randomUUID().toString(), 1, "2026-09-25T09:30:00Z")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof LoadResponse
                        || r instanceof com.example.starter.baggage.BaggageDtos.CutoffUpdateResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 无论谁先提交，按事务提交顺序补偿另一操作后，最终状态一致：行李已装载且截载已配置
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        if (results.get(0) instanceof LoadResponse) {
            // 装载先提交：随后以新版本配置截载，装载时刻早于截载，无例外
            updateCutoff("LEG1", 2, "2026-09-25T09:30:00Z").andExpect(status().isOk())
                    .andExpect(jsonPath("$.newExceptions", hasSize(0)));
        } else {
            // 截载先提交：随后以新版本装载，当前时刻早于截载，允许装载
            load("LEG1", 2, List.of("BAG1")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/legs/LEG1/cutoff"))
                .andExpect(jsonPath("$.cutoffTime").value("2026-09-25T09:30:00Z"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void loadIdempotency_fingerprintIncludesOperatorAndContainer() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null).andExpect(status().isCreated());
        registerBag("BAG1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "OP1", "containerId", "ULD1", "bagTags", List.of("BAG1"));
        postJson("/api/legs/LEG1/load", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键同参（含操作者与容器）重放首次完整结果
        postJson("/api/legs/LEG1/load", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键异操作者或异容器 -> 409
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "OP2", "containerId", "ULD1", "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());
        postJson("/api/legs/LEG1/load", Map.of("requestId", requestId, "expectedVersion", 1,
                "operator", "OP1", "containerId", "ULD2", "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());

        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        String operator = jdbcTemplate.queryForObject(
                "SELECT operator FROM load_record WHERE bag_tag = 'BAG1'", String.class);
        String container = jdbcTemplate.queryForObject(
                "SELECT container_id FROM load_record WHERE bag_tag = 'BAG1'", String.class);
        assertThat(operator).isEqualTo("OP1");
        assertThat(container).isEqualTo("ULD1");
    }

    @Test
    void loadStatus_reportsLoadedAndNextLegCutoff() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", DEPARTURE).andExpect(status().isCreated());
        updateCutoff("LEG1", 1, CUTOFF).andExpect(status().isOk());
        registerBag("BAG1", List.of("LEG1"));

        mockMvc.perform(get("/api/bags/BAG1/load-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.loadedAt").doesNotExist())
                .andExpect(jsonPath("$.nextLegId").value("LEG1"))
                .andExpect(jsonPath("$.nextLegCutoffTime").value(CUTOFF));

        now.set(Instant.parse("2026-09-25T09:00:00Z"));
        load("LEG1", 2, List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/load-status"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.loadedAt").value("2026-09-25T09:00:00Z"));

        mockMvc.perform(get("/api/bags/BAG_MISSING/load-status")).andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      String departureTime) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        if (departureTime != null) {
            body.put("departureTime", departureTime);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion,
                "operator", "OP1", "containerId", "ULD1",
                "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions updateCutoff(String legId, int expectedVersion, String cutoffTime)
            throws Exception {
        return postJson("/api/legs/" + legId + "/cutoff", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "cutoffTime", cutoffTime));
    }

    private ResultActions reassign(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags/" + bagTag + "/reassign", Map.of(
                "requestId", UUID.randomUUID().toString(), "legIds", legIds));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation)
            throws Exception {
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
        List<Future<Object>> futures = new java.util.ArrayList<>();
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
        List<Object> results = new java.util.ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
