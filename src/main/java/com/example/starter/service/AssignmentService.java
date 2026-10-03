package com.example.starter.service;

import com.example.starter.error.ApiException;
import com.example.starter.model.AssignmentRow;
import com.example.starter.model.PilotRow;
import com.example.starter.model.SegmentRow;
import com.example.starter.model.SegmentStatus;
import com.example.starter.repo.AssignmentRepository;
import com.example.starter.repo.PilotRepository;
import com.example.starter.repo.SegmentRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 指派、登轮、离轮与撤销的业务规则。
 *
 * <p>幂等：指派与登轮携带 requestId，同参重放返回首次结果，异参返回 409；
 * 业务失败时事务回滚，请求键不落库，因此失败不占用请求键。</p>
 *
 * <p>并发：同一引航员的指派通过行锁串行化；登轮与撤销通过守卫更新保证只有一方生效。</p>
 */
@Service
public class AssignmentService {

    private final SegmentRepository segmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final PilotRepository pilotRepository;
    private final Clock clock;

    public AssignmentService(SegmentRepository segmentRepository,
                             AssignmentRepository assignmentRepository,
                             PilotRepository pilotRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.pilotRepository = pilotRepository;
        this.clock = clock;
    }

    /**
     * 为航段指派引航员。资格不足 422，区间重叠 409，requestId 异参 409。
     */
    @Transactional
    public AssignmentRow assign(long segmentId, String pilotCode, String requestId) {
        String fingerprint = segmentId + "|" + pilotCode;
        var existing = assignmentRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            AssignmentRow row = existing.get();
            if (row.requestFingerprint().equals(fingerprint)) {
                return row;
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已用于其他指派参数");
        }
        SegmentRow segment = requireSegment(segmentId);
        if (segment.status() != SegmentStatus.REQUESTED) {
            throw ApiException.conflict("INVALID_STATE",
                    "航段当前状态不允许指派: " + segment.status());
        }
        PilotRow pilot = pilotRepository.findByCode(pilotCode)
                .orElseThrow(() -> ApiException.notFound("PILOT_NOT_FOUND", "引航员不存在: " + pilotCode));
        if (segment.requiredLevel() > pilot.qualificationLevel()) {
            throw ApiException.unprocessable("QUALIFICATION_INSUFFICIENT",
                    "船舶要求等级 " + segment.requiredLevel() + " 高于引航员资格等级 "
                            + pilot.qualificationLevel());
        }
        pilotRepository.lockById(pilot.id());
        if (assignmentRepository.countOverlapping(pilot.id(), segment.estStartUtc(),
                segment.estEndUtc()) > 0) {
            throw ApiException.conflict("PILOT_OCCUPIED",
                    "引航员在重叠的预计或实际区间内已有占用");
        }
        try {
            long id = assignmentRepository.insert(segmentId, pilot.id(), requestId, fingerprint,
                    Instant.now(clock));
            segmentRepository.updateStatus(segmentId, SegmentStatus.ASSIGNED);
            return assignmentRepository.findLatestBySegmentId(segmentId)
                    .filter(a -> a.id() == id).orElseThrow();
        } catch (DuplicateKeyException e) {
            AssignmentRow row = assignmentRepository.findByRequestId(requestId).orElseThrow();
            if (row.requestFingerprint().equals(fingerprint)) {
                return row;
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 已用于其他指派参数");
        }
    }

    /**
     * 确认登轮。与撤销竞争时只有一方生效；同 requestId 重放返回首次结果。
     */
    @Transactional
    public AssignmentRow board(long segmentId, String requestId) {
        String fingerprint = "board|" + segmentId;
        AssignmentRow assignment = assignmentRepository.findLatestBySegmentId(segmentId)
                .orElseThrow(() -> ApiException.conflict("INVALID_STATE", "航段尚未指派"));
        if (assignment.boardedAtUtc() != null) {
            if (requestId.equals(assignment.boardRequestId())
                    && fingerprint.equals(assignment.boardRequestFingerprint())) {
                return assignment;
            }
            throw ApiException.conflict("ALREADY_BOARDED", "航段已确认登轮");
        }
        if (assignment.status() != com.example.starter.model.AssignmentStatus.ACTIVE) {
            throw ApiException.conflict("INVALID_STATE", "指派已释放，无法登轮");
        }
        int updated = assignmentRepository.markBoarded(assignment.id(), Instant.now(clock),
                requestId, fingerprint);
        if (updated == 0) {
            throw ApiException.conflict("STATE_RACE_LOST", "登轮与撤销竞争，指派已被释放");
        }
        segmentRepository.updateStatus(segmentId, SegmentStatus.BOARDED);
        return assignmentRepository.findLatestBySegmentId(segmentId).orElseThrow();
    }

    /**
     * 确认离轮（交接完成）。离轮时间不得早于登轮时间，否则 422。
     */
    @Transactional
    public AssignmentRow disembark(long segmentId, Instant disembarkedAtUtc) {
        AssignmentRow assignment = assignmentRepository.findActiveBySegmentId(segmentId)
                .orElseThrow(() -> ApiException.conflict("INVALID_STATE", "没有生效中的指派"));
        if (assignment.boardedAtUtc() == null) {
            throw ApiException.conflict("INVALID_STATE", "尚未登轮，不能确认离轮");
        }
        if (disembarkedAtUtc.isBefore(assignment.boardedAtUtc())) {
            throw ApiException.unprocessable("INVALID_TIME_RANGE", "离轮时间早于登轮时间");
        }
        assignmentRepository.complete(assignment.id(), disembarkedAtUtc);
        segmentRepository.updateStatus(segmentId, SegmentStatus.COMPLETED);
        return assignmentRepository.findLatestBySegmentId(segmentId).orElseThrow();
    }

    /**
     * 撤销未登轮的指派并释放占用；已登轮只能确认离轮，撤销返回 409。
     */
    @Transactional
    public AssignmentRow cancelAssignment(long segmentId) {
        AssignmentRow assignment = assignmentRepository.findActiveBySegmentId(segmentId)
                .orElseThrow(() -> ApiException.conflict("INVALID_STATE", "没有生效中的指派"));
        int updated = assignmentRepository.releaseIfNotBoarded(assignment.id(),
                Instant.now(clock), "CANCELLED");
        if (updated == 0) {
            throw ApiException.conflict("ALREADY_BOARDED", "已登轮，只能确认离轮，不能撤销");
        }
        segmentRepository.updateStatus(segmentId, SegmentStatus.REQUESTED);
        return assignmentRepository.findLatestBySegmentId(segmentId).orElseThrow();
    }

    private SegmentRow requireSegment(long segmentId) {
        return segmentRepository.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("SEGMENT_NOT_FOUND", "航段不存在: " + segmentId));
    }
}