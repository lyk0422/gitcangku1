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

import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RetagRequest;
import com.example.starter.baggage.BaggageDtos.RetagResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 换标并发边界（真实 H2 行锁与唯一约束）：
 * 同一行李并发换标只有一个成功；两件行李并发抢占同一新标签只有一个成功。
 */
@SpringBootTest
class BaggageRetagConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_tag_alias");
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
    void concurrentRetagSameBag_onlyOneSucceeds() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1");

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> baggageService.retag(
                new RetagRequest(UUID.randomUUID().toString(), "op-1", "ID-1", 1, "B2")));
        tasks.add(() -> baggageService.retag(
                new RetagRequest(UUID.randomUUID().toString(), "op-1", "ID-1", 1, "B3")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RetagResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer generation = jdbcTemplate.queryForObject(
                "SELECT tag_generation FROM bag WHERE bag_id = 'ID-1'", Integer.class);
        assertThat(generation).isEqualTo(2);
        Integer aliasCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_id = 'ID-1'", Integer.class);
        assertThat(aliasCount).isEqualTo(2);
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_id = 'ID-1' AND event_type = 'TAG_REPLACED'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    void concurrentRetagDifferentBagsSameNewTag_onlyOneOccupies() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("ID-1", "B1");
        registerBag("ID-2", "C1");

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.retag(
                        new RetagRequest(UUID.randomUUID().toString(), "op-1", "ID-1", 1, "NEW")),
                () -> baggageService.retag(
                        new RetagRequest(UUID.randomUUID().toString(), "op-1", "ID-2", 1, "NEW")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RetagResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 全局只有一个 NEW 标签，归获胜行李所有
        String owner = jdbcTemplate.queryForObject(
                "SELECT bag_id FROM bag_tag_alias WHERE bag_tag = 'NEW'", String.class);
        Integer totalNew = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_tag = 'NEW'", Integer.class);
        assertThat(totalNew).isEqualTo(1);
        String winnerTag = jdbcTemplate.queryForObject(
                "SELECT bag_tag FROM bag WHERE bag_id = ?", String.class, owner);
        assertThat(winnerTag).isEqualTo("NEW");
        String loserTag = jdbcTemplate.queryForObject(
                "SELECT bag_tag FROM bag WHERE bag_id = ?", String.class,
                "ID-1".equals(owner) ? "ID-2" : "ID-1");
        assertThat(loserTag).isIn("B1", "C1");
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), "op-1", legId, origin, destination));
    }

    private void registerBag(String bagId, String bagTag) {
        baggageService.registerBag(new RegisterBagRequest(
                UUID.randomUUID().toString(), "op-1", bagId, bagTag, List.of("LEG1")));
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
