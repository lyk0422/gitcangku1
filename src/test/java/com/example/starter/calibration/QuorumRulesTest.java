package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.service.QuorumRules;

/**
 * 离群复核法定人数纯逻辑：至少两名不同于提交人的有效复核人、同议才成裁；
 * 结论相反保持待仲裁；STALE 不计入；第三名复核人打破僵局；差额计数。
 */
class QuorumRulesTest {

    private static OutlierReview review(String reviewer, ReviewDecision decision, boolean stale) {
        return new OutlierReview(0L, 1L, reviewer, decision, "reason-" + reviewer,
                1, 10L, stale, Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void noOrSingleReviewStaysPending() {
        assertEquals(OutlierStatus.PENDING, QuorumRules.evaluate(List.of()));
        assertEquals(OutlierStatus.PENDING,
                QuorumRules.evaluate(List.of(review("bob", ReviewDecision.ACCEPT, false))));
    }

    @Test
    void twoSameDecisionsFormVerdict() {
        assertEquals(OutlierStatus.ACCEPTED, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.ACCEPT, false),
                review("carol", ReviewDecision.ACCEPT, false))));
        assertEquals(OutlierStatus.REJECTED, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.REJECT, false),
                review("carol", ReviewDecision.REJECT, false))));
    }

    @Test
    void splitDecisionsStayPendingArbitrationAndThirdReviewerBreaksTie() {
        List<OutlierReview> split = List.of(
                review("bob", ReviewDecision.ACCEPT, false),
                review("carol", ReviewDecision.REJECT, false));
        assertEquals(OutlierStatus.PENDING, QuorumRules.evaluate(split));

        // 第三名不同复核人同议 ACCEPT → 形成 ACCEPTED 裁决
        assertEquals(OutlierStatus.ACCEPTED, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.ACCEPT, false),
                review("carol", ReviewDecision.REJECT, false),
                review("dave", ReviewDecision.ACCEPT, false))));
        // 第三名同议 REJECT → 形成 REJECTED 裁决
        assertEquals(OutlierStatus.REJECTED, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.ACCEPT, false),
                review("carol", ReviewDecision.REJECT, false),
                review("dave", ReviewDecision.REJECT, false))));
    }

    @Test
    void staleReviewsDoNotCountTowardQuorum() {
        // 两条复核但一条 STALE，有效仅一人 → 仍待裁决
        assertEquals(OutlierStatus.PENDING, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.ACCEPT, false),
                review("carol", ReviewDecision.ACCEPT, true))));
        // 两条 STALE 同样不成裁
        assertEquals(OutlierStatus.PENDING, QuorumRules.evaluate(List.of(
                review("bob", ReviewDecision.REJECT, true),
                review("carol", ReviewDecision.REJECT, true))));
    }

    @Test
    void missingVotesReportsActualRequiredAndGap() {
        assertEquals(2, QuorumRules.missingSameVotes(List.of(), ReviewDecision.ACCEPT));
        assertEquals(1, QuorumRules.missingSameVotes(
                List.of(review("bob", ReviewDecision.ACCEPT, false)), ReviewDecision.ACCEPT));
        assertEquals(0, QuorumRules.missingSameVotes(
                List.of(review("bob", ReviewDecision.ACCEPT, false),
                        review("carol", ReviewDecision.ACCEPT, false)),
                ReviewDecision.ACCEPT));
        // 相反结论不抵票：要求 2 名 REJECT，实际 0，差额 2
        assertEquals(2, QuorumRules.missingSameVotes(
                List.of(review("bob", ReviewDecision.ACCEPT, false)), ReviewDecision.REJECT));
    }
}
