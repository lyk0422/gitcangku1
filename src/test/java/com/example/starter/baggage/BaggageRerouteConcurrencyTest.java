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
 * 剩余行程改派并发边界测试（真实 H2 MySQL 兼容模式、真实线程与行锁）：
 * 改派与相关新航段封舱按提交顺序裁决；两个改派只有一个生效；
 * 改派与同袋装载互斥，不能出现已装载旧航段却挂上新路线；失败无部分后缀或历史。
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
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM reroute_history");
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
    void concurrentRerouteAndSealOfNewLeg_commitOrderDecides() throws Exception {
        // 原行程 L1；新后缀 N1 与原路线同样 PEK->SHA，改派与 N1 封舱并发
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "PEK", "SHA");
        registerBag("BAG1", List.of("L1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1"))),
                () -> baggageService.seal("N1",
                        new SealRequest(UUID.randomUUID().toString(), 1)));
        List<Object> results = runConcurrently(tasks);

        boolean rerouted = results.stream().anyMatch(RerouteResponse.class::isInstance);
        boolean sealed = results.stream().anyMatch(SealResponse.class::isInstance);
        // 封舱先提交则改派失败（422 新航段非 OPEN）；改派先提交则两者都成功（改派不封舱）
        assertThat(sealed).isTrue();
        String n1Status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'N1'", String.class);
        Integer routeVersion = jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        if (rerouted) {
            // 改派先提交：允许之后封舱；N1 最终 SEALED，路线为 N1
            assertThat(n1Status).isEqualTo("SEALED");
            assertThat(routeVersion).isEqualTo(2);
            assertThat(historyCount).isEqualTo(1);
        } else {
            // 封舱先提交：改派 422，路线仍为 L1，无历史、无部分后缀
            assertThat(n1Status).isEqualTo("SEALED");
            assertThat(routeVersion).isEqualTo(1);
            assertThat(historyCount).isZero();
            List<String> legs = jdbcTemplate.queryForList(
                    "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);
            assertThat(legs).containsExactly("L1");
            assertThat(results).anySatisfy(r -> {
                assertThat(r).isInstanceOf(ApiException.class);
                assertThat(((ApiException) r).getStatus().value()).isEqualTo(409);
            });
        }
    }

    @Test
    void concurrentTwoReroutes_onlyOneTakesEffect() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "SHA");
        registerLeg("K1", "PEK", "CTU");
        registerLeg("K2", "CTU", "SHA");
        registerBag("BAG1", List.of("L1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1", "N2"))),
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("K1", "K2"))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RerouteResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer routeVersion = jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(routeVersion).isEqualTo(2);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isEqualTo(1);
        List<String> legs = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);
        assertThat(legs).hasSize(2);
        boolean newRoute = legs.equals(List.of("N1", "N2"));
        boolean altRoute = legs.equals(List.of("K1", "K2"));
        assertThat(newRoute ^ altRoute).isTrue();
    }

    @Test
    void concurrentRerouteAndLoadOldLeg_neverLoadedOldLegWithNewRoute() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "SHA");
        registerBag("BAG1", List.of("L1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.reroute(new RerouteRequest(
                        UUID.randomUUID().toString(), "BAG1", 1, List.of("N1", "N2"))),
                () -> baggageService.load("L1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))));
        List<Object> results = runConcurrently(tasks);

        boolean rerouted = results.stream().anyMatch(RerouteResponse.class::isInstance);
        boolean loaded = results.stream().anyMatch(LoadResponse.class::isInstance);
        // 恰有一个成功；二者都成功或都失败都不允许
        assertThat(rerouted ^ loaded).isTrue();

        String loadedLeg = jdbcTemplate.queryForObject(
                "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer routeVersion = jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        List<String> legs = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);
        if (rerouted) {
            // 改派先提交：旧航段装载必失败，行李挂新路线且未装载
            assertThat(routeVersion).isEqualTo(2);
            assertThat(legs).containsExactly("N1", "N2");
            assertThat(loadedLeg).isNull();
            Integer loadRecords = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
            assertThat(loadRecords).isZero();
            // 新后缀可正常装载
            baggageService.load("N1",
                    new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        } else {
            // 装载先提交：改派必失败（已装载 422），行李挂旧路线且装载在 L1
            assertThat(routeVersion).isEqualTo(1);
            assertThat(legs).containsExactly("L1");
            assertThat(loadedLeg).isEqualTo("L1");
            assertThat(results).anySatisfy(r -> {
                assertThat(r).isInstanceOf(ApiException.class);
                assertThat(((ApiException) r).getStatus().value()).isEqualTo(409);
            });
        }
    }

    @Test
    void concurrentSameRerouteRequestId_replaysSingleEffect() throws Exception {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("N1", "PEK", "XIY");
        registerLeg("N2", "XIY", "SHA");
        registerBag("BAG1", List.of("L1"));

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
        Integer routeVersion = jdbcTemplate.queryForObject(
                "SELECT route_version FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(routeVersion).isEqualTo(2);
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reroute_history WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(historyCount).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
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
