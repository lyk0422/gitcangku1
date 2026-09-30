package com.example.starter.service;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.TimelineView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * requestId 幂等语义的真实 H2 数据库测试。
 */
class AllocationIdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T20 = Instant.parse("2026-10-01T20:00:00Z");
    private static final Instant T21 = Instant.parse("2026-10-01T21:00:00Z");

    private final AllocationService service;

    AllocationIdempotencyIntegrationTest(AllocationService service) {
        this.service = service;
    }

    @Test
    void sameApplyRequestIdReplaysFirstResult() {
        ApplyRequest body = Requests.of("P1", "T", "G1", T20, T21, 60, "rid-apply-1");
        RequestView first = service.apply(body);
        RequestView replay = service.apply(body);

        assertThat(replay.requestNo()).isEqualTo(first.requestNo());
        assertThat(replay.status()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_request WHERE request_no = ?",
                Integer.class, first.requestNo())).isEqualTo(1);
    }

    @Test
    void sameApplyRequestIdWithChangedParametersIsRejectedWith409() {
        service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, "rid-apply-2"));
        ApplyRequest changed = Requests.of("P1", "T", "G1",
                Instant.parse("2026-10-01T20:30:00Z"), T21, 30, "rid-apply-2");

        assertThatThrownBy(() -> service.apply(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");
    }

    @Test
    void approveFailureDoesNotConsumeIdempotencyKeyAndCanRetry() {
        RequestView blocker = service.apply(Requests.of("P-block", "T", "G1",
                T20, T21, 60, null));
        service.approve(blocker.requestNo(), null);
        // 候选窗口 20:30-21:30 与阻塞窗口重叠，且超出 [20:00,21:00) 空档
        RequestView waiting = service.apply(Requests.of("P-wait", "T", "G2",
                Instant.parse("2026-10-01T20:30:00Z"),
                Instant.parse("2026-10-01T21:30:00Z"), 60, null));

        // 首次批准因重叠失败：事务回滚，不占幂等键
        assertThatThrownBy(() -> service.approve(waiting.requestNo(), "rid-approve-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("时间区间重叠");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_call WHERE request_id = 'rid-approve-1'",
                Integer.class)).isZero();

        // 阻塞窗口被撤销；候选超出空档不会被自动接替，仍为 PENDING
        service.cancel(blocker.requestNo(), "G1", null);
        assertThat(service.getRequest(waiting.requestNo()).status()).isEqualTo("PENDING");

        // 用同一 requestId 重试批准：成功且占用幂等键；再放重放返回首次结果
        RequestView retried = service.approve(waiting.requestNo(), "rid-approve-1");
        assertThat(retried.status()).isEqualTo("ALLOCATED");
        RequestView replay = service.approve(waiting.requestNo(), "rid-approve-1");
        assertThat(replay.status()).isEqualTo("ALLOCATED");
        assertThat(replay.allocatedAt()).isEqualTo(retried.allocatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_call WHERE request_id = 'rid-approve-1'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void sameCancelRequestIdReplaysWithoutPromotingTwice() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView waiter1 = service.apply(Requests.of("P-w1", "T", "G2",
                Instant.parse("2026-10-01T20:10:00Z"),
                Instant.parse("2026-10-01T20:50:00Z"), 40, null));

        RequestView first = service.cancel(primary.requestNo(), "G1", "rid-cancel-1");
        RequestView replay = service.cancel(primary.requestNo(), "G1", "rid-cancel-1");

        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(replay.status()).isEqualTo("CANCELLED");
        assertThat(service.getRequest(waiter1.requestNo()).status()).isEqualTo("ALLOCATED");

        TimelineView timeline = service.getTimeline("T");
        assertThat(timeline.successions()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_call WHERE request_id = 'rid-cancel-1'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateCancelWithoutRequestIdDoesNotAdvanceWaitersAgain() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, null));
        service.approve(primary.requestNo(), null);
        RequestView waiter1 = service.apply(Requests.of("P-w1", "T", "G2",
                Instant.parse("2026-10-01T20:10:00Z"),
                Instant.parse("2026-10-01T20:50:00Z"), 40, null));
        RequestView waiter2 = service.apply(Requests.of("P-w2", "T", "G3",
                Instant.parse("2026-10-01T20:20:00Z"),
                Instant.parse("2026-10-01T20:30:00Z"), 10, null));

        service.cancel(primary.requestNo(), "G1", null);
        // 重复撤销（不带幂等键）必须 409，且不能再次推进候补
        assertThatThrownBy(() -> service.cancel(primary.requestNo(), "G1", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已撤销");

        assertThat(service.getRequest(waiter1.requestNo()).status()).isEqualTo("ALLOCATED");
        assertThat(service.getRequest(waiter2.requestNo()).status()).isEqualTo("PENDING");
        assertThat(service.getTimeline("T").successions()).hasSize(1);
    }

    @Test
    void sameCancelRequestIdWithChangedGroupIsRejectedWith409() {
        RequestView primary = service.apply(Requests.of("P-primary", "T", "G1",
                T20, T21, 60, "rid-cancel-2"));
        service.cancel(primary.requestNo(), "G1", "rid-cancel-2");
        assertThatThrownBy(() -> service.cancel(primary.requestNo(), "G2", "rid-cancel-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void approveIdempotentReplayReturnsFirstResult() {
        RequestView primary = service.apply(Requests.of("P1", "T", "G1", T20, T21, 60, null));
        RequestView first = service.approve(primary.requestNo(), "rid-approve-2");
        RequestView replay = service.approve(primary.requestNo(), "rid-approve-2");
        assertThat(replay.status()).isEqualTo("ALLOCATED");
        assertThat(replay.allocatedAt()).isEqualTo(first.allocatedAt());
    }
}
