package com.example.starter.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发与幂等边界测试：真实并发调用，校验版本冲突、锚点不变与同键重放。
 */
class ConcurrencyTest extends AbstractApiTest {

    @BeforeEach
    void registerEquipment() throws Exception {
        var result = postJson("/api/equipment", registerEquipment("req-reg", "EQ-1", 100));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    /**
     * 两个任务同一起跑线并发执行，带超时防止悬挂。
     */
    private List<MvcResult> runConcurrently(Callable<MvcResult> first, Callable<MvcResult> second)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<MvcResult> wrappedFirst = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return first.call();
            };
            Callable<MvcResult> wrappedSecond = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return second.call();
            };
            Future<MvcResult> futureFirst = pool.submit(wrappedFirst);
            Future<MvcResult> futureSecond = pool.submit(wrappedSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futureFirst.get(30, TimeUnit.SECONDS), futureSecond.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentRevisionAndMaintenanceKeepAnchorStable() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 100));

        var results = runConcurrently(
                () -> postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-r", 2, 150)),
                () -> postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-m", 2, "R-1", 1)));

        var statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        // 同一设备版本2只允许一个写成功，另一个必须409
        assertThat(statuses).containsExactly(200, 409);

        var history = body(getJson("/api/equipment/EQ-1/history"));
        var maintenances = history.get("maintenances");
        var reading = history.get("readings").get(0);
        if (maintenances.size() == 1) {
            // 保养先成功：锚点快照固定为100，读数未被修订
            assertThat(maintenances.get(0).get("anchorAccumulatedMinutes").asLong()).isEqualTo(100);
            assertThat(maintenances.get(0).get("anchorRevisionNo").asInt()).isEqualTo(1);
            assertThat(reading.get("accumulatedMinutes").asLong()).isEqualTo(100);
            assertThat(reading.get("currentRevision").asInt()).isEqualTo(1);
        } else {
            // 修订先成功：不存在保养记录，读数为修订后值
            assertThat(maintenances).isEmpty();
            assertThat(reading.get("accumulatedMinutes").asLong()).isEqualTo(150);
            assertThat(reading.get("currentRevision").asInt()).isEqualTo(2);
        }
        assertThat(history.get("version").asInt()).isEqualTo(3);
    }

    @Test
    void concurrentWritesWithSameExpectedVersionAllowSingleSuccess() throws Exception {
        var results = runConcurrently(
                () -> postJson("/api/equipment/EQ-1/readings",
                        addReading("req-a", 1, "R-A", "2026-01-01T01:00:00Z", 10)),
                () -> postJson("/api/equipment/EQ-1/readings",
                        addReading("req-b", 1, "R-B", "2026-01-01T02:00:00Z", 20)));

        var statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(201, 409);

        var history = body(getJson("/api/equipment/EQ-1/history"));
        assertThat(history.get("readings")).hasSize(1);
        assertThat(history.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleSuccess() throws Exception {
        var results = runConcurrently(
                () -> postJson("/api/equipment/EQ-1/readings",
                        addReading("req-same", 1, "R-1", "2026-01-01T01:00:00Z", 10)),
                () -> postJson("/api/equipment/EQ-1/readings",
                        addReading("req-same", 1, "R-1", "2026-01-01T01:00:00Z", 10)));

        // 同键同参：两个请求都拿到原成功结果
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(201);
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(201);
        assertThat(content(results.get(0))).isEqualTo(content(results.get(1)));

        var history = body(getJson("/api/equipment/EQ-1/history"));
        assertThat(history.get("readings")).hasSize(1);
        assertThat(history.get("version").asInt()).isEqualTo(2);
        Integer keyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE request_id = 'req-same'", Integer.class);
        assertThat(keyCount).isEqualTo(1);
    }
}
