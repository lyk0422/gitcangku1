package com.example.starter.firmware.freeze;

import com.example.starter.firmware.api.CreateFreezeRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.EmergencyGrant;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterConfirmerRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.FreezeService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * 冻结与发布/拉取/回执的真实并发提交顺序、批量回滚与 freezeKey 幂等边界测试（真实 H2 事务与行锁）。
 */
@SpringBootTest
class FreezeConcurrencyTest {

    /**
     * 可控 UTC 时钟：冻结窗口判定与扫荡时刻由此驱动。
     */
    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-09-26T00:00:00Z");

        void set(Instant t) {
            this.instant = t;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private FreezeService freezeService;

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
        jdbc.update("DELETE FROM freeze_emergency_exception");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM freeze_order");
        jdbc.update("DELETE FROM freeze_confirmer");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

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

    private String iso(long deltaSeconds) {
        return clock.instant().plusSeconds(deltaSeconds).toString();
    }

    private long seedSetup(String model, String device) {
        releaseService.create(new CreateReleaseRequest("rel-" + device, model, "1.0.0", "2.0.0", 100));
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?,?,?,?)",
                device, model, "1.0.0", 1);
        return taskService.pull(device, "pull-" + device).task().taskId();
    }

    @Test
    void 并发冻结开始与回执_按提交顺序产生唯一终态_已完成不被冻结改写() throws Exception {
        int frozenFirst = 0;
        int receiptFirst = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String device = "d" + round;
            long taskId = seedSetup(model, device);
            int seq = round;

            Callable<Object> freeze = () -> freezeService.createFreeze(new CreateFreezeRequest(
                    "frz" + seq, iso(-5), iso(3600), List.of(model), List.of()));
            Callable<Object> receipt = () -> taskService.receipt(taskId,
                    new ReceiptRequest("rcp" + seq, ReceiptResult.SUCCESS));

            List<Object> results = runConcurrently(List.of(freeze, receipt));
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            Object receiptResult = results.get(1);

            if (finalStatus == TaskStatus.RELEASE_FROZEN) {
                // 冻结先提交：扫荡把 PENDING 转 RELEASE_FROZEN，回执 409，设备版本停留 1.0.0
                frozenFirst++;
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("TASK_FROZEN");
                        });
                assertThat(jdbc.queryForObject("SELECT current_version FROM device WHERE device_id=?",
                        String.class, device)).isEqualTo("1.0.0");
            } else {
                // 回执先提交：任务 SUCCESS，随后冻结不得改写已完成回执
                receiptFirst++;
                assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(jdbc.queryForObject("SELECT current_version FROM device WHERE device_id=?",
                        String.class, device)).isEqualTo("2.0.0");
                assertThat(jdbc.queryForObject("SELECT frozen_freeze_id FROM rollout_task WHERE id=?",
                        Object.class, taskId)).isNull();
            }
        }
        assertThat(frozenFirst + receiptFirst).isEqualTo(10);
    }

    @Test
    void 并发冻结开始与无例外新拉取_不残留PENDING且拉取要么422要么任务终被冻结() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "mm" + round;
            releaseService.create(new CreateReleaseRequest("rel" + round, model, "1.0.0", "2.0.0", 100));
            jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?,?,?,?)",
                    "dd" + round, model, "1.0.0", 1);
            int seq = round;

            Callable<Object> freeze = () -> freezeService.createFreeze(new CreateFreezeRequest(
                    "fz" + seq, iso(-5), iso(3600), List.of(model), List.of()));
            Callable<Object> pull = () -> taskService.pull("dd" + seq, "pl" + seq, null);

            List<Object> results = runConcurrently(List.of(freeze, pull));
            Object pullResult = results.get(1);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id=? AND status='PENDING'",
                    Long.class, "dd" + round);
            if (pullResult instanceof ApiException ae) {
                // 冻结先提交并生效：无例外拉取 422，无任务残留
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("EMERGENCY_GRANT_REQUIRED");
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE device_id=?",
                        Long.class, "dd" + round);
                assertThat(count).isZero();
            } else {
                // 拉取先提交创建 PENDING，随后冻结扫荡必须把它转为 RELEASE_FROZEN
                assertThat(pending).as("第%d轮不得残留PENDING", round).isZero();
                TaskStatus status = taskRepository.findByReleaseAndDevice(
                        jdbc.queryForObject("SELECT id FROM release_order WHERE model=?", Long.class, model),
                        "dd" + round).orElseThrow().status();
                assertThat(status).isEqualTo(TaskStatus.RELEASE_FROZEN);
            }
        }
    }

    @Test
    void 并发同freezeKey创建冻结令_重放一致且只落一条() throws Exception {
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> freezeService.createFreeze(new CreateFreezeRequest(
                    "same-frz", iso(3600), iso(7200), List.of("m1"), List.of())).freezeId());
        }
        List<Object> results = runConcurrently(tasks);
        for (Object r : results) {
            assertThat(r).isNotInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发同freezeKey紧急拉取_只建一条任务且只落一条例外() throws Exception {
        freezeService.registerConfirmer(new RegisterConfirmerRequest("c1", "alice"));
        freezeService.registerConfirmer(new RegisterConfirmerRequest("c2", "bob"));
        releaseService.create(new CreateReleaseRequest("rel", "m1", "1.0.0", "2.0.0", 100));
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?,?,?,?)",
                "d1", "m1", "1.0.0", 1);
        long freezeId = freezeService.createFreeze(new CreateFreezeRequest(
                "frz", iso(-5), iso(3600), List.of("m1"), List.of())).freezeId();

        EmergencyGrant grant = new EmergencyGrant("INC-1", "alice", "bob");
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "same-pull", grant).task().taskId());
        }
        List<Object> results = runConcurrently(tasks);
        for (Object r : results) {
            assertThat(r).isNotInstanceOf(Exception.class);
        }
        assertThat(results.stream().map(Object::toString).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT emergency_freeze_id FROM rollout_task", Long.class))
                .isEqualTo(freezeId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_emergency_exception", Long.class))
                .isEqualTo(1);
    }
}
