package com.example.starter.service;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.RequestView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 申请、批准、撤销拒绝分支的真实 H2 数据库测试。
 */
class AllocationRejectionIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T20 = Instant.parse("2026-10-01T20:00:00Z");
    private static final Instant T21 = Instant.parse("2026-10-01T21:00:00Z");

    private final AllocationService service;

    AllocationRejectionIntegrationTest(AllocationService service) {
        this.service = service;
    }

    @Test
    void endNotAfterStartIsRejectedWith422() {
        assertThatThrownBy(() -> service.apply(Requests.of("P", "T", "G", T21, T20, 60, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("结束时刻必须晚于开始时刻");
        assertThatThrownBy(() -> service.apply(Requests.of("P", "T", "G", T20, T20, 0, null)))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void durationLongerThanWindowIsRejectedWith422() {
        assertThatThrownBy(() -> service.apply(Requests.of("P", "T", "G", T20, T21, 61, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("所需分钟数超过窗口时长");
    }

    @Test
    void windowInPastIsRejectedWith422() {
        clock.advance(Duration.ofHours(10)); // 现在 22:00
        assertThatThrownBy(() -> service.apply(Requests.of("P", "T", "G", T20, T21, 60, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("开始时刻必须晚于当前时间");
    }

    @Test
    void overlappingApprovalIsRejectedWith409() {
        RequestView first = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        service.approve(first.requestNo(), null);

        RequestView second = service.apply(Requests.of("P2", "T", "G2",
                Instant.parse("2026-10-01T20:30:00Z"),
                Instant.parse("2026-10-01T21:30:00Z"), 60, null));
        assertThatThrownBy(() -> service.approve(second.requestNo(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("时间区间重叠");
        assertThat(service.getRequest(second.requestNo()).status()).isEqualTo("PENDING");

        // 首尾相接不重叠，可以批准
        RequestView adjacent = service.apply(Requests.of("P3", "T", "G3",
                T21, Instant.parse("2026-10-01T22:00:00Z"), 60, null));
        assertThat(service.approve(adjacent.requestNo(), null).status()).isEqualTo("ALLOCATED");
    }

    @Test
    void otherGroupCannotCancel() {
        RequestView first = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        service.approve(first.requestNo(), null);
        assertThatThrownBy(() -> service.cancel(first.requestNo(), "G2", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("仅归属研究组可撤销");
        assertThat(service.getRequest(first.requestNo()).status()).isEqualTo("ALLOCATED");
    }

    @Test
    void startedWindowCannotBeCancelledOrApproved() {
        RequestView allocated = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        service.approve(allocated.requestNo(), null);
        RequestView pending = service.apply(Requests.of("P2", "T2", "G2", T20, T21, 60, null));

        clock.advance(Duration.ofHours(9)); // 现在 21:00，窗口 [20:00,21:00) 已结束
        assertThatThrownBy(() -> service.cancel(allocated.requestNo(), "G1", null))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("窗口已开始");
        assertThatThrownBy(() -> service.approve(pending.requestNo(), null))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("窗口已开始");
        assertThat(service.getRequest(allocated.requestNo()).status()).isEqualTo("ALLOCATED");
        assertThat(service.getRequest(pending.requestNo()).status()).isEqualTo("PENDING");
    }

    @Test
    void approvingCancelledRequestIsRejected() {
        RequestView first = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        service.cancel(first.requestNo(), "G1", null);
        assertThatThrownBy(() -> service.approve(first.requestNo(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤销");
    }

    @Test
    void unknownRequestYields404() {
        assertThatThrownBy(() -> service.approve("REQ-999999", null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel("REQ-999999", "G1", null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getRequest("REQ-999999"))
                .isInstanceOf(NotFoundException.class);
    }
}
