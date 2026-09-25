package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegionLimitItem;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateRegionLimitsRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.RegionThrottleService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域限流并发边界测试：真实并发打到 H2 事务与行锁上，
 * 验证区域上限不超发、进行中计数无漂移、公平排队顺序与限流事件幂等。
 */
@SpringBootTest
class RegionThrottleConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RegionThrottleService regionThrottleService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_region_limit");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
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

    private long createReleaseWithLimit(String requestId, String region, int maxInFlight) {
        long releaseId = releaseService.create(
                new CreateReleaseRequest(requestId, "m1", "1.0.0", "2.0.0", 100)).releaseId();
        regionThrottleService.updateLimits(releaseId,
                new UpdateRegionLimitsRequest(requestId + "-lim", 1, List.of(new RegionLimitItem(region, maxInFlight))));
        return releaseId;
    }

    private void register(String deviceId, String region) {
        deviceService.register(new RegisterDeviceRequest("req-reg-" + deviceId, deviceId, "m1", "1.0.0", 1, region));
    }

    private long inFlight(long releaseId, String region) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task t JOIN device d ON t.device_id = d.device_id"
                        + " WHERE t.release_id = ? AND t.status = 'PENDING' AND d.region = ?",
                Long.class, releaseId, region);
        return count == null ? 0 : count;
    }

    @Test
    void 并发拉取_区域上限严格不超发() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "cn-north", 3);
        int devices = 12;
        for (int i = 0; i < devices; i++) {
            register("d" + i, "cn-north");
        }

        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            String deviceId = "d" + i;
            tasks.add(() -> taskService.pull(deviceId, "req-pull-" + deviceId));
        }
        List<Object> results = runConcurrently(tasks);

        long issued = 0;
        long throttled = 0;
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            if (PullResponse.RESULT_ISSUED.equals(response.result())) {
                issued++;
                assertThat(response.task()).isNotNull();
            } else {
                assertThat(response.result()).isEqualTo(PullResponse.RESULT_THROTTLED);
                assertThat(response.task()).isNull();
                throttled++;
            }
        }
        assertThat(issued).isEqualTo(3);
        assertThat(throttled).isEqualTo(devices - 3);
        // 计数与实际下发严格一致：不超发、不漏发
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record", Long.class))
                .isEqualTo(devices - 3);
    }

    @Test
    void 并发回执与重复回执_进行中计数精确无漂移() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "cn-north", 2);
        register("d1", "cn-north");
        register("d2", "cn-north");
        long task1 = taskService.pull("d1", "req-p1").task().taskId();
        long task2 = taskService.pull("d2", "req-p2").task().taskId();
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(2);

        // 同一任务的并发重复回执（同结果）与另一任务的回执并行：计数只按任务终结各减一次
        List<Callable<Object>> receipts = new ArrayList<>();
        receipts.add(() -> taskService.receipt(task1, new ReceiptRequest("req-rc1", ReceiptResult.SUCCESS)));
        receipts.add(() -> taskService.receipt(task1, new ReceiptRequest("req-rc1b", ReceiptResult.SUCCESS)));
        receipts.add(() -> taskService.receipt(task2, new ReceiptRequest("req-rc2", ReceiptResult.FAILED)));
        List<Object> results = runConcurrently(receipts);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(inFlight(releaseId, "cn-north")).isZero();

        // 名额释放后新设备可拉取，拉满后再次限流
        register("d3", "cn-north");
        register("d4", "cn-north");
        register("d5", "cn-north");
        assertThat(taskService.pull("d3", "req-p3").result()).isEqualTo(PullResponse.RESULT_ISSUED);
        assertThat(taskService.pull("d4", "req-p4").result()).isEqualTo(PullResponse.RESULT_ISSUED);
        assertThat(taskService.pull("d5", "req-p5").result()).isEqualTo(PullResponse.RESULT_THROTTLED);
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(2);
    }

    @Test
    void 并发拉取与上限配置修改_按提交顺序裁决且计数一致() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "cn-north", 1);
        int devices = 8;
        for (int i = 0; i < devices; i++) {
            register("d" + i, "cn-north");
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            String deviceId = "d" + i;
            tasks.add(() -> taskService.pull(deviceId, "req-pull-" + deviceId));
        }
        // 配置修改（版本2 -> 3，上限放宽为4）与拉取并发：由发布单行锁串行化
        tasks.add(() -> regionThrottleService.updateLimits(releaseId,
                new UpdateRegionLimitsRequest("req-lim2", 2, List.of(new RegionLimitItem("cn-north", 4)))));
        List<Object> results = runConcurrently(tasks);

        assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
        long issued = results.stream()
                .filter(r -> r instanceof PullResponse pr && PullResponse.RESULT_ISSUED.equals(pr.result()))
                .count();
        // 无论配置修改与拉取的提交顺序如何，最终计数与实际下发一致，且不超过最终上限
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(issued);
        assertThat(issued).isBetween(1L, 4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(issued);
        assertThat(releaseService.findOrder(releaseId).version()).isEqualTo(3);
    }

    @Test
    void 并发公平排队_最早等待者获得释放名额() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "cn-north", 1);
        register("d0", "cn-north");
        long task0 = taskService.pull("d0", "req-p0").task().taskId();
        int waiters = 6;
        for (int i = 1; i <= waiters; i++) {
            register("w" + i, "cn-north");
            assertThat(taskService.pull("w" + i, "req-pw" + i).result())
                    .isEqualTo(PullResponse.RESULT_THROTTLED);
        }
        // 控制等待时刻：w3 最早（09:00），其余依次更晚（10:00 起每分钟一位）
        for (int i = 1; i <= waiters; i++) {
            jdbc.update("UPDATE region_wait_record SET waited_at = ? WHERE release_id = ? AND device_id = ?",
                    Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(i)),
                    releaseId, "w" + i);
        }
        jdbc.update("UPDATE region_wait_record SET waited_at = ? WHERE release_id = ? AND device_id = 'w3'",
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 9, 0)), releaseId);

        taskService.receipt(task0, new ReceiptRequest("req-rc0", ReceiptResult.SUCCESS));

        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 1; i <= waiters; i++) {
            String deviceId = "w" + i;
            tasks.add(() -> taskService.pull(deviceId, "req-pw2-" + deviceId));
        }
        List<Object> results = runConcurrently(tasks);

        List<String> issuedDevices = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Object result = results.get(i);
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            if (PullResponse.RESULT_ISSUED.equals(response.result())) {
                issuedDevices.add("w" + (i + 1));
            }
        }
        // 仅最早等待者 w3 获得名额，其余仍被限流
        assertThat(issuedDevices).containsExactly("w3");
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record", Long.class))
                .isEqualTo(waiters - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_wait_record WHERE device_id = 'w3'", Long.class)).isZero();
    }

    @Test
    void 并发同requestId限流拉取_限流事件只记录一次() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "cn-north", 1);
        register("d0", "cn-north");
        register("d1", "cn-north");
        taskService.pull("d0", "req-p0");

        int threads = 6;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "req-same-throttle"));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            assertThat(((PullResponse) result).result()).isEqualTo(PullResponse.RESULT_THROTTLED);
        }
        // 同键重放首次结果：等待记录与限流事件只写入一次
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_throttle_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }
}
