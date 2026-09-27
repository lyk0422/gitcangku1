package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.MoveApplyRequest;
import com.example.starter.evidence.dto.MoveCancelRequest;
import com.example.starter.evidence.dto.MoveConfirmRequest;
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
 * 库位迁移并发与幂等边界测试（真实 H2 行锁与事务）：
 * 双人确认/撤销/借出并发时按事务提交顺序裁决，不得出现库位部分迁移、
 * 重复封签快照或已撤销单据仍完成迁移的结果。
 */
@SpringBootTest
class LocationMoveConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private LocationMoveController moveController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private LocationMoveService moveService;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void createLocation(String code) {
        ResponseEntity<String> response = moveController.createLocation("admin",
                new LocationCreateRequest(uniqueKey("CMD"), code, code + "-name"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void intake(String actor, String evidenceKey, String locationCode) {
        ResponseEntity<String> response = evidenceController.intake(actor, new IntakeRequest(
                uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1", locationCode));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String applyMove(String actor, List<String> keys, String source, String target,
                             long version) {
        String moveKey = uniqueKey("MOVE");
        ResponseEntity<String> response = moveController.applyMove(actor,
                new MoveApplyRequest(moveKey, keys, source, target, version));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
    void concurrentTwoConfirmsExactlyOneCompletes() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source);
        createLocation(target);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, source);
        intake("alice", ev2, source);
        String moveKey = applyMove("alice", List.of(ev1, ev2), source, target, 1);

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmMove("bob",
                        new MoveConfirmRequest(uniqueKey("CMD"), moveKey)),
                () -> moveController.confirmMove("carol",
                        new MoveConfirmRequest(uniqueKey("CMD"), moveKey)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var detail = moveService.moveDetail(moveKey);
        assertThat(detail.move().status()).isEqualTo(LocationMoveStatus.COMPLETED.name());
        assertThat(detail.snapshots()).hasSize(2);
        for (String ev : List.of(ev1, ev2)) {
            var inventory = moveService.locationInventory(target);
            assertThat(inventory.items().stream().anyMatch(e -> e.evidenceKey().equals(ev)))
                    .isTrue();
        }
        assertThat(moveService.locationInventory(source).items()).isEmpty();
    }

    @Test
    void concurrentConfirmAndCancelExactlyOneWins() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source);
        createLocation(target);
        String ev = uniqueKey("EV");
        intake("alice", ev, source);
        String moveKey = applyMove("alice", List.of(ev), source, target, 1);

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmMove("bob",
                        new MoveConfirmRequest(uniqueKey("CMD"), moveKey)),
                () -> moveController.cancelMove("alice",
                        new MoveCancelRequest(uniqueKey("CMD"), moveKey)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        var detail = moveService.moveDetail(moveKey);
        var evidence = moveService.locationInventory(source).items().stream()
                .filter(e -> e.evidenceKey().equals(ev)).findFirst();
        if (detail.move().status().equals(LocationMoveStatus.COMPLETED.name())) {
            // 确认先提交：证物必须在目标库位、版本 2，快照存在
            assertThat(detail.snapshots()).hasSize(1);
            var moved = moveService.locationInventory(target).items().stream()
                    .filter(e -> e.evidenceKey().equals(ev)).findFirst().orElseThrow();
            assertThat(moved.locationVersion()).isEqualTo(2L);
            assertThat(moveService.locationInventory(source).items()
                    .stream().noneMatch(e -> e.evidenceKey().equals(ev))).isTrue();
        } else {
            // 撤销先提交：迁移不得发生，证物留在源库位、版本 1，无快照
            assertThat(detail.move().status()).isEqualTo(LocationMoveStatus.CANCELLED.name());
            assertThat(detail.snapshots()).isEmpty();
            assertThat(evidence).isPresent();
            assertThat(evidence.orElseThrow().locationVersion()).isEqualTo(1L);
            assertThat(moveService.locationInventory(target).items()).isEmpty();
        }
    }

    @Test
    void concurrentSameConfirmCommandKeyReplaysSingleResult() throws Exception {
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source);
        createLocation(target);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, source);
        intake("alice", ev2, source);
        String moveKey = applyMove("alice", List.of(ev1, ev2), source, target, 1);

        MoveConfirmRequest request = new MoveConfirmRequest(uniqueKey("CMD"), moveKey);
        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmMove("bob", request),
                () -> moveController.confirmMove("bob", request));

        assertThat(statuses).containsOnly(200);

        var detail = moveService.moveDetail(moveKey);
        assertThat(detail.move().status()).isEqualTo(LocationMoveStatus.COMPLETED.name());
        assertThat(detail.snapshots()).hasSize(2);
        assertThat(moveService.locationInventory(target).items()).hasSize(2);
    }

    @Test
    void concurrentConfirmAndBorrowAdjudicatedByCommitOrderWithoutInconsistency() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String source = uniqueKey("SRC");
        String target = uniqueKey("TGT");
        createLocation(source);
        createLocation(target);
        String ev = uniqueKey("EV");
        intake("alice", ev, source);
        String moveKey = applyMove("alice", List.of(ev), source, target, 1);

        LocalDateTime dueAt = LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC);
        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmMove("bob",
                        new MoveConfirmRequest(uniqueKey("CMD"), moveKey)),
                () -> evidenceController.borrow("alice", ev,
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "carol",
                                "鉴定", dueAt)));

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);

        var detail = moveService.moveDetail(moveKey);
        if (detail.move().status().equals(LocationMoveStatus.COMPLETED.name())) {
            // 确认先提交：快照只写一次、证物位于目标库位（借出若随后成功，状态可为 BORROWED）。
            assertThat(detail.snapshots()).hasSize(1);
            var atTarget = moveService.locationInventory(target).items().stream()
                    .filter(e -> e.evidenceKey().equals(ev)).findFirst();
            assertThat(atTarget).isPresent();
        } else {
            // 借出先提交：确认必须 409，证物借出中且留在源库位，无快照
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
            assertThat(detail.move().status()).isEqualTo(LocationMoveStatus.AWAITING_SECOND.name());
            assertThat(detail.snapshots()).isEmpty();
            var atSource = moveService.locationInventory(source).items().stream()
                    .filter(e -> e.evidenceKey().equals(ev)).findFirst().orElseThrow();
            assertThat(atSource.status()).isEqualTo(EvidenceStatus.BORROWED);
        }
    }

    @Test
    void concurrentCompletingOverlappingMovesOnlyOneWinsWholeSet() throws Exception {
        String source = uniqueKey("SRC");
        String target1 = uniqueKey("TGT1");
        String target2 = uniqueKey("TGT2");
        createLocation(source);
        createLocation(target1);
        createLocation(target2);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, source);
        intake("alice", ev2, source);
        String move1 = applyMove("alice", List.of(ev1, ev2), source, target1, 1);
        String move2 = applyMove("bob", List.of(ev2, ev1), source, target2, 1);

        // 两张待确认单并发完成：证据行锁串行化，先提交者迁移整集，后提交者整单 422 回滚。
        List<Integer> statuses = runConcurrently(
                () -> moveController.confirmMove("carol",
                        new MoveConfirmRequest(uniqueKey("CMD"), move1)),
                () -> moveController.confirmMove("dave",
                        new MoveConfirmRequest(uniqueKey("CMD"), move2)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 两件证物必须在同一目标库位，版本均为 2；只有赢家留下快照
        var completed = statuses.get(0) == 200 ? move1 : move2;
        var rolledBack = completed.equals(move1) ? move2 : move1;
        String winningTarget = completed.equals(move1) ? target1 : target2;
        var detail = moveService.moveDetail(completed);
        assertThat(detail.move().status()).isEqualTo(LocationMoveStatus.COMPLETED.name());
        assertThat(detail.snapshots()).hasSize(2);
        for (String ev : List.of(ev1, ev2)) {
            var present = moveService.locationInventory(winningTarget).items().stream()
                    .filter(e -> e.evidenceKey().equals(ev)).findFirst().orElseThrow();
            assertThat(present.locationVersion()).isEqualTo(2L);
        }
        var loser = moveService.moveDetail(rolledBack);
        assertThat(loser.move().status()).isEqualTo(LocationMoveStatus.AWAITING_SECOND.name());
        assertThat(loser.snapshots()).isEmpty();
        assertThat(moveService.locationInventory(source).items()).isEmpty();
        assertThat(moveService.locationInventory(
                completed.equals(move1) ? target2 : target1).items()).isEmpty();
    }
}
