package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.support.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 发布：主流程、版本冲突、授权失效、快照只读与幂等边界。
 */
class PublishServiceTest {

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

    private static ApiException assertStatus(HttpStatus status, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(status, e.status(), e.getMessage());
        return e;
    }

    private Draft createDraft(String requestId) {
        return fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, requestId, 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
    }

    @Test
    void publishFirstTime() {
        createDraft(rid());
        PublishedSchedule published = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, rid(), 1, 0);
        assertEquals(1, published.version());
        assertEquals(1, published.draftVersion());
        assertEquals(1, published.segments().size());
        // 快照可读回
        PublishedSchedule stored = fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(1, stored.version());
        assertEquals("s1", stored.segments().get(0).segmentId());
    }

    @Test
    void republishIncrementsVersion() {
        createDraft(rid());
        fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0);
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "12:00", "12:01")));
        PublishedSchedule v2 = fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY,
                rid(), 2, 1);
        assertEquals(2, v2.version());
        assertEquals(2, v2.draftVersion());
        assertEquals("s2", fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY)
                .segments().get(0).segmentId());
    }

    @Test
    void draftVersionMismatchRejected() {
        createDraft(rid());
        assertStatus(HttpStatus.CONFLICT, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 99, 0));
        // 未产生发布
        assertStatus(HttpStatus.NOT_FOUND,
                () -> fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY));
    }

    @Test
    void publishedVersionConflictRejected() {
        createDraft(rid());
        fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0);
        // 再次以 expectedPublishedVersion=0 发布
        assertStatus(HttpStatus.CONFLICT, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0));
        // 快照保持第一版
        assertEquals(1, fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }

    @Test
    void publishWithoutDraftRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0));
    }

    @Test
    void publishWithoutChannelRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.publishService.publish(
                "no-such-channel", TestFixture.DAY, rid(), 1, 0));
    }

    @Test
    void grantRevokedBeforePublishFails() {
        // 撤销先提交：发布必须失败
        createDraft(rid());
        fx.grantService.revoke(grant.id(), rid());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY));
    }

    @Test
    void publishBeforeRevokeSucceedsAndSnapshotSurvives() {
        // 发布先提交：发布成功；撤销不改写历史快照，但播出查询应用撤销结果
        createDraft(rid());
        PublishedSchedule published = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, rid(), 1, 0);
        assertEquals(1, published.version());

        fx.grantService.revoke(grant.id(), rid());

        // 快照未被改写
        PublishedSchedule stored = fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(1, stored.version());
        assertEquals(1, stored.segments().size());
        assertEquals(TestFixture.ASSET_60S, stored.segments().get(0).assetId());

        // 播出查询应用撤销结果：命中片段时段返回保底素材
        var decision = fx.decisionService.decide(TestFixture.CHANNEL, TestFixture.at("10:00"));
        assertEquals("FALLBACK", decision.type().name());
        assertEquals("GRANT_REVOKED", decision.reason());
        assertEquals(TestFixture.FALLBACK_ASSET, decision.assetId());
    }

    @Test
    void idempotentReplayReturnsOriginalResult() {
        createDraft(rid());
        String requestId = rid();
        PublishedSchedule first = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, requestId, 1, 0);
        PublishedSchedule replay = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, requestId, 1, 0);
        assertEquals(first.version(), replay.version());
        // 未重复增版本
        assertEquals(1, fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }

    @Test
    void sameRequestIdWithDifferentParamsConflict() {
        createDraft(rid());
        String requestId = rid();
        fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY, requestId, 1, 0);
        assertStatus(HttpStatus.CONFLICT, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, requestId, 1, 1));
    }

    @Test
    void failedPublishDoesNotConsumeRequestId() {
        createDraft(rid());
        String requestId = rid();
        // 草稿版本不符，第一次失败
        assertStatus(HttpStatus.CONFLICT, () -> fx.publishService.publish(
                TestFixture.CHANNEL, TestFixture.DAY, requestId, 99, 0));
        // 修正参数后同一 requestId 可成功
        PublishedSchedule published = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, requestId, 1, 0);
        assertEquals(1, published.version());
    }

    @Test
    void emptyDraftPublishesEmptySchedule() {
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of());
        PublishedSchedule published = fx.publishService.publish(TestFixture.CHANNEL,
                TestFixture.DAY, rid(), 1, 0);
        assertEquals(1, published.version());
        assertEquals(0, published.segments().size());
    }

    @Test
    void draftSegmentsAreDefensivelyCopiedIntoSnapshot() {
        createDraft(rid());
        fx.publishService.publish(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1, 0);
        // 草稿后续替换不影响已发布快照
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "12:00", "12:01")));
        PublishedSchedule stored = fx.publishService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(1, stored.segments().size());
        assertEquals("s1", stored.segments().get(0).segmentId());
    }
}
