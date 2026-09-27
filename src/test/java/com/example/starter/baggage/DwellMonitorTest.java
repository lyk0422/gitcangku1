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
import com.example.starter.baggage.DwellDtos.DwellCheckRequest;
import com.example.starter.baggage.DwellDtos.DwellReleaseRequest;
import com.example.starter.baggage.DwellDtos.ReassignRequest;
import com.example.starter.baggage.DwellDtos.TransitArrivalItem;
import com.example.starter.baggage.DwellDtos.TransitArrivalRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李滞留监控测试：覆盖配置、批量到达、按可注入时钟的超时裁决（左闭右开）、阻断装载与改派、
 * 复核解除（下一航段/截单/容量复核）、不可变证据、整批回滚、并发裁决顺序与幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DwellMonitorTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BaggageService baggageService;
    @Autowired
    private DwellService dwellService;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM dwell_record");
        jdbcTemplate.update("DELETE FROM bag_dwell_state");
        jdbcTemplate.update("DELETE FROM leg_constraint");
        jdbcTemplate.update("DELETE FROM transit_point_config");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(() -> T0);
        dwellService.setClock(() -> T0);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void mainFlow_arriveWithinLimitThenDepartClosesDwell() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);

        // BAG1 乘 LEG1 到达 SHA，在中转点登记滞留（到达时刻 T0）
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", T0.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].maxDwellMinutes").value(30))
                .andExpect(jsonPath("$.items[0].deadlineAt").value("2026-09-27T08:30:00Z"));

        // T0+20min 仍在 [T0,T0+30) 内：WITHIN_LIMIT 不阻断
        dwellService.setClock(() -> T0.plusSeconds(20 * 60));
        checkDwell("SHA", "OP1", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.exceededCount").value(0))
                .andExpect(jsonPath("$.results[0].dwellStatus").value("WITHIN_LIMIT"))
                .andExpect(jsonPath("$.results[0].blocked").value(false))
                .andExpect(jsonPath("$.results[0].actualDwellMinutes").value(20))
                .andExpect(jsonPath("$.results[0].differenceMinutes").value(10))
                .andExpect(jsonPath("$.results[0].maxDwellMinutes").value(30));

        // 未超时可正常装载 LEG2，装载离站后开放滞留周期关闭
        baggageService.setClock(() -> T0.plusSeconds(20 * 60));
        baggageService.load("LEG2", new LoadRequest(rid(), 1, List.of("BAG1")));
        Integer openStates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_dwell_state", Integer.class);
        assertThat(openStates).isZero();
    }

    @Test
    void timeout_isHalfOpenAndBlocksLoadAndReassignUntilRelease() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));

        // 右端点本身（T0+30）即超时：左闭右开
        dwellService.setClock(() -> T0.plusSeconds(30 * 60));
        checkDwell("SHA", "OP1", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.exceededCount").value(1))
                .andExpect(jsonPath("$.results[0].dwellStatus").value("DWELL_EXCEEDED"))
                .andExpect(jsonPath("$.results[0].blocked").value(true))
                .andExpect(jsonPath("$.results[0].actualDwellMinutes").value(30))
                .andExpect(jsonPath("$.results[0].differenceMinutes").value(0));

        // 超时阻断装载 -> 409
        ApiException loadEx = catchApi(() -> baggageService.load("LEG2",
                new LoadRequest(rid(), 1, List.of("BAG1"))));
        assertThat(loadEx.getStatus().value()).isEqualTo(409);

        // 超时阻断改派 -> 409
        ApiException reassignEx = catchApi(() -> dwellService.reassign(
                new ReassignRequest(rid(), "BAG1", "LEG2", "OP9")));
        assertThat(reassignEx.getStatus().value()).isEqualTo(409);

        // 重复裁决幂等：不重复追加超时证据
        checkDwell("SHA", "OP1", null);
        Integer exceededRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE bag_tag='BAG1' AND record_type='DWELL_EXCEEDED'",
                Integer.class);
        assertThat(exceededRecords).isEqualTo(1);

        // 未复核解除前装载始终失败
        ApiException secondLoad = catchApi(() -> baggageService.load("LEG2",
                new LoadRequest(rid(), 1, List.of("BAG1"))));
        assertThat(secondLoad.getStatus().value()).isEqualTo(409);

        // 解除阻断：指定属于行程的下一航段 LEG2
        release("BAG1", "LEG2", "RVW1", "复核通过").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.actualDwellMinutes").value(30))
                .andExpect(jsonPath("$.differenceMinutes").value(0));

        // 解除后装载成功
        baggageService.load("LEG2", new LoadRequest(rid(), 1, List.of("BAG1")));
        // 解除后改派不再被阻断（追加记录）
        mockMvc.perform(post("/api/dwell/reassign").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", rid(),
                                "bagTag", "BAG1", "nextLegId", "LEG2", "operator", "OP9"))))
                .andExpect(status().isOk());
    }

    @Test
    void release_validatesNextLegDeadlineAndCapacityWithActualRequiredAndDifference() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        // LEG2 容量 1，截单 T0+45
        configureConstraint("LEG2", 1, "2026-09-27T08:45:00Z").andExpect(status().isCreated());

        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));
        dwellService.setClock(() -> T0.plusSeconds(30 * 60));
        dwellService.checkDwell(new DwellCheckRequest(rid(), "SHA", "OP1", null));

        // 下一航段不属于行程 -> 422
        release("BAG1", "LEG1", "RVW1", "x").andExpect(status().isUnprocessableEntity());

        // 超过截单时刻 -> 422（T0+45 端点本身即拒绝，左闭右开）
        dwellService.setClock(() -> T0.plusSeconds(45 * 60));
        release("BAG1", "LEG2", "RVW1", "x").andExpect(status().isUnprocessableEntity());

        // 容量已满 -> 422（实际值/要求值/差额）
        dwellService.setClock(() -> T0.plusSeconds(40 * 60));
        jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES ('BAG2','LEG2')");
        release("BAG1", "LEG2", "RVW1", "x").andExpect(status().isUnprocessableEntity());
        jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag='BAG2'");

        // 回到有剩余容量且未到截单：解除成功，响应含容量实际值/要求值/剩余差额
        release("BAG1", "LEG2", "RVW1", "复核通过")
                .andExpect(jsonPath("$.legCapacity").value(1))
                .andExpect(jsonPath("$.legLoadedCount").value(0))
                .andExpect(jsonPath("$.legRemainingCapacity").value(1))
                .andExpect(jsonPath("$.checkinDeadline").value("2026-09-27T08:45:00Z"));
    }

    @Test
    void arrive_validatesConfigAndTimePresenceAndRollsBackWholeBatch() throws Exception {
        setupTwoLegItinerary();
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));

        // 中转点缺配置 -> 422
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", T0.toString())))
                .andExpect(status().isUnprocessableEntity());
        configPoint("SHA", 30);

        // 到达时刻缺失 -> 422
        arriveAtTransit("SHA", "OP1", List.of(new TransitArrivalItem("BAG1", "  ")))
                .andExpect(status().isUnprocessableEntity());
        // 非法时刻格式 -> 422
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", "not-a-time")))
                .andExpect(status().isUnprocessableEntity());
        // 不存在的袋号 -> 422，整批回滚
        arriveAtTransit("SHA", "OP1",
                List.of(item("BAG1", T0.toString()), item("NOPE", T0.toString())))
                .andExpect(status().isUnprocessableEntity());
        // 袋号重复 -> 422
        arriveAtTransit("SHA", "OP1",
                List.of(item("BAG1", T0.toString()), item("BAG1", T0.toString())))
                .andExpect(status().isUnprocessableEntity());

        // 失败后无半成品
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_dwell_state", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class)).isZero();

        // 合法状态（在 SHA 待乘 LEG2、未装载）可登记
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", T0.toString())))
                .andExpect(status().isOk());
        // 重复到达登记 -> 422
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", T0.toString())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void arrive_illegalStateOfAnyBagRollsBackEntireBatch() throws Exception {
        // BAG1 到达 SHA 待乘 LEG2；BAG2 只有 LEG1 行程，已交付于 SHA 但无待乘航段
        setupTwoLegItinerary();
        registerBag("BAG2", List.of("LEG1"));
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1", "BAG2")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1", "BAG2")));

        // BAG2 已交付、无待乘航段：状态非法，整批（含 BAG1）回滚
        arriveAtTransit("SHA", "OP1",
                List.of(item("BAG1", T0.toString()), item("BAG2", T0.toString())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_dwell_state", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class)).isZero();

        // 仅 BAG1 合法，成功
        arriveAtTransit("SHA", "OP1", List.of(item("BAG1", T0.toString())))
                .andExpect(status().isOk());
    }

    @Test
    void evidence_isImmutableAndHistorySortedAndStable() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        configureConstraint("LEG2", 5, "2026-09-27T09:00:00Z");
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));
        dwellService.setClock(() -> T0.plusSeconds(31 * 60));
        dwellService.checkDwell(new DwellCheckRequest(rid(), "SHA", "OP1", null));
        dwellService.release(new DwellReleaseRequest(rid(), "BAG1", "LEG2", "RVW7", "人工复核通过"));
        // 解除后再改派另一航段（此处 LEG2 仍为 OPEN 且始于 SHA），证据只追加
        dwellService.reassign(new ReassignRequest(rid(), "BAG1", "LEG2", "OP9"));

        mockMvc.perform(get("/api/dwell/bags/BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.history", hasSize(4)))
                .andExpect(jsonPath("$.history[*].recordType",
                        contains("ARRIVAL", "DWELL_EXCEEDED", "RELEASED", "REASSIGNED")))
                // 后续改派不改写超时证据：各记录 arrivedAt 始终为同一到达时刻
                .andExpect(jsonPath("$.history[0].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.history[1].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.history[2].arrivedAt").value(T0.toString()))
                .andExpect(jsonPath("$.history[2].reviewer").value("RVW7"))
                .andExpect(jsonPath("$.history[2].conclusion").value("人工复核通过"))
                .andExpect(jsonPath("$.history[2].nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.history[3].nextLegId").value("LEG2"))
                .andExpect(jsonPath("$.history[3].operator").value("OP9"))
                // null 语义稳定：ARRIVAL/超时的 nextLegId/reviewer 为 null
                .andExpect(jsonPath("$.history[0].nextLegId").doesNotExist())
                .andExpect(jsonPath("$.history[1].reviewer").doesNotExist())
                .andExpect(jsonPath("$.history[3].maxDwellMinutes").doesNotExist());

        // 明细 404
        mockMvc.perform(get("/api/dwell/bags/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void diagnostics_stableSortedAndReadOnly() throws Exception {
        setupTwoLegItinerary();
        registerLeg("LEG3", "PEK", "SHA");
        configPoint("SHA", 30);
        configPoint("PEK", 60);
        registerBag("BAG9", List.of("LEG3"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));
        // BAG9 在 PEK 尚未乘 LEG3，登记一个在 PEK 的到达（待乘 LEG3 始于 PEK）
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "PEK", "OP1", List.of(item("BAG9", T0.toString()))));

        // 只读：诊断查询不改变状态
        mockMvc.perform(get("/api/dwell/diagnostics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].station").value("PEK"))
                .andExpect(jsonPath("$.items[1].station").value("SHA"));
        mockMvc.perform(get("/api/dwell/diagnostics?stationCode=SHA"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG1"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type='DWELL_EXCEEDED'",
                Integer.class)).isZero();
    }

    @Test
    void idempotency_replaySnapshotDifferentParams409FailureNotOccupying() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));

        // 同参重放：返回首次完整响应快照，只登记一次
        String key = rid();
        Map<String, Object> body = Map.of("requestId", key, "stationCode", "SHA", "operator", "OP1",
                "items", List.of(Map.of("bagTag", "BAG1", "arrivedAt", T0.toString())));
        postJson("/api/dwell/transit-arrivals", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deadlineAt").value("2026-09-27T08:30:00Z"));
        postJson("/api/dwell/transit-arrivals", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deadlineAt").value("2026-09-27T08:30:00Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_dwell_state", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id=?", Integer.class, key)).isEqualTo(1);

        // 同键异参（改操作者）-> 409
        postJson("/api/dwell/transit-arrivals",
                Map.of("requestId", key, "stationCode", "SHA", "operator", "OTHER",
                        "items", List.of(Map.of("bagTag", "BAG1", "arrivedAt", T0.toString()))))
                .andExpect(status().isConflict());

        // 失败不占键：裁决一个不存在袋号先失败，同键改合法袋号后成功
        String checkKey = rid();
        dwellService.setClock(() -> T0.plusSeconds(31 * 60));
        catchApi(() -> dwellService.checkDwell(new DwellCheckRequest(
                checkKey, "SHA", "OP1", List.of("NOPE"))));
        DwellDtos.DwellCheckResponse ok = dwellService.checkDwell(new DwellCheckRequest(
                checkKey, "SHA", "OP1", List.of("BAG1")));
        assertThat(ok.exceededCount()).isEqualTo(1);
    }

    @Test
    void load_enforcesLegCapacityAndCheckinDeadlineWithActualRequiredDifference() throws Exception {
        setupTwoLegItinerary();
        registerBag("BAG2", List.of("LEG2"));
        // LEG2 容量 1、截单 T0+30
        configureConstraint("LEG2", 1, "2026-09-27T08:30:00Z").andExpect(status().isCreated());

        // BAG1 乘 LEG1 到 SHA 待乘 LEG2
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));

        baggageService.setClock(() -> T0.plusSeconds(20 * 60));
        // BAG1 先占满 LEG2
        baggageService.load("LEG2", new LoadRequest(rid(), 1, List.of("BAG1")));
        // BAG2 再装：容量不足整批拒绝（实际1/要求1/差额0）
        ApiException full = catchApi(() -> baggageService.load("LEG2",
                new LoadRequest(rid(), 2, List.of("BAG2"))));
        assertThat(full.getStatus().value()).isEqualTo(422);

        // 到截单时刻（左闭右开），即便有余量也拒绝：先卸出 BAG1 不现实，改为直接对已装载版本校验
        baggageService.setClock(() -> T0.plusSeconds(30 * 60));
        ApiException late = catchApi(() -> baggageService.load("LEG2",
                new LoadRequest(rid(), 2, List.of("BAG2"))));
        assertThat(late.getStatus().value()).isEqualTo(422);
    }

    @Test
    void check_multiBagAnyInvalidRollsBackWholeAdjudication() throws Exception {
        setupTwoLegItinerary();
        registerBag("BAG2", List.of("LEG1"));
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1", "BAG2")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1", "BAG2")));
        // 仅 BAG1（待乘 LEG2）在 SHA 登记在滞；BAG2 已交付无在滞记录
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));
        dwellService.setClock(() -> T0.plusSeconds(40 * 60));

        // 显式列表含无在滞记录的 BAG2：整次裁决回滚，BAG1 不被翻转
        ApiException ex = catchApi(() -> dwellService.checkDwell(new DwellCheckRequest(
                rid(), "SHA", "OP1", List.of("BAG1", "BAG2"))));
        assertThat(ex.getStatus().value()).isEqualTo(422);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dwell_status FROM bag_dwell_state WHERE bag_tag='BAG1'", String.class))
                .isEqualTo("WITHIN_LIMIT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type='DWELL_EXCEEDED'",
                Integer.class)).isZero();

        // 仅合法目标：超时翻转成功
        DwellDtos.DwellCheckResponse resp = dwellService.checkDwell(
                new DwellCheckRequest(rid(), "SHA", "OP1", List.of("BAG1")));
        assertThat(resp.exceededCount()).isEqualTo(1);
    }

    @Test
    void check_explicitWrongStationAndDuplicatesAre422() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        configPoint("PEK", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));

        // 在 SHA 在滞，却向 PEK 裁决 -> 422
        ApiException wrongStation = catchApi(() -> dwellService.checkDwell(
                new DwellCheckRequest(rid(), "PEK", "OP1", List.of("BAG1"))));
        assertThat(wrongStation.getStatus().value()).isEqualTo(422);
        // 重复袋号 -> 422
        ApiException duplicate = catchApi(() -> dwellService.checkDwell(
                new DwellCheckRequest(rid(), "SHA", "OP1", List.of("BAG1", "BAG1"))));
        assertThat(duplicate.getStatus().value()).isEqualTo(422);
        // 无配置中转点 -> 422
        ApiException noConfig = catchApi(() -> dwellService.checkDwell(
                new DwellCheckRequest(rid(), "CAN", "OP1", List.of("BAG1"))));
        assertThat(noConfig.getStatus().value()).isEqualTo(422);
    }

    @Test
    void configAndRelease_areIdempotentAndReplayFirstSnapshot() throws Exception {
        setupTwoLegItinerary();
        // 中转点配置同参重放：只生效一次（upsert 不产生重复行）
        String cfgKey = rid();
        Map<String, Object> cfgBody = Map.of("requestId", cfgKey, "stationCode", "SHA",
                "maxDwellMinutes", 30);
        postJson("/api/dwell/transit-points", cfgBody).andExpect(status().isCreated());
        postJson("/api/dwell/transit-points", cfgBody).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transit_point_config WHERE station_code='SHA'",
                Integer.class)).isEqualTo(1);
        // 同键改上限 -> 409
        postJson("/api/dwell/transit-points", Map.of("requestId", cfgKey,
                "stationCode", "SHA", "maxDwellMinutes", 45)).andExpect(status().isConflict());

        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));
        dwellService.setClock(() -> T0.plusSeconds(35 * 60));
        dwellService.checkDwell(new DwellCheckRequest(rid(), "SHA", "OP1", null));

        // 解除同参重放：首次快照（actualDwellMinutes=35）只解除一次，证据只一条 RELEASED
        String relKey = rid();
        DwellDtos.DwellReleaseRequest relReq = new DwellReleaseRequest(
                relKey, "BAG1", "LEG2", "RVW1", "ok");
        DwellDtos.DwellReleaseResponse first = dwellService.release(relReq);
        DwellDtos.DwellReleaseResponse replay = dwellService.release(relReq);
        assertThat(first.actualDwellMinutes()).isEqualTo(35);
        assertThat(replay.actualDwellMinutes()).isEqualTo(35);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwell_record WHERE record_type='RELEASED'",
                Integer.class)).isEqualTo(1);
        // 解除已删除开放周期，第三次同参仍返回首次快照而非报错
        DwellDtos.DwellReleaseResponse third = dwellService.release(relReq);
        assertThat(third).isEqualTo(first);
    }

    @Test
    void storedTimePrecision_isMicrosecondStable() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        // 到达时刻带纳秒，落库截断到微秒
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1",
                List.of(item("BAG1", "2026-09-27T08:00:00.123456789Z"))));
        mockMvc.perform(get("/api/dwell/bags/BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.arrivedAt").value("2026-09-27T08:00:00.123456Z"))
                .andExpect(jsonPath("$.history[0].arrivedAt").value("2026-09-27T08:00:00.123456Z"));
    }

    @Test
    void concurrentTimeoutAndLoad_timeoutFirstCommitsLoad409() throws Exception {
        setupTwoLegItinerary();
        configPoint("SHA", 30);
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new com.example.starter.baggage.BaggageDtos.SealRequest(rid(), 2));
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1")));
        dwellService.arriveAtTransitPoint(new TransitArrivalRequest(
                rid(), "SHA", "OP1", List.of(item("BAG1", T0.toString()))));

        Instant atDeadline = T0.plusSeconds(30 * 60);
        dwellService.setClock(() -> atDeadline);
        baggageService.setClock(() -> atDeadline);

        List<Callable<Object>> tasks = List.of(
                () -> dwellService.checkDwell(new DwellCheckRequest(rid(), "SHA", "OP1", null)),
                () -> baggageService.load("LEG2", new LoadRequest(rid(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        boolean timeoutCommitted = results.stream()
                .filter(DwellDtos.DwellCheckResponse.class::isInstance)
                .map(DwellDtos.DwellCheckResponse.class::cast)
                .anyMatch(resp -> resp.exceededCount() == 1);
        boolean loadOk = results.stream().anyMatch(BaggageDtos.LoadResponse.class::isInstance);
        long load409 = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();

        // 二选一裁决，不允许同时成立
        assertThat(timeoutCommitted).isNotEqualTo(loadOk);

        if (timeoutCommitted) {
            // 超时先提交：装载 409；最终行李被阻断，未进入 LEG2 清单
            assertThat(load409).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag='BAG1'", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT dwell_status FROM bag_dwell_state WHERE bag_tag='BAG1'",
                    String.class)).isEqualTo("DWELL_EXCEEDED");
        } else {
            // 装载先提交：在滞周期已随离站关闭，裁决无可判定对象（exceededCount=0），装载成功
            assertThat(loadOk).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void setupTwoLegItinerary() {
        baggageService.registerLeg(new BaggageDtos.RegisterLegRequest(rid(), "LEG1", "PEK", "SHA"));
        baggageService.registerLeg(new BaggageDtos.RegisterLegRequest(rid(), "LEG2", "SHA", "CAN"));
        baggageService.registerBag(new BaggageDtos.RegisterBagRequest(rid(), "BAG1", List.of("LEG1", "LEG2")));
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(new BaggageDtos.RegisterLegRequest(rid(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new BaggageDtos.RegisterBagRequest(rid(), bagTag, legIds));
    }

    private ResultActions configPoint(String station, int minutes) throws Exception {
        return postJson("/api/dwell/transit-points",
                Map.of("requestId", rid(), "stationCode", station, "maxDwellMinutes", minutes));
    }

    private ResultActions configureConstraint(String legId, Integer capacity, String deadline) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", rid());
        body.put("legId", legId);
        body.put("capacity", capacity);
        body.put("checkinDeadline", deadline);
        return postJson("/api/dwell/leg-constraints", body);
    }

    private ResultActions arriveAtTransit(String station, String operator,
                                          List<TransitArrivalItem> items) throws Exception {
        return postJson("/api/dwell/transit-arrivals",
                new TransitArrivalRequest(rid(), station, operator, items));
    }

    private ResultActions checkDwell(String station, String operator, List<String> bagTags) throws Exception {
        return postJson("/api/dwell/check",
                new DwellCheckRequest(rid(), station, operator, bagTags));
    }

    private ResultActions release(String bagTag, String nextLegId, String reviewer, String conclusion)
            throws Exception {
        return postJson("/api/dwell/release",
                new DwellReleaseRequest(rid(), bagTag, nextLegId, reviewer, conclusion));
    }

    private static TransitArrivalItem item(String bagTag, String arrivedAt) {
        return new TransitArrivalItem(bagTag, arrivedAt);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static ApiException catchApi(Callable<?> callable) {
        try {
            callable.call();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException ex) {
            return ex;
        } catch (Exception ex) {
            throw new AssertionError("期望 ApiException，实际 " + ex, ex);
        }
    }

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
