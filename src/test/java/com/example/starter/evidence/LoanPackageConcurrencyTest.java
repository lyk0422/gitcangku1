package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CasePermissionGrantRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageReturnRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组合借出包并发边界测试（真实 H2 行锁与事务）：
 * 含重叠证物的两个归还请求并发时只能一个成功；互不重叠的请求按提交顺序累计，
 * 包状态与已归还集合始终一致。
 */
@SpringBootTest
class LoanPackageConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private LoanPackageController packageController;

    @Autowired
    private LoanPackageService packageService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime due(long seconds) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private List<String> prepareEvidence(String caseKey, int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = uniqueKey("EV");
            ResponseEntity<String> response = evidenceController.intake("alice",
                    new IntakeRequest(uniqueKey("CMD"), key, caseKey, "DOCUMENT", "SEAL-1"));
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            keys.add(key);
        }
        return keys;
    }

    private void grant(String caseKey, String userId) {
        packageController.grantPermission("alice",
                new CasePermissionGrantRequest(uniqueKey("REQ"), caseKey, userId));
    }

    private void createPackage(String packageKey, List<String> keys) {
        List<PackageCreateRequest.Item> items = keys.stream()
                .map(key -> new PackageCreateRequest.Item(key, 1L))
                .toList();
        ResponseEntity<String> response = packageController.createPackage("alice",
                new PackageCreateRequest(uniqueKey("REQ"), packageKey, "bob", "庭审核验",
                        due(3600), items));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> returnBatch(String packageKey,
                                                         List<PackageReturnRequest.Item> items) {
        return () -> packageController.returnBatch("alice", packageKey,
                new PackageReturnRequest(uniqueKey("REQ"), "recv", "revw", items));
    }

    private PackageReturnRequest.Item returnItem(String evidenceKey) {
        return new PackageReturnRequest.Item(evidenceKey, 1L);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start,
                   Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return call.call().getStatusCode().value();
            } catch (ApiException e) {
                return e.status().value();
            }
        }
    }

    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOverlappingReturnsOnlyOneSucceeds() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 3);
        String packageKey = uniqueKey("PKG");
        grant(caseKey, "recv");
        grant(caseKey, "revw");
        createPackage(packageKey, keys);

        // 两个归还请求共享同一件证物（不同 requestId，非幂等重放）
        List<Integer> statuses = runConcurrently(
                returnBatch(packageKey, List.of(returnItem(keys.get(0)))),
                returnBatch(packageKey, List.of(returnItem(keys.get(0)), returnItem(keys.get(1)))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 已归还集合与包状态一致：恰有一个批次落账
        var view = packageService.getPackage(packageKey);
        assertThat(view.batches()).hasSize(1);
        long returnedCount = view.items().stream()
                .filter(item -> item.status() == PackageItemStatus.RETURNED).count();
        int batchItemCount = view.batches().get(0).items().size();
        assertThat(returnedCount).isEqualTo(batchItemCount);
        assertThat(view.status()).isEqualTo(PackageStatus.PARTIAL);
        // 剩余集合 = 全部 - 已归还
        var remaining = packageService.getRemaining(packageKey);
        assertThat(remaining.remaining()).hasSize(3 - (int) returnedCount);
    }

    @Test
    void concurrentDisjointReturnsBothAccumulateInCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 4);
        String packageKey = uniqueKey("PKG");
        grant(caseKey, "recv");
        grant(caseKey, "revw");
        createPackage(packageKey, keys);

        // 互不重叠的两个归还请求并发：两个都可成功，按提交顺序累计
        List<Integer> statuses = runConcurrently(
                returnBatch(packageKey, List.of(returnItem(keys.get(0)))),
                returnBatch(packageKey, List.of(returnItem(keys.get(1)))));

        assertThat(statuses).containsOnly(200);

        var view = packageService.getPackage(packageKey);
        assertThat(view.status()).isEqualTo(PackageStatus.PARTIAL);
        assertThat(view.batches()).hasSize(2);
        // 批次序号按提交顺序唯一递增
        assertThat(view.batches().get(0).batchSeq()).isEqualTo(1);
        assertThat(view.batches().get(1).batchSeq()).isEqualTo(2);
        long returnedCount = view.items().stream()
                .filter(item -> item.status() == PackageItemStatus.RETURNED).count();
        assertThat(returnedCount).isEqualTo(2);
        var remaining = packageService.getRemaining(packageKey);
        assertThat(remaining.remaining()).containsExactly(keys.get(2), keys.get(3));
    }

    @Test
    void concurrentFinalReturnsClosePackageExactlyOnce() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant(caseKey, "recv");
        grant(caseKey, "revw");
        createPackage(packageKey, keys);

        // 两个请求都尝试归还最后一件（重叠同一证物）：只能一个成功并触发关闭
        List<Integer> statuses = runConcurrently(
                returnBatch(packageKey, List.of(returnItem(keys.get(0)), returnItem(keys.get(1)))),
                returnBatch(packageKey, List.of(returnItem(keys.get(1)))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var view = packageService.getPackage(packageKey);
        assertThat(view.status()).isEqualTo(PackageStatus.CLOSED);
        assertThat(view.closedAt()).isNotNull();
        // 全部证物已归还，关闭快照只写一次且包含全部批次
        long returnedCount = view.items().stream()
                .filter(item -> item.status() == PackageItemStatus.RETURNED).count();
        assertThat(returnedCount).isEqualTo(2);
        var chain = packageService.getChain(packageKey);
        assertThat(chain.closeSnapshot()).isNotNull();
        assertThat(chain.closeSnapshot()).contains("\"batchCount\":" + view.batches().size());
        assertThat(packageService.getRemaining(packageKey).remaining()).isEmpty();
    }

    @Test
    void concurrentSameReturnRequestIdReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        List<String> keys = prepareEvidence(caseKey, 2);
        String packageKey = uniqueKey("PKG");
        grant(caseKey, "recv");
        grant(caseKey, "revw");
        createPackage(packageKey, keys);

        PackageReturnRequest request = new PackageReturnRequest(uniqueKey("REQ"), "recv", "revw",
                List.of(new PackageReturnRequest.Item(keys.get(0), 1L)));
        List<Integer> statuses = runConcurrently(
                () -> packageController.returnBatch("alice", packageKey, request),
                () -> packageController.returnBatch("alice", packageKey, request));

        assertThat(statuses).containsOnly(200);

        var view = packageService.getPackage(packageKey);
        assertThat(view.batches()).hasSize(1);
        long returnedCount = view.items().stream()
                .filter(item -> item.status() == PackageItemStatus.RETURNED).count();
        assertThat(returnedCount).isEqualTo(1);
    }
}
