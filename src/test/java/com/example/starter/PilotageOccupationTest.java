package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.service.ApiException;
import com.example.starter.domain.AssignmentStatus;
import com.example.starter.domain.SegmentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 占用与状态规则测试：重叠拒绝、登轮延续占用、撤销释放、取消保留事实，均落真实 H2。
 */
class PilotageOccupationTest extends AbstractPilotageTest {

    @Test
    void overlapRejectedAndFailedRequestKeyReusable() {
        long pilot = pilotId("P001");
        long seg1 = newSegment("MV-A", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        var first = service.assign("r-ov-1", seg1, pilot);
        assertEquals(AssignmentStatus.ACTIVE, first.assignment().status());

        long seg2 = newSegment("MV-B", 1, "2026-10-04T09:30:00Z", "2026-10-04T10:30:00Z", "锚地B");
        ApiException overlap = assertThrows(ApiException.class, () -> service.assign("r-ov-2", seg2, pilot));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("PILOT_INTERVAL_OVERLAP", overlap.code());

        // 失败不占请求键：同一 requestId 换不重叠参数可成功
        long seg3 = newSegment("MV-C", 1, "2026-10-04T10:00:00Z", "2026-10-04T11:00:00Z", "锚地C");
        var reused = service.assign("r-ov-2", seg3, pilot);
        assertEquals(AssignmentStatus.ACTIVE, reused.assignment().status());
        assertEquals(seg3, reused.assignment().segmentId());

        // 边界相邻不算重叠；另一引航员同区间不受限
        long seg4 = newSegment("MV-D", 1, "2026-10-04T09:30:00Z", "2026-10-04T10:30:00Z", "锚地D");
        var other = service.assign("r-ov-3", seg4, pilotId("P002"));
        assertEquals(AssignmentStatus.ACTIVE, other.assignment().status());
    }

    @Test
    void boardedOccupationExtendsUntilActualDeparture() {
        long pilot = pilotId("P001");
        long seg1 = newSegment("MV-E", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long assignmentId = service.assign("r-bd-1", seg1, pilot).assignment().id();

        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        service.board(assignmentId, "b-bd-1");

        // 预计 10:00 结束，但已登轮未离轮，10:05 的新航段仍冲突
        long seg2 = newSegment("MV-F", 1, "2026-10-04T10:05:00Z", "2026-10-04T11:00:00Z", "锚地B");
        ApiException stillOccupied = assertThrows(ApiException.class, () -> service.assign("r-bd-2", seg2, pilot));
        assertEquals(HttpStatus.CONFLICT, stillOccupied.status());

        // 实际 10:10 离轮：10:05 仍重叠实际占用，10:15 起可指派
        clock.setInstant(Instant.parse("2026-10-04T10:10:00Z"));
        service.complete(assignmentId);
        ApiException overlapActual = assertThrows(ApiException.class, () -> service.assign("r-bd-3", seg2, pilot));
        assertEquals(HttpStatus.CONFLICT, overlapActual.status());
        long seg3 = newSegment("MV-G", 1, "2026-10-04T10:15:00Z", "2026-10-04T11:00:00Z", "锚地C");
        var after = service.assign("r-bd-4", seg3, pilot);
        assertEquals(AssignmentStatus.ACTIVE, after.assignment().status());
    }

    @Test
    void revokeReleasesAndBoardedCannotBeRevoked() {
        long pilot = pilotId("P001");
        long seg1 = newSegment("MV-H", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long assignmentId = service.assign("r-rv-1", seg1, pilot).assignment().id();

        var revoked = service.revoke(assignmentId);
        assertEquals(AssignmentStatus.REVOKED, revoked.status());
        assertNotNull(revoked.revokedAtUtc());
        assertEquals(SegmentStatus.REQUESTED, service.getSegment(seg1).status());

        // 撤销释放占用：重叠新航段可指派；已撤销指派不可登轮
        long seg2 = newSegment("MV-I", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地B");
        service.assign("r-rv-2", seg2, pilot);
        ApiException boardRevoked = assertThrows(ApiException.class, () -> service.board(assignmentId, "b-rv-1"));
        assertEquals(HttpStatus.CONFLICT, boardRevoked.status());
    }

    @Test
    void cancelKeepsBoardingFactsAndReleasesActiveAssignment() {
        long pilot = pilotId("P001");
        long seg1 = newSegment("MV-J", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long a1 = service.assign("r-cc-1", seg1, pilot).assignment().id();
        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        service.board(a1, "b-cc-1");

        var cancelled = service.cancelSegment(seg1);
        assertEquals(SegmentStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.cancelledAtUtc());

        // 取消后登轮事实保留，离轮交接仍可完成
        clock.setInstant(Instant.parse("2026-10-04T10:10:00Z"));
        var completed = service.complete(a1);
        assertEquals(AssignmentStatus.COMPLETED, completed.status());
        var timeline = service.vesselTimeline("MV-J");
        assertEquals(1, timeline.size());
        assertEquals(SegmentStatus.CANCELLED, timeline.get(0).segment().status());
        assertEquals(Instant.parse("2026-10-04T09:05:00Z"), timeline.get(0).latestAssignment().boardedAtUtc());
        assertEquals(Instant.parse("2026-10-04T10:10:00Z"), timeline.get(0).latestAssignment().departedAtUtc());

        // 未登轮指派随取消撤销并释放
        long seg2 = newSegment("MV-K", 1, "2026-10-04T12:00:00Z", "2026-10-04T13:00:00Z", "锚地B");
        long a2 = service.assign("r-cc-2", seg2, pilot).assignment().id();
        service.cancelSegment(seg2);
        assertEquals(AssignmentStatus.REVOKED, assignmentRepo.findById(a2).orElseThrow().status());
        long seg3 = newSegment("MV-L", 1, "2026-10-04T12:00:00Z", "2026-10-04T13:00:00Z", "锚地C");
        service.assign("r-cc-3", seg3, pilot);
    }

    @Test
    void boardingPointLockedAfterBoardingAndOriginalKept() {
        long pilot = pilotId("P001");
        long segId = newSegment("MV-M", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        service.changeBoardingPoint(segId, "锚地B");
        var changed = service.getSegment(segId);
        assertEquals("锚地B", changed.boardingPoint());
        assertEquals("锚地A", changed.originalBoardingPoint());

        long assignmentId = service.assign("r-bp-1", segId, pilot).assignment().id();
        service.board(assignmentId, "b-bp-1");
        ApiException locked = assertThrows(ApiException.class,
                () -> service.changeBoardingPoint(segId, "锚地C"));
        assertEquals(HttpStatus.CONFLICT, locked.status());
        assertEquals("锚地B", service.getSegment(segId).boardingPoint());
    }

    @Test
    void completeRequiresBoardingAndReplayable() {
        long pilot = pilotId("P001");
        long segId = newSegment("MV-N", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long assignmentId = service.assign("r-cp-1", segId, pilot).assignment().id();
        ApiException notBoarded = assertThrows(ApiException.class, () -> service.complete(assignmentId));
        assertEquals(HttpStatus.CONFLICT, notBoarded.status());

        service.board(assignmentId, "b-cp-1");
        clock.setInstant(Instant.parse("2026-10-04T10:10:00Z"));
        service.complete(assignmentId);
        var replay = service.complete(assignmentId);
        assertEquals(AssignmentStatus.COMPLETED, replay.status());
        assertNull(service.getSegment(segId).cancelledAtUtc());
        assertTrue(service.pilotTimeline(pilot).stream()
                .anyMatch(item -> item.assignment().id() == assignmentId
                        && item.assignment().status() == AssignmentStatus.COMPLETED));
    }
}