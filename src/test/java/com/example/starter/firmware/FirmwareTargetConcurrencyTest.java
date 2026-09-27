package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchTagItemRequest;
import com.example.starter.firmware.api.BatchTagsRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TargetExpressionRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.repo.ReleaseTargetRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.DeviceTagService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 目标策略并发测试（真实 H2 事务与行锁）：标签批量修改与发布单快照解析按提交顺序串行裁决，
 * 快照只可能基于一个已提交字典版本；同 requestId 重放副作用只发生一次。
 */
@SpringBootTest
class FirmwareTargetConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceTagService deviceTagService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ReleaseTargetRepository releaseTargetRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_target");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_tag");
        jdbc.update("DELETE FROM tag_revision");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE tag_dict_meta SET version = 0 WHERE id = 1");
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

    private BatchTagsRequest batchRequest(String requestId, String operator, String deviceId, String... tags) {
        return new BatchTagsRequest(requestId, operator,
                List.of(new BatchTagItemRequest(deviceId, List.of(tags))));
    }

    private CreateReleaseRequest releaseRequest(String requestId, String model) {
        return new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100,
                null, null, "alice",
                new TargetExpressionRequest(List.of("beta"), List.of(), List.of()));
    }

    @Test
    void 并发批量改标签与创建发布_快照只基于一个已提交版本_两种顺序都出现() throws Exception {
        int releaseFirst = 0;
        int batchFirst = 0;
        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            String model = "m" + round;
            // d1 无 beta（批量后才有），d2 初始即有 beta
            deviceService.register(new RegisterDeviceRequest("rd1-" + round, "d1-" + round, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("rd2-" + round, "d2-" + round, model, "1.0.0", 2));
            deviceTagService.batchModify(batchRequest("init-" + round, "alice", "d2-" + round, "beta"));
            final int seq = round;
            // init 是本轮之前最后一次修订；记录基线版本，断言只允许基线或基线+1两个一致结果
            final long baseVersion = jdbc.queryForObject(
                    "SELECT version FROM tag_dict_meta WHERE id=1", Long.class);

            List<Object> results = runConcurrently(List.of(
                    () -> deviceTagService.batchModify(
                            batchRequest("batch-" + seq, "bob", "d1-" + seq, "beta")),
                    () -> releaseService.create(releaseRequest("rel-" + seq, model)).releaseId()));
            assertThat(results).as("第%d轮两个操作都应成功", seq)
                    .noneMatch(r -> r instanceof Exception);

            ReleaseOrder order = releaseService.findOrder((Long) results.get(1));
            long snapshotSize = releaseTargetRepository.countMembers(order.id());
            if (order.tagDictVersion() == baseVersion && snapshotSize == 1) {
                // 发布先提交：解析时为基线版本（init后），快照仅 d2
                releaseFirst++;
            } else if (order.tagDictVersion() == baseVersion + 1 && snapshotSize == 2) {
                // 批量先提交：字典版本加一，快照包含 d1、d2
                batchFirst++;
            } else {
                throw new AssertionError("非预期中间态: dictVersion=" + order.tagDictVersion()
                        + ", baseVersion=" + baseVersion + ", snapshotSize=" + snapshotSize);
            }
        }
        // 闩锁对齐下两种提交顺序都应实际出现，且始终不存在半成品快照
        assertThat(releaseFirst).isGreaterThan(0);
        assertThat(batchFirst).isGreaterThan(0);
        assertThat(releaseFirst + batchFirst).isEqualTo(rounds);
    }

    @Test
    void 并发同requestId批量修改_重放一致_字典版本只加一次_修订只一条() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "1.0.0", 1));
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceTagService.batchModify(batchRequest("same-batch", "alice", "d1", "beta")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM tag_dict_meta WHERE id=1", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_revision", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT tag FROM device_tag WHERE device_id='d1'", String.class))
                .containsExactly("beta");
    }

    @Test
    void 并发拉取_快照成员只建一条任务_非成员绝不建任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "1.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("rd2", "d2", "m1", "1.0.0", 2));
        deviceTagService.batchModify(batchRequest("init", "alice", "d1", "beta"));
        long releaseId = releaseService.create(releaseRequest("rel", "m1")).releaseId();
        assertThat(releaseTargetRepository.countMembers(releaseId)).isEqualTo(1);

        int threads = 8;
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "pull-member-" + seq.incrementAndGet()));
        }
        // requestId 各自不同，靠唯一约束与行锁收敛
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            assertThat(((PullResponse) result).task()).isNotNull();
        }
        Long memberTasks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id=? AND device_id='d1'",
                Long.class, releaseId);
        assertThat(memberTasks).isEqualTo(1L);

        // 非快照成员并发拉取：全部无任务且不产生任何行
        List<Callable<Object>> outsiderTasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            outsiderTasks.add(() -> taskService.pull("d2", "pull-out-" + seq.incrementAndGet()));
        }
        List<Object> outsiderResults = runConcurrently(outsiderTasks);
        for (Object result : outsiderResults) {
            assertThat(((PullResponse) result).task()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id=?",
                Long.class, releaseId)).isEqualTo(1L);
    }
}
