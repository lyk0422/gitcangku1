package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

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
 * 迁移并发与幂等边界测试：双人确认、撤销与借出并发时按事务提交顺序裁决，
 * 不允许出现已撤销迁移单仍执行、或同一迁移单被两名第二人重复执行的结果。
 */
@SpringBootTest
class MoveConcurrencyTest {

    @Autowired
    private LocationController locationController;

    @Autowired
    private MoveController moveController;

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
        ResponseEntity<String> response = locationController.create("admin",
                new LocationCreateRequest(uniqueKey("CMD"), locationCode, "并发测试库位"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private void intake(String actor, String evidenceKey, String locationCode) {
        ResponseEntity<String> response = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT",
                        "SEAL-1", locationCode));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 准备源/目标库位与两件库内证物，创建迁移申请，返回 [moveKey, source, target, ev1, ev2]。
     */
    private String[] prepareMove() {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        createLocation(source);
        createLocation(target);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, source);
        intake("alice", ev2, source);
        int version = locationService.inventory(source).version();
        String moveKey = uniqueKey("MOVE");
        ResponseEntity<String> response = moveController.createMove("carol",
                new MoveCreateRequest(moveKey, List.of(ev1, ev2), source, target, version));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return new String[]{moveKey, source, target, ev1, ev2};
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
    void concurrentTwoDistinctConfirmersCompleteInCommitOrder() throws Exception {
        String[] fixture = prepareMove();
        String moveKey = fixture[0];

        // 两名不同保管人同时确认同一 PENDING 迁移单：
        // 先提交者成为首人确认，后提交者作为第二人执行整单迁移。
        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("alice", moveKey, new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.confirm("bob", moveKey, new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsOnly(200);
        var move = moveService.getMove(moveKey);
        assertThat(move.status()).isEqualTo(MoveStatus.COMPLETED);
        assertThat(move.firstConfirmer()).isNotEqualTo(move.secondConfirmer());
        assertThat(move.firstConfirmer()).isIn("alice", "bob");
        assertThat(move.secondConfirmer()).isIn("alice", "bob");

        // 整单原子迁移：两件证物都已切换库位，逐件快照完整
        assertThat(locationService.inventory(fixture[1]).evidence()).isEmpty();
        assertThat(locationService.inventory(fixture[2]).evidence()).hasSize(2);
        assertThat(moveService.listSnapshots(moveKey)).hasSize(2);
        assertThat(moveService.getRecord(moveKey).moveKey()).isEqualTo(moveKey);
    }

    @Test
    void concurrentSecondConfirmersExactlyOneExecutes() throws Exception {
        String[] fixture = prepareMove();
        String moveKey = fixture[0];
        // 首人确认完成后，两名不同保管人同时尝试第二人确认
        moveController.confirm("alice", moveKey, new CommandRequest(uniqueKey("CMD")));

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("bob", moveKey, new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.confirm("dave", moveKey, new CommandRequest(uniqueKey("CMD"))));

        // 恰一人执行成功，另一人得到 409（已完成）
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var move = moveService.getMove(moveKey);
        assertThat(move.status()).isEqualTo(MoveStatus.COMPLETED);
        // 迁移只执行一次：快照恰好两件，库位库存不重复
        assertThat(moveService.listSnapshots(moveKey)).hasSize(2);
        assertThat(locationService.inventory(fixture[2]).evidence()).hasSize(2);
        assertThat(locationService.inventory(fixture[1]).evidence()).isEmpty();
    }

    @Test
    void concurrentConfirmAndCancelExactlyOneWins() throws Exception {
        String[] fixture = prepareMove();
        String moveKey = fixture[0];

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("alice", moveKey, new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.cancel("carol", moveKey, new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var move = moveService.getMove(moveKey);
        if (move.status() == MoveStatus.FIRST_CONFIRMED) {
            // 确认先提交：撤销不得再生效
            assertThat(move.firstConfirmer()).isEqualTo("alice");
        } else {
            // 撤销先提交：确认不得再生效，证物不得迁移
            assertThat(move.status()).isEqualTo(MoveStatus.CANCELLED);
            assertThat(locationService.inventory(fixture[1]).evidence()).hasSize(2);
            assertThat(moveService.listSnapshots(moveKey)).isEmpty();
        }
    }

    @Test
    void concurrentSecondConfirmAndCancelExactlyOneWins() throws Exception {
        String[] fixture = prepareMove();
        String moveKey = fixture[0];
        moveController.confirm("alice", moveKey, new CommandRequest(uniqueKey("CMD")));

        List<Integer> statuses = runConcurrently(
                () -> moveController.confirm("bob", moveKey, new CommandRequest(uniqueKey("CMD"))),
                () -> moveController.cancel("carol", moveKey, new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var move = moveService.getMove(moveKey);
        if (move.status() == MoveStatus.COMPLETED) {
            // 第二人确认先提交：迁移已执行，撤销不得生效
            assertThat(locationService.inventory(fixture[2]).evidence()).hasSize(2);
            assertThat(moveService.listSnapshots(moveKey)).hasSize(2);
        } else {
            // 撤销先提交：迁移不得执行
            assertThat(move.status()).isEqualTo(MoveStatus.CANCELLED);
            assertThat(locationService.inventory(fixture[1]).evidence()).hasSize(2);
            assertThat(moveService.listSnapshots(moveKey)).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateCreateWithSameMoveKeyReplaysSingleResult() throws Exception {
        String source = uniqueKey("LOC-S");
        String target = uniqueKey("LOC-T");
        createLocation(source);
        createLocation(target);
        String ev1 = uniqueKey("EV");
        intake("alice", ev1, source);
        int version = locationService.inventory(source).version();
        String moveKey = uniqueKey("MOVE");
        MoveCreateRequest request = new MoveCreateRequest(moveKey, List.of(ev1), source, target,
                version);

        List<Integer> statuses = runConcurrently(
                () -> moveController.createMove("carol", request),
                () -> moveController.createMove("carol", request));

        assertThat(statuses).containsOnly(201);
        var move = moveService.getMove(moveKey);
        assertThat(move.status()).isEqualTo(MoveStatus.PENDING);
        assertThat(move.evidenceKeys()).containsExactly(ev1);
    }
}
