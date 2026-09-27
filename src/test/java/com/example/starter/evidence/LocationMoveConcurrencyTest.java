package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.EvidenceShelveRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.MoveCommandRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
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
 * 位置迁移并发与幂等边界测试（真实 H2 行锁与事务）：
 * 双人确认竞争、第二人确认与撤销竞争、确认与借出/归还并发按事务提交顺序裁决，
 * 不允许出现部分迁移、重复封签快照或在借出状态完成迁移。
 */
@SpringBootTest
class LocationMoveConcurrencyTest {

    @Autowired
    private LocationMoveController moveController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private LocationMoveService moveService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String cmd() {
        return uniqueKey("CMD");
    }

    private void location(String code) {
        ResponseEntity<String> response = moveController.createLocation("alice",
                new LocationCreateRequest(cmd(), code, "库位-" + code));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String intake(String actor) {
        String evidenceKey = uniqueKey("EV");
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(cmd(), evidenceKey, "CASE-1", "DOCUMENT", uniqueKey("SEAL")));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return evidenceKey;
    }

    private void shelve(String actor, String evidenceKey, String locationCode) {
        ResponseEntity<String> response = moveController.shelve(actor, evidenceKey,
                new EvidenceShelveRequest(cmd(), locationCode));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 构造两个库位与 n 件 alice 保管、已上架源库位的证物（版本 1），返回证物键。
     */
    private List<String> stock(String source, String target, int n) {
        location(source);
        location(target);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = intake("alice");
            shelve("alice", key, source);
            keys.add(key);
        }
        return keys;
    }

    private String applyMove(String actor, String moveKey, List<String> keys,
                             String source, String target) {
        ResponseEntity<String> response = moveController.createMove(actor,
                new MoveCreateRequest(cmd(), moveKey, keys, source, target, 1L));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return moveKey;
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
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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

    private Callable<ResponseEntity<String>> confirmFirst(String actor, String moveKey) {
        return () -> moveController.confirmFirst(actor, moveKey, new MoveCommandRequest(cmd()));
    }

    private Callable<ResponseEntity<String>> confirmSecond(String actor, String moveKey) {
        return () -> moveController.confirmSecond(actor, moveKey, new MoveCommandRequest(cmd()));
    }

    @Test
    void concurrentFirstConfirmationsExactlyOneWins() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, keys, locA, locB);

        List<Integer> statuses = runConcurrently(confirmFirst("alice", moveKey),
                confirmFirst("bob", moveKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var view = moveService.getMove(moveKey);
        assertThat(view.status()).isEqualTo(MoveStatus.AWAITING_SECOND);
        assertThat(view.secondConfirmer()).isNull();
    }

    @Test
    void concurrentSecondConfirmAndCancelExactlyOneWins() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 3);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, keys, locA, locB);
        assertThat(moveController.confirmFirst("alice", moveKey, new MoveCommandRequest(cmd()))
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(confirmSecond("bob", moveKey),
                () -> moveController.cancel("alice", moveKey, new MoveCommandRequest(cmd())));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var view = moveService.getMove(moveKey);
        var snapshots = moveService.snapshots(moveKey);
        if (view.status() == MoveStatus.COMPLETED) {
            // 第二人确认先提交：全部证物已在目标库位，逐件快照恰好 3 条，撤销请求落败
            assertThat(view.secondConfirmer()).isEqualTo("bob");
            assertThat(snapshots).hasSize(3);
            assertThat(moveService.stock(locA).items()).isEmpty();
            assertThat(moveService.stock(locB).items()).hasSize(3);
        } else {
            // 撤销先提交：单据 CANCELLED，无任何快照与库位变更，确认请求落败
            assertThat(view.status()).isEqualTo(MoveStatus.CANCELLED);
            assertThat(snapshots).isEmpty();
            assertThat(moveService.stock(locA).items()).hasSize(3);
            assertThat(moveService.stock(locB).items()).isEmpty();
        }
    }

    @Test
    void concurrentSecondConfirmationsByDifferentCustodiansCompleteOnce() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, keys, locA, locB);
        assertThat(moveController.confirmFirst("alice", moveKey, new MoveCommandRequest(cmd()))
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(confirmSecond("bob", moveKey),
                confirmSecond("carol", moveKey));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var view = moveService.getMove(moveKey);
        assertThat(view.status()).isEqualTo(MoveStatus.COMPLETED);
        assertThat(view.secondConfirmer()).isIn("bob", "carol");
        // 封签快照逐件仅写一次，目标库位版本各加 1
        assertThat(moveService.snapshots(moveKey)).hasSize(2);
        assertThat(moveService.stock(locB).items()).allSatisfy(
                item -> assertThat(item.version()).isEqualTo(2));
    }

    @Test
    void concurrentSameCommandKeySecondConfirmReplaysWithoutDoubleWrite() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 2);
        String moveKey = uniqueKey("MOVE");
        String commandKey = cmd();
        applyMove("alice", moveKey, keys, locA, locB);
        assertThat(moveController.confirmFirst("alice", moveKey, new MoveCommandRequest(cmd()))
                .getStatusCode().value()).isEqualTo(200);

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmSecond("bob", moveKey,
                        new MoveCommandRequest(commandKey)),
                () -> moveController.confirmSecond("bob", moveKey,
                        new MoveCommandRequest(commandKey)));

        // 同键同参：先到的执行成功，唯一冲突后重放，双方都得到 200
        assertThat(statuses).containsOnly(200);
        assertThat(moveService.getMove(moveKey).status()).isEqualTo(MoveStatus.COMPLETED);
        assertThat(moveService.snapshots(moveKey)).hasSize(2);
        assertThat(moveService.stock(locB).items()).hasSize(2);
    }

    @Test
    void concurrentConfirmAndBorrowSerializeSafelyWithoutBrokenSnapshot() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 1);
        String evidenceKey = keys.get(0);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, keys, locA, locB);
        assertThat(moveController.confirmFirst("alice", moveKey, new MoveCommandRequest(cmd()))
                .getStatusCode().value()).isEqualTo(200);

        String loanKey = uniqueKey("LOAN");
        List<Integer> statuses = runConcurrently(confirmSecond("bob", moveKey),
                () -> evidenceController.borrow("alice", evidenceKey,
                        new LoanCreateRequest(cmd(), loanKey, "mallory", "鉴定",
                                LocalDateTime.now().plusHours(2))));

        // 借出恒为 200（若在迁移之后串行执行也合法）；确认可能 200 或因借出先提交而 409
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(200, 409);

        var view = moveService.getMove(moveKey);
        var snapshots = moveService.snapshots(moveKey);
        if (view.status() == MoveStatus.COMPLETED) {
            // 确认先提交：快照必须全部记录 SEALED（绝不能在借出状态写快照），随后借出串行生效
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.get(0).evidenceStatus()).isEqualTo(EvidenceStatus.SEALED);
            assertThat(moveService.stock(locB).items()).hasSize(1);
        } else {
            // 借出先提交：证物 BORROWED 在源库位，确认被拒，无快照
            assertThat(view.status()).isEqualTo(MoveStatus.AWAITING_SECOND);
            assertThat(snapshots).isEmpty();
            assertThat(moveService.stock(locA).items())
                    .allSatisfy(item -> assertThat(item.status()).isEqualTo(EvidenceStatus.BORROWED));
            assertThat(moveService.stock(locB).items()).isEmpty();
        }
    }

    @Test
    void concurrentConfirmAndReturnNeverCompletesMove() throws Exception {
        String locA = uniqueKey("LOC");
        String locB = uniqueKey("LOC");
        List<String> keys = stock(locA, locB, 1);
        String evidenceKey = keys.get(0);
        String moveKey = uniqueKey("MOVE");
        applyMove("alice", moveKey, keys, locA, locB);
        assertThat(moveController.confirmFirst("alice", moveKey, new MoveCommandRequest(cmd()))
                .getStatusCode().value()).isEqualTo(200);

        // 窗口内借出：证物 BORROWED，版本已漂移
        String loanKey = uniqueKey("LOAN");
        assertThat(evidenceController.borrow("alice", evidenceKey,
                new LoanCreateRequest(cmd(), loanKey, "mallory", "鉴定",
                        LocalDateTime.now().plusHours(2)))
                .getStatusCode().value()).isEqualTo(200);

        // 归还核验与第二人确认并发：无论谁先提交，迁移都不允许完成
        List<Integer> statuses = runConcurrently(confirmSecond("bob", moveKey),
                () -> evidenceController.returnLoan("alice", evidenceKey,
                        new LoanReturnRequest(cmd(), loanKey, true, "封条完好归还")));

        assertThat(statuses.get(1)).isEqualTo(200);
        // 归还先提交 → 版本不符 422；确认先拿到锁 → 借出状态 409
        assertThat(statuses.get(0)).isIn(409, 422);
        assertThat(moveService.getMove(moveKey).status()).isNotEqualTo(MoveStatus.COMPLETED);
        assertThat(moveService.snapshots(moveKey)).isEmpty();
        // 证物完好归还后仍 SEALED 留在源库位，整单未迁移
        assertThat(moveService.stock(locA).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(EvidenceStatus.SEALED);
                    assertThat(item.locationCode()).isEqualTo(locA);
                });
        assertThat(moveService.stock(locB).items()).isEmpty();
    }
}
