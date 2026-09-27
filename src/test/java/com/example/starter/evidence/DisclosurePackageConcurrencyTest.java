package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
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
 * 披露包并发边界测试（真实 H2 行锁与事务）：建包、批量增删、借出、交付、撤销
 * 并发时按事务提交顺序裁决；同 packageKey 重放幂等，失败不占键，不留半成品状态。
 */
@SpringBootTest
class DisclosurePackageConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private DisclosurePackageController packageController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private DisclosurePackageService packageService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
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

    private Callable<ResponseEntity<String>> createPackage(String actor, String packageKey,
                                                           String caseKey, int revision,
                                                           LocalDateTime asOf,
                                                           List<String> evidenceKeys) {
        return () -> packageController.create(actor,
                new PackageCreateRequest(packageKey, caseKey, revision, asOf, evidenceKeys));
    }

    @Test
    void concurrentCreateWithSamePackageKeyReplaysSingleResult() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String packageKey = uniqueKey("PKG");
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));

        List<Integer> statuses = runConcurrently(
                createPackage("carol", packageKey, caseKey, 1, asOf, List.of(ev)),
                createPackage("carol", packageKey, caseKey, 1, asOf, List.of(ev)));

        // 同键同参并发：两者都拿到首次结果（一个执行、一个重放）
        assertThat(statuses).containsOnly(201);
        var pkg = packageService.getPackage(packageKey);
        assertThat(pkg.itemCount()).isEqualTo(1);
        assertThat(pkg.items()).hasSize(1);
        assertThat(pkg.items().get(0).evidenceKey()).isEqualTo(ev);
    }

    @Test
    void concurrentCreateSameCaseRevisionDifferentKeysExactlyOneWins() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String key1 = uniqueKey("PKG");
        String key2 = uniqueKey("PKG");
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));

        List<Integer> statuses = runConcurrently(
                createPackage("carol", key1, caseKey, 1, asOf, List.of(ev)),
                createPackage("carol", key2, caseKey, 1, asOf, List.of(ev)));

        // 同一案件同一版本：按提交顺序恰有一个成功，另一个 409
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        String winner = statuses.get(0) == 201 ? key1 : key2;
        String loser = statuses.get(0) == 201 ? key2 : key1;
        assertThat(packageService.getPackage(winner).revision()).isEqualTo(1);
        try {
            packageService.getPackage(loser);
            throw new AssertionError("失败方不应留下半成品包");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(404);
        }
    }

    @Test
    void concurrentBorrowAndCreateResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String packageKey = uniqueKey("PKG");
        // 截至时刻在未来：借出若先提交，建包将看到截至时刻借出未归还
        LocalDateTime asOf = utc(BASE.plusSeconds(36000));

        List<Integer> statuses = runConcurrently(
                createPackage("carol", packageKey, caseKey, 1, asOf, List.of(ev)),
                () -> evidenceController.borrow("alice", ev,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "bob",
                                "鉴定用", utc(BASE.plusSeconds(7200)))));

        // 借出不受建包影响恒成功；建包按提交顺序为 201（先冻结）或 422（看到借出）
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(201, 422);
        var chain = evidenceController.custodyChain(ev);
        assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        if (statuses.get(0) == 201) {
            // 建包先提交：包冻结于借出之前，快照保管人仍为 alice，且不被后续借出改写
            var pkg = packageService.getPackage(packageKey);
            assertThat(pkg.items().get(0).custodianId()).isEqualTo("alice");
            assertThat(pkg.items().get(0).chainVersion()).isEqualTo(0);
        } else {
            // 借出先提交：整包不生成，不留半成品
            try {
                packageService.getPackage(packageKey);
                throw new AssertionError("422 时不得留下包");
            } catch (ApiException e) {
                assertThat(e.status().value()).isEqualTo(404);
            }
        }
    }

    @Test
    void concurrentDeliverAndRevokeExactlyOneWins() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String packageKey = uniqueKey("PKG");
        ResponseEntity<String> created = packageController.create("carol",
                new PackageCreateRequest(packageKey, caseKey, 1,
                        utc(BASE.plusSeconds(3600)), List.of(ev)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                () -> packageController.deliver("dave", packageKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> packageController.revoke("carol", packageKey,
                        new PackageRevokeRequest(uniqueKey("CMD"), "并发撤销")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var pkg = packageService.getPackage(packageKey);
        if (statuses.get(0) == 200) {
            // 交付先提交：状态 DELIVERED，撤销被拒
            assertThat(pkg.status()).isEqualTo(DisclosurePackageStatus.DELIVERED);
            assertThat(pkg.deliveredBy()).isEqualTo("dave");
            assertThat(packageService.revocation(packageKey).revoked()).isFalse();
        } else {
            // 撤销先提交：状态 REVOKED 且写原因，交付被拒
            assertThat(pkg.status()).isEqualTo(DisclosurePackageStatus.REVOKED);
            var revocation = packageService.revocation(packageKey);
            assertThat(revocation.revoked()).isTrue();
            assertThat(revocation.revokeReason()).isEqualTo("并发撤销");
        }
    }

    @Test
    void concurrentUpdateItemsAndDeliverResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        String packageKey = uniqueKey("PKG");
        packageController.create("carol",
                new PackageCreateRequest(packageKey, caseKey, 1,
                        utc(BASE.plusSeconds(3600)), List.of(ev1, ev2)));

        List<Integer> statuses = runConcurrently(
                () -> packageController.updateItems("carol", packageKey,
                        new PackageItemsUpdateRequest(uniqueKey("CMD"), List.of(), List.of(ev2))),
                () -> packageController.deliver("dave", packageKey,
                        new CommandRequest(uniqueKey("CMD"))));

        // 交付恒成功；增删按提交顺序为 200（先生效）或 409（交付后不可改）
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(200, 409);
        var pkg = packageService.getPackage(packageKey);
        assertThat(pkg.status()).isEqualTo(DisclosurePackageStatus.DELIVERED);
        if (statuses.get(0) == 200) {
            assertThat(pkg.itemCount()).isEqualTo(1);
            assertThat(pkg.items().get(0).evidenceKey()).isEqualTo(ev1);
        } else {
            assertThat(pkg.itemCount()).isEqualTo(2);
        }
    }
}
