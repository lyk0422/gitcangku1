package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.PackageAdjustRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * 披露包并发与幂等边界测试：案件版本按事务提交顺序串行裁决；
 * 同 packageKey/命令键并发只产生一个包；交付与撤销竞争恰一者成功。
 */
@SpringBootTest
class DisclosurePackageConcurrencyTest {

    @Autowired
    private DisclosurePackageController controller;

    @Autowired
    private DisclosurePackageService packageService;

    @Autowired
    private EvidenceController evidenceController;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String intake(String caseKey) {
        String evidenceKey = uniqueKey("EV");
        ResponseEntity<String> response = evidenceController.intake("alice",
                new com.example.starter.evidence.dto.IntakeRequest(
                        uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return evidenceKey;
    }

    private PackageCreateRequest createRequest(String commandKey, String caseKey, List<String> keys) {
        return new PackageCreateRequest(commandKey, caseKey, keys,
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
    }

    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        private StatusCall(CountDownLatch ready, CountDownLatch start,
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
    void concurrentCreatesForSameCaseAssessVersionsInCommitOrder() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = intake(caseKey);
        String ev2 = intake(caseKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.create("alice",
                        createRequest(uniqueKey("CMD"), caseKey, List.of(ev1))),
                () -> controller.create("alice",
                        createRequest(uniqueKey("CMD"), caseKey, List.of(ev2))));

        assertThat(statuses).containsOnly(201);
        var packages = packageService.listByCase(caseKey);
        assertThat(packages).hasSize(2);
        assertThat(packages.stream().map(PackageView::packageVersion).sorted().toList())
                .containsExactly(1, 2);
    }

    @Test
    void concurrentSameCommandKeyCreatesExactlyOnePackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = intake(caseKey);
        String commandKey = uniqueKey("CMD");
        PackageCreateRequest request = createRequest(commandKey, caseKey, List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> controller.create("alice", request),
                () -> controller.create("alice", request));

        assertThat(statuses).containsOnly(201);
        var packages = packageService.listByCase(caseKey);
        assertThat(packages).hasSize(1);
        assertThat(packages.get(0).packageVersion()).isEqualTo(1);
    }

    @Test
    void failedCreateDoesNotBlockPackageKeyOrVersionOnLaterSuccess() {
        String caseKey = uniqueKey("CASE");
        String ev = intake(caseKey);
        String commandKey = uniqueKey("CMD");

        // 截至时刻早于入库：422，事务回滚不占版本
        try {
            packageService.createPackage("alice",
                    new PackageCreateRequest(commandKey, caseKey, List.of(ev),
                            LocalDateTime.now(ZoneOffset.UTC).minusDays(1)),
                    "hash-failed");
            throw new AssertionError("应抛出 422");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }

        // 修正截至时刻后同命令键重试成功，版本仍为 1
        StoredResponse success = packageService.createPackage("alice",
                createRequest(commandKey, caseKey, List.of(ev)), "hash-success");
        assertThat(success.status()).isEqualTo(201);
        assertThat(packageService.listByCase(caseKey)).hasSize(1);
        assertThat(packageService.listByCase(caseKey).get(0).packageVersion()).isEqualTo(1);
    }

    @Test
    void concurrentDeliverAndRevokeExactlyOneWins() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = intake(caseKey);
        ResponseEntity<String> created = controller.create("alice",
                createRequest(uniqueKey("CMD"), caseKey, List.of(ev)));
        String packageKey = packageService.listByCase(caseKey).get(0).packageKey();
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                () -> controller.deliver("alice", packageKey, new CommandRequest(uniqueKey("CMD"))),
                () -> controller.revoke("alice", packageKey,
                        new PackageRevokeRequest(uniqueKey("CMD"), "concurrent defect")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var pkg = packageService.getPackage(packageKey).pkg();
        if (pkg.status() == DisclosurePackageStatus.DELIVERED) {
            assertThat(pkg.deliveredBy()).isEqualTo("alice");
            assertThat(pkg.revokeReason()).isNull();
        } else {
            assertThat(pkg.status()).isEqualTo(DisclosurePackageStatus.REVOKED);
            assertThat(pkg.revokeReason()).isEqualTo("concurrent defect");
            assertThat(pkg.deliveredBy()).isNull();
        }
    }

    @Test
    void concurrentAdjustsSerializeIntoConsecutiveVersions() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = intake(caseKey);
        String ev2 = intake(caseKey);
        String ev3 = intake(caseKey);
        ResponseEntity<String> created = controller.create("alice",
                createRequest(uniqueKey("CMD"), caseKey, List.of(ev1)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String baseKey = packageService.listByCase(caseKey).get(0).packageKey();

        List<Integer> statuses = runConcurrently(
                () -> controller.adjust("alice", baseKey, new PackageAdjustRequest(
                        uniqueKey("CMD"), List.of(ev2), List.of())),
                () -> controller.adjust("alice", baseKey, new PackageAdjustRequest(
                        uniqueKey("CMD"), List.of(ev3), List.of())));

        assertThat(statuses).containsOnly(201);
        var packages = packageService.listByCase(caseKey);
        assertThat(packages).hasSize(3);
        assertThat(packages.stream().map(PackageView::packageVersion).sorted().toList())
                .containsExactly(1, 2, 3);
        // 两个修订包均基于 v1，彼此独立冻结
        assertThat(packages.get(0).basePackageKey()).isEqualTo(baseKey);
        assertThat(packages.get(1).basePackageKey()).isEqualTo(baseKey);
    }
}
