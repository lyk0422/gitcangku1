package com.example.starter.service;

import com.example.starter.model.ObservationRequest;
import com.example.starter.repository.ObservationRequestRepository;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.RequestView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 并发批准、撤销与候补接替的真实 H2 数据库测试。
 *
 * <p>用 {@link CyclicBarrier} 让真实操作线程同时释放，按提交顺序裁决，
 * 所有断言核对最终业务数据而非打印；整体设 20 秒超时防止死锁挂起。
 */
class AllocationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T20 = Instant.parse("2026-10-01T20:00:00Z");
    private static final Instant T21 = Instant.parse("2026-10-01T21:00:00Z");
    private static final Instant T22 = Instant.parse("2026-10-01T22:00:00Z");

    private final AllocationService service;
    private final ObservationRequestRepository repository;

    AllocationConcurrencyIntegrationTest(AllocationService service,
                                        ObservationRequestRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Test
    void concurrentOverlappingApprovalsLeaveExactlyOneAllocated() {
        RequestView r1 = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        RequestView r2 = service.apply(Requests.of("P2", "T", "G2",
                Instant.parse("2026-10-01T20:30:00Z"), T22, 90, null));

        var outcomes = runConcurrently(
                () -> service.approve(r1.requestNo(), null),
                () -> service.approve(r2.requestNo(), null));

        long allocated = outcomes.successes().size();
        long conflicts = outcomes.conflicts().size();
        assertThat(allocated).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(outcomes.conflicts().get(0)).hasMessageContaining("时间区间重叠");

        List<String> statuses = repository.findByInstrument("T").stream()
                .map(r -> r.getStatus().name()).toList();
        assertThat(statuses).containsExactlyInAnyOrder("ALLOCATED", "PENDING");
        assertNoOverlappingAllocated("T");
    }

    @Test
    void concurrentCancelAndPromotionStayConsistent() {
        // 撤销与空档内候补批准并发：无论提交顺序如何，最终只能有一个窗口占用空档
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView w1 = service.apply(Requests.of("P-w1", "T", "G2",
                Instant.parse("2026-10-01T20:10:00Z"),
                Instant.parse("2026-10-01T20:50:00Z"), 40, null));
        RequestView w2 = service.apply(Requests.of("P-w2", "T", "G3",
                Instant.parse("2026-10-01T20:20:00Z"),
                Instant.parse("2026-10-01T20:30:00Z"), 10, null));

        var outcomes = runConcurrently(
                () -> service.cancel(primary.requestNo(), "G1", null),
                () -> service.approve(w2.requestNo(), null));

        assertThat(outcomes.successes()).hasSize(1);
        assertThat(outcomes.conflicts()).hasSize(1);

        // 最终：主申请撤销，w1 接替唯一空档，w2 仍候补；只有一次接替流水
        assertThat(service.getRequest(primary.requestNo()).status()).isEqualTo("CANCELLED");
        assertThat(service.getRequest(w1.requestNo()).status()).isEqualTo("ALLOCATED");
        assertThat(service.getRequest(w1.requestNo()).promotedFromRequestNo())
                .isEqualTo(primary.requestNo());
        assertThat(service.getRequest(w2.requestNo()).status()).isEqualTo("PENDING");
        assertThat(service.getTimeline("T").successions()).hasSize(1);
        assertNoOverlappingAllocated("T");
    }

    @Test
    void concurrentCancelAndAdjacentApprovalBothSucceed() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView adjacent = service.apply(Requests.of("P-adj", "T", "G2",
                T21, T22, 60, null));

        var outcomes = runConcurrently(
                () -> service.cancel(primary.requestNo(), "G1", null),
                () -> service.approve(adjacent.requestNo(), null));

        assertThat(outcomes.conflicts()).isEmpty();
        assertThat(outcomes.successes()).hasSize(2);
        assertThat(service.getRequest(primary.requestNo()).status()).isEqualTo("CANCELLED");
        assertThat(service.getRequest(adjacent.requestNo()).status()).isEqualTo("ALLOCATED");
        // 相邻窗口不匹配空档，不产生接替
        assertThat(service.getTimeline("T").successions()).isEmpty();
    }

    @Test
    void concurrentDuplicateApplyWithSameRequestIdCreatesOneRequest() {
        var outcomes = runConcurrently(
                () -> service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, "rid-race-1")),
                () -> service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, "rid-race-1")));

        assertThat(outcomes.conflicts()).isEmpty();
        assertThat(outcomes.successes()).hasSize(2);
        assertThat(outcomes.successes().get(0).requestNo())
                .isEqualTo(outcomes.successes().get(1).requestNo());
        assertThat(repository.findByInstrument("T")).hasSize(1);
        Integer calls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_call WHERE request_id = 'rid-race-1'",
                Integer.class);
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCancelPromotesWaiterExactlyOnce() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView w1 = service.apply(Requests.of("P-w1", "T", "G2",
                Instant.parse("2026-10-01T20:10:00Z"),
                Instant.parse("2026-10-01T20:50:00Z"), 40, null));

        var outcomes = runConcurrently(
                () -> service.cancel(primary.requestNo(), "G1", null),
                () -> service.cancel(primary.requestNo(), "G1", null));

        assertThat(outcomes.successes()).hasSize(1);
        assertThat(outcomes.conflicts()).hasSize(1);
        assertThat(outcomes.conflicts().get(0)).hasMessageContaining("已撤销");
        assertThat(service.getRequest(w1.requestNo()).status()).isEqualTo("ALLOCATED");
        assertThat(service.getTimeline("T").successions()).hasSize(1);
        assertNoOverlappingAllocated("T");
    }

    private void assertNoOverlappingAllocated(String instrumentId) {
        List<ObservationRequest> allocated = repository.findAllocatedByInstrument(instrumentId);
        for (int i = 0; i < allocated.size(); i++) {
            for (int j = i + 1; j < allocated.size(); j++) {
                ObservationRequest a = allocated.get(i);
                ObservationRequest b = allocated.get(j);
                assertThat(TimeWindows.overlaps(
                        a.getWindowStart(), a.getWindowEnd(),
                        b.getWindowStart(), b.getWindowEnd()))
                        .as("仪器 %s 的已分配窗口 %s 与 %s 重叠",
                                instrumentId, a.getRequestNo(), b.getRequestNo())
                        .isFalse();
            }
        }
    }

    /**
     * 让两个业务操作在屏障处同时释放并执行，20 秒内收敛。
     */
    private Outcomes runConcurrently(ThrowingSupplier<RequestView> op1,
                                     ThrowingSupplier<RequestView> op2) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            return assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> {
                Future<RequestView> f1 = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return op1.get();
                });
                Future<RequestView> f2 = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return op2.get();
                });
                Outcomes outcomes = new Outcomes();
                collect(f1, outcomes);
                collect(f2, outcomes);
                return outcomes;
            });
        } finally {
            pool.shutdownNow();
        }
    }

    private void collect(Future<RequestView> future, Outcomes outcomes) {
        try {
            outcomes.successes.add(future.get(20, TimeUnit.SECONDS));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ConflictException conflict) {
                outcomes.conflicts.add(conflict);
            } else {
                throw new RuntimeException(cause);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class Outcomes {
        private final java.util.List<RequestView> successes =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final java.util.List<ConflictException> conflicts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private List<RequestView> successes() {
            return successes;
        }

        private List<ConflictException> conflicts() {
            return conflicts;
        }
    }
}
