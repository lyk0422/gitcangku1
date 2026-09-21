package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.support.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 草稿整份替换：主流程、校验分支、版本冲突与幂等边界。
 */
class DraftServiceTest {

    private TestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TestFixture();
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static ApiException assertStatus(HttpStatus status, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(status, e.status(), e.getMessage());
        return e;
    }

    @Test
    void createDraftFirstTime() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        Draft draft = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
        assertEquals(1, draft.version());
        assertEquals(1, draft.segments().size());
        assertEquals("s1", draft.segments().get(0).segmentId());
        // 持久化可读回
        assertEquals(1, fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }

    @Test
    void replaceExistingDraftIncrementsVersion() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
        Draft v2 = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 1,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "11:00", "11:01")));
        assertEquals(2, v2.version());
        assertEquals("s2", v2.segments().get(0).segmentId());
    }

    @Test
    void versionConflictRejectedAndDraftUnchanged() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
        // 过期版本
        assertStatus(HttpStatus.CONFLICT, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "11:00", "11:01"))));
        // 未来版本
        assertStatus(HttpStatus.CONFLICT, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 5,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "11:00", "11:01"))));
        // 原草稿未被改变
        Draft current = fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY);
        assertEquals(1, current.version());
        assertEquals("s1", current.segments().get(0).segmentId());
    }

    @Test
    void overlappingSegmentsRejected() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        fx.grantCoveringDay(TestFixture.ASSET_30S);
        // 30 秒片段落在 60 秒片段区间内
        DraftSegment s1 = TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01");
        DraftSegment s2 = new DraftSegment("s2", TestFixture.ASSET_30S,
                TestFixture.at("10:00").plusSeconds(30), TestFixture.at("10:01"));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of(s1, s2)));
    }

    @Test
    void adjacentSegmentsAllowed() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        Draft draft = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"),
                        TestFixture.segment("s2", TestFixture.ASSET_60S, "10:01", "10:02")));
        assertEquals(2, draft.segments().size());
    }

    @Test
    void crossDaySegmentRejected() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        // 结束时间跨入次日
        DraftSegment late = new DraftSegment("s1", TestFixture.ASSET_60S,
                TimeSupport.dayEnd(TestFixture.DAY).minusSeconds(30),
                TimeSupport.dayEnd(TestFixture.DAY).plusSeconds(30));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of(late)));
        // 开始时间早于当日
        DraftSegment early = new DraftSegment("s2", TestFixture.ASSET_60S,
                TimeSupport.dayStart(TestFixture.DAY).minusSeconds(30),
                TimeSupport.dayStart(TestFixture.DAY).plusSeconds(30));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of(early)));
    }

    @Test
    void durationMismatchRejected() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        // 60 秒素材只排了 30 秒
        DraftSegment short_ = new DraftSegment("s1", TestFixture.ASSET_60S,
                TestFixture.at("10:00"), TestFixture.at("10:00").plusSeconds(30));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of(short_)));
    }

    @Test
    void missingGrantRejected() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"))));
    }

    @Test
    void revokedGrantRejected() {
        Grant grant = fx.grantCoveringDay(TestFixture.ASSET_60S);
        fx.grants.markRevoked(grant.id());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"))));
    }

    @Test
    void splicedGrantsRejected() {
        // 两条授权各覆盖片段一半，不允许拼接多条授权
        fx.grants.insert(new Grant(UUID.randomUUID().toString(), TestFixture.CHANNEL,
                TestFixture.ASSET_60S, TestFixture.at("10:00"),
                TestFixture.at("10:00").plusSeconds(30), false));
        fx.grants.insert(new Grant(UUID.randomUUID().toString(), TestFixture.CHANNEL,
                TestFixture.ASSET_60S, TestFixture.at("10:00").plusSeconds(30),
                TestFixture.at("10:01"), false));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"))));
    }

    @Test
    void unknownAssetRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of(TestFixture.segment("s1", "no-such-asset", "10:00", "10:01"))));
    }

    @Test
    void unknownChannelRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.draftService.replace(
                "no-such-channel", TestFixture.DAY, rid(), 0, List.of()));
    }

    @Test
    void emptySegmentListAllowed() {
        Draft draft = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, rid(), 0,
                List.of());
        assertEquals(1, draft.version());
        assertTrue(draft.segments().isEmpty());
    }

    @Test
    void invalidSegmentIntervalRejected() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        DraftSegment reversed = new DraftSegment("s1", TestFixture.ASSET_60S,
                TestFixture.at("10:01"), TestFixture.at("10:00"));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, rid(), 0, List.of(reversed)));
    }

    @Test
    void idempotentReplayReturnsOriginalResult() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        String requestId = rid();
        List<DraftSegment> segments =
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"));
        Draft first = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, requestId,
                0, segments);
        Draft replay = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, requestId,
                0, segments);
        assertEquals(first.version(), replay.version());
        assertEquals(first.segments(), replay.segments());
        // 未重复增版本
        assertEquals(1, fx.draftService.get(TestFixture.CHANNEL, TestFixture.DAY).version());
    }

    @Test
    void sameRequestIdWithDifferentParamsConflict() {
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        String requestId = rid();
        fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, requestId, 0,
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01")));
        assertStatus(HttpStatus.CONFLICT, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, requestId, 0,
                List.of(TestFixture.segment("s2", TestFixture.ASSET_60S, "11:00", "11:01"))));
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() {
        String requestId = rid();
        List<DraftSegment> segments =
                List.of(TestFixture.segment("s1", TestFixture.ASSET_60S, "10:00", "10:01"));
        // 无授权，第一次失败
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.draftService.replace(
                TestFixture.CHANNEL, TestFixture.DAY, requestId, 0, segments));
        // 补上授权后同一 requestId 可成功
        fx.grantCoveringDay(TestFixture.ASSET_60S);
        Draft draft = fx.draftService.replace(TestFixture.CHANNEL, TestFixture.DAY, requestId,
                0, segments);
        assertEquals(1, draft.version());
    }
}
