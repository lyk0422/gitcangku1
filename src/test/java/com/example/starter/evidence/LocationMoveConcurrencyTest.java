package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.MoveApplyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
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
 * 库位迁移并发与幂等边界测试：第二人确认/撤销/借出并发时按事务提交顺序裁决，
 * 同 moveKey 并发申请只落一单，快照不重复写入。
 */
@SpringBootTest
class LocationMoveConcurrencyTest {

    @Autowired
    private MoveController moveController;

    @Autowired
    private LocationController locationController;

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private MoveService moveService;

    @Autowired
    private LocationService locationService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void createLocation(String locationCode) {
        ResponseEntity<String> response = locationController.create("ops",
                new LocationCreateRequest(uniqueKey("CMD"), locationCode, "货架"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void intakeAt(String actor, String evidenceKey, String locationCode) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT",
                        "SEAL-" + evidenceKey, locationCode));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 准备源/目标库位与两件源库位证物并申请迁移，返回 [moveKey, source, target, ev1, ev2]。
     */
    private String[] prepareMove() {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        createLocation(source);
        createLocation(target);
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        int version = locationService.inventory(source).version();
        String moveKey = uniqueKey("MOVE");
        ResponseEntity<String> applied = moveController.apply("alice",
                new MoveApplyRequest(moveKey, List.of(ev1, ev2), source, target, version));
        assertThat(applied.getStatusCode().value()).isEqualTo(201);
        return new String[]{moveKey, source, target, ev1, ev2};
    }

    private void firstConfirm(String moveKey) {
        ResponseEntity<String> response = moveController.confirm("carol", moveKey,
                new CommandRequest(uniqueKey("CMD")));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
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
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSecondConfirmAndCancelExactlyOneWins() throws Exception {
        String[] ctx = prepareMove();
        firstConfirm(ctx[0]);

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("dave", ctx[0], new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.cancel("alice", ctx[0], new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var move = moveService.getMove(ctx[0]);
        if (move.status() == MoveStatus.COMPLETED) {
            // 确认先提交：整单迁移完成，快照逐件写入
            assertThat(move.secondConfirmer()).isEqualTo("dave");
            assertThat(moveService.listSnapshots(ctx[0])).hasSize(2);
            assertThat(locationService.inventory(ctx[1]).evidence()).isEmpty();
            assertThat(locationService.inventory(ctx[2]).evidence()).hasSize(2);
        } else {
            // 撤销先提交：证物不得迁移，无快照，且不可再确认
            assertThat(move.status()).isEqualTo(MoveStatus.CANCELLED);
            assertThat(moveService.listSnapshots(ctx[0])).isEmpty();
            assertThat(locationService.inventory(ctx[1]).evidence()).hasSize(2);
            assertThat(locationService.inventory(ctx[2]).evidence()).isEmpty();
        }
    }

    @Test
    void concurrentSecondConfirmsByDifferentCustodiansExecuteExactlyOnce() throws Exception {
        String[] ctx = prepareMove();
        firstConfirm(ctx[0]);

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("dave", ctx[0], new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.confirm("erin", ctx[0], new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var move = moveService.getMove(ctx[0]);
        assertThat(move.status()).isEqualTo(MoveStatus.COMPLETED);
        assertThat(move.secondConfirmer()).isIn("dave", "erin");
        // 快照只写入一次，源库位版本只递增一次
        assertThat(moveService.listSnapshots(ctx[0])).hasSize(2);
        assertThat(locationService.inventory(ctx[1]).version()).isEqualTo(3);
        assertThat(locationService.inventory(ctx[1]).evidence()).isEmpty();
        assertThat(locationService.inventory(ctx[2]).evidence()).hasSize(2);
    }

    @Test
    void concurrentDuplicateApplyWithSameMoveKeyCreatesSingleOrder() throws Exception {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        createLocation(source);
        createLocation(target);
        intakeAt("alice", ev1, source);
        intakeAt("alice", ev2, source);
        int version = locationService.inventory(source).version();
        String moveKey = uniqueKey("MOVE");
        MoveApplyRequest request = new MoveApplyRequest(moveKey, List.of(ev1, ev2),
                source, target, version);

        List<Integer> statuses = runConcurrently(
                () -> moveController.apply("alice", request),
                () -> moveController.apply("alice", request));

        // 一个创建（201），一个重放（200）；也可能都走重放路径
        assertThat(statuses).allMatch(s -> s == 200 || s == 201);
        assertThat(statuses).contains(201);
        var move = moveService.getMove(moveKey);
        assertThat(move.status()).isEqualTo(MoveStatus.PENDING);
        assertThat(move.evidenceKeys()).containsExactlyInAnyOrder(ev1, ev2);
    }

    @Test
    void concurrentBorrowAndSecondConfirmResolveByCommitOrder() throws Exception {
        String[] ctx = prepareMove();
        firstConfirm(ctx[0]);

        List<Integer> statuses = runConcurrently(
                () -> evidenceController.borrow("alice", ctx[3],
                        new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"), "borrower-1",
                                "court", LocalDateTime.now().plusHours(1).withNano(0))),
                () -> moveController.confirm("dave", ctx[0], new CommandRequest(uniqueKey("CMD"))));

        var move = moveService.getMove(ctx[0]);
        var sourceInventory = locationService.inventory(ctx[1]);
        var targetInventory = locationService.inventory(ctx[2]);
        if (move.status() == MoveStatus.COMPLETED) {
            // 确认先提交：迁移完成后借出仍可对已迁移证物生效（状态 SEALED、保管人不变）
            assertThat(statuses).containsExactlyInAnyOrder(200, 200);
            assertThat(targetInventory.evidence()).hasSize(2);
            assertThat(moveService.listSnapshots(ctx[0])).hasSize(2);
        } else {
            // 借出先提交：第二人确认必须 409 且整单回滚，两件证物都留在源库位
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(move.status()).isEqualTo(MoveStatus.FIRST_CONFIRMED);
            assertThat(sourceInventory.evidence()).hasSize(2);
            assertThat(targetInventory.evidence()).isEmpty();
            assertThat(moveService.listSnapshots(ctx[0])).isEmpty();
        }
    }
}
