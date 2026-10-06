package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateDualReleaseRequest;
import com.example.starter.firmware.api.DualPullRequest;
import com.example.starter.firmware.api.DualPullResponse;
import com.example.starter.firmware.api.DualReceiptRequest;
import com.example.starter.firmware.api.DualTaskView;
import com.example.starter.firmware.api.DualTrialRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.DualTaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DualTaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.DualReleaseService;
import com.example.starter.firmware.service.DualTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
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
 * 双分区试启动并发测试：健康确认与失败、到期检查竞争只产生一个终态，
 * 统计只计一次；真实并发打到 H2 事务与唯一约束上。
 */
@SpringBootTest
class FirmwareDualPartitionConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DualReleaseService dualReleaseService;

    @Autowired
    private DualTaskService dualTaskService;

    @Autowired
    private DualTaskRepository dualTaskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dual_task");
        jdbc.update("DELETE FROM dual_release");
        jdbc.update("DELETE FROM device_boot_state");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(T0);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
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

    private long newTrialTask(int round) {
        String deviceId = "d" + round;
        String model = "m" + round;
        deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "v1", 1));
        long releaseId = dualReleaseService.create(
                new CreateDualReleaseRequest("req-r" + round, "op", model, "v2")).releaseId();
        long taskId = dualTaskService.pull(deviceId, new DualPullRequest("req-p" + round, "op"))
                .task().taskId();
        dualTaskService.trial(taskId, new DualTrialRequest("req-t" + round, "op", "B", "v2"));
        return taskId;
    }

    @Test
    void 并发确认与失败_只产生一个终态_统计只计一次() throws Exception {
        int committed = 0;
        int rolledBack = 0;
        for (int round = 0; round < 10; round++) {
            long taskId = newTrialTask(round);
            String deviceId = "d" + round;
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> dualTaskService.confirm(taskId, new DualReceiptRequest("req-c" + seq, "op")),
                    () -> dualTaskService.fail(taskId, new DualReceiptRequest("req-f" + seq, "op"))));

            DualTaskStatus finalStatus = dualTaskRepository.findById(taskId).orElseThrow().status();
            long releaseId = dualTaskRepository.findById(taskId).orElseThrow().releaseId();
            var release = dualReleaseService.findRelease(releaseId);
            assertThat(release.committedCount() + release.rolledBackCount())
                    .as("每任务终态统计只计一次（第%d轮）", round).isEqualTo(1);
            String version = deviceService.get(deviceId).currentVersion();
            String partition = dualTaskService.bootView(deviceId).currentPartition();
            if (finalStatus == DualTaskStatus.COMMITTED) {
                committed++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(version).isEqualTo("v2");
                assertThat(partition).isEqualTo("B");
            } else {
                rolledBack++;
                assertThat(finalStatus).isEqualTo(DualTaskStatus.ROLLED_BACK);
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(version).isEqualTo("v1");
                assertThat(partition).isEqualTo("A");
            }
        }
        assertThat(committed + rolledBack).isEqualTo(10);
    }

    @Test
    void 并发确认与到期结算_只产生回滚终态且只记录一次() throws Exception {
        for (int round = 0; round < 5; round++) {
            long taskId = newTrialTask(round);
            long releaseId = dualTaskRepository.findById(taskId).orElseThrow().releaseId();
            final int seq = round;
            clock.advance(Duration.ofSeconds(61));

            List<Object> results = runConcurrently(List.of(
                    () -> dualTaskService.confirm(taskId, new DualReceiptRequest("req-c" + seq, "op")),
                    () -> dualTaskService.getTask(taskId),
                    () -> dualTaskService.listByRelease(releaseId),
                    () -> dualReleaseService.get(releaseId)));

            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            assertThat(results.get(2)).isNotInstanceOf(Exception.class);
            assertThat(results.get(3)).isNotInstanceOf(Exception.class);
            var task = dualTaskRepository.findById(taskId).orElseThrow();
            assertThat(task.status()).isEqualTo(DualTaskStatus.ROLLED_BACK);
            assertThat(task.rollbackReason().name()).isEqualTo("EXPIRED");
            var release = dualReleaseService.findRelease(releaseId);
            assertThat(release.rolledBackCount()).as("回滚只记录一次（第%d轮）", round).isEqualTo(1);
            assertThat(release.committedCount()).isZero();
            assertThat(deviceService.get("d" + round).currentVersion()).isEqualTo("v1");
        }
    }

    @Test
    void 并发拉取_同设备同发布单至多一条未终结任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "v1", 1));
        long releaseId = dualReleaseService.create(
                new CreateDualReleaseRequest("req-r", "op", "m1", "v2")).releaseId();

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> dualTaskService.pull("d1", new DualPullRequest("req-pull-" + seq, "op")));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(DualPullResponse.class);
            DualPullResponse response = (DualPullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(dualTaskRepository.countActiveByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }

    @Test
    void 并发同requestId确认_重放一致且只提交一次() throws Exception {
        long taskId = newTrialTask(0);
        long releaseId = dualTaskRepository.findById(taskId).orElseThrow().releaseId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> dualTaskService.confirm(taskId, new DualReceiptRequest("req-same", "op")));
        }
        List<Object> results = runConcurrently(tasks);

        DualTaskView first = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(DualTaskView.class);
            DualTaskView view = (DualTaskView) result;
            assertThat(view.status()).isEqualTo("COMMITTED");
            if (first == null) {
                first = view;
            } else {
                assertThat(view).isEqualTo(first);
            }
        }
        assertThat(dualReleaseService.findRelease(releaseId).committedCount()).isEqualTo(1);
        assertThat(deviceService.get("d0").currentVersion()).isEqualTo("v2");
    }

    @Test
    void 并发回滚后重试拉取_新任务ID且至多一条未终结() throws Exception {
        long oldTaskId = newTrialTask(0);
        long releaseId = dualTaskRepository.findById(oldTaskId).orElseThrow().releaseId();
        clock.advance(Duration.ofSeconds(61));

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> dualTaskService.pull("d0", new DualPullRequest("req-retry-" + seq, "op")));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(DualPullResponse.class);
            DualPullResponse response = (DualPullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskIds.get(0)).isNotEqualTo(oldTaskId);
        assertThat(dualTaskRepository.countActiveByReleaseAndDevice(releaseId, "d0")).isEqualTo(1);
        assertThat(dualReleaseService.findRelease(releaseId).rolledBackCount()).isEqualTo(1);
    }
}