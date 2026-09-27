package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterFirmwareRequest;
import com.example.starter.firmware.api.RevokeCertChainRequest;
import com.example.starter.firmware.api.RolloverCertChainRequest;
import com.example.starter.firmware.api.UpsertCompatRequest;
import com.example.starter.firmware.domain.CertReviewStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.CertificateService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 证书轮换并发与幂等边界：真实并发打到 H2 事务、行锁与唯一约束上，
 * 验证轮换/撤销/启动/回执按提交顺序裁决，同 certKey 重放只生效一次，失败不留半成品。
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:firmware_cert_conc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class FirmwareCertConcurrencyTest {

    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    static final String ROLLOVER_AT = "2026-11-01T00:00:00Z";

    static final FirmwareCertificateTest.MutableClock CLOCK = new FirmwareCertificateTest.MutableClock(T0);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock businessClock() {
            return CLOCK;
        }
    }

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        CLOCK.setInstant(T0);
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM firmware_cert_chain");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM firmware_compat");
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
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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

    private void bootstrap(String model, int devices) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            String id = model + "-d" + i;
            deviceService.register(new RegisterDeviceRequest("rd-" + model + "-" + i, id, model, "1.0.0", i));
            ids.add(id);
        }
        certificateService.registerFirmware(new RegisterFirmwareRequest(
                "fw-" + model, model, "2.0.0", "alice"));
        certificateService.upsertCompat(new UpsertCompatRequest(
                "compat-" + model, model, "1.0.0", "2.0.0", true, "alice"));
        certificateService.registerChain(new RegisterCertChainRequest(
                "chain1-" + model, model, "2.0.0", 1, "fp-v1",
                "2026-09-01T00:00:00Z", null, ids, "alice"));
    }

    private String deviceId(String model, int index) {
        return model + "-d" + index;
    }

    @Test
    void 并发轮换_同certKey重放只产生一条新链() throws Exception {
        bootstrap("m1", 1);
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> certificateService.rollover(new RolloverCertChainRequest(
                    "same-roll", "m1", "2.0.0", 1, 2, "fp-v2", ROLLOVER_AT, List.of("m1-d0"), "alice")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).as("同 certKey 并发必须全部重放成功，不得抛错").isNotInstanceOf(Exception.class);
        }
        Long chains = jdbc.queryForObject(
                "SELECT COUNT(*) FROM firmware_cert_chain WHERE model = 'm1'", Long.class);
        assertThat(chains).isEqualTo(2);
        assertThat(certificateService.listChains("m1", "2.0.0").chains())
                .extracting(c -> com.example.starter.firmware.api.CertChainView.class.cast(c).chainVersion())
                .containsExactly(1, 2);
        // 旧链恰好闭合一次
        String validTo = jdbc.queryForObject(
                "SELECT valid_to_utc FROM firmware_cert_chain WHERE chain_version = 1 AND model = 'm1'",
                String.class);
        assertThat(validTo).isEqualTo(ROLLOVER_AT);
        Long trustV2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_trust WHERE model = 'm1' AND chain_version = 2", Long.class);
        assertThat(trustV2).isEqualTo(1);
    }

    @Test
    void 并发异键轮换_恰好一个成功_另一个可区分冲突且无多余链() throws Exception {
        bootstrap("m1", 1);
        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> certificateService.rollover(new RolloverCertChainRequest(
                        "roll-a", "m1", "2.0.0", 1, 2, "fp-a", ROLLOVER_AT, List.of("m1-d0"), "alice")),
                (Callable<Object>) () -> certificateService.rollover(new RolloverCertChainRequest(
                        "roll-b", "m1", "2.0.0", 1, 2, "fp-b", ROLLOVER_AT, List.of("m1-d0"), "alice"))));

        long success = results.stream().filter(r -> !(r instanceof Exception)).count();
        List<ApiException> conflicts = results.stream()
                .filter(r -> r instanceof ApiException)
                .map(r -> (ApiException) r).toList();
        assertThat(success).isEqualTo(1);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicts.get(0).code()).isEqualTo("OLD_CHAIN_NOT_OPEN");
        Long chains = jdbc.queryForObject(
                "SELECT COUNT(*) FROM firmware_cert_chain WHERE model = 'm1'", Long.class);
        assertThat(chains).isEqualTo(2);
    }

    @Test
    void 并发轮换与撤销_按提交顺序产生唯一合法结果() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            final int seq = round;
            bootstrap(model, 1);
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> certificateService.rollover(new RolloverCertChainRequest(
                            "roll-" + seq, model, "2.0.0", 1, 2, "fp-v2",
                            ROLLOVER_AT, List.of(deviceId(model, 0)), "alice")),
                    (Callable<Object>) () -> certificateService.revoke(new RevokeCertChainRequest(
                            "revoke-" + seq, model, "2.0.0", 1, "leak", "bob"))));

            String v1Status = jdbc.queryForObject(
                    "SELECT status FROM firmware_cert_chain WHERE model = ? AND chain_version = 1",
                    String.class, model);
            Long v2Count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM firmware_cert_chain WHERE model = ? AND chain_version = 2",
                    Long.class, model);
            String v1ValidTo = jdbc.queryForObject(
                    "SELECT valid_to_utc FROM firmware_cert_chain WHERE model = ? AND chain_version = 1",
                    String.class, model);

            Object rollResult = results.get(0);
            Object revokeResult = results.get(1);

            if (v2Count == 0) {
                // 撤销先提交：轮换必须失败（旧链非 ACTIVE），不得产生 v2，旧链不得被闭合
                assertThat(rollResult).isInstanceOf(ApiException.class);
                assertThat(((ApiException) rollResult).code()).isEqualTo("OLD_CHAIN_NOT_ACTIVE");
                assertThat(v1Status).isEqualTo("REVOKED");
                assertThat(v1ValidTo).isNull();
                // 撤销成功者返回正常，另一情形只可能是重放，不会是其他错误
                assertThat(revokeResult).isNotInstanceOf(Exception.class);
            } else {
                // 轮换先提交：v2 存在，v1 已闭合；随后撤销把 v1 置 REVOKED
                assertThat(rollResult).isNotInstanceOf(Exception.class);
                assertThat(v2Count).isEqualTo(1);
                assertThat(v1ValidTo).isEqualTo(ROLLOVER_AT);
                assertThat(revokeResult).isNotInstanceOf(Exception.class);
                String v1After = jdbc.queryForObject(
                        "SELECT status FROM firmware_cert_chain WHERE model = ? AND chain_version = 1",
                        String.class, model);
                assertThat(v1After).isEqualTo("REVOKED");
            }
        }
    }

    @Test
    void 并发批量启动_同设备集合最多每设备一条任务() throws Exception {
        bootstrap("m1", 4);
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "rel", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        List<String> all = List.of("m1-d0", "m1-d1", "m1-d2", "m1-d3");

        int threads = 5;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.batchStart(releaseId,
                    new BatchStartRequest("batch-" + seq, all, "alice")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        Long taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
        assertThat(taskCount).isEqualTo(4);
        for (String deviceId : all) {
            Long perDevice = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, releaseId, deviceId);
            assertThat(perDevice).isEqualTo(1);
        }
    }

    @Test
    void 并发撤销与回执_按提交顺序要么自动更新要么待复核() throws Exception {
        // 两事务不竞争同一行锁（回执锁发布单、撤销锁固件登记行），结果取决于提交顺序，
        // 因此每轮只断言最终状态自洽；两个分支各自的确定性覆盖见 FirmwareCertificateTest。
        int autoUpdated = 0;
        int pushedToReview = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            final int seq = round;
            bootstrap(model, 1);
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    "rel" + seq, model, "1.0.0", "2.0.0", 100)).releaseId();
            String d0 = deviceId(model, 0);
            long taskId = taskService.pull(d0, "pull" + seq).task().taskId();

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("rcpt" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> certificateService.revoke(new RevokeCertChainRequest(
                            "rev" + seq, model, "2.0.0", 1, "leak", "bob"))));
            assertThat(results).noneMatch(r -> r instanceof Exception
                    && !(r instanceof ApiException));

            var task = jdbc.queryForMap("SELECT status, review_status FROM rollout_task WHERE id = ?", taskId);
            String version = deviceService.get(d0).currentVersion();
            String reviewStatus = (String) task.get("review_status");
            if (reviewStatus != null && reviewStatus.equals("CERTIFICATE_REVOKED_REVIEW")) {
                // 撤销先于回执裁决：待人工复核，设备版本不动
                pushedToReview++;
                assertThat(task.get("status")).isEqualTo("SUCCESS");
                assertThat(version).isEqualTo("1.0.0");
                // 人工确认后版本才更新
                taskService.review(taskId, new com.example.starter.firmware.api.TaskReviewRequest(
                        "confirm" + round, com.example.starter.firmware.api.TaskReviewRequest.ReviewAction.CONFIRMED,
                        "ok", "carol"));
                assertThat(deviceService.get(d0).currentVersion()).isEqualTo("2.0.0");
            } else {
                // 回执先裁决：链当时未撤销，SUCCESS 直接更新版本，无复核
                autoUpdated++;
                assertThat(task.get("status")).isEqualTo("SUCCESS");
                assertThat(reviewStatus).isNull();
                assertThat(version).isEqualTo("2.0.0");
            }
            // 无论谁先，任务都不可能残留 PENDING
            assertThat(TaskStatus.valueOf((String) task.get("status"))).isNotEqualTo(TaskStatus.PENDING);
        }
        assertThat(autoUpdated + pushedToReview).isEqualTo(10);
    }

    @Test
    void 并发同certKey撤销_只裁决一次且状态一致() throws Exception {
        bootstrap("m1", 1);
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> certificateService.revoke(new RevokeCertChainRequest(
                    "same-revoke", "m1", "2.0.0", 1, "leak", "bob")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        Long revoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM firmware_cert_chain WHERE model = 'm1' AND status = 'REVOKED'",
                Long.class);
        assertThat(revoked).isEqualTo(1);
        String revokedAt = jdbc.queryForObject(
                "SELECT revoked_at_utc FROM firmware_cert_chain WHERE model = 'm1' AND chain_version = 1",
                String.class);
        assertThat(revokedAt).isEqualTo(T0.toString());
        assertThat(CertReviewStatus.CONFIRMED).isNotNull();
    }
}
