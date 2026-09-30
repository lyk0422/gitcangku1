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
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UTC 装载截止时刻控制测试：覆盖截止前可装载、截止时刻恰好到达即 422 且不占名额、
 * 成功装载保留航段与截止快照、封舱/到达后改时刻 409、被拒装载留痕不被追认、
 * 按行李+航段状态查询、装载幂等重放/异参 409，以及截止边界与装载/封舱并发。
 * 全部基于 H2 MySQL 兼容模式真实数据库，时钟可控，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCutoffTest {

    private static final Instant T_1159 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T_1200 = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant T_1201 = Instant.parse("2026-09-30T12:01:00Z");
    private static final String CUTOFF_1200 = "2026-09-30T12:00:00Z";
    private static final String CUTOFF_1300 = "2026-09-30T13:00:00Z";

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
        jdbcTemplate.update("DELETE FROM load_rejection");
        jdbcTemplate.update("DELETE FROM bag_leg_load");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(() -> T_1159);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        baggageService.setClock(Instant::now);
    }

    @Test
    void cutoff_oneMinuteBeforeLoads_exactlyAtCutoffRejectsWithoutOccupyingSlot() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("F"));
        registerBag("BAG2", List.of("F"));

        // 11:59 完成交接（登记即位于 F 始发站）的行李可在 11:59 装载
        baggageService.setClock(() -> T_1159);
        load("F", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200))
                .andExpect(jsonPath("$.loaded", contains("BAG1")));

        // 同一件行李 12:00 才到：截止时刻恰好到达即关闭装载，返回 422
        baggageService.setClock(() -> T_1200);
        load("F", 2, List.of("BAG2")).andExpect(status().isUnprocessableEntity());

        // 422 不占装载名额：无装载记录、航段版本不变、BAG2 未移动
        Integer slotTaken = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG2'", Integer.class);
        assertThat(slotTaken).isZero();
        Integer trailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load WHERE bag_tag = 'BAG2'", Integer.class);
        assertThat(trailCount).isZero();
        mockMvc.perform(get("/api/legs/F/manifest"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));

        // 拒绝留痕：原因 CUTOFF_CLOSED，截止快照为 12:00，尝试时刻为 12:00
        java.time.OffsetDateTime[] rejection = jdbcTemplate.query(
                "SELECT cutoff_snapshot, attempted_at FROM load_rejection"
                        + " WHERE bag_tag = 'BAG2' AND leg_id = 'F'",
                rs -> {
                    rs.next();
                    return new java.time.OffsetDateTime[] {
                            rs.getObject("cutoff_snapshot", java.time.OffsetDateTime.class),
                            rs.getObject("attempted_at", java.time.OffsetDateTime.class)};
                });
        assertThat(rejection[0].toInstant()).isEqualTo(T_1200);
        assertThat(rejection[1].toInstant()).isEqualTo(T_1200);
        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM load_rejection WHERE bag_tag = 'BAG2' AND leg_id = 'F'",
                String.class);
        assertThat(reason).isEqualTo("CUTOFF_CLOSED");
        Integer requestLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE operation = 'LOAD'", Integer.class);
        // 仅成功的 BAG1 装载占用一个幂等键，失败请求不入 request_log
        assertThat(requestLogs).isEqualTo(1);

        // 12:01 仍然逾期
        baggageService.setClock(() -> T_1201);
        load("F", 2, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cutoff_successfulLoadKeepsSnapshot_whenCutoffLaterChanges() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("F"));

        baggageService.setClock(() -> T_1159);
        load("F", 1, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200));

        // OPEN 航段可把截止改到 13:00
        updateCutoff("F", CUTOFF_1300).andExpect(status().isOk())
                .andExpect(jsonPath("$.loadingCutoff").value(CUTOFF_1300))
                .andExpect(jsonPath("$.version").value(2));

        // 已完成的装载留痕仍保留 12:00 的截止快照，不被后续改时刻覆盖
        java.time.OffsetDateTime[] trail = jdbcTemplate.query(
                "SELECT cutoff_snapshot, loaded_at FROM bag_leg_load WHERE bag_tag = 'BAG1' AND leg_id = 'F'",
                rs -> {
                    rs.next();
                    return new java.time.OffsetDateTime[] {
                            rs.getObject("cutoff_snapshot", java.time.OffsetDateTime.class),
                            rs.getObject("loaded_at", java.time.OffsetDateTime.class)};
                });
        assertThat(trail[0].toInstant()).isEqualTo(T_1200);
        assertThat(trail[1].toInstant()).isEqualTo(T_1159);
    }

    @Test
    void updateCutoff_rejectedAfterSealAndArrival_andCannotRatifyRejectedLoad() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("F"));

        // 12:00 装载被拒并留痕
        baggageService.setClock(() -> T_1200);
        String rejectedRequestId = UUID.randomUUID().toString();
        postJson("/api/legs/F/load", Map.of("requestId", rejectedRequestId,
                "expectedVersion", 1, "bagTags", List.of("BAG1")))
                .andExpect(status().isUnprocessableEntity());

        // 改时刻不会追认被拒装载：拒绝流水仍在，且没有成功留痕
        Integer rejections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection WHERE request_id = ?", Integer.class, rejectedRequestId);
        assertThat(rejections).isEqualTo(1);

        // 航段仍 OPEN，延后截止后重新提交（新的装载决策）可成功，但被拒记录不被改写
        updateCutoff("F", CUTOFF_1300).andExpect(status().isOk());
        postJson("/api/legs/F/load", Map.of("requestId", rejectedRequestId,
                "expectedVersion", 1, "bagTags", List.of("BAG1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1300));
        Integer stillRejected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection WHERE request_id = ? AND reason = 'CUTOFF_CLOSED'",
                Integer.class, rejectedRequestId);
        assertThat(stillRejected).isEqualTo(1);

        // 封舱后改时刻 -> 409
        seal("F", 2).andExpect(status().isOk());
        updateCutoff("F", "2099-01-01T00:00:00Z").andExpect(status().isConflict());

        // 最终到达后改时刻 -> 409
        arriveExact("F", List.of("BAG1")).andExpect(status().isOk());
        updateCutoff("F", "2099-01-01T00:00:00Z").andExpect(status().isConflict());

        // 截止时刻字段在到达后保持封舱前的 13:00，未被任何修改改动
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.legStatus").value("ARRIVED"))
                .andExpect(jsonPath("$.loadingCutoff").value(CUTOFF_1300));
    }

    @Test
    void updateCutoff_invalidTimeIs400_andIdempotentConflict() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);

        updateCutoff("F", "not-a-time").andExpect(status().isBadRequest());
        // 航段不存在 -> 404
        updateCutoff("MISSING", CUTOFF_1300).andExpect(status().isNotFound());

        // 同 requestId 改成不同时刻 -> 409
        String requestId = UUID.randomUUID().toString();
        postJson("/api/legs/F/cutoff", Map.of("requestId", requestId, "loadingCutoff", CUTOFF_1300))
                .andExpect(status().isOk());
        postJson("/api/legs/F/cutoff",
                Map.of("requestId", requestId, "loadingCutoff", "2026-09-30T14:00:00Z"))
                .andExpect(status().isConflict());
        // 同参重放原结果
        postJson("/api/legs/F/cutoff", Map.of("requestId", requestId, "loadingCutoff", CUTOFF_1300))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadingCutoff").value(CUTOFF_1300));
    }

    @Test
    void statusQuery_reflectsHandoverCutoffLoadAndRejectionPerLeg() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerLeg("G", "SHA", "CAN", "2026-09-30T18:00:00Z");
        registerBag("BAG1", List.of("F", "G"));

        // 11:59：BAG1 在 F 始发站完成交接、未装载、截止未关闭
        baggageService.setClock(() -> T_1159);
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handedOver").value(true))
                .andExpect(jsonPath("$.cutoffClosed").value(false))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"))
                .andExpect(jsonPath("$.loadingCutoff").value(CUTOFF_1200))
                .andExpect(jsonPath("$.lastRejectReason").doesNotExist());
        // 后续航段 G 尚未交接
        mockMvc.perform(get("/api/bags/BAG1/legs/G/status"))
                .andExpect(jsonPath("$.handedOver").value(false))
                .andExpect(jsonPath("$.loadStatus").value("NOT_APPLICABLE"));

        // 装载成功后状态为 LOADED
        load("F", 1, List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.loadStatus").value("LOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200))
                .andExpect(jsonPath("$.loadedAt").value(T_1159.toString()));

        // 到达卸下：F 航段留痕保留，状态 UNLOADED；进入 G 始发站后 G 为待装载
        seal("F", 2).andExpect(status().isOk());
        arriveExact("F", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.legStatus").value("ARRIVED"))
                .andExpect(jsonPath("$.loadStatus").value("UNLOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200));
        mockMvc.perform(get("/api/bags/BAG1/legs/G/status"))
                .andExpect(jsonPath("$.handedOver").value(true))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"));

        // 12:00 后查询 F：截止已关闭但留痕不受影响
        baggageService.setClock(() -> T_1200);
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.cutoffClosed").value(true))
                .andExpect(jsonPath("$.loadStatus").value("UNLOADED"));

        // 未知行李/航段 -> 404
        mockMvc.perform(get("/api/bags/NOPE/legs/F/status")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/BAG1/legs/NOPE/status")).andExpect(status().isNotFound());
    }

    @Test
    void load_idempotentReplay_andDifferentBagOrLegReturns409() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1300);
        registerLeg("H", "PEK", "XIY", CUTOFF_1300);
        registerBag("BAG1", List.of("F"));
        registerBag("BAG2", List.of("F"));

        baggageService.setClock(() -> T_1159);
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> firstLoad = Map.of("requestId", requestId,
                "expectedVersion", 1, "bagTags", List.of("BAG1"));
        postJson("/api/legs/F/load", firstLoad).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1300));

        // 同键同参重放首次装载结果：版本不再推进、留痕只有一条
        postJson("/api/legs/F/load", firstLoad).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        Integer trailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load WHERE bag_tag = 'BAG1' AND leg_id = 'F'", Integer.class);
        assertThat(trailCount).isEqualTo(1);

        // 同键换行李 -> 409
        postJson("/api/legs/F/load", Map.of("requestId", requestId,
                "expectedVersion", 1, "bagTags", List.of("BAG2")))
                .andExpect(status().isConflict());
        // 同键换航段 -> 409
        postJson("/api/legs/H/load", Map.of("requestId", requestId,
                "expectedVersion", 1, "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());
        // BAG2 未被异参请求装载
        Integer bag2Loaded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE bag_tag = 'BAG2' AND loaded_leg_id IS NOT NULL",
                Integer.class);
        assertThat(bag2Loaded).isZero();
    }

    @Test
    void concurrentLoadAndSeal_onlyCompliantUnsealedLoadSurvives() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1300);
        registerBag("BAG1", List.of("F"));
        registerBag("BAG2", List.of("F"));
        baggageService.setClock(() -> T_1159);
        baggageService.load("F", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));

        // 装载 BAG2 与封舱同版本并发：只能一个成功，另一个 409
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("F",
                        new LoadRequest(UUID.randomUUID().toString(), 2, List.of("BAG2"))),
                () -> baggageService.seal("F",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof LoadResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'F'", String.class);
        if ("OPEN".equals(status)) {
            // 装载先成功：补封舱后清单含两件，且都是截止内、未封舱时写入的
            SealResponse sealed = baggageService.seal("F",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).containsExactly("BAG1", "BAG2");
        } else {
            // 封舱先成功：BAG2 的装载不可能存在，清单只有 BAG1
            assertThat(status).isEqualTo("SEALED");
            Integer bag2Record = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag_leg_load WHERE bag_tag = 'BAG2'", Integer.class);
            assertThat(bag2Record).isZero();
            assertThat(baggageService.getManifest("F").manifest()).containsExactly("BAG1");
        }
        // 所有留痕装载均满足“截止内且航段未封舱”
        Integer sealedLoads = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load t JOIN leg l ON t.leg_id = l.leg_id"
                        + " WHERE l.status IN ('SEALED','ARRIVED') AND t.loaded_at >= l.loading_cutoff",
                Integer.class);
        assertThat(sealedLoads).isZero();
    }

    @Test
    void concurrentLoadsAtCutoff_allRejectedAndNoLoadRecord() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("F"));
        registerBag("BAG2", List.of("F"));
        // 时钟固定在截止时刻：所有并发装载都必须 422
        baggageService.setClock(() -> T_1200);

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> baggageService.load("F",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        tasks.add(() -> baggageService.load("F",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG2"))));
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ApiException.class);
            assertThat(((ApiException) result).getStatus().value()).isEqualTo(422);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load", Integer.class)).isZero();
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'F'", Integer.class);
        assertThat(version).isEqualTo(1);
        Integer rejections = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection WHERE reason = 'CUTOFF_CLOSED'", Integer.class);
        assertThat(rejections).isEqualTo(2);

        // 两件行李的状态查询都带 CUTOFF_CLOSED 拒绝原因
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.cutoffClosed").value(true))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"))
                .andExpect(jsonPath("$.lastRejectReason").value("CUTOFF_CLOSED"));
    }

    @Test
    void separateLegs_keepIndependentTracesWhenLaterLegCutoffChanges() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerLeg("G", "SHA", "CAN", "2026-09-30T18:00:00Z");
        registerBag("BAG1", List.of("F", "G"));

        baggageService.setClock(() -> T_1159);
        load("F", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("F", 2).andExpect(status().isOk());
        arriveExact("F", List.of("BAG1")).andExpect(status().isOk());

        // F 已到达后，G 仍 OPEN，调整 G 的截止时刻不影响 F 的留痕
        updateCutoff("G", "2026-09-30T19:30:00Z").andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/legs/F/status"))
                .andExpect(jsonPath("$.loadStatus").value("UNLOADED"))
                .andExpect(jsonPath("$.cutoffSnapshot").value(CUTOFF_1200));
        mockMvc.perform(get("/api/bags/BAG1/legs/G/status"))
                .andExpect(jsonPath("$.loadingCutoff").value("2026-09-30T19:30:00Z"))
                .andExpect(jsonPath("$.loadStatus").value("NOT_LOADED"));

        // bag_leg_load 中 F 航段恰有一条独立留痕
        Integer fTrails = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load WHERE bag_tag = 'BAG1' AND leg_id = 'F'", Integer.class);
        assertThat(fTrails).isEqualTo(1);
    }

    @Test
    void rejectedBatch_leavesNoPartialLoad_andQueryShowsReason() throws Exception {
        registerLeg("F", "PEK", "SHA", CUTOFF_1200);
        registerBag("BAG1", List.of("F"));
        registerBag("BAG2", List.of("F"));

        // 12:00 截止时刻到达：截止对航段整体关闭，整批 422 且无一件移动
        baggageService.setClock(() -> T_1200);
        load("F", 1, List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_leg_load", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_rejection", Integer.class)).isEqualTo(2);

        // 回到截止前，整批两件均可装载（逾期失败不占名额、不阻塞合法行李）
        baggageService.setClock(() -> T_1159);
        load("F", 1, List.of("BAG1", "BAG2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", hasSize(2)));
    }

    private void registerLeg(String legId, String origin, String destination, String cutoff) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination, cutoff));
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

    private ResultActions arriveExact(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions updateCutoff(String legId, String cutoff) throws Exception {
        return postJson("/api/legs/" + legId + "/cutoff", Map.of(
                "requestId", UUID.randomUUID().toString(), "loadingCutoff", cutoff));
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
