package com.example.starter.firmware;

import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RehearsalResultRequest;
import com.example.starter.firmware.api.StartRehearsalRequest;
import com.example.starter.firmware.domain.RehearsalStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.RehearsalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 回滚演练并发裁决与可控时钟边界测试：真实并发打到 H2 事务与唯一约束上；
 * 时钟使用可控固定 Clock 验证结束早于开始的 422 分支与时长实际值。
 */
@SpringBootTest
class FirmwareRehearsalConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private RehearsalService rehearsalService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_gate");
        jdbc.update("DELETE FROM rollback_rehearsal_device");
        jdbc.update("DELETE FROM rollback_rehearsal");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        Mockito.reset(clock);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(T0);
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

    @Test
    void 并发开始_同型号同目标版本至多一个RUNNING_其余409() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "2.0.0", 1));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> rehearsalService.start(new StartRehearsalRequest(
                    "rs" + seq, "alice", "m1", "2.0.0", "1.0.0", List.of("d1"))).rehearsalId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("REHEARSAL_ALREADY_RUNNING"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal WHERE status = 'RUNNING'", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发提交不同requestId_一个成功授予门禁_其余409_无重复门禁() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "2.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("rd2", "d2", "m1", "2.0.0", 2));
        long rehearsalId = rehearsalService.start(new StartRehearsalRequest(
                "rs1", "alice", "m1", "2.0.0", "1.0.0", List.of("d2", "d1"))).rehearsalId();

        when(clock.instant()).thenReturn(T0.plusSeconds(60));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> rehearsalService.submitResults(rehearsalId,
                    new RehearsalResultRequest("rf" + seq, "alice", List.of(
                            new RehearsalResultRequest.DeviceResult("d1", "SUCCESS"),
                            new RehearsalResultRequest.DeviceResult("d2", "SUCCESS")))).status());
        }
        List<Object> results = runConcurrently(tasks);

        long succeeded = results.stream().filter("SUCCEEDED"::equals).count();
        long finishedConflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("REHEARSAL_FINISHED"))
                .count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(finishedConflicts).isEqualTo(3);

        assertThat(rehearsalService.detail(rehearsalId).status())
                .isEqualTo(RehearsalStatus.SUCCEEDED.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result = 'SUCCESS'", Long.class))
                .isEqualTo(2);
        // 仅首个成功提交占用幂等键，冲突未占键
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE api = 'rehearsal.submit'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 并发提交同requestId_重放同一快照_门禁只授予一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "2.0.0", 1));
        long rehearsalId = rehearsalService.start(new StartRehearsalRequest(
                "rs1", "alice", "m1", "2.0.0", "1.0.0", List.of("d1"))).rehearsalId();

        when(clock.instant()).thenReturn(T0.plusSeconds(30));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> rehearsalService.submitResults(rehearsalId,
                    new RehearsalResultRequest("same-rid", "alice",
                            List.of(new RehearsalResultRequest.DeviceResult("d1", "SUCCESS"))))
                    .actualSuccessCount());
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'same-rid'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 可控时钟_结束早于开始422且不授门禁_拨回开始之后成功且时长为实际值() {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "2.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("rd2", "d2", "m1", "2.0.0", 2));
        long rehearsalId = rehearsalService.start(new StartRehearsalRequest(
                "rs1", "alice", "m1", "2.0.0", "1.0.0", List.of("d1", "d2"))).rehearsalId();
        assertThat(rehearsalService.detail(rehearsalId).startedAtUtc()).isEqualTo(T0.toString());

        // 结束时刻早于开始时刻（UTC，左闭右开）：422，演练仍 RUNNING，无门禁，设备结果未写入
        when(clock.instant()).thenReturn(T0.minusSeconds(60));
        assertThatThrownBy(() -> rehearsalService.submitResults(rehearsalId,
                new RehearsalResultRequest("rf-early", "alice", List.of(
                        new RehearsalResultRequest.DeviceResult("d1", "SUCCESS"),
                        new RehearsalResultRequest.DeviceResult("d2", "SUCCESS")))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("REHEARSAL_END_BEFORE_START");
                });
        assertThat(rehearsalService.detail(rehearsalId).status())
                .isEqualTo(RehearsalStatus.RUNNING.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result IS NOT NULL", Long.class)).isZero();

        // 时钟拨到开始之后：成功，时长=120秒（实际值）
        when(clock.instant()).thenReturn(T0.plusSeconds(120));
        var view = rehearsalService.submitResults(rehearsalId,
                new RehearsalResultRequest("rf-ok", "alice", List.of(
                        new RehearsalResultRequest.DeviceResult("d1", "SUCCESS"),
                        new RehearsalResultRequest.DeviceResult("d2", "SUCCESS"))));
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.durationSeconds()).isEqualTo(120L);
        assertThat(view.endedAtUtc()).isEqualTo(T0.plusSeconds(120).toString());
        assertThat(view.missingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
    }
}
