package com.example.starter.firmware;

import com.example.starter.firmware.api.CompleteRehearsalRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.StartRehearsalRequest;
import com.example.starter.firmware.domain.RehearsalStatus;
import com.example.starter.firmware.domain.RollbackRehearsal;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.RehearsalRepository;
import com.example.starter.firmware.service.RehearsalService;
import com.example.starter.firmware.service.ReleaseService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回滚演练并发、事务原子性与幂等边界测试：真实并发打到 H2 事务、行锁与唯一约束上。
 */
@SpringBootTest
class FirmwareRehearsalConcurrencyTest {

    @Autowired
    private RehearsalService rehearsalService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private RehearsalRepository rehearsalRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM release_gate");
        jdbc.update("DELETE FROM rollback_rehearsal_device");
        jdbc.update("DELETE FROM rollback_rehearsal");
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

    private void register(String id, String model, String version, int bucket) {
        deviceRepository.insert(new com.example.starter.firmware.domain.Device(id, model, version, bucket));
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

    private StartRehearsalRequest startRequest(String requestId, String model, String target,
                                               String rollback, List<String> deviceIds) {
        return new StartRehearsalRequest(requestId, "alice", model, target, rollback, deviceIds);
    }

    private CompleteRehearsalRequest successResults(String requestId, List<String> deviceIds) {
        return new CompleteRehearsalRequest(requestId, "alice",
                deviceIds.stream().map(id -> new CompleteRehearsalRequest.DeviceResultInput(id,
                        com.example.starter.firmware.domain.ReceiptResult.SUCCESS, null)).toList());
    }

    @Test
    void 并发开始_同版本组合至多一场进行中演练() throws Exception {
        register("d1", "m1", "2.0.0", 1);
        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> rehearsalService.start(
                    startRequest("rs" + seq, "m1", "2.0.0", "1.0.0", List.of("d1"))).rehearsalId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "REHEARSAL_IN_PROGRESS".equals(ae.code()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal WHERE status = 'RUNNING'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal_device", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发同requestId开始_集合顺序不同指纹一致_只创建一场() throws Exception {
        register("d1", "m1", "2.0.0", 1);
        register("d2", "m1", "2.0.0", 2);
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<String> ids = i % 2 == 0 ? List.of("d1", "d2") : List.of("d2", "d1");
            tasks.add(() -> rehearsalService.start(
                    startRequest("rs-same", "m1", "2.0.0", "1.0.0", ids)).rehearsalId());
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        long id = (long) results.get(0);
        assertThat(results).allMatch(r -> r.equals(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal_device", Long.class)).isEqualTo(2);
    }

    @Test
    void 并发提交结果_同键重放异键409_门禁仅一条且设备仅回退一次() throws Exception {
        register("d1", "m1", "2.0.0", 1);
        register("d2", "m1", "2.0.0", 2);
        long rehearsalId = rehearsalService.start(
                startRequest("rs1", "m1", "2.0.0", "1.0.0", List.of("d2", "d1"))).rehearsalId();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> rehearsalService.complete(rehearsalId,
                    successResults("rc-same", List.of("d1", "d2"))));
        }
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> rehearsalService.complete(rehearsalId,
                    successResults("rc-other" + seq, List.of("d1", "d2"))));
        }
        List<Object> results = runConcurrently(tasks);

        long replayed = results.stream()
                .filter(r -> !(r instanceof Exception)).count();
        long finalized409 = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "REHEARSAL_FINALIZED".equals(ae.code()))
                .count();
        assertThat(replayed).isEqualTo(6);
        assertThat(finalized409).isEqualTo(2);
        assertThat(rehearsalService.findRehearsal(rehearsalId).status()).isEqualTo(RehearsalStatus.PASSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
        assertThat(deviceRepository.findById("d1").orElseThrow().currentVersion()).isEqualTo("1.0.0");
        assertThat(deviceRepository.findById("d2").orElseThrow().currentVersion()).isEqualTo("1.0.0");
    }

    @Test
    void 提交时设备状态已变化_整批422回滚_无结果无门禁_恢复后同键可成功() {
        register("d1", "m1", "2.0.0", 1);
        register("d2", "m1", "2.0.0", 2);
        long rehearsalId = rehearsalService.start(
                startRequest("rs1", "m1", "2.0.0", "1.0.0", List.of("d1", "d2"))).rehearsalId();

        // 演练开始后设备版本被其他流程改变：SUCCESS 证据与当前状态不一致，整批拒绝
        deviceRepository.updateCurrentVersion("d1", "2.1.0");
        assertThatThrownBy(() -> rehearsalService.complete(rehearsalId,
                successResults("rc1", List.of("d1", "d2"))))
                .isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(ae.code()).isEqualTo("VERSION_INCOMPATIBLE");
                        });

        RollbackRehearsal stillRunning = rehearsalService.findRehearsal(rehearsalId);
        assertThat(stillRunning.status()).isEqualTo(RehearsalStatus.RUNNING);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();
        assertThat(deviceRepository.findById("d1").orElseThrow().currentVersion()).isEqualTo("2.1.0");
        assertThat(deviceRepository.findById("d2").orElseThrow().currentVersion()).isEqualTo("2.0.0");

        // 失败不占键：恢复设备状态后同 requestId 提交成功，设备真实回退并授予门禁
        deviceRepository.updateCurrentVersion("d1", "2.0.0");
        rehearsalService.complete(rehearsalId, successResults("rc1", List.of("d1", "d2")));
        assertThat(rehearsalService.findRehearsal(rehearsalId).status()).isEqualTo(RehearsalStatus.PASSED);
        assertThat(deviceRepository.findById("d1").orElseThrow().currentVersion()).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
    }

    @Test
    void 失败批终结演练但不产生门禁_成功设备也不回退_重开新演练后通过() {
        register("d1", "m1", "2.0.0", 1);
        register("d2", "m1", "2.0.0", 2);
        long firstId = rehearsalService.start(
                startRequest("rs1", "m1", "2.0.0", "1.0.0", List.of("d1", "d2"))).rehearsalId();

        rehearsalService.complete(firstId, new CompleteRehearsalRequest("rc1", "alice", List.of(
                new CompleteRehearsalRequest.DeviceResultInput("d1",
                        com.example.starter.firmware.domain.ReceiptResult.SUCCESS, null),
                new CompleteRehearsalRequest.DeviceResultInput("d2",
                        com.example.starter.firmware.domain.ReceiptResult.FAILED, "回退校验失败"))));

        RollbackRehearsal failed = rehearsalService.findRehearsal(firstId);
        assertThat(failed.status()).isEqualTo(RehearsalStatus.FAILED);
        assertThat(failed.successCount()).isEqualTo(1);
        assertThat(failed.failedCount()).isEqualTo(1);
        assertThat(failed.gateDeniedReason()).isEqualTo("DEVICE_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();
        assertThat(deviceRepository.findById("d1").orElseThrow().currentVersion()).isEqualTo("2.0.0");

        // 门禁校验直接拒绝
        assertThatThrownBy(() -> rehearsalService.requireReleaseGate("m1", "1.0.0", "2.0.0"))
                .isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("GATE_NOT_READY"));

        // 同版本组合重开新演练（active_key 已随终态释放）
        long secondId = rehearsalService.start(
                startRequest("rs2", "m1", "2.0.0", "1.0.0", List.of("d1", "d2"))).rehearsalId();
        assertThat(secondId).isNotEqualTo(firstId);
        rehearsalService.complete(secondId, successResults("rc2", List.of("d1", "d2")));
        assertThat(rehearsalService.findRehearsal(secondId).status()).isEqualTo(RehearsalStatus.PASSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
        rehearsalService.requireReleaseGate("m1", "1.0.0", "2.0.0");
    }

    @Test
    void 并发结果提交与发布创建_按提交顺序裁决且最终状态一致() throws Exception {
        for (int round = 0; round < 8; round++) {
            String model = "m" + round;
            String deviceId = "d" + round;
            register(deviceId, model, "2.0.0", 1);
            long rehearsalId = rehearsalService.start(
                    startRequest("rs" + round, model, "2.0.0", "1.0.0", List.of(deviceId))).rehearsalId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rehearsalService.complete(rehearsalId,
                            successResults("rc" + seq, List.of(deviceId))),
                    (Callable<Object>) () -> releaseService.create(new CreateReleaseRequest(
                            "rr" + seq, model, "1.0.0", "2.0.0", 100)).releaseId()));

            // 结果提交必先成功；发布创建要么成功（提交先裁决）要么 409 GATE_NOT_READY（创建先裁决）
            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            Object createResult = results.get(1);
            if (createResult instanceof ApiException ae) {
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("GATE_NOT_READY");
            } else {
                assertThat(createResult).isInstanceOf(Long.class);
            }
            assertThat(rehearsalService.findRehearsal(rehearsalId).status())
                    .isEqualTo(RehearsalStatus.PASSED);
            Long gates = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_gate WHERE model = ?", Long.class, model);
            Long releases = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_order WHERE model = ?", Long.class, model);
            assertThat(gates).isEqualTo(1);
            // 发布单若创建成功必然持有对应门禁；未成功则数量为0，不存在无门禁的正式投放
            assertThat(releases).isBetween(0L, 1L);
            assertThat(releases).isLessThanOrEqualTo(gates);
        }
    }
}
