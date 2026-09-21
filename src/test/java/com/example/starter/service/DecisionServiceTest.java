package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Decision;
import com.example.starter.domain.Grant;
import com.example.starter.support.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 播出决定：节目命中、空档、无编排、授权撤销与边界时刻。
 */
class DecisionServiceTest {

    private TestFixture fx;
    private Grant grant;

    @BeforeEach
    void setUp() {
        fx = new TestFixture();
        grant = fx.grantCoveringDay(TestFixture.ASSET_60S);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private void publishTwoSegments() {
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"),
                        TestFixture.segment("s2", TestFixture.ASSET_60S, "10:01", "10:02")));
        fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0);
    }

    @Test
    void programHitReturnsProgramAsset() {
        publishTwoSegments();
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:00").plusSeconds(30));
        assertEquals(Decision.DecisionType.PROGRAM, decision.type());
        assertEquals(TestFixture.ASSET_60S, decision.assetId());
        assertEquals("s1", decision.segmentId());
        assertNull(decision.reason());
    }

    @Test
    void noPublishedScheduleReturnsFallback() {
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:00"));
        assertEquals(Decision.DecisionType.FALLBACK, decision.type());
        assertEquals(TestFixture.FALLBACK_ASSET, decision.assetId());
        assertEquals("NO_PUBLISHED_SCHEDULE", decision.reason());
    }

    @Test
    void gapReturnsFallback() {
        publishTwoSegments();
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:05"));
        assertEquals(Decision.DecisionType.FALLBACK, decision.type());
        assertEquals(TestFixture.FALLBACK_ASSET, decision.assetId());
        assertEquals("GAP", decision.reason());
    }

    @Test
    void revokedGrantReturnsFallback() {
        publishTwoSegments();
        fx.grantService.revoke(grant.id(), rid());
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:00"));
        assertEquals(Decision.DecisionType.FALLBACK, decision.type());
        assertEquals(TestFixture.FALLBACK_ASSET, decision.assetId());
        assertEquals("GRANT_REVOKED", decision.reason());
    }

    @Test
    void unknownChannelRejected() {
        ApiException e = assertThrows(ApiException.class,
                () -> fx.decisionService.decide("no-such-channel", TestFixture.at("10:00")));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }

    @Test
    void segmentStartIsInclusive() {
        publishTwoSegments();
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:00"));
        assertEquals(Decision.DecisionType.PROGRAM, decision.type());
        assertEquals("s1", decision.segmentId());
    }

    @Test
    void segmentEndIsExclusiveAndNextSegmentStarts() {
        publishTwoSegments();
        // 10:01 整点属于 s2
        Decision atBoundary = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:01"));
        assertEquals(Decision.DecisionType.PROGRAM, atBoundary.type());
        assertEquals("s2", atBoundary.segmentId());
        // 10:02 之后为空档
        Decision after = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:02"));
        assertEquals(Decision.DecisionType.FALLBACK, after.type());
        assertEquals("GAP", after.reason());
    }

    @Test
    void decisionOnlyReadsPublishedSnapshotNotDraft() {
        // 草稿存在但未发布：仍返回无编排
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
        Decision decision = fx.decisionService.decide(TestFixture.CHANNEL,
                TestFixture.at("10:00"));
        assertEquals(Decision.DecisionType.FALLBACK, decision.type());
        assertEquals("NO_PUBLISHED_SCHEDULE", decision.reason());
    }
}
