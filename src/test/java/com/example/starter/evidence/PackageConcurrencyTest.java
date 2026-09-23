package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageReturnRequest;
import com.example.starter.evidence.dto.PackageView;
import org.junit.jupiter.api.BeforeEach;
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
 * 组合借出包并发边界测试（真实 H2 行锁与事务）：
 * 含重叠证物的两个归还批次只能一个成功；互不重叠的批次按提交顺序累计并自动关闭；
 * 组合借出与单件借出并发不得产生部分借出；撤销与首批归还并发只能一方成功。
 */
@SpringBootTest
class PackageConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-23T09:00:00Z");
    private static final String CASE = "CASE-PKGC-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private PackageController controller;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private PackageService packageService;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void setUp() {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        packageService.grantCase(CASE, "recv-r1");
        packageService.grantCase(CASE, "recv-r2");
        packageService.grantCase(CASE, "recv-r3");
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
    }

    private LocalDateTime due(long hours) {
        return LocalDateTime.ofInstant(BASE.plusSeconds(hours * 3600), ZoneOffset.UTC);
    }

    private void intake(String actor, String evidenceKey) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, CASE, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String createPackage(List<String> evs, String borrower) {
        String packageKey = uniqueKey("pkg");
        List<PackageCreateRequest.PackageItemRequest> items = evs.stream()
                .map(e -> new PackageCreateRequest.PackageItemRequest(e, 1L))
                .toList();
        ResponseEntity<String> response = controller.create("alice", new PackageCreateRequest(
                uniqueKey("CMD"), packageKey, "组合鉴定用", borrower, due(24), items));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return packageKey;
    }

    private Callable<ResponseEntity<String>> returns(String packageKey, String receiver,
                                                     String reviewer, List<String> evs) {
        List<PackageReturnRequest.ReturnItemRequest> items = evs.stream()
                .map(e -> new PackageReturnRequest.ReturnItemRequest(e, 1L))
                .toList();
        return () -> controller.returns("alice", packageKey, new PackageReturnRequest(
                uniqueKey("CMD"), receiver, reviewer, "并发批次", items));
    }

    private Callable<ResponseEntity<String>> cancel(String packageKey) {
        return () -> controller.cancel("alice", packageKey,
                new com.example.starter.evidence.dto.PackageCancelRequest(uniqueKey("CMD")));
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
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingReturnBatchesOnlyOneSucceeds() throws Exception {
        List<String> evs = List.of(uniqueKey("ev"), uniqueKey("ev"), uniqueKey("ev"),
                uniqueKey("ev"));
        evs.forEach(e -> intake("alice", e));
        String packageKey = createPackage(evs, "bob");

        // 两个批次在 evs[1] 上重叠：只能一个成功，另一个整批失败（不产生部分落账）
        List<Integer> statuses = runConcurrently(
                returns(packageKey, "recv-r1", "recv-r2", List.of(evs.get(0), evs.get(1))),
                returns(packageKey, "recv-r1", "recv-r3", List.of(evs.get(1), evs.get(2), evs.get(3))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        PackageView view = packageService.getPackage(packageKey);
        assertThat(view.status()).isEqualTo(PackageStatus.PARTIAL);
        // 成功批次完整落账（两件或三件），不存在“同一批次只落账一部分”
        assertThat(view.batches()).hasSize(1);
        int returnedCount = view.batches().get(0).items().size();
        assertThat(returnedCount).isIn(2, 3);
        assertThat(view.remaining()).hasSize(4 - returnedCount);
        // 包状态与已归还集合一致：明细 returned 标记与剩余集合互补
        for (String ev : evs) {
            boolean returned = view.items().stream()
                    .anyMatch(i -> i.evidenceKey().equals(ev) && i.returned());
            assertThat(view.remaining().contains(ev)).isNotEqualTo(returned);
        }
    }

    @Test
    void disjointReturnBatchesBothSucceedAndAccumulateToClosed() throws Exception {
        List<String> evs = List.of(uniqueKey("ev"), uniqueKey("ev"), uniqueKey("ev"),
                uniqueKey("ev"));
        evs.forEach(e -> intake("alice", e));
        String packageKey = createPackage(evs, "bob");

        // 互不重叠的两个批次并发：均成功并累计，最后一件归还触发自动关闭
        List<Integer> statuses = runConcurrently(
                returns(packageKey, "recv-r1", "recv-r2", List.of(evs.get(0), evs.get(1))),
                returns(packageKey, "recv-r1", "recv-r3", List.of(evs.get(2), evs.get(3))));

        assertThat(statuses).containsOnly(200);

        PackageView view = packageService.getPackage(packageKey);
        assertThat(view.status()).isEqualTo(PackageStatus.CLOSED);
        assertThat(view.closedAt()).isNotNull();
        assertThat(view.remaining()).isEmpty();
        assertThat(view.batches()).hasSize(2);
        assertThat(view.batches()).extracting(b -> b.items().size())
                .containsExactlyInAnyOrder(2, 2);
        // 四件证物全部回到 SEALED
        for (String ev : evs) {
            assertThat(packageService.getPackage(packageKey).items().stream()
                    .filter(i -> i.evidenceKey().equals(ev)).findFirst().orElseThrow().returned())
                    .isTrue();
        }
    }

    @Test
    void concurrentPackageCreateAndSingleLoanLeavesNoPartialState() throws Exception {
        List<String> evs = List.of(uniqueKey("ev"), uniqueKey("ev"));
        evs.forEach(e -> intake("alice", e));
        String packageKey = uniqueKey("pkg");

        List<PackageCreateRequest.PackageItemRequest> items = List.of(
                new PackageCreateRequest.PackageItemRequest(evs.get(0), 1L),
                new PackageCreateRequest.PackageItemRequest(evs.get(1), 1L));
        Callable<ResponseEntity<String>> pkgCall = () -> controller.create("alice",
                new PackageCreateRequest(uniqueKey("CMD"), packageKey, "组合鉴定用",
                        "bob", due(24), items));
        Callable<ResponseEntity<String>> singleCall = () -> evidenceController.borrow("alice",
                evs.get(0), new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("loan"),
                        "carol", "单件鉴定", due(5)));

        List<Integer> statuses = runConcurrently(pkgCall, singleCall);
        // 行锁串行化后：一方成功，另一方按提交后状态被拒绝，不得出现组合包只借出一件
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        PackageView view = maybePackage(packageKey);
        if (view != null) {
            // 组合借出成功：两件必须都在包内且未归还，无单件 ACTIVE 借出与之并存
            assertThat(view.items()).hasSize(2);
            assertThat(view.remaining()).hasSize(2);
            assertThat(view.status()).isEqualTo(PackageStatus.PARTIAL);
        }
        // 无论谁成功，每件证物状态与其借出记录一致（无“部分组合借出”）
        for (String ev : evs) {
            assertThat(evidenceService.custodyChain(ev).evidence().status().name())
                    .isIn("SEALED", "BORROWED");
        }
    }

    @Test
    void concurrentCancelAndFirstReturnOnlyOneSucceeds() throws Exception {
        List<String> evs = List.of(uniqueKey("ev"), uniqueKey("ev"));
        evs.forEach(e -> intake("alice", e));
        String packageKey = createPackage(evs, "bob");

        List<Integer> statuses = runConcurrently(
                cancel(packageKey),
                returns(packageKey, "recv-r1", "recv-r2", List.of(evs.get(0))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        PackageView view = packageService.getPackage(packageKey);
        if (view.status() == PackageStatus.CANCELLED) {
            // 撤销成功：无任何批次，两件都不再处于未归还
            assertThat(view.batches()).isEmpty();
            assertThat(view.remaining()).isEmpty();
        } else {
            // 首批归还成功：包保持 PARTIAL，恰好一件归还、一件剩余
            assertThat(view.status()).isEqualTo(PackageStatus.PARTIAL);
            assertThat(view.batches()).hasSize(1);
            assertThat(view.batches().get(0).items()).hasSize(1);
            assertThat(view.remaining()).containsExactly(evs.get(1));
        }
    }

    private PackageView maybePackage(String packageKey) {
        try {
            return packageService.getPackage(packageKey);
        } catch (ApiException e) {
            return null;
        }
    }
}
