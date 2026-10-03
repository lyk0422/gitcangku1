package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.domain.RunSegment;
import com.example.starter.domain.SwitchRequest;
import com.example.starter.service.ApiException;
import com.example.starter.service.VoidResult;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 并发场景测试：真实线程并发调用事务服务，协调真实操作并设置超时，核对最终数据。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    /** 并发审核同一切换申请：最多生成一个后继运行段，败者 409 可重试。 */
    @Test
    void concurrentDecisionsProduceSingleSuccessorSegment() throws Exception {
        createInjectionSegment("W-CONC");
        addFourValidSamples("W-CONC");
        long requestId = createPendingSwitch("W-CONC", "sw-1");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> decideA = () -> decideWithStatus(requestId, "dec-a", barrier);
            Callable<Integer> decideB = () -> decideWithStatus(requestId, "dec-b", barrier);
            Future<Integer> futureA = executor.submit(decideA);
            Future<Integer> futureB = executor.submit(decideB);
            int statusA = futureA.get(15, TimeUnit.SECONDS);
            int statusB = futureB.get(15, TimeUnit.SECONDS);

            assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        List<RunSegment> segments = segmentRepo.findByWell("W-CONC");
        assertThat(segments).hasSize(2);
        assertThat(segments.stream().filter(segment -> segment.getEndTime() == null).count()).isEqualTo(1);
        assertThat(segments.get(1).getStartTime()).isEqualTo(segments.get(0).getEndTime());
        SwitchRequest decided = switchRepo.findById(requestId).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo("APPROVED");
        assertThat(switchRepo.findFrozenSamples(requestId)).hasSize(4);
    }

    /** 采样作废与切换审核并发：审核只能基于同一已提交有效集合，不能靠已作废读数过关。 */
    @Test
    void concurrentVoidAndApprovalUseCommittedValidSet() throws Exception {
        createInjectionSegment("W-RACE");
        List<Long> sampleIds = addFourValidSamples("W-RACE");
        long requestId = createPendingSwitch("W-RACE", "sw-1");
        long voidedId = sampleIds.get(3);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int approvalStatus;
        VoidResult voidResult;
        try {
            Callable<VoidResult> voidTask = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return wellService.voidSample("W-RACE", voidedId, "void-1", "读数漂移",
                        new BigDecimal("12.5"), T0.minus(2, ChronoUnit.MINUTES));
            };
            Callable<Integer> approveTask = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    wellService.decide(requestId, "dec-1", true, null);
                    return 200;
                } catch (ApiException ex) {
                    return ex.getStatus();
                }
            };
            Future<VoidResult> voidFuture = executor.submit(voidTask);
            Future<Integer> approveFuture = executor.submit(approveTask);
            voidResult = voidFuture.get(15, TimeUnit.SECONDS);
            approvalStatus = approveFuture.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(voidResult.voidedSample().isVoided()).isTrue();
        SwitchRequest decided = switchRepo.findById(requestId).orElseThrow();
        List<RunSegment> segments = segmentRepo.findByWell("W-RACE");
        if (approvalStatus == 200) {
            // 审核先提交：快照冻结的是作废前的四个原始读数，替代读数不在快照内
            assertThat(decided.getStatus()).isEqualTo("APPROVED");
            assertThat(segments).hasSize(2);
            assertThat(switchRepo.findFrozenSamples(requestId))
                    .extracting(sample -> sample.getId())
                    .containsExactlyElementsOf(sampleIds);
        } else {
            // 作废先提交：审核只能看到替代后的有效集合，12.5 MPa 超范围必须拒绝
            assertThat(approvalStatus).isEqualTo(422);
            assertThat(decided.getStatus()).isEqualTo("REJECTED");
            assertThat(decided.getRejectReason()).contains("12.5");
            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).getEndTime()).isNull();
            assertThat(switchRepo.findFrozenSamples(requestId)).isEmpty();
        }
    }

    private int decideWithStatus(long requestId, String decisionRequestId, CyclicBarrier barrier)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            wellService.decide(requestId, decisionRequestId, true, null);
            return 200;
        } catch (ApiException ex) {
            return ex.getStatus();
        }
    }
}