package com.example.starter.service;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.TimelineView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 申请、批准、撤销与候补接替主流程的真实 H2 数据库测试。
 */
class AllocationMainFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T20 = Instant.parse("2026-10-01T20:00:00Z");
    private static final Instant T21 = Instant.parse("2026-10-01T21:00:00Z");

    private final AllocationService service;

    AllocationMainFlowIntegrationTest(AllocationService service) {
        this.service = service;
    }

    @Test
    void applyApproveQueryTimeline() {
        RequestView view = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.requestNo()).startsWith("REQ-");

        RequestView allocated = service.approve(view.requestNo(), null);
        assertThat(allocated.status()).isEqualTo("ALLOCATED");
        assertThat(allocated.allocatedAt()).isNotNull();

        RequestView fetched = service.getRequest(view.requestNo());
        assertThat(fetched.status()).isEqualTo("ALLOCATED");

        TimelineView timeline = service.getTimeline("T");
        assertThat(timeline.allocated()).extracting(RequestView::requestNo)
                .containsExactly(view.requestNo());
        assertThat(timeline.waitingQueue()).isEmpty();
    }

    @Test
    void cancelTriggersPromotionOfFirstFittingWaiter() {
        // 20:00-21:00 已分配
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);

        // 候补：20:10-20:50 完整落入空档，可接替
        RequestView fitting = service.apply(Requests.of("P-fitting", "T", "G2",
                Instant.parse("2026-10-01T20:10:00Z"),
                Instant.parse("2026-10-01T20:50:00Z"), 40, null));

        // 另一个重叠批准返回 409（主流程内一并验证时间轴唯一性）
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.web.ConflictException.class,
                () -> service.approve(fitting.requestNo(), null));

        TimelineView beforeCancel = service.getTimeline("T");
        assertThat(beforeCancel.waitingQueue()).extracting(RequestView::requestNo)
                .containsExactly(fitting.requestNo());

        // 撤销已分配窗口
        RequestView cancelled = service.cancel(primary.requestNo(), "G1", null);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledAt()).isNotNull();

        RequestView promoted = service.getRequest(fitting.requestNo());
        assertThat(promoted.status()).isEqualTo("ALLOCATED");
        assertThat(promoted.promotedFromRequestNo()).isEqualTo(primary.requestNo());

        TimelineView afterCancel = service.getTimeline("T");
        assertThat(afterCancel.allocated()).extracting(RequestView::requestNo)
                .containsExactly(fitting.requestNo());
        assertThat(afterCancel.waitingQueue()).isEmpty();
        assertThat(afterCancel.successions()).hasSize(1);
        assertThat(afterCancel.successions().get(0).vacatedRequestNo()).isEqualTo(primary.requestNo());
        assertThat(afterCancel.successions().get(0).promotedRequestNo()).isEqualTo(fitting.requestNo());
        assertThat(afterCancel.successions().get(0).gapStart()).isEqualTo(T20);
        assertThat(afterCancel.successions().get(0).gapEnd()).isEqualTo(T21);
    }

    @Test
    void firstWaiterNotFittingStaysQueuedAndLaterFittingOneIsChosenInOrder() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);

        // 更早的候补 20:00-21:30：超出空档，不合格，必须保留且不被跳过性“丢弃”
        RequestView tooLong = service.apply(Requests.of("P-too-long", "T", "G2",
                T20, Instant.parse("2026-10-01T21:30:00Z"), 90, null));
        // 次早的候补 20:30-20:40：合格
        RequestView fitting = service.apply(Requests.of("P-fitting", "T", "G3",
                Instant.parse("2026-10-01T20:30:00Z"),
                Instant.parse("2026-10-01T20:40:00Z"), 10, null));

        service.cancel(primary.requestNo(), "G1", null);

        assertThat(service.getRequest(tooLong.requestNo()).status()).isEqualTo("PENDING");
        RequestView promoted = service.getRequest(fitting.requestNo());
        assertThat(promoted.status()).isEqualTo("ALLOCATED");
        assertThat(promoted.promotedFromRequestNo()).isEqualTo(primary.requestNo());

        TimelineView timeline = service.getTimeline("T");
        assertThat(timeline.waitingQueue()).extracting(RequestView::requestNo)
                .containsExactly(tooLong.requestNo());
        assertThat(timeline.successions())
                .extracting(s -> s.promotedRequestNo())
                .containsExactly(fitting.requestNo());
    }

    @Test
    void noFittingWaiterLeavesGapEmptyAndCancelStaysConsistent() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView tooWide = service.apply(Requests.of("P-wide", "T", "G2",
                T20, Instant.parse("2026-10-01T21:30:00Z"), 90, null));

        service.cancel(primary.requestNo(), "G1", null);

        assertThat(service.getRequest(primary.requestNo()).status()).isEqualTo("CANCELLED");
        assertThat(service.getRequest(tooWide.requestNo()).status()).isEqualTo("PENDING");
        TimelineView timeline = service.getTimeline("T");
        assertThat(timeline.allocated()).isEmpty();
        assertThat(timeline.successions()).isEmpty();
    }
}
