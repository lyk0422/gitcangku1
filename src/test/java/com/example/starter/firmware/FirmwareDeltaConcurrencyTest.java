package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MaintenanceRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
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
 * 增量发布并发边界测试：真实并发打到 H2 事务与行锁上，验证维护更新与拉取、安装回执串行，
 * 并发相同回执最多统计一次。
 */
@SpringBootTest
class FirmwareDeltaConcurrencyTest {

    private static final String A = "a".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String P = "e".repeat(64);

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
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

    private long preparePulledTask() {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, A));
        releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100,
                null, null, "DELTA", A, P, D));
        PullResponse pull = taskService.pull("d1", "req-p");
        assertThat(pull.task()).isNotNull();
        assertThat(pull.task().imageGeneration()).isEqualTo(1);
        assertThat(pull.task().baseDigest()).isEqualTo(A);
        return pull.task().taskId();
    }

    @Test
    void 并发相同回执_最多统计一次_设备只安装一次() throws Exception {
        long taskId = preparePulledTask();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS, 1, A, D)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT round_success FROM release_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT image_generation FROM device WHERE device_id = 'd1'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_digest FROM device WHERE device_id = 'd1'",
                String.class)).isEqualTo(D);
    }

    @Test
    void 维护与回执并发_串行后恰一方成功_数据一致() throws Exception {
        long taskId = preparePulledTask();

        List<Callable<Object>> tasks = List.of(
                () -> deviceService.maintain("d1",
                        new MaintenanceRequest("req-m", "ops", "1.1.0", C, 1)),
                () -> taskService.receipt(taskId, new ReceiptRequest("req-s", ReceiptResult.SUCCESS, 1, A, D)));
        List<Object> results = runConcurrently(tasks);

        Object maintenance = results.get(0);
        Object receipt = results.get(1);
        boolean maintenanceWon = !(maintenance instanceof Exception);
        boolean receiptWon = !(receipt instanceof Exception);
        assertThat(maintenanceWon).isNotEqualTo(receiptWon);
        if (maintenanceWon) {
            assertThat((ApiException) receipt).extracting(e -> e.status())
                    .isEqualTo(HttpStatus.CONFLICT);
        } else {
            assertThat((ApiException) maintenance).extracting(e -> e.status())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        // 镜像代次恰好推进一次；成功方决定最终镜像与统计
        assertThat(jdbc.queryForObject("SELECT image_generation FROM device WHERE device_id = 'd1'",
                Integer.class)).isEqualTo(2);
        String digest = jdbc.queryForObject("SELECT current_digest FROM device WHERE device_id = 'd1'",
                String.class);
        String taskStatus = jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId);
        int roundSuccess = jdbc.queryForObject("SELECT round_success FROM release_order", Integer.class);
        if (receiptWon) {
            assertThat(digest).isEqualTo(D);
            assertThat(taskStatus).isEqualTo("SUCCESS");
            assertThat(roundSuccess).isEqualTo(1);
        } else {
            assertThat(digest).isEqualTo(C);
            assertThat(taskStatus).isEqualTo("PENDING");
            assertThat(roundSuccess).isZero();
        }
    }

    @Test
    void 并发拉取_增量任务最多一条且冻结值一致() throws Exception {
        preparePulledTask();
        // 已有任务时并发拉取返回同一任务；再验证新设备并发拉取只建一条
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "1.0.0", 6, A));

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d2", "req-p2-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        long taskId = -1;
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            assertThat(response.task().baseDigest()).isEqualTo(A);
            assertThat(response.task().imageGeneration()).isEqualTo(1);
            if (taskId == -1) {
                taskId = response.task().taskId();
            } else {
                assertThat(response.task().taskId()).isEqualTo(taskId);
            }
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = 'd2'", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发同键维护_幂等重放代次只加一() throws Exception {
        preparePulledTask();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.maintain("d1",
                    new MaintenanceRequest("req-m-idem", "ops", "1.1.0", C, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT image_generation FROM device WHERE device_id = 'd1'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-m-idem'", Long.class)).isEqualTo(1);
    }
}