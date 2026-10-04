package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DualBootReceiptRequest;
import com.example.starter.firmware.api.DualBootReceiptRequest.DualBootReceiptAction;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.DualBootTaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DualBootTaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.DualBootTaskService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
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
 * 双分区并发边界测试：健康确认、失败与到期结算竞争只产生一个终态，
 * 失败率统计只计一次；并发拉取同设备至多一个未终结任务。真实并发打到 H2 事务上。
 */
@SpringBootTest
class FirmwareDualBootConcurrencyTest {

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-04T00:00:00Z"));
        }
    }

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DualBootTaskService dualBootTaskService;

    @Autowired
    private DualBootTaskRepository dualBootTaskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dual_boot_task");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

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

    private long newTrialTask(String tag) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + tag, "d-" + tag, "m-" + tag,
                "1.0.0", 1, "A"));
        long releaseId = releaseService.createDualBoot(
                new CreateReleaseRequest("req-r-" + tag, "m-" + tag, "1.0.0", "2.0.0", 100, 2, 100))
                .releaseId();
        long taskId = taskService.pull("d-" + tag, "req-p-" + tag).dualBootTask().taskId();
        dualBootTaskService.receipt(taskId, new DualBootReceiptRequest("req-t-" + tag,
                DualBootReceiptAction.TRIAL_BOOT, "B", "2.0.0"));
        return taskId;
    }
    @Test
    void 并发健康确认与失败_只产生一个终态_统计只计一次() throws Exception {
        int committed = 0;
        int rolledBack = 0;
        for (int round = 0; round < 10; round++) {
            String tag = "r" + round;
            long taskId = newTrialTask(tag);
            clock.advanceSeconds(10);

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> dualBootTaskService.receipt(taskId,
                            new DualBootReceiptRequest("req-h-" + tag, DualBootReceiptAction.HEALTHY, null, null)),
                    (Callable<Object>) () -> dualBootTaskService.receipt(taskId,
                            new DualBootReceiptRequest("req-f-" + tag, DualBootReceiptAction.FAILED, null, null))));

            DualBootTaskStatus finalStatus = dualBootTaskRepository.findById(taskId).orElseThrow().status();
            String version = deviceService.get("d-" + tag).currentVersion();
            if (finalStatus == DualBootTaskStatus.COMMITTED) {
                committed++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(version).isEqualTo("2.0.0");
            } else {
                rolledBack++;
                assertThat(finalStatus).isEqualTo(DualBootTaskStatus.ROLLED_BACK);
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(version).isEqualTo("1.0.0");
            }
            Long stats = jdbc.queryForObject(
                    "SELECT round_success + round_failed FROM release_order WHERE model = ?",
                    Long.class, "m-" + tag);
            assertThat(stats).as("第%d轮统计只计一次", round).isEqualTo(1);
        }
        assertThat(committed + rolledBack).isEqualTo(10);
    }

    @Test
    void 到期后并发健康确认与查询结算_只回滚一次() throws Exception {
        for (int round = 0; round < 10; round++) {
            String tag = "x" + round;
            long taskId = newTrialTask(tag);
            clock.advanceSeconds(61);

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> dualBootTaskService.receipt(taskId,
                            new DualBootReceiptRequest("req-h-" + tag, DualBootReceiptAction.HEALTHY, null, null)),
                    (Callable<Object>) () -> dualBootTaskService.get(taskId)));

            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class, ae -> {
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isIn("HEALTH_CONFIRM_EXPIRED", "TASK_ROLLED_BACK");
            });
            DualBootTaskStatus finalStatus = dualBootTaskRepository.findById(taskId).orElseThrow().status();
            assertThat(finalStatus).isEqualTo(DualBootTaskStatus.ROLLED_BACK);
            assertThat(deviceService.get("d-" + tag).currentVersion()).isEqualTo("1.0.0");
            Long failed = jdbc.queryForObject(
                    "SELECT round_failed FROM release_order WHERE model = ?", Long.class, "m-" + tag);
            assertThat(failed).as("第%d轮回滚只记一次", round).isEqualTo(1);
        }
    }

    @Test
    void 并发拉取_同设备同发布单至多一个未终结任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1, "A"));
        long releaseId = releaseService.createDualBoot(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100, 2, 100)).releaseId();

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq).dualBootTask().taskId());
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(results).allMatch(id -> id.equals(results.get(0)));
        assertThat(dualBootTaskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }
}