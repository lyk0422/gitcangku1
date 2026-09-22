package com.example.starter.firmware;

import com.example.starter.firmware.dto.DeviceRegisterRequest;
import com.example.starter.firmware.dto.DeviceResponse;
import com.example.starter.firmware.dto.PullRequest;
import com.example.starter.firmware.dto.RatioUpdateRequest;
import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.ReleaseCreateRequest;
import com.example.starter.firmware.dto.ReleaseResponse;
import com.example.starter.firmware.dto.RequestIdOnlyRequest;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
 * 并发与幂等边界测试：真实并发执行，断言最终结果与数据库状态一致。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:firmware_test_concurrent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class ConcurrencyTest extends AbstractIntegrationTest {

    private static final String DB_URL =
            "jdbc:h2:mem:firmware_test_concurrent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @AfterAll
    static void releaseDatabase() throws Exception {
        shutdownDatabase(DB_URL);
    }

    /**
     * 用统一发令枪并发执行一批任务，收集正常结果或 ApiException，全部在超时内完成。
     */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return task.call();
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        gate.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private ReleaseResponse newActiveRelease(String model, int ratio) {
        return releaseService.create(new ReleaseCreateRequest(
                "rel-" + model + "-" + ratio + "-" + System.nanoTime(), model, "1.0", "2.0", ratio));
    }

    @Test
    void concurrentSameRequestId_replaysSingleSuccess() throws Exception {
        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.register(
                    new DeviceRegisterRequest("idem-1", "dev-c", "model-A", "1.0", 10)));
        }
        List<Object> results = runConcurrently(tasks);

        DeviceResponse first = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(DeviceResponse.class);
            if (first == null) {
                first = (DeviceResponse) result;
            } else {
                assertThat(result).isEqualTo(first);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentPull_sameDevice_createsSingleTask() throws Exception {
        deviceService.register(new DeviceRegisterRequest("d1", "dev-c", "model-A", "1.0", 10));
        ReleaseResponse release = newActiveRelease("model-A", 50);

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "pull-" + i;
            tasks.add(() -> taskService.pull(new PullRequest(requestId, "dev-c")));
        }
        List<Object> results = runConcurrently(tasks);

        long taskId = -1;
        for (Object result : results) {
            assertThat(result).isInstanceOf(TaskResponse.class);
            TaskResponse task = (TaskResponse) result;
            if (taskId < 0) {
                taskId = task.id();
            } else {
                assertThat(task.id()).isEqualTo(taskId);
            }
            assertThat(task.releaseId()).isEqualTo(release.id());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRatioUpdate_sameExpectedVersion_exactlyOneWins() throws Exception {
        ReleaseResponse release = newActiveRelease("model-A", 10);

        List<Callable<Object>> tasks = List.of(
                () -> releaseService.updateRatio(release.id(), new RatioUpdateRequest("ru-a", 30, 1)),
                () -> releaseService.updateRatio(release.id(), new RatioUpdateRequest("ru-b", 40, 1)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof ReleaseResponse).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException
                        && "VERSION_CONFLICT".equals(((ApiException) r).getCode()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        ReleaseResponse winner = results.stream()
                .filter(r -> r instanceof ReleaseResponse)
                .map(r -> (ReleaseResponse) r).findFirst().orElseThrow();
        ReleaseResponse current = releaseService.get(release.id());
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.ratio()).isEqualTo(winner.ratio());
    }

    @Test
    void concurrentPullVsCancel_consistentCommitOrder() throws Exception {
        deviceService.register(new DeviceRegisterRequest("d1", "dev-c", "model-A", "1.0", 10));
        ReleaseResponse release = newActiveRelease("model-A", 50);

        List<Callable<Object>> tasks = List.of(
                () -> taskService.pull(new PullRequest("pull-1", "dev-c")),
                () -> releaseService.cancel(release.id(), new RequestIdOnlyRequest("cancel-1")));
        List<Object> results = runConcurrently(tasks);

        Object pullResult = results.get(0);
        Object cancelResult = results.get(1);
        assertThat(cancelResult).isInstanceOf(ReleaseResponse.class);
        assertThat(((ReleaseResponse) cancelResult).status()).isEqualTo("CANCELLED");

        List<TaskResponse> tasksOfRelease = taskService.query(release.id(), null);
        if (pullResult instanceof TaskResponse pulled) {
            // 拉取先提交：任务随后被取消，迟到回执必须 409 且不更新设备版本。
            assertThat(tasksOfRelease).hasSize(1);
            assertThat(tasksOfRelease.get(0).status()).isEqualTo("CANCELLED");
            Object lateReceipt = runConcurrently(List.of(
                    () -> taskService.receipt(pulled.id(), new ReceiptRequest("rc-late", "SUCCESS"))))
                    .get(0);
            assertThat(lateReceipt).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lateReceipt).getCode()).isEqualTo("TASK_CANCELLED");
            assertThat(deviceService.get("dev-c").currentVersion()).isEqualTo("1.0");
        } else {
            // 取消先提交：拉取只能得到 NO_TASK_AVAILABLE，且不产生任务。
            assertThat(pullResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) pullResult).getCode()).isEqualTo("NO_TASK_AVAILABLE");
            assertThat(tasksOfRelease).isEmpty();
        }
    }

    @Test
    void concurrentReceiptVsCancel_consistentCommitOrder() throws Exception {
        deviceService.register(new DeviceRegisterRequest("d1", "dev-c", "model-A", "1.0", 10));
        ReleaseResponse release = newActiveRelease("model-A", 50);
        TaskResponse task = taskService.pull(new PullRequest("pull-1", "dev-c"));

        List<Callable<Object>> tasks = List.of(
                () -> taskService.receipt(task.id(), new ReceiptRequest("rc-1", "SUCCESS")),
                () -> releaseService.cancel(release.id(), new RequestIdOnlyRequest("cancel-1")));
        List<Object> results = runConcurrently(tasks);

        Object receiptResult = results.get(0);
        TaskResponse finalTask = taskService.query(release.id(), "dev-c").get(0);
        if (receiptResult instanceof TaskResponse) {
            // 回执先提交：任务已成功，取消不得回滚成功设备。
            assertThat(finalTask.status()).isEqualTo("SUCCESS");
            assertThat(deviceService.get("dev-c").currentVersion()).isEqualTo("2.0");
        } else {
            // 取消先提交：回执必须 409，设备版本不变。
            assertThat(receiptResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) receiptResult).getCode()).isEqualTo("TASK_CANCELLED");
            assertThat(finalTask.status()).isEqualTo("CANCELLED");
            assertThat(deviceService.get("dev-c").currentVersion()).isEqualTo("1.0");
        }
    }
}
