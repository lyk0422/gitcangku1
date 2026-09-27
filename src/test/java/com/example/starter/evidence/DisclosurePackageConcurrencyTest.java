package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageDeliverRequest;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageView;
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
 * 披露包并发与幂等边界测试（真实 H2 行锁与事务）：
 * 同键并发建包只生成一个版本；建包与增删按案件锁提交顺序裁决；
 * 交付与撤销并发恰一方生效，不出现版本号冲突或状态矛盾。
 */
@SpringBootTest
class DisclosurePackageConcurrencyTest {

    private static final Instant BASE = Instant.now();
    private static final LocalDateTime AS_OF =
            LocalDateTime.ofInstant(BASE.plusSeconds(3600).plusSeconds(9 * 3600), ZoneOffset.UTC);

    @Autowired
    private DisclosurePackageController controller;

    @Autowired
    private DisclosurePackageService packageService;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String actor, String caseKey, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private Callable<ResponseEntity<String>> create(String actor, String caseKey,
                                                    List<String> keys, String commandKey) {
        return () -> controller.create(actor, caseKey,
                new PackageCreateRequest(commandKey, keys, AS_OF));
    }

    private Callable<ResponseEntity<String>> update(String actor, String caseKey,
                                                    List<String> keys) {
        return () -> controller.updateItems(actor, caseKey,
                new PackageItemsUpdateRequest(uniqueKey("CMD"), keys, AS_OF));
    }

    private Callable<ResponseEntity<String>> deliver(String actor, String caseKey) {
        return () -> controller.deliver(actor, caseKey,
                new PackageDeliverRequest(uniqueKey("CMD"), "并发交付"));
    }

    private Callable<ResponseEntity<String>> revoke(String actor, String caseKey) {
        return () -> controller.revoke(actor, caseKey,
                new PackageRevokeRequest(uniqueKey("CMD"), "并发撤销"));
    }

    private Callable<ResponseEntity<String>> borrow(String actor, String evidenceKey,
                                                    String borrower) {
        return () -> evidenceController.borrow(actor, evidenceKey,
                new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), borrower,
                        "鉴定用", AS_OF.minusHours(8)));
    }

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
            start.await(10, TimeUnit.SECONDS);
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameCommandKeyCreatesExactlyOneVersionAndReplays() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        String commandKey = uniqueKey("CMD");

        List<Integer> statuses = runConcurrently(
                create("officer", caseKey, List.of(e1), commandKey),
                create("officer", caseKey, List.of(e1), commandKey));

        assertThat(statuses).containsOnly(201);
        List<PackageView> versions = packageService.listPackages(caseKey);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).packageVersion()).isEqualTo(1);
        assertThat(versions.get(0).items()).hasSize(1);
    }

    @Test
    void concurrentCreateAndUpdateResolveByCommitOrder() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        intake("alice", caseKey, e2);

        // 案件尚无包：建包与增删并发。增删先提交得 404，建包先提交则增删随后生成版本 2。
        List<Integer> statuses = runConcurrently(
                create("officer", caseKey, List.of(e1), uniqueKey("CMD")),
                update("officer", caseKey, List.of(e1, e2)));

        long success = statuses.stream().filter(s -> s == 200 || s == 201).count();
        assertThat(success).isGreaterThanOrEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 404);

        List<PackageView> versions = packageService.listPackages(caseKey);
        if (statuses.contains(404)) {
            // 增删先提交（无包 404），建包随后成功：仅版本 1。
            assertThat(versions).hasSize(1);
            assertThat(versions.get(0).packageVersion()).isEqualTo(1);
        } else {
            // 建包先提交，增删随后成功：版本 1 与版本 2 连续，版本号/指纹不冲突。
            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            assertThat(versions).hasSize(2);
            assertThat(versions.get(0).packageVersion()).isEqualTo(1);
            assertThat(versions.get(1).packageVersion()).isEqualTo(2);
            assertThat(versions.get(0).packageKey()).isNotEqualTo(versions.get(1).packageKey());
        }
        assertThat(versions.get(versions.size() - 1).status()).isEqualTo(PackageStatus.GENERATED);
    }

    @Test
    void concurrentDeliverAndRevokeExactlyOneWins() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        intake("alice", caseKey, e1);
        ResponseEntity<String> created = controller.create("officer", caseKey,
                new PackageCreateRequest(uniqueKey("CMD"), List.of(e1), AS_OF));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Integer> statuses = runConcurrently(
                deliver("officer", caseKey),
                revoke("officer", caseKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        PackageView latest = packageService.getLatestPackage(caseKey);
        assertThat(latest.packageVersion()).isEqualTo(1);
        // 终态唯一：要么已交付、要么已撤销，不允许字段互相矛盾。
        if (latest.status() == PackageStatus.DELIVERED) {
            assertThat(latest.deliveredBy()).isEqualTo("officer");
            assertThat(latest.revokedBy()).isNull();
        } else {
            assertThat(latest.status()).isEqualTo(PackageStatus.REVOKED);
            assertThat(latest.revokedBy()).isEqualTo("officer");
            assertThat(latest.revokeReason()).isNotBlank();
            assertThat(latest.deliveredBy()).isNull();
        }
    }

    @Test
    void concurrentCreateAndBorrowResolveByCommitOrderWithAsOfReplay() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String caseKey = uniqueKey("CASE");
        String e1 = uniqueKey("EV");
        intake("alice", caseKey, e1);

        // 建包（asOf 在借出时刻之后）与借出并发：借出先提交则建包按截至时刻看到借出未归还 -> 422；
        // 建包先拿到证物锁则固化成功，借出走证物锁随后生效，但不得回写已固化包。
        List<Integer> statuses = runConcurrently(
                create("officer", caseKey, List.of(e1), uniqueKey("CMD")),
                borrow("alice", e1, "bob"));

        int packageStatus = statuses.get(0);
        int borrowStatus = statuses.get(1);
        assertThat(borrowStatus).isEqualTo(200);
        assertThat(packageStatus).isIn(201, 422);

        var chain = evidenceService.custodyChain(e1);
        if (packageStatus == 422) {
            // 借出先提交：截至时刻借出未归还，整包不生成且不留版本。
            assertThat(packageService.listPackages(caseKey)).isEmpty();
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
            assertThat(chain.loans()).hasSize(1);
        } else {
            // 建包先提交：包固化为封签正常、位置 alice；借出之后生效不改写包。
            PackageView pkg = packageService.getLatestPackage(caseKey);
            assertThat(pkg.packageVersion()).isEqualTo(1);
            PackageItemView item = pkg.items().get(0);
            assertThat(item.custodianAtAsOf()).isEqualTo("alice");
            assertThat(item.sealSnapshot().intactAtAsOf()).isTrue();
            assertThat(item.summary().activeLoanAtAsOf()).isFalse();
            assertThat(chain.evidence().status()).isEqualTo(EvidenceStatus.BORROWED);
        }
    }
}
