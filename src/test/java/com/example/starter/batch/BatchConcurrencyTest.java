package com.example.starter.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发边界测试：最终检验、两个批准与召回并发时按事务提交顺序生效，
 * 不出现失败批次被放行、召回后再次放行或同一人完成两角色批准。
 */
@SpringBootTest
class BatchConcurrencyTest {

    @Autowired
    private BatchService service;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String newBatch(List<String> items) {
        String batchKey = unique("batch");
        service.createBatch(new CreateBatchRequest(unique("cmd"), batchKey, "PROD-1", "LOT-1",
                Instant.parse("2026-09-21T08:00:00Z"), items));
        return batchKey;
    }

    private void pass(String batchKey, String item, String inspector) {
        service.submitTest(batchKey, new SubmitTestRequest(unique("cmd"), unique("tk"), item,
                TestOutcome.PASS, inspector));
    }

    private String toPendingRelease() {
        String batchKey = newBatch(List.of("ITEM-A", "ITEM-B"));
        pass(batchKey, "ITEM-A", "insp-1");
        pass(batchKey, "ITEM-B", "insp-2");
        return batchKey;
    }

    /** 两个任务同一起跑线并发执行。 */
    private static <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrappedFirst = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return first.call();
            };
            Callable<T> wrappedSecond = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return second.call();
            };
            Future<T> f1 = pool.submit(wrappedFirst);
            Future<T> f2 = pool.submit(wrappedSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 并发执行并捕获业务异常，返回结果或异常。 */
    private static Callable<Object> capture(Callable<Object> task) {
        return () -> {
            try {
                return task.call();
            } catch (ApiException e) {
                return e;
            }
        };
    }

    @Test
    void concurrentDifferentRoleApprovalsReleaseBatch() throws Exception {
        String batchKey = toPendingRelease();
        List<Object> results = runConcurrently(
                capture(() -> service.approve(batchKey, "quality-1", ApprovalRole.QUALITY, unique("cmd"))),
                capture(() -> service.approve(batchKey, "ops-1", ApprovalRole.OPERATIONS, unique("cmd"))));
        assertThat(results).allMatch(r -> r instanceof ApprovalResponse);
        BatchDetailResponse detail = service.detail(batchKey);
        assertThat(detail.status()).isEqualTo(BatchStatus.RELEASED);
        assertThat(detail.approvals()).hasSize(2);
    }

    @Test
    void concurrentSameActorTwoRolesOnlyOneWins() throws Exception {
        String batchKey = toPendingRelease();
        List<Object> results = runConcurrently(
                capture(() -> service.approve(batchKey, "same-person", ApprovalRole.QUALITY, unique("cmd"))),
                capture(() -> service.approve(batchKey, "same-person", ApprovalRole.OPERATIONS, unique("cmd"))));
        long succeeded = results.stream().filter(r -> r instanceof ApprovalResponse).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException e && e.status().value() == 409)
                .count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        BatchDetailResponse detail = service.detail(batchKey);
        assertThat(detail.status()).isEqualTo(BatchStatus.RELEASE_REVIEW);
        assertThat(detail.approvals()).hasSize(1);
    }

    @Test
    void concurrentRecallAndFinalApprovalStayConsistent() throws Exception {
        String batchKey = toPendingRelease();
        service.approve(batchKey, "quality-1", ApprovalRole.QUALITY, unique("cmd"));
        List<Object> results = runConcurrently(
                capture(() -> service.approve(batchKey, "ops-1", ApprovalRole.OPERATIONS, unique("cmd"))),
                capture(() -> service.recall(batchKey, "anyone-1",
                        new RecallRequest(unique("cmd"), "并发召回"))));
        Object approval = results.get(0);
        Object recall = results.get(1);
        BatchStatus finalStatus = service.detail(batchKey).status();
        if (recall instanceof RecallResponse) {
            // 批准先提交、召回后提交：最终 RECALLED，且召回后不能再放行
            assertThat(approval).isInstanceOf(ApprovalResponse.class);
            assertThat(finalStatus).isEqualTo(BatchStatus.RECALLED);
        } else {
            // 召回先拿到锁时批次尚未 RELEASED：召回 409，批次正常放行
            assertThat(recall).isInstanceOf(ApiException.class);
            assertThat(((ApiException) recall).status().value()).isEqualTo(409);
            assertThat(approval).isInstanceOf(ApprovalResponse.class);
            assertThat(finalStatus).isEqualTo(BatchStatus.RELEASED);
        }
    }

    @Test
    void concurrentFinalTestAndApprovalStayConsistent() throws Exception {
        String batchKey = newBatch(List.of("ITEM-A", "ITEM-B"));
        pass(batchKey, "ITEM-A", "insp-1");
        List<Object> results = runConcurrently(
                capture(() -> service.submitTest(batchKey,
                        new SubmitTestRequest(unique("cmd"), unique("tk"), "ITEM-B",
                                TestOutcome.PASS, "insp-2"))),
                capture(() -> service.approve(batchKey, "quality-1", ApprovalRole.QUALITY, unique("cmd"))));
        Object test = results.get(0);
        Object approval = results.get(1);
        assertThat(test).isInstanceOf(TestResultResponse.class);
        BatchStatus finalStatus = service.detail(batchKey).status();
        if (approval instanceof ApprovalResponse) {
            // 检验先提交：批准有效，进入 RELEASE_REVIEW
            assertThat(finalStatus).isEqualTo(BatchStatus.RELEASE_REVIEW);
        } else {
            // 批准先拿到锁时检验未满足：422，批次停在 PENDING_RELEASE
            assertThat(approval).isInstanceOf(ApiException.class);
            assertThat(((ApiException) approval).status().value()).isEqualTo(422);
            assertThat(finalStatus).isEqualTo(BatchStatus.PENDING_RELEASE);
        }
    }

    @Test
    void concurrentFailAndApprovalNeverReleaseRejectedBatch() throws Exception {
        String batchKey = newBatch(List.of("ITEM-A", "ITEM-B"));
        pass(batchKey, "ITEM-A", "insp-1");
        List<Object> results = runConcurrently(
                capture(() -> service.submitTest(batchKey,
                        new SubmitTestRequest(unique("cmd"), unique("tk"), "ITEM-B",
                                TestOutcome.FAIL, "insp-2"))),
                capture(() -> service.approve(batchKey, "quality-1", ApprovalRole.QUALITY, unique("cmd"))));
        Object test = results.get(0);
        Object approval = results.get(1);
        assertThat(test).isInstanceOf(TestResultResponse.class);
        // 无论提交顺序如何，失败批次绝不能被放行：批准必然失败（422 或 409），批次必然 REJECTED
        assertThat(approval).isInstanceOf(ApiException.class);
        assertThat(((ApiException) approval).status().value()).isIn(409, 422);
        assertThat(service.detail(batchKey).status()).isEqualTo(BatchStatus.REJECTED);
    }
}
