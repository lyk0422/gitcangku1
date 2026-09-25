package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateRegionLimitRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.RegionThrottleRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 验证并发下同区域拉取不超发不漏发、回执释放名额计数一致、公平队列唯一胜出者。
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
    private RegionThrottleRepository regionThrottleRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
        executor = Executors.newFixedThreadPool(12);
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

    private long createReleaseWithLimit(String requestId, String model, int limit) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100, limit))
                .releaseId();
    }

    private void register(String deviceId, String model, String region) {
        deviceService.register(new RegisterDeviceRequest("req-" + deviceId, deviceId, model, "1.0.0", 1, region));
    }

    @Test
    void 并发拉取_同区域不超发不漏发_计数与等待记录一致() throws Exception {
        int deviceCount = 10;
        int limit = 3;
        for (int i = 0; i < deviceCount; i++) {
            register("d" + i, "m1", "cn-north");
        }
        long releaseId = createReleaseWithLimit("req-r", "m1", limit);

        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < deviceCount; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d" + seq, "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        long dispatched = 0;
        long throttled = 0;
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            if (response.isThrottled()) {
                throttled++;
                assertThat(response.task()).isNull();
            } else {
                dispatched++;
                assertThat(response.task()).isNotNull();
            }
        }
        // 严格等于上限：不超发；其余全部被限流：不漏发
        assertThat(dispatched).isEqualTo(limit);
        assertThat(throttled).isEqualTo(deviceCount - limit);
        // 进行中计数与实际下发严格一致
        assertThat(regionThrottleRepository.countInFlight(releaseId, "cn-north")).isEqualTo(limit);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(limit);
        // 每个被限流设备各一条等待记录与一条限流历史
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(deviceCount - limit);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_throttle_event WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(deviceCount - limit);
    }

    @Test
    void 并发回执与拉取_进行中计数永不越限且最终一致() throws Exception {
        register("d0", "m1", "cn-north");
        register("d1", "m1", "cn-north");
        long releaseId = createReleaseWithLimit("req-r", "m1", 2);
        long task0 = taskService.pull("d0", "req-p0").task().taskId();
        long task1 = taskService.pull("d1", "req-p1").task().taskId();
        for (int i = 2; i < 6; i++) {
            register("d" + i, "m1", "cn-north");
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> taskService.receipt(task0, new ReceiptRequest("req-s0", ReceiptResult.SUCCESS)));
        tasks.add(() -> taskService.receipt(task1, new ReceiptRequest("req-s1", ReceiptResult.FAILED)));
        for (int i = 2; i < 6; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d" + seq, "req-p" + seq));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        long dispatchedPulls = results.stream().skip(2)
                .filter(r -> ((PullResponse) r).task() != null).count();
        // 两个回执释放两个名额，被下发的拉取数等于最终进行中数，且不越限
        long inFlight = regionThrottleRepository.countInFlight(releaseId, "cn-north");
        assertThat(inFlight).isEqualTo(dispatchedPulls);
        assertThat(inFlight).isLessThanOrEqualTo(2);
        // 实际 PENDING 任务数与计数一致；终结任务恰为两条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                Long.class, releaseId)).isEqualTo(inFlight);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status IN ('SUCCESS','FAILED')",
                Long.class, releaseId)).isEqualTo(2);
        // 被限流的拉取都有等待记录，等待数 + 下发数 = 拉取总数
        long waiting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_wait_record WHERE release_id = ?", Long.class, releaseId);
        assertThat(waiting + dispatchedPulls).isEqualTo(4);
    }

    @Test
    void 并发公平排队_仅最早等待者获得名额() throws Exception {
        // 预置等待记录：d0 最早，d3 最晚（可控时刻直接写入）
        for (int i = 0; i < 4; i++) {
            register("d" + i, "m1", "cn-north");
        }
        long releaseId = createReleaseWithLimit("req-r", "m1", 1);
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO region_wait_record (release_id, region, device_id, last_throttled_at)"
                            + " VALUES (?, 'cn-north', ?, ?)",
                    releaseId, "d" + i, java.sql.Timestamp.valueOf("2026-01-01 10:00:0" + i));
        }

        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d" + seq, "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<String> dispatchedDevices = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            if (response.task() != null) {
                dispatchedDevices.add(response.task().deviceId());
            }
        }
        // 只有一个名额，且必须属于等待时刻最早的 d0
        assertThat(dispatchedDevices).containsExactly("d0");
        assertThat(regionThrottleRepository.countInFlight(releaseId, "cn-north")).isEqualTo(1);
        // d0 的等待记录已移除，其余三台仍在等待（再次被限流会刷新各自的限流时刻，顺序不固定）
        assertThat(regionThrottleRepository.findWaiting(releaseId, "cn-north"))
                .extracting(RegionThrottleRepository.WaitEntry::deviceId)
                .containsExactlyInAnyOrder("d1", "d2", "d3");
    }

    @Test
    void 并发区域上限修改_同一expectedVersion只有一个成功() throws Exception {
        long releaseId = createReleaseWithLimit("req-r", "m1", 5);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> releaseService.updateRegionLimit(releaseId,
                    new UpdateRegionLimitRequest("req-rl-" + seq, 1, 10 + seq)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 版本只加一次，上限为胜出者的值
        assertThat(releaseService.findOrder(releaseId).version()).isEqualTo(2);
        assertThat(releaseService.findOrder(releaseId).regionLimit()).isIn(10, 11);
    }

    @Test
    void 并发同requestId拉取_幂等重放只下发一次() throws Exception {
        register("d1", "m1", "cn-north");
        long releaseId = createReleaseWithLimit("req-r", "m1", 5);

        int threads = 6;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "req-pull-same"));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(1);
        assertThat(regionThrottleRepository.countInFlight(releaseId, "cn-north")).isEqualTo(1);
    }
}
