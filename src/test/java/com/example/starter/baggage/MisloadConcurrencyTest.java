package com.example.starter.baggage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.MisloadBagItem;
import com.example.starter.baggage.BaggageDtos.MisloadConfirmRequest;
import com.example.starter.baggage.BaggageDtos.MisloadPreviewRequest;
import com.example.starter.baggage.BaggageDtos.MisloadRecoveryItem;
import com.example.starter.baggage.BaggageDtos.MisloadRegisterRequest;
import com.example.starter.baggage.BaggageDtos.MisloadRegisterResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RecoverySegment;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错装批次并发边界测试（真实 H2 MySQL 兼容模式、多连接行锁，无 sleep 或打印式验证）：
 * 同件行李并发登记两个事件只允许一个胜出；登记与装载、确认与封舱、登记与补到按提交顺序
 * 产生唯一一致状态，不出现部分改派或一件行李同时处于两条路径；同 confirm requestId
 * 并发只生效一次。
 */
@SpringBootTest
class MisloadConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private MisloadService misloadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_path_snapshot");
        jdbcTemplate.update("DELETE FROM misload_proposal");
        jdbcTemplate.update("DELETE FROM misload_item");
        jdbcTemplate.update("DELETE FROM misload_incident");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        misloadService.setClock(() -> Instant.parse("2026-09-23T03:04:05Z"));
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentRegisterSameBags_singleIncidentWins() throws Exception {
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_MA", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerLeg("LEG_MB", "KWL", "SHA", "2026-09-22T08:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        arriveOnLeg("LEG_O1", 1, List.of("BAG1", "BAG2"));

        List<Callable<Object>> tasks = List.of(
                () -> misloadService.registerMisload(new MisloadRegisterRequest(
                        UUID.randomUUID().toString(), "INC_A", "LEG_MA",
                        List.of(misloadBag("BAG1", 3, "SHA"), misloadBag("BAG2", 3, "SHA")))),
                () -> misloadService.registerMisload(new MisloadRegisterRequest(
                        UUID.randomUUID().toString(), "INC_B", "LEG_MB",
                        List.of(misloadBag("BAG2", 3, "SHA"), misloadBag("BAG1", 3, "SHA")))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(MisloadRegisterResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 两件行李挂在同一个未结事件上，无重复 MISLOADED 事件，版本只推进一次
        List<String> openIncidents = jdbcTemplate.queryForList(
                "SELECT DISTINCT open_incident_id FROM bag WHERE bag_tag IN ('BAG1','BAG2')",
                String.class);
        assertThat(openIncidents).hasSize(1);
        String winner = openIncidents.get(0);
        assertThat(winner).isIn("INC_A", "INC_B");
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident WHERE status = 'OPEN'", Integer.class);
        assertThat(openCount).isEqualTo(1);
        for (String bagTag : List.of("BAG1", "BAG2")) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = ?", String.class, bagTag);
            Integer version = jdbcTemplate.queryForObject(
                    "SELECT version FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
            assertThat(status).isEqualTo("MISLOADED");
            assertThat(version).isEqualTo(4);
            Integer misloadEvents = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag_event WHERE bag_tag = ? AND event_type = 'MISLOADED'",
                    Integer.class, bagTag);
            assertThat(misloadEvents).isEqualTo(1);
        }
    }

    @Test
    void concurrentRegisterAndLoad_consistentSingleOrdering() throws Exception {
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        arriveOnLeg("LEG_O1", 1, List.of("BAG1", "BAG2"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG_O2",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> misloadService.registerMisload(new MisloadRegisterRequest(
                        UUID.randomUUID().toString(), "INC1", "LEG_M",
                        List.of(misloadBag("BAG1", 3, "SHA"), misloadBag("BAG2", 3, "SHA")))));
        List<Object> results = runConcurrently(tasks);

        boolean loadWon = results.stream().anyMatch(LoadResponse.class::isInstance);
        boolean registerWon = results.stream().filter(MisloadRegisterResponse.class::isInstance).findAny()
                .isPresent();
        assertThat(loadWon).isNotEqualTo(registerWon);

        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        String loadedLeg = jdbcTemplate.queryForObject(
                "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        if (loadWon) {
            // 装载先提交：整单错装登记失败，BAG1 在途，无事件/无未结事件
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(loadedLeg).isEqualTo("LEG_O2");
            assertThat(countOpenIncidents()).isZero();
            assertThat(countWhere("SELECT COUNT(*) FROM misload_item")).isEqualTo(0);
        } else {
            // 登记先提交：装载失败，无装载记录，BAG1 挂未结错装
            assertThat(bagStatus).isEqualTo("MISLOADED");
            assertThat(loadedLeg).isNull();
            assertThat(countOpenIncidents()).isEqualTo(1);
            assertThat(countWhere("SELECT COUNT(*) FROM load_record")).isZero();
        }
    }

    @Test
    void concurrentConfirmAndSeal_allOrNothingReroute() throws Exception {
        preparePreviewedIncident();

        List<Callable<Object>> tasks = List.of(
                () -> misloadService.confirmMisload("INC1",
                        new MisloadConfirmRequest(UUID.randomUUID().toString())),
                () -> baggageService.seal("LEG_R1",
                        new SealRequest(UUID.randomUUID().toString(), 1)));
        List<Object> results = runConcurrently(tasks);

        boolean confirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident WHERE incident_id = 'INC1'"
                        + " AND status = 'CONFIRMED'", Integer.class) == 1;
        boolean sealed = "SEALED".equals(jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG_R1'", String.class));

        if (confirmed) {
            // 改派先提交：封舱随后可成功（恢复段仍 OPEN），两件行李完整进入代次 1
            assertThat(sealed).isTrue();
            assertThat(results).anyMatch(SealResponse.class::isInstance);
            for (String bagTag : List.of("BAG1", "BAG2")) {
                Integer generation = jdbcTemplate.queryForObject(
                        "SELECT path_generation FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
                Integer genRows = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM bag_itinerary WHERE bag_tag = ? AND generation = 1",
                        Integer.class, bagTag);
                String openIncident = jdbcTemplate.queryForObject(
                        "SELECT open_incident_id FROM bag WHERE bag_tag = ?", String.class, bagTag);
                assertThat(generation).isEqualTo(1);
                assertThat(genRows).isGreaterThanOrEqualTo(1);
                assertThat(openIncident).isNull();
            }
        } else {
            // 封舱先提交：确认整单 422 回滚，无一件行李改派，无快照、无新代次
            assertThat(sealed).isTrue();
            boolean confirm422 = results.stream()
                    .filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .anyMatch(ex -> ex.getStatus().value() == 422);
            assertThat(confirm422).isTrue();
            for (String bagTag : List.of("BAG1", "BAG2")) {
                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM bag WHERE bag_tag = ?", String.class, bagTag);
                Integer generation = jdbcTemplate.queryForObject(
                        "SELECT path_generation FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
                assertThat(status).isEqualTo("MISLOADED");
                assertThat(generation).isZero();
            }
            assertThat(countWhere("SELECT COUNT(*) FROM bag_path_snapshot")).isZero();
            assertThat(countWhere("SELECT COUNT(*) FROM bag_itinerary WHERE generation > 0")).isZero();
        }
    }

    @Test
    void concurrentRegisterAndRecover_singleConsistentState() throws Exception {
        // BAG1 在 LEG_O1 差异短卸（滞留 PEK），随后被发现在 SHA 错装到达；补到与错装登记并发
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        baggageService.load("LEG_O1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BAG1", "BAG2")));
        baggageService.seal("LEG_O1", new SealRequest(UUID.randomUUID().toString(), 2));
        baggageService.arriveDifference("LEG_O1",
                new com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest(
                        UUID.randomUUID().toString(), 3, List.of("BAG2")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.recover(new RecoverRequest(
                        UUID.randomUUID().toString(), "BAG1", "LEG_O1", "SHA")),
                () -> misloadService.registerMisload(new MisloadRegisterRequest(
                        UUID.randomUUID().toString(), "INC1", "LEG_M",
                        List.of(misloadBag("BAG1", 3, "SHA"), misloadBag("BAG2", 3, "SHA")))));
        List<Object> results = runConcurrently(tasks);

        boolean recovered = results.stream().anyMatch(RecoverResponse.class::isInstance);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        String openIncident = jdbcTemplate.queryForObject(
                "SELECT open_incident_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG1'"
                        + " AND event_type IN ('RECOVERED','MISLOADED')", Integer.class);
        // 无论谁先提交，只可能发生其中一种状态转移，事件不并存
        assertThat(events).isEqualTo(1);
        if (recovered) {
            assertThat(status).isEqualTo("RECOVERED");
            assertThat(openIncident).isNull();
            assertThat(countOpenIncidents()).isZero();
        } else {
            assertThat(status).isEqualTo("MISLOADED");
            assertThat(openIncident).isEqualTo("INC1");
            assertThat(countOpenIncidents()).isEqualTo(1);
        }
    }

    @Test
    void concurrentSameConfirmRequestId_singleEffect() throws Exception {
        preparePreviewedIncident();
        String requestId = UUID.randomUUID().toString();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> misloadService.confirmMisload("INC1",
                    new MisloadConfirmRequest(requestId)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result ->
                assertThat(result.getClass().getSimpleName()).isEqualTo("MisloadConfirmResponse"));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        Integer snapshotKinds = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT bag_tag, snapshot_kind FROM bag_path_snapshot"
                        + " WHERE incident_id = 'INC1')", Integer.class);
        assertThat(snapshotKinds).isEqualTo(4);
        Integer reroutedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE event_type = 'REROUTED'", Integer.class);
        assertThat(reroutedEvents).isEqualTo(2);
        for (String bagTag : List.of("BAG1", "BAG2")) {
            Integer generation = jdbcTemplate.queryForObject(
                    "SELECT path_generation FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
            assertThat(generation).isEqualTo(1);
        }
    }

    @Test
    void concurrentRegisterAndArrive_registerNeverPartiallyApplied() throws Exception {
        // 两件行李已在 LEG_O1 封舱（在途，bag 版本 2）；到达确认与错装登记并发
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        baggageService.load("LEG_O1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BAG1", "BAG2")));
        baggageService.seal("LEG_O1", new SealRequest(UUID.randomUUID().toString(), 2));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.arrive("LEG_O1",
                        new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                                UUID.randomUUID().toString(), List.of("BAG1", "BAG2"))),
                () -> misloadService.registerMisload(new MisloadRegisterRequest(
                        UUID.randomUUID().toString(), "INC1", "LEG_M",
                        List.of(misloadBag("BAG1", 2, "SHA"), misloadBag("BAG2", 2, "SHA")))));
        List<Object> results = runConcurrently(tasks);

        // 到达必然成功；错装登记无论先后都失败：先登记则行李在途 422，先到达则版本推进到 3 -> 409
        boolean registerFailed = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 409 || ex.getStatus().value() == 422);
        assertThat(registerFailed).isTrue();
        assertThat(countOpenIncidents()).isZero();
        assertThat(countWhere("SELECT COUNT(*) FROM misload_item")).isZero();

        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG_O1'", String.class);
        assertThat(legStatus).isEqualTo("ARRIVED");
        for (String bagTag : List.of("BAG1", "BAG2")) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = ?", String.class, bagTag);
            String location = jdbcTemplate.queryForObject(
                    "SELECT current_location FROM bag WHERE bag_tag = ?", String.class, bagTag);
            String openIncident = jdbcTemplate.queryForObject(
                    "SELECT open_incident_id FROM bag WHERE bag_tag = ?", String.class, bagTag);
            Integer misloadEvents = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag_event WHERE bag_tag = ? AND event_type = 'MISLOADED'",
                    Integer.class, bagTag);
            assertThat(status).isEqualTo("IN_TRANSIT");
            assertThat(location).isEqualTo("SHA");
            assertThat(openIncident).isNull();
            assertThat(misloadEvents).isZero();
        }
    }

    // ---- 场景装配 ----

    /** 两件错装行李已预览恢复路径：BAG1 经 KMG 中转两段，BAG2 直飞一段；LEG_R1 为共用恢复段。 */
    private void preparePreviewedIncident() {
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerLeg("LEG_R1", "SHA", "KMG", "2026-10-01T08:00:00Z");
        registerLeg("LEG_R2", "KMG", "CAN", "2026-10-01T12:00:00Z");
        registerLeg("LEG_RD", "SHA", "CAN", "2026-10-02T08:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        arriveOnLeg("LEG_O1", 1, List.of("BAG1", "BAG2"));
        misloadService.registerMisload(new MisloadRegisterRequest(
                UUID.randomUUID().toString(), "INC1", "LEG_M",
                List.of(misloadBag("BAG1", 3, "SHA"), misloadBag("BAG2", 3, "SHA"))));
        misloadService.previewMisload("INC1", new MisloadPreviewRequest(
                UUID.randomUUID().toString(),
                List.of(
                        new MisloadRecoveryItem("BAG1",
                                List.of(new RecoverySegment("LEG_R1"), new RecoverySegment("LEG_R2"))),
                        new MisloadRecoveryItem("BAG2",
                                List.of(new RecoverySegment("LEG_RD"))))));
    }

    private void registerLeg(String legId, String origin, String destination, String departureTime) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination,
                        departureTime));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private void arriveOnLeg(String legId, int loadVersion, List<String> bagTags) {
        baggageService.load(legId, new LoadRequest(UUID.randomUUID().toString(), loadVersion, bagTags));
        baggageService.seal(legId, new SealRequest(UUID.randomUUID().toString(), loadVersion + 1));
        baggageService.arrive(legId, new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                UUID.randomUUID().toString(), bagTags));
    }

    private MisloadBagItem misloadBag(String bagTag, int version, String scanStation) {
        return new MisloadBagItem(bagTag, version, scanStation);
    }

    private int countOpenIncidents() {
        return countWhere("SELECT COUNT(*) FROM misload_incident WHERE status = 'OPEN'");
    }

    private int countWhere(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
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
