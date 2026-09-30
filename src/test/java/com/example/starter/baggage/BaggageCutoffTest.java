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
import com.example.starter.baggage.BaggageDtos.SealRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UTC 装载截止时刻控制测试：截止前交接完成才可装载、临界时刻 12:00 恰好关闭、
 * 逾期 422 不占名额并留痕、封舱/到达后改时刻 409、截止快照固化、
 * 装载幂等重放与换参 409、按行李+航段状态查询、各航段独立留痕，
 * 以及装载与封舱、截止时刻并发的真实 H2（MODE=MySQL）边界。
 * 全部使用真实数据库与可控时钟，不使用 mock，并发协调真实事务并设超时。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCutoffTest {

    private static final Instant T_1159 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T_115959 = Instant.parse("2026-09-30T11:59:59Z");
    private static final Instant T_1200 = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant T_120001 = Instant.parse("2026-09-30T12:00:01Z");
    private static final String CUTOFF_1200 = "2026-09-30T12:00:00Z";

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
        jdbcTemplate.update("DELETE FROM load_rejection");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(() -> T_1159);
        executor = Executors.newFixedThreadPool(5);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        baggageService.setClock(Instant::now);
    }

    @Test
    void load_beforeCutoffWithHandover_succeedsAndReturnsCutoffSnapshot() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));

        // 11:59 完成交接（登记后即在始发站待装），截止前可装载
        load("LEGF", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF_1200))
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 装载记录固化航段与当时的截止快照
        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT leg_id, cutoff_snapshot, loaded_at FROM load_record WHERE bag_tag = 'BAG1'");
        assertThat(record.get("leg_id")).isEqualTo("LEGF");
        assertThat(((java.time.OffsetDateTime) record.get("cutoff_snapshot")).toInstant())
                .isEqualTo(T_1200);
        assertThat(((java.time.OffsetDateTime) record.get("loaded_at")).toInstant())
                .isEqualTo(T_1159);

        legBagStatus("BAG1", "LEGF")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handedOver").value(true))
                .andExpect(jsonPath("$.cutoffOpen").value(true))
                .andExpect(jsonPath("$.loadStatus").value("LOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200))
                .andExpect(jsonPath("$.rejections", hasSize(0)));
    }

    @Test
    void load_oneSecondBeforeCutoffSucceeds_exactlyAtCutoffClosesAndDoesNotOccupySlot() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG_EARLY", List.of("LEGF"));
        registerBag("BAG_LATE", List.of("LEGF"));

        // 11:59:59 严格早于 12:00 -> 放行
        baggageService.setClock(() -> T_115959);
        load("LEGF", 1, List.of("BAG_EARLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 截止时刻恰好到达（now == cutoff）即关闭：同一航段 12:00 才提交的行李 422
        baggageService.setClock(() -> T_1200);
        load("LEGF", 2, List.of("BAG_LATE")).andExpect(status().isUnprocessableEntity());

        // 12:00:01 同样拒绝
        baggageService.setClock(() -> T_120001);
        load("LEGF", 2, List.of("BAG_LATE")).andExpect(status().isUnprocessableEntity());

        // 被拒行李不占装载名额：无装载记录、航段版本不推进、行李未移动
        Integer lateRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG_LATE'", Integer.class);
        assertThat(lateRecords).isZero();
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(version).isEqualTo(2);
        mockMvc.perform(get("/api/bags/BAG_LATE/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));

        // 拒绝原因可查：两次 CUTOFF_REACHED 留痕
        legBagStatus("BAG_LATE", "LEGF")
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"))
                .andExpect(jsonPath("$.cutoffOpen").value(false))
                .andExpect(jsonPath("$.rejections", hasSize(2)))
                .andExpect(jsonPath("$.rejections[*].reasonCode",
                        contains("CUTOFF_REACHED", "CUTOFF_REACHED")));
    }

    @Test
    void load_afterCutoff_422AndBatchFullyRollsBackWithPerBagTrail() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG_OK", List.of("LEGF"));
        registerBag("BAG_LATE", List.of("LEGF"));

        // 截止是航段级条件：12:00 批量中两件都按 CUTOFF_REACHED 留痕
        baggageService.setClock(() -> T_1200);
        load("LEGF", 1, List.of("BAG_OK", "BAG_LATE")).andExpect(status().isUnprocessableEntity());

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(records).isZero();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT bag_tag, request_id, reason_code FROM load_rejection"
                        + " WHERE leg_id = 'LEGF' ORDER BY bag_tag");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("reason_code"))
                .containsExactly("CUTOFF_REACHED", "CUTOFF_REACHED");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("request_id")).isNotNull());

        // 航段版本不推进
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(version).isEqualTo(1);
    }

    @Test
    void load_oneBagFailsHandover_legalCompanionRollsBackWithCompanionReason() throws Exception {
        // 截止前：BAG_OK 交接满足 LEGF；BAG_WRONG 的待乘航段是 LEGG，交接不满足
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerLeg("LEGG", "PEK", "XIY", CUTOFF_1200);
        registerBag("BAG_OK", List.of("LEGF"));
        registerBag("BAG_WRONG", List.of("LEGG"));

        baggageService.setClock(() -> T_1159);
        load("LEGF", 1, List.of("BAG_OK", "BAG_WRONG"))
                .andExpect(status().isUnprocessableEntity());

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(records).isZero();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT bag_tag, request_id, reason_code FROM load_rejection"
                        + " WHERE leg_id = 'LEGF' ORDER BY bag_tag");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("bag_tag")).isEqualTo("BAG_OK");
        assertThat(rows.get(0).get("reason_code")).isEqualTo("BATCH_COMPANION_REJECTED");
        // 连带拒绝不归咎于请求参数本身，request_id 为 NULL
        assertThat(rows.get(0).get("request_id")).isNull();
        assertThat(rows.get(1).get("bag_tag")).isEqualTo("BAG_WRONG");
        assertThat(rows.get(1).get("reason_code")).isEqualTo("WRONG_WAITING_LEG");
        assertThat(rows.get(1).get("request_id")).isNotNull();

        // 合法行李未移动，航段版本不变；随后单独装载合法行李成功
        mockMvc.perform(get("/api/bags/BAG_OK/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        load("LEGF", 1, List.of("BAG_OK")).andExpect(status().isOk());
    }

    @Test
    void updateCutoff_openLegAllowed_bumpsVersionAndKeepsLoadedSnapshot() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));
        load("LEGF", 1, List.of("BAG1")).andExpect(status().isOk());

        // OPEN 航段可改截止时刻，版本推进；已装载记录的快照仍是旧时刻
        updateCutoff("LEGF", 2, "2026-09-30T13:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.cutoffTime").value("2026-09-30T13:00:00Z"));

        java.time.OffsetDateTime snapshot = jdbcTemplate.queryForObject(
                "SELECT cutoff_snapshot FROM load_record WHERE bag_tag = 'BAG1'",
                (rs, rowNum) -> rs.getObject(1, java.time.OffsetDateTime.class));
        assertThat(snapshot.toInstant()).isEqualTo(T_1200);
        legBagStatus("BAG1", "LEGF")
                .andExpect(jsonPath("$.cutoffTime").value("2026-09-30T13:00:00Z"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200))
                .andExpect(jsonPath("$.loadStatus").value("LOADED"));
    }

    @Test
    void updateCutoff_afterSealOrArrive_returns409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", CUTOFF_1200);
        registerLeg("LEG2", "PEK", "CAN", CUTOFF_1200);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG2"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG2")).andExpect(status().isOk());

        // 封舱后改时刻 -> 409（版本匹配，仍因状态拒绝）
        updateCutoff("LEG1", 3, "2026-09-30T13:00:00Z").andExpect(status().isConflict());
        // 行李最终到达后改时刻 -> 409
        updateCutoff("LEG2", 4, "2026-09-30T13:00:00Z").andExpect(status().isConflict());

        // 时刻保持原值，版本不因被拒修改而变化
        assertThat(queryCutoff("LEG1")).isEqualTo(T_1200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectedLoadCannotBeRetroactivelyValidated_historyTrailStaysRejected() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));

        // 12:00 被拒并留痕
        baggageService.setClock(() -> T_1200);
        String rejectedRequestId = UUID.randomUUID().toString();
        postJson("/api/legs/LEGF/load", Map.of(
                "requestId", rejectedRequestId, "expectedVersion", 1,
                "bagTags", List.of("BAG1"))).andExpect(status().isUnprocessableEntity());

        // OPEN 航段把时刻延后是合法操作，但这不会改写历史拒绝：旧留痕仍为 CUTOFF_REACHED
        updateCutoff("LEGF", 1, "2026-09-30T12:30:00Z").andExpect(status().isOk());
        List<Map<String, Object>> rejections = jdbcTemplate.queryForList(
                "SELECT reason_code FROM load_rejection WHERE bag_tag = 'BAG1' AND leg_id = 'LEGF'");
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).get("reason_code")).isEqualTo("CUTOFF_REACHED");

        // 延后时刻后是一次全新的成功装载，loaded_at 为 12:00 而非被追认成更早的时刻
        load("LEGF", 2, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffTime").value("2026-09-30T12:30:00Z"));
        java.time.OffsetDateTime loadedAt = jdbcTemplate.queryForObject(
                "SELECT loaded_at FROM load_record WHERE bag_tag = 'BAG1'",
                (rs, rowNum) -> rs.getObject(1, java.time.OffsetDateTime.class));
        assertThat(loadedAt.toInstant()).isEqualTo(T_1200);
    }

    @Test
    void load_idempotentReplayReturnsFirstResult_evenAfterCutoffChanges() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "requestId", requestId, "expectedVersion", 1, "bagTags", List.of("BAG1"));
        postJson("/api/legs/LEGF/load", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF_1200));

        // 首次成功后修改截止时刻
        updateCutoff("LEGF", 2, "2026-09-30T13:00:00Z").andExpect(status().isOk());

        // 同 requestId 同参重放首次结果：旧版本、旧截止快照，不再次推进
        postJson("/api/legs/LEGF/load", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF_1200));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void load_sameRequestIdDifferentBagOrLeg_returns409() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerLeg("LEGG", "PEK", "XIY", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));
        registerBag("BAG2", List.of("LEGF"));

        String requestId = UUID.randomUUID().toString();
        postJson("/api/legs/LEGF/load", Map.of(
                "requestId", requestId, "expectedVersion", 1,
                "bagTags", List.of("BAG1"))).andExpect(status().isOk());
        // 换行李 -> 409
        postJson("/api/legs/LEGF/load", Map.of(
                "requestId", requestId, "expectedVersion", 2,
                "bagTags", List.of("BAG2"))).andExpect(status().isConflict());
        // 换航段 -> 409
        postJson("/api/legs/LEGG/load", Map.of(
                "requestId", requestId, "expectedVersion", 1,
                "bagTags", List.of("BAG1"))).andExpect(status().isConflict());

        // 只有首次装载生效
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void updateCutoff_idempotentReplayAndDifferentParams409() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId,
                "expectedVersion", 1, "cutoffTime", "2026-09-30T12:30:00Z");
        putJson("/api/legs/LEGF/cutoff", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同参重放：版本只推进一次
        putJson("/api/legs/LEGF/cutoff", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键换时刻 -> 409
        putJson("/api/legs/LEGF/cutoff", Map.of("requestId", requestId,
                "expectedVersion", 2, "cutoffTime", "2026-09-30T13:00:00Z"))
                .andExpect(status().isConflict());
        assertThat(queryCutoff("LEGF")).isEqualTo(Instant.parse("2026-09-30T12:30:00Z"));
    }

    @Test
    void failedLoad_doesNotOccupyRequestId() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEGF"));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "requestId", requestId, "expectedVersion", 1, "bagTags", List.of("BAG1"));
        // 12:00 失败（422），不占键
        baggageService.setClock(() -> T_1200);
        postJson("/api/legs/LEGF/load", body).andExpect(status().isUnprocessableEntity());
        // 时钟回到截止前，同键同参重新执行成功
        baggageService.setClock(() -> T_1159);
        postJson("/api/legs/LEGF/load", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 拒绝留痕仍保留，成功装载只有一条；request_log 只记录成功一次
        Integer rejections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(rejections).isEqualTo(1);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void statusQuery_followsLifecycleAndKeepsPerLegTrailIndependent() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", CUTOFF_1200);
        registerLeg("LEG2", "SHA", "CAN", "2026-09-30T18:00:00Z");
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        // 第二程尚未交接：在 LEG2 无留痕、人不在 SHA
        legBagStatus("BAG1", "LEG2")
                .andExpect(jsonPath("$.handedOver").value(false))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").doesNotExist());

        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk());
        legBagStatus("BAG1", "LEG1")
                .andExpect(jsonPath("$.legStatus").value("SEALED"))
                .andExpect(jsonPath("$.loadStatus").value("LOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200));

        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        legBagStatus("BAG1", "LEG1")
                .andExpect(jsonPath("$.legStatus").value("ARRIVED"))
                .andExpect(jsonPath("$.loadStatus").value("LOADED"))
                .andExpect(jsonPath("$.handedOver").value(true));

        // 后续航段时刻变化不覆盖已完成航段记录（LEG2 仍为版本 1）
        updateCutoff("LEG2", 1, "2026-09-30T19:00:00Z").andExpect(status().isOk());
        legBagStatus("BAG1", "LEG1")
                .andExpect(jsonPath("$.cutoffTime").value(CUTOFF_1200))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200));
        legBagStatus("BAG1", "LEG2")
                .andExpect(jsonPath("$.cutoffTime").value("2026-09-30T19:00:00Z"))
                .andExpect(jsonPath("$.handedOver").value(true))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"));

        // 到达后改已完成航段时刻仍 409
        updateCutoff("LEG1", 4, "2026-09-30T20:00:00Z").andExpect(status().isConflict());
    }

    @Test
    void statusQuery_404AndNotInItinerary422() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("LEG1"));
        mockMvc.perform(get("/api/bags/NOPE/legs/LEG1/status")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG1/legs/NOPE/status")).andExpect(status().isNotFound());

        registerLeg("LEG2", "PEK", "CAN", CUTOFF_1200);
        legBagStatus("BAG1", "LEG2").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentLoadsAtCutoff_allRejectedZeroRecords() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        for (int i = 1; i <= 4; i++) {
            registerBag("BAG" + i, List.of("LEGF"));
        }
        baggageService.setClock(() -> T_1200);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final String bagTag = "BAG" + i;
            tasks.add(() -> baggageService.load("LEGF",
                    new LoadRequest(UUID.randomUUID().toString(), 1, List.of(bagTag))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ApiException.class);
            assertThat(((ApiException) result).getStatus().value()).isEqualTo(422);
        });
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(records).isZero();
        Integer rejections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(rejections).isEqualTo(4);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEGF'", Integer.class);
        assertThat(version).isEqualTo(1);
    }

    @Test
    void concurrentLoadAndSeal_onlyCompliantUnsealedLoadRecordsSurvive() throws Exception {
        baggageService.setClock(() -> T_1159);
        // 多轮放大窗口：每轮独立航段与行李
        for (int round = 1; round <= 8; round++) {
            String legId = "LEGF" + round;
            baggageService.registerLeg(new RegisterLegRequest(UUID.randomUUID().toString(),
                    legId, "PEK", "SHA", CUTOFF_1200));
            List<String> bags = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                String bagTag = "BAG" + round + "_" + i;
                baggageService.registerBag(new RegisterBagRequest(
                        UUID.randomUUID().toString(), bagTag, List.of(legId)));
                bags.add(bagTag);
            }
            // BAG1 先装载成功，版本到 2
            LoadResponse first = baggageService.load(legId, new LoadRequest(
                    UUID.randomUUID().toString(), 1, List.of(bags.get(0))));
            assertThat(first.version()).isEqualTo(2);

            // 其余 3 件装载与封舱同版本同时提交
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 1; i < bags.size(); i++) {
                final String bagTag = bags.get(i);
                tasks.add(() -> baggageService.load(legId,
                        new LoadRequest(UUID.randomUUID().toString(), 2, List.of(bagTag))));
            }
            tasks.add(() -> baggageService.seal(legId,
                    new SealRequest(UUID.randomUUID().toString(), 2)));
            List<Object> results = runConcurrently(tasks);

            long winners = results.stream()
                    .filter(r -> r instanceof LoadResponse || r instanceof BaggageDtos.SealResponse)
                    .count();
            assertThat(winners).isEqualTo(1);

            String legStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM leg WHERE leg_id = ?", String.class, legId);
            List<String> loadedBags = jdbcTemplate.queryForList(
                    "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag",
                    String.class, legId);
            if ("SEALED".equals(legStatus)) {
                // 封舱先提交：清单只含封舱前装载，封舱后的装载一律不得存在
                assertThat(loadedBags).containsExactly(bags.get(0));
            } else {
                // 某件装载先成功：主线程补封舱，清单必须与现存装载记录完全一致
                assertThat(legStatus).isEqualTo("OPEN");
                Integer currentVersion = jdbcTemplate.queryForObject(
                        "SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
                BaggageDtos.SealResponse sealed = baggageService.seal(legId,
                        new SealRequest(UUID.randomUUID().toString(), currentVersion));
                assertThat(sealed.manifest()).containsExactlyElementsOf(loadedBags);
                assertThat(loadedBags).hasSize(2);
            }

            // 所有留存装载记录都符合截止（loaded_at < cutoff，快照 = 12:00）
            org.springframework.jdbc.core.RowMapper<java.time.OffsetDateTime> timeMapper =
                    (rs, rowNum) -> rs.getObject(1, java.time.OffsetDateTime.class);
            List<java.time.OffsetDateTime> loadedTimes = jdbcTemplate.query(
                    "SELECT loaded_at FROM load_record WHERE leg_id = ?", timeMapper, legId);
            List<java.time.OffsetDateTime> snapshots = jdbcTemplate.query(
                    "SELECT cutoff_snapshot FROM load_record WHERE leg_id = ?", timeMapper, legId);
            assertThat(loadedTimes).isNotEmpty();
            assertThat(loadedTimes).allSatisfy(t -> assertThat(t.toInstant()).isEqualTo(T_1159));
            assertThat(snapshots).allSatisfy(t -> assertThat(t.toInstant()).isEqualTo(T_1200));
        }
    }

    @Test
    void concurrentCutoffUpdates_singleWinnerByVersion() throws Exception {
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.updateCutoff("LEGF",
                    new BaggageDtos.UpdateCutoffRequest(UUID.randomUUID().toString(),
                            1, "2026-09-30T12:30:00Z")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof BaggageDtos.LegResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(queryCutoff("LEGF")).isEqualTo(Instant.parse("2026-09-30T12:30:00Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEGF'", Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidCutoffTime_returns400() throws Exception {
        postJson("/api/legs", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "legId", "LEGF", "origin", "PEK", "destination", "SHA",
                "cutoffTime", "not-a-time")).andExpect(status().isBadRequest());
        registerLeg("LEGF", "PEK", "SHA", CUTOFF_1200);
        putJson("/api/legs/LEGF/cutoff", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1,
                "cutoffTime", "12:00")).andExpect(status().isBadRequest());
    }

    private java.time.Instant queryCutoff(String legId) {
        return jdbcTemplate.queryForObject(
                "SELECT cutoff_time FROM leg WHERE leg_id = ?",
                (rs, rowNum) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                legId);
    }

    private ResultActions registerLeg(String legId, String origin, String destination, String cutoff)
            throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination,
                "cutoffTime", cutoff));
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

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions updateCutoff(String legId, int expectedVersion, String cutoff)
            throws Exception {
        return putJson("/api/legs/" + legId + "/cutoff", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "cutoffTime", cutoff));
    }

    private ResultActions legBagStatus(String bagTag, String legId) throws Exception {
        return mockMvc.perform(get("/api/bags/" + bagTag + "/legs/" + legId + "/status"));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mockMvc.perform(put(url)
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
