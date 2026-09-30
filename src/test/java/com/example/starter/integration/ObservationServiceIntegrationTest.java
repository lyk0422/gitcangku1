package com.example.starter.integration;

import com.example.starter.ResetDatabase;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfig;
import com.example.starter.service.ConflictException;
import com.example.starter.service.ForbiddenException;
import com.example.starter.service.ObservationService;
import com.example.starter.service.ValidationException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.PromotionView;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.SlotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 H2（MODE=MySQL）上的业务集成测试：主流程、422/409/403、
 * 候补接替、幂等重放与查询。不使用 mock 或内存 Map 代替数据库结论。
 */
@SpringBootTest
@ResetDatabase
class ObservationServiceIntegrationTest {

    private static final Instant T2000 = Instant.parse("2026-09-30T20:00:00Z");
    private static final Instant T2010 = Instant.parse("2026-09-30T20:10:00Z");
    private static final Instant T2050 = Instant.parse("2026-09-30T20:50:00Z");
    private static final Instant T2100 = Instant.parse("2026-09-30T21:00:00Z");

    @Autowired
    private ObservationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.FIXED_NOW);
    }

    private ApplyRequest request(String no, String project, String instrument,
                                 Instant start, Instant end, int minutes) {
        return new ApplyRequest(no, project, instrument, start, end, minutes);
    }

    @Test
    @DisplayName("申请进入候补，批准后锁定整段窗口并出现在时间轴")
    void applyApproveAndTimeline() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        RequestView approved = service.approve(null, "R-1");

        assertThat(approved.status()).isEqualTo("ALLOCATED");

        List<SlotView> timeline = service.timeline("T");
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).requestNo()).isEqualTo("R-1");
        assertThat(timeline.get(0).startAt()).isEqualTo(T2000);
        assertThat(timeline.get(0).endAt()).isEqualTo(T2100);
        assertThat(timeline.get(0).fromPromotion()).isFalse();

        assertThat(service.waitlist("T").waiting()).isEmpty();
    }

    @Test
    @DisplayName("结束不晚于开始返回422并指出原因")
    void endNotAfterStartRejected422() {
        assertThatThrownBy(() -> service.apply(null,
                request("R-BAD1", "P1", "T", T2100, T2100, 30)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("结束时刻必须晚于开始时刻");

        assertThatThrownBy(() -> service.apply(null,
                request("R-BAD2", "P1", "T", T2100, T2000, 30)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("结束时刻必须晚于开始时刻");

        // 422 不落任何业务数据
        assertThat(service.requests("T", null)).isEmpty();
    }

    @Test
    @DisplayName("所需时长超过窗口返回422")
    void durationExceedsWindowRejected422() {
        assertThatThrownBy(() -> service.apply(null,
                request("R-BAD3", "P1", "T", T2000, T2010, 11)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("所需分钟数")
                .hasMessageContaining("超过窗口时长");
    }

    @Test
    @DisplayName("与已分配窗口重叠的批准返回409，且不产生双重占用")
    void overlappingApproveRejected409() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");

        service.apply(null, request("R-2", "P2", "T",
                Instant.parse("2026-09-30T20:30:00Z"),
                Instant.parse("2026-09-30T21:30:00Z"), 60));

        assertThatThrownBy(() -> service.approve(null, "R-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("与已分配窗口重叠");

        // 左闭右开：首尾相接（21:00 开始）不重叠，可以批准
        service.apply(null, request("R-3", "P2", "T", T2100,
                Instant.parse("2026-09-30T22:00:00Z"), 60));
        assertThat(service.approve(null, "R-3").status()).isEqualTo("ALLOCATED");

        List<SlotView> timeline = service.timeline("T");
        assertThat(timeline).extracting(SlotView::requestNo)
                .containsExactly("R-1", "R-3");
    }

    @Test
    @DisplayName("重复申请编号返回409")
    void duplicateRequestNoRejected409() {
        service.apply(null, request("R-DUP", "P1", "T", T2000, T2100, 60));
        assertThatThrownBy(() -> service.apply(null,
                request("R-DUP", "P2", "T", T2000, T2100, 60)))
                .isInstanceOf(ConflictException.class);
        assertThat(service.requests(null, null)).hasSize(1);
    }

    @Test
    @DisplayName("非本项目撤销返回403；窗口已开始（含开始时刻本身）不得回收")
    void forbiddenOwnerAndStartedWindow() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");

        assertThatThrownBy(() -> service.cancel(null, "R-1", "P2"))
                .isInstanceOf(ForbiddenException.class);

        clock.setInstant(T2000);
        assertThatThrownBy(() -> service.cancel(null, "R-1", "P1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("窗口已开始");

        // 未被撤销：仍占用时间轴
        assertThat(service.timeline("T")).hasSize(1);
    }

    @Test
    @DisplayName("撤销候补申请不产生空档也不推进接替")
    void cancelPendingLeavesNoGap() {
        service.apply(null, request("R-W", "P1", "T", T2000, T2100, 60));
        CancelResponse response = service.cancel(null, "R-W", "P1");

        assertThat(response.cancelled().status()).isEqualTo("CANCELLED");
        assertThat(response.promoted()).isNull();
        assertThat(response.gapStartAt()).isNull();
        assertThat(response.gapEndAt()).isNull();
        assertThat(service.timeline("T")).isEmpty();
        assertThat(service.promotions()).isEmpty();
    }

    @Test
    @DisplayName("重复撤销返回409，不会再次推进候补")
    void doubleCancelDoesNotRepromote() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");
        service.apply(null, request("R-W", "P2", "T", T2010, T2050, 40));

        service.cancel(null, "R-1", "P1");

        assertThatThrownBy(() -> service.cancel(null, "R-1", "P1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤销");

        // 只有一次接替，候补状态不被二次推进
        assertThat(service.promotions()).hasSize(1);
        assertThat(service.timeline("T")).hasSize(1);
        assertThat(service.timeline("T").get(0).requestNo()).isEqualTo("R-W");
    }

    @Test
    @DisplayName("撤销后按候补顺序寻找首个完整适配者：更早但不适配者跳过，适配者接替")
    void cancelPromotesFirstFittingCandidateInOrder() {
        // R-1：仪器 T，20:00-21:00 已分配
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");

        // 候补按申请时间排序：W1 最早但窗口超出空档结束（21:05 > 21:00），不适配
        clock.advanceSeconds(1);
        service.apply(null, request("W1", "P2", "T",
                Instant.parse("2026-09-30T20:55:00Z"),
                Instant.parse("2026-09-30T21:05:00Z"), 10));
        // W2 第二，完整落入空档 20:10-20:50
        clock.advanceSeconds(1);
        service.apply(null, request("W2", "P3", "T", T2010, T2050, 40));
        // W3 最晚，与 W2 同区间，W2 接替后它必须继续候补
        clock.advanceSeconds(1);
        service.apply(null, request("W3", "P4", "T", T2010, T2050, 40));

        // 另一仪器更早申请的候补不得串到 T 的队列
        service.apply(null, request("WX", "P5", "OTHER", T2010, T2050, 40));

        CancelResponse response = service.cancel("cancel-r1", "R-1", "P1");

        assertThat(response.cancelled().status()).isEqualTo("CANCELLED");
        assertThat(response.gapStartAt()).isEqualTo(T2000);
        assertThat(response.gapEndAt()).isEqualTo(T2100);
        assertThat(response.promoted()).isNotNull();
        assertThat(response.promoted().requestNo()).isEqualTo("W2");
        assertThat(response.promoted().status()).isEqualTo("PROMOTED");
        assertThat(response.promoted().promotedFromRequestNo()).isEqualTo("R-1");

        List<SlotView> timeline = service.timeline("T");
        assertThat(timeline).hasSize(1);
        SlotView slot = timeline.get(0);
        assertThat(slot.requestNo()).isEqualTo("W2");
        assertThat(slot.startAt()).isEqualTo(T2010);
        assertThat(slot.endAt()).isEqualTo(T2050);
        assertThat(slot.fromPromotion()).isTrue();
        assertThat(slot.promotedFromRequestNo()).isEqualTo("R-1");

        // W1/W3 仍候补，顺序保持 申请时间升序
        assertThat(service.waitlist("T").waiting())
                .extracting(RequestView::requestNo)
                .containsExactly("W1", "W3");

        List<PromotionView> promotions = service.promotions();
        assertThat(promotions).hasSize(1);
        PromotionView event = promotions.get(0);
        assertThat(event.cancelledRequestNo()).isEqualTo("R-1");
        assertThat(event.promotedRequestNo()).isEqualTo("W2");
        assertThat(event.gapStartAt()).isEqualTo(T2000);
        assertThat(event.gapEndAt()).isEqualTo(T2100);

        // 原始空档位置 20:00-20:10、20:50-21:00 保持空闲：与之相接的区间可再批准
        service.apply(null, request("R-TAIL", "P6", "T",
                Instant.parse("2026-09-30T20:00:00Z"), T2010, 10));
        assertThat(service.approve(null, "R-TAIL").status()).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("无合格候补时接替失败：撤销状态与空档保持一致，无双重占用")
    void noFittingCandidateKeepsGapConsistent() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");
        service.apply(null, request("W1", "P2", "T",
                Instant.parse("2026-09-30T19:50:00Z"),
                Instant.parse("2026-09-30T20:20:00Z"), 30));
        service.apply(null, request("W2", "P3", "T",
                Instant.parse("2026-09-30T20:55:00Z"),
                Instant.parse("2026-09-30T21:05:00Z"), 10));

        CancelResponse response = service.cancel(null, "R-1", "P1");
        assertThat(response.promoted()).isNull();
        assertThat(response.cancelled().status()).isEqualTo("CANCELLED");

        // 空档完整空闲：整段 20:00-21:00 可重新批准
        service.apply(null, request("R-2", "P4", "T", T2000, T2100, 60));
        assertThat(service.approve(null, "R-2").status()).isEqualTo("ALLOCATED");
        assertThat(service.timeline("T")).hasSize(1);
        assertThat(service.promotions()).isEmpty();
    }

    @Test
    @DisplayName("同一 requestId 重放申请首次结果；换参数返回409")
    void idempotentApplyReplayAndConflict() {
        ApplyRequest first = request("R-IDEM", "P1", "T", T2000, T2100, 60);
        RequestView view1 = service.apply("key-apply-1", first);
        RequestView view2 = service.apply("key-apply-1", first);

        assertThat(view2).isEqualTo(view1);
        assertThat(service.requests("T", null)).hasSize(1);

        ApplyRequest different = request("R-OTHER", "P1", "T", T2000, T2100, 60);
        assertThatThrownBy(() -> service.apply("key-apply-1", different))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("幂等键已用于不同的请求参数");
        assertThat(service.requests("T", null)).hasSize(1);
    }

    @Test
    @DisplayName("批准事务失败不占幂等键：冲突返回409后可用同一键重试成功")
    void failedApproveDoesNotConsumeIdempotencyKey() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");
        // R-2 与 R-1 重叠，且其窗口超出 R-1 空档结束（21:30 > 21:00），
        // 撤销 R-1 后它不会被候补接替，仍可用同一幂等键重试批准
        service.apply(null, request("R-2", "P2", "T",
                Instant.parse("2026-09-30T20:30:00Z"),
                Instant.parse("2026-09-30T21:30:00Z"), 60));

        assertThatThrownBy(() -> service.approve("key-approve-retry", "R-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重叠");

        // 键未被占用：撤销冲突窗口（R-2 不适配空档不会被接替）后用同一 requestId 重试批准
        CancelResponse cancelled = service.cancel(null, "R-1", "P1");
        assertThat(cancelled.promoted()).isNull();
        RequestView retried = service.approve("key-approve-retry", "R-2");
        assertThat(retried.status()).isEqualTo("ALLOCATED");

        // 再用同一键重放，仍是首次成功的结果
        assertThat(service.approve("key-approve-retry", "R-2"))
                .isEqualTo(retried);
    }

    @Test
    @DisplayName("撤销重放首次结果，不再次推进候补")
    void idempotentCancelReplay() {
        service.apply(null, request("R-1", "P1", "T", T2000, T2100, 60));
        service.approve(null, "R-1");
        service.apply(null, request("W1", "P2", "T", T2010, T2050, 40));
        service.apply(null, request("W2", "P3", "T", T2010, T2050, 40));

        CancelResponse first = service.cancel("key-cancel-1", "R-1", "P1");
        CancelResponse replay = service.cancel("key-cancel-1", "R-1", "P1");

        assertThat(replay).isEqualTo(first);
        assertThat(service.promotions()).hasSize(1);
        assertThat(service.timeline("T")).hasSize(1);
    }

    @Test
    @DisplayName("查询可列出申请状态并按仪器/项目过滤")
    void requestsQueryAndFilter() {
        service.apply(null, request("R-1", "P1", "T1", T2000, T2100, 60));
        service.apply(null, request("R-2", "P2", "T2", T2000, T2100, 60));

        assertThat(service.requests("T1", null)).extracting(RequestView::requestNo)
                .containsExactly("R-1");
        assertThat(service.requests(null, "P2")).extracting(RequestView::requestNo)
                .containsExactly("R-2");
        assertThat(service.requests("T1", "P2")).isEmpty();
        assertThat(service.requests(null, null)).hasSize(2);
    }
}
