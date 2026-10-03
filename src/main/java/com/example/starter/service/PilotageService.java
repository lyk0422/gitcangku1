package com.example.starter.service;

import com.example.starter.domain.Assignment;
import com.example.starter.domain.AssignmentStatus;
import com.example.starter.domain.Pilot;
import com.example.starter.domain.Segment;
import com.example.starter.domain.SegmentStatus;
import com.example.starter.repo.AssignmentRepository;
import com.example.starter.repo.PilotRepository;
import com.example.starter.repo.SegmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 引航航段业务：申请、指派、登轮确认、交接完成、撤销与取消，全部带事务与行锁。
 */
@Service
public class PilotageService {

    /** 指派结果：replayed 为 true 表示同参重放，返回首次结果。 */
    public record AssignOutcome(Assignment assignment, boolean replayed) {
    }

    /** 登轮结果：replayed 为 true 表示同参重放，返回首次结果。 */
    public record BoardOutcome(Assignment assignment, boolean replayed) {
    }

    /** 引航员时间轴条目：指派及其服务的船舶代号。 */
    public record PilotTimelineItem(Assignment assignment, String vesselCode) {
    }

    /** 船舶时间轴条目：航段及其最近一次指派（可为 null）。 */
    public record VesselTimelineItem(Segment segment, Assignment latestAssignment) {
    }

    private final PilotRepository pilotRepo;
    private final SegmentRepository segmentRepo;
    private final AssignmentRepository assignmentRepo;
    private final Clock clock;

    public PilotageService(PilotRepository pilotRepo, SegmentRepository segmentRepo,
                           AssignmentRepository assignmentRepo, Clock clock) {
        this.pilotRepo = pilotRepo;
        this.segmentRepo = segmentRepo;
        this.assignmentRepo = assignmentRepo;
        this.clock = clock;
    }

    @Transactional
    public Segment createSegment(String vesselCode, int requiredGrade, Instant estStartUtc, Instant estEndUtc,
                                 String boardingPoint) {
        if (!estEndUtc.isAfter(estStartUtc)) {
            throw ApiException.unprocessable("SEGMENT_TIME_REVERSED", "预计结束时刻必须晚于预计开始时刻");
        }
        long id = segmentRepo.insert(vesselCode, requiredGrade, estStartUtc, estEndUtc, boardingPoint,
                clock.instant());
        return segmentRepo.findById(id).orElseThrow();
    }

    @Transactional
    public AssignOutcome assign(String requestId, long segmentId, long pilotId) {
        var existing = assignmentRepo.findByRequestId(requestId);
        if (existing.isPresent()) {
            Assignment found = existing.get();
            if (found.segmentId() == segmentId && found.pilotId() == pilotId) {
                return new AssignOutcome(found, true);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "请求键已被不同参数的指派占用");
        }
        Pilot pilot = pilotRepo.findByIdForUpdate(pilotId)
                .orElseThrow(() -> ApiException.notFound("引航员不存在: " + pilotId));
        Segment segment = segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("航段不存在: " + segmentId));
        if (segment.status() != SegmentStatus.REQUESTED) {
            throw ApiException.conflict("SEGMENT_NOT_ASSIGNABLE", "航段当前状态不可指派: " + segment.status());
        }
        if (!pilot.active()) {
            throw ApiException.unprocessable("PILOT_INACTIVE", "引航员不在岗");
        }
        if (pilot.qualificationGrade() < segment.requiredGrade()) {
            throw ApiException.unprocessable("QUALIFICATION_INSUFFICIENT", "引航员资格等级低于船舶要求等级");
        }
        if (!assignmentRepo.findOccupiedOverlapping(pilotId, segment.estStartUtc(), segment.estEndUtc()).isEmpty()) {
            throw ApiException.conflict("PILOT_INTERVAL_OVERLAP", "引航员在重叠航段上已有占用");
        }
        long id;
        try {
            id = assignmentRepo.insert(requestId, segmentId, pilotId, segment.estStartUtc(), segment.estEndUtc(),
                    clock.instant());
        } catch (DuplicateKeyException e) {
            Assignment raced = assignmentRepo.findByRequestId(requestId).orElseThrow();
            if (raced.segmentId() == segmentId && raced.pilotId() == pilotId) {
                return new AssignOutcome(raced, true);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "请求键已被不同参数的指派占用");
        }
        segmentRepo.updateStatus(segmentId, SegmentStatus.ASSIGNED, null);
        return new AssignOutcome(assignmentRepo.findById(id).orElseThrow(), false);
    }

    @Transactional
    public Segment changeBoardingPoint(long segmentId, String boardingPoint) {
        Segment segment = segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("航段不存在: " + segmentId));
        if (segment.status() != SegmentStatus.REQUESTED && segment.status() != SegmentStatus.ASSIGNED) {
            throw ApiException.conflict("BOARDING_POINT_LOCKED", "登轮后或已结束的航段不可修改登轮点");
        }
        segmentRepo.updateBoardingPoint(segmentId, boardingPoint);
        return segmentRepo.findById(segmentId).orElseThrow();
    }

    @Transactional
    public BoardOutcome board(long assignmentId, String requestId) {
        Assignment assignment = assignmentRepo.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> ApiException.notFound("指派不存在: " + assignmentId));
        if (assignment.status() == AssignmentStatus.BOARDED) {
            if (assignment.boardingRequestId().equals(requestId)) {
                return new BoardOutcome(assignment, true);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "该指派已用其他请求键确认登轮");
        }
        if (assignment.status() != AssignmentStatus.ACTIVE) {
            throw ApiException.conflict("ASSIGNMENT_NOT_BOARDABLE", "指派当前状态不可登轮: " + assignment.status());
        }
        assignmentRepo.markBoarded(assignmentId, clock.instant(), requestId);
        segmentRepo.updateStatus(assignment.segmentId(), SegmentStatus.BOARDED, null);
        return new BoardOutcome(assignmentRepo.findById(assignmentId).orElseThrow(), false);
    }

    @Transactional
    public Assignment complete(long assignmentId) {
        Assignment assignment = assignmentRepo.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> ApiException.notFound("指派不存在: " + assignmentId));
        if (assignment.status() == AssignmentStatus.COMPLETED) {
            return assignment;
        }
        if (assignment.status() != AssignmentStatus.BOARDED) {
            throw ApiException.conflict("ASSIGNMENT_NOT_COMPLETABLE", "仅已登轮的指派可确认离轮");
        }
        assignmentRepo.markCompleted(assignmentId, clock.instant());
        // 已取消的航段保持 CANCELLED，仅记录离轮事实；未取消的航段推进为已完成
        Segment segment = segmentRepo.findById(assignment.segmentId()).orElseThrow();
        if (segment.status() == SegmentStatus.BOARDED) {
            segmentRepo.updateStatus(assignment.segmentId(), SegmentStatus.COMPLETED, null);
        }
        return assignmentRepo.findById(assignmentId).orElseThrow();
    }

    @Transactional
    public Assignment revoke(long assignmentId) {
        Assignment assignment = assignmentRepo.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> ApiException.notFound("指派不存在: " + assignmentId));
        if (assignment.status() == AssignmentStatus.REVOKED) {
            return assignment;
        }
        if (assignment.status() != AssignmentStatus.ACTIVE) {
            throw ApiException.conflict("ASSIGNMENT_NOT_REVOCABLE", "已登轮或已完成的指派不可撤销");
        }
        assignmentRepo.markRevoked(assignmentId, clock.instant());
        segmentRepo.updateStatus(assignment.segmentId(), SegmentStatus.REQUESTED, null);
        return assignmentRepo.findById(assignmentId).orElseThrow();
    }

    @Transactional
    public Segment cancelSegment(long segmentId) {
        Segment segment = segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("航段不存在: " + segmentId));
        if (segment.status() == SegmentStatus.CANCELLED) {
            return segment;
        }
        if (segment.status() == SegmentStatus.COMPLETED) {
            throw ApiException.conflict("SEGMENT_NOT_CANCELLABLE", "已完成的航段不可取消");
        }
        Instant now = clock.instant();
        for (Assignment assignment : assignmentRepo.findBySegmentId(segmentId)) {
            if (assignment.status() == AssignmentStatus.ACTIVE) {
                assignmentRepo.markRevoked(assignment.id(), now);
            }
        }
        segmentRepo.updateStatus(segmentId, SegmentStatus.CANCELLED, now);
        return segmentRepo.findById(segmentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Segment getSegment(long segmentId) {
        return segmentRepo.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("航段不存在: " + segmentId));
    }

    @Transactional(readOnly = true)
    public List<PilotTimelineItem> pilotTimeline(long pilotId) {
        pilotRepo.findById(pilotId).orElseThrow(() -> ApiException.notFound("引航员不存在: " + pilotId));
        List<Assignment> assignments = assignmentRepo.findByPilotId(pilotId);
        Map<Long, String> vesselBySegment = new HashMap<>();
        List<PilotTimelineItem> items = new ArrayList<>();
        for (Assignment assignment : assignments) {
            String vesselCode = vesselBySegment.computeIfAbsent(assignment.segmentId(),
                    id -> segmentRepo.findById(id).map(Segment::vesselCode).orElse(""));
            items.add(new PilotTimelineItem(assignment, vesselCode));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public List<VesselTimelineItem> vesselTimeline(String vesselCode) {
        List<VesselTimelineItem> items = new ArrayList<>();
        for (Segment segment : segmentRepo.findByVesselCode(vesselCode)) {
            List<Assignment> assignments = assignmentRepo.findBySegmentId(segment.id());
            Assignment latest = assignments.isEmpty() ? null : assignments.get(assignments.size() - 1);
            items.add(new VesselTimelineItem(segment, latest));
        }
        return items;
    }
}