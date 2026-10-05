package com.example.starter.baggage;

import java.math.BigDecimal;
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

import com.example.starter.baggage.BaggageDtos.BagCompartment;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.MoveCompartmentRequest;
import com.example.starter.baggage.BaggageDtos.MoveCompartmentResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双舱航段并发边界测试：换舱与封舱以版本决定唯一先后，失败不移动任何行李；
 * 并发装载同舱只有一个胜者，舱重永不超限。基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
class BaggageBalanceConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seal_item");
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
    void concurrentMoveAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG_C");
        registerBag("BAG_F", "60");
        registerBag("BAG_A", "40");
        baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BAG_F", "BAG_A"), "tester",
                List.of(new BagCompartment("BAG_F", "FWD"), new BagCompartment("BAG_A", "AFT"))));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.moveCompartment("LEG_C",
                        new MoveCompartmentRequest(UUID.randomUUID().toString(), 2, "tester",
                                List.of(new BagCompartment("BAG_F", "AFT"),
                                        new BagCompartment("BAG_A", "FWD")))),
                () -> baggageService.seal("LEG_C",
                        new SealRequest(UUID.randomUUID().toString(), 2, "tester")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof MoveCompartmentResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG_C'", String.class);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG_C'", Integer.class);
        assertThat(version).isEqualTo(3);
        String fwdBag = jdbcTemplate.queryForObject(
                "SELECT compartment FROM load_record WHERE bag_tag = 'BAG_F'", String.class);
        if ("SEALED".equals(status)) {
            // 封舱先成功：冻结快照保持原舱位，换舱失败不移动任何行李
            assertThat(fwdBag).isEqualTo("FWD");
            BigDecimal frozenMoment = jdbcTemplate.queryForObject(
                    "SELECT moment_kgm FROM seal_item WHERE leg_id = 'LEG_C' AND bag_tag = 'BAG_F'",
                    BigDecimal.class);
            assertThat(frozenMoment).isEqualByComparingTo("-120.000");
        } else {
            // 换舱先成功：航段仍开放，舱位已交换，封舱因版本冲突未生效
            assertThat(status).isEqualTo("OPEN");
            assertThat(fwdBag).isEqualTo("AFT");
            Integer frozenItems = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM seal_item WHERE leg_id = 'LEG_C'", Integer.class);
            assertThat(frozenItems).isZero();
        }
    }

    @Test
    void concurrentLoads_sameCompartmentSingleWinnerThenCapacityEnforced() throws Exception {
        registerLeg("LEG_C");
        registerBag("BAG_1", "300");
        registerBag("BAG_2", "300");
        registerBag("BAG_3", "300");
        registerBag("BAG_4", "300");
        baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BAG_1", "BAG_2"), "tester",
                List.of(new BagCompartment("BAG_1", "FWD"), new BagCompartment("BAG_2", "FWD"))));

        // 两个并发装载竞争同一版本：只有一个胜者
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(), 2,
                        List.of("BAG_3"), "tester", List.of(new BagCompartment("BAG_3", "FWD")))),
                () -> baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(), 2,
                        List.of("BAG_4"), "tester", List.of(new BagCompartment("BAG_4", "FWD")))));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(LoadResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 前舱当前 900 千克；败者以新版本重试仍受舱重约束：900+300=1200 超限 -> 422
        String loserTag = results.get(0) instanceof LoadResponse ? "BAG_4" : "BAG_3";
        try {
            baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(), 3,
                    List.of(loserTag), "tester", List.of(new BagCompartment(loserTag, "FWD"))));
            org.assertj.core.api.Assertions.fail("预期舱重超限 422");
        } catch (ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(422);
        }
        BigDecimal fwdTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(b.mass_kg), 0) FROM load_record r JOIN bag b ON b.bag_tag = r.bag_tag"
                        + " WHERE r.leg_id = 'LEG_C' AND r.compartment = 'FWD'", BigDecimal.class);
        assertThat(fwdTotal).isEqualByComparingTo("900.000");
        // 败者未被移动，可改装后舱
        LoadResponse moved = baggageService.load("LEG_C", new LoadRequest(UUID.randomUUID().toString(),
                3, List.of(loserTag), "tester", List.of(new BagCompartment(loserTag, "AFT"))));
        assertThat(moved.version()).isEqualTo(4);
    }

    private void registerLeg(String legId) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, "PEK", "SHA", "COMPARTMENT"));
    }

    private void registerBag(String bagTag, String massKg) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag,
                List.of("LEG_C"), new BigDecimal(massKg)));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（业务异常包装为返回值）。 */
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
