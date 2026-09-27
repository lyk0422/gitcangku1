package com.example.starter.firmware;

import com.example.starter.firmware.api.ConfirmReviewRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterCertRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReviewStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.CertService;
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
 * 证书轮换并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上，
 * 验证同键重放、唯一链约束、撤销与回执按提交顺序裁决。
 */
@SpringBootTest
class FirmwareCertConcurrencyTest {

    @Autowired
    private CertService certService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM cert_chain");
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

    private static String utc(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).toString();
    }

    @Test
    void 并发登记同链版本_唯一约束只成功一次() throws Exception {
        long now = Instant.now().getEpochSecond();
        // 先登记基链，使并发登记都通过轮换校验，真正打到 (firmware_version, chain_version) 唯一约束上
        certService.register(new RegisterCertRequest("req-base", "2.0.0", "chain-base",
                utc(now - 7200), utc(now - 3600), null, null, "op-1"));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> certService.register(new RegisterCertRequest("req-c" + seq, "2.0.0",
                    "chain-v1", utc(now - 3600), utc(now + 3600), "chain-base", null, "op-1")).chainId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Long chains = jdbc.queryForObject("SELECT COUNT(*) FROM cert_chain", Long.class);
        assertThat(chains).isEqualTo(2);
    }

    @Test
    void 并发同certKey登记_重放一致且只登记一条() throws Exception {
        long now = Instant.now().getEpochSecond();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> certService.register(new RegisterCertRequest("cert-same", "2.0.0",
                    "chain-v1", utc(now - 3600), utc(now + 3600), null, null, "op-1")).chainId());
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(results).allMatch(r -> r.equals(results.get(0)));
        Long chains = jdbc.queryForObject("SELECT COUNT(*) FROM cert_chain", Long.class);
        assertThat(chains).isEqualTo(1);
    }

    @Test
    void 并发撤销与回执_按提交顺序裁决且状态一致() throws Exception {
        int receiptFirst = 0;
        int revokeFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            String firmwareVersion = "2.0." + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", firmwareVersion, 100))
                    .releaseId();
            long now = Instant.now().getEpochSecond();
            long chainId = certService.register(new RegisterCertRequest("req-c" + round, firmwareVersion,
                    "chain-v1", utc(now - 3600), utc(now + 3600), null, List.of(deviceId), "op-1")).chainId();
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> certService.revoke(chainId, "req-rv" + seq)));

            assertThat(results.get(1)).as("撤销始终成功").isNotInstanceOf(Exception.class);
            assertThat(results.get(0)).as("回执始终受理").isNotInstanceOf(Exception.class);
            RolloutTask task = taskRepository.findById(taskId).orElseThrow();
            String deviceVersion = deviceService.get(deviceId).currentVersion();
            if (task.reviewStatus() == null) {
                // 回执先提交：直接成功并更新设备版本
                receiptFirst++;
                assertThat(deviceVersion).isEqualTo(firmwareVersion);
            } else {
                // 撤销先提交：回执标记复核，设备版本不更新；人工确认后完成
                revokeFirst++;
                assertThat(task.reviewStatus()).isEqualTo(ReviewStatus.CERTIFICATE_REVOKED_REVIEW);
                assertThat(deviceVersion).isEqualTo("1.0.0");
                taskService.confirmReview(taskId, new ConfirmReviewRequest("req-cf" + seq, "ops-1"));
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo(firmwareVersion);
            }
            assertThat(task.status().name()).isEqualTo("SUCCESS");
            assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
        }
        assertThat(receiptFirst + revokeFirst).isEqualTo(10);
    }
}
