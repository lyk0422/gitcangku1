package com.example.starter.baggage;

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
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RerouteRequest;
import com.example.starter.baggage.BaggageDtos.RerouteResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改派并发裁决测试（真实 H2 MySQL 兼容模式 + 行锁）：
 * 改派与同袋装载、新航段封舱、另一改派并发时按提交顺序裁决，
 * 已装载旧航段与新路线不得并存，失败无部分后缀或历史；同键并发只生效一次。
 */
@SpringBootTest
class BaggageRerouteConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_reroute_history");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentRerouteAndLoadOnSameBag_singleWinnerNoOldLoadedWithNewRoute() throws Exception {
        // BAG1 完成 L1 到达 SHA，原待乘 L2；改派候选 N1
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        baggageService.load("L1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("L1", new SealRequest(UUID.randomUUID().toString(), 2));
        // 精确到达推进到 SHA
        baggageService.arrive("L1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                UUID.randomUUID().toString(), List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1"))),
                () -> baggageService.load("L2",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        boolean rerouteWon = results.stream().anyMatch(RerouteResponse.class::isInstance);
        boolean loadWon = results.stream().anyMatch(LoadResponse.class::isInstance);
        assertThat(rerouteWon).isNotEqualTo(loadWon);

        String loadedLeg = jdbcTemplate.queryForObject(
                "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer routeVersion = jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        List<String> legIds = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);

        if (rerouteWon) {
            // 改派先提交：旧航段装载随后必然失败，行李挂新路线且未装载
            assertThat(loadWon).isFalse();
            assertThat(loadedLeg).isNull();
            assertThat(routeVersion).isEqualTo(2);
            assertThat(historyCount).isEqualTo(1);
            assertThat(legIds).containsExactly("L1", "N1");
        } else {
            // 装载先提交：行李已装载到旧航段，改派失败，无历史无版本递增
            assertThat(loadedLeg).isEqualTo("L2");
            assertThat(routeVersion).isEqualTo(1);
            assertThat(historyCount).isZero();
            assertThat(legIds).containsExactly("L1", "L2");
        }
    }

    @Test
    void concurrentRerouteAndSealOnNewLeg_commitOrderAdjudicates() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "SHA", "CAN");
        registerBag("BAG1", List.of("L1", "N1"));
        baggageService.load("L1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("L1", new SealRequest(UUID.randomUUID().toString(), 2));
        baggageService.arrive("L1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                UUID.randomUUID().toString(), List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1"))),
                () -> baggageService.seal("N1",
                        new SealRequest(UUID.randomUUID().toString(), 1)));
        List<Object> results = runConcurrently(tasks);

        boolean rerouteOk = results.stream().anyMatch(RerouteResponse.class::isInstance);
        long sealSuccesses = results.stream().filter(SealResponse.class::isInstance).count();
        // 封舱竞争新航段行锁：封舱先提交则恰好一个成功（封舱），改派 409
        if (!rerouteOk) {
            assertThat(sealSuccesses).isEqualTo(1);
            long conflicts = results.stream()
                    .filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .filter(ex -> ex.getStatus().value() == 409)
                    .count();
            assertThat(conflicts).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'",
                    Integer.class)).isZero();
        } else {
            // 改派先提交允许之后封舱：封舱同样成功，新路线随后可正常封舱
            assertThat(sealSuccesses).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM leg WHERE leg_id = 'N1'", String.class))
                    .isEqualTo("SEALED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void concurrentTwoReroutes_onlyFirstCommitWins() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        baggageService.load("L1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("L1", new SealRequest(UUID.randomUUID().toString(), 2));
        baggageService.arrive("L1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                UUID.randomUUID().toString(), List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1", "N2"))),
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("L2"))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RerouteResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(1);
        List<String> legIds = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);
        // 获胜者只能是其中一条路线，绝不会出现混合后缀
        RerouteResponse winner = results.stream()
                .filter(RerouteResponse.class::isInstance)
                .map(RerouteResponse.class::cast)
                .findFirst()
                .orElseThrow();
        List<String> expectedLegs = winner.itinerary().stream()
                .map(com.example.starter.baggage.BaggageDtos.ItineraryItem::legId)
                .toList();
        assertThat(legIds).containsExactlyElementsOf(expectedLegs);
        assertThat(legIds).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdReroute_singleEffect() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
        registerLeg("N1", "SHA", "KWL");
        registerLeg("N2", "KWL", "CAN");
        registerBag("BAG1", List.of("L1", "L2"));
        baggageService.load("L1", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("L1", new SealRequest(UUID.randomUUID().toString(), 2));
        baggageService.arrive("L1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                UUID.randomUUID().toString(), List.of("BAG1")));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.reroute(new RerouteRequest(
                    requestId, "BAG1", 1, List.of("N1", "N2"))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(RerouteResponse.class);
            assertThat(((RerouteResponse) result).routeVersion()).isEqualTo(2);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_reroute_history WHERE bag_tag = 'BAG1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
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
