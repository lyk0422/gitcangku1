package com.example.starter.service;

import com.example.starter.error.ApiException;
import com.example.starter.model.SegmentRow;
import com.example.starter.model.SegmentStatus;
import com.example.starter.repo.AssignmentRepository;
import com.example.starter.repo.BoardingPointChangeRepository;
import com.example.starter.repo.SegmentRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 航段申请、船舶取消与登轮点变更。
 */
@Service
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final BoardingPointChangeRepository changeRepository;
    private final Clock clock;

    public SegmentService(SegmentRepository segmentRepository,
                          AssignmentRepository assignmentRepository,
                          BoardingPointChangeRepository changeRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.changeRepository = changeRepository;
        this.clock = clock;
    }

    /**
     * 创建航段申请。预计结束必须晚于开始，否则 422。
     */
    @Transactional
    public SegmentRow create(String vesselCode, int requiredLevel, Instant estStartUtc,
                             Instant estEndUtc, String boardingPoint) {
        if (!estEndUtc.isAfter(estStartUtc)) {
            throw ApiException.unprocessable("INVALID_TIME_RANGE",
                    "预计结束时间必须晚于预计开始时间");
        }
        long id = segmentRepository.insert(vesselCode, requiredLevel, estStartUtc, estEndUtc,
                boardingPoint, Instant.now(clock));
        return segmentRepository.findById(id).orElseThrow();
    }

    /**
     * 船舶取消航段：释放生效指派，但已发生的登轮/离轮时间保留不变。
     */
    @Transactional
    public SegmentRow cancelByVessel(long segmentId) {
        SegmentRow segment = requireSegment(segmentId);
        if (segment.status() == SegmentStatus.CANCELLED
                || segment.status() == SegmentStatus.COMPLETED) {
            throw ApiException.conflict("INVALID_STATE",
                    "航段当前状态不允许取消: " + segment.status());
        }
        Instant now = Instant.now(clock);
        assignmentRepository.releaseActiveBySegmentId(segmentId, now, "VESSEL_CANCELLED");
        segmentRepository.markCancelled(segmentId, now);
        return segmentRepository.findById(segmentId).orElseThrow();
    }

    /**
     * 登轮前修改登轮点，原值写入变更历史；已登轮及之后的状态返回 409。
     */
    @Transactional
    public SegmentRow changeBoardingPoint(long segmentId, String newBoardingPoint) {
        SegmentRow segment = requireSegment(segmentId);
        if (segment.status() != SegmentStatus.REQUESTED
                && segment.status() != SegmentStatus.ASSIGNED) {
            throw ApiException.conflict("INVALID_STATE",
                    "已登轮或已结束，不允许修改登轮点: " + segment.status());
        }
        changeRepository.insert(segmentId, segment.boardingPoint(), newBoardingPoint,
                Instant.now(clock));
        segmentRepository.updateBoardingPoint(segmentId, newBoardingPoint);
        return segmentRepository.findById(segmentId).orElseThrow();
    }

    public SegmentRow requireSegment(long segmentId) {
        return segmentRepository.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("SEGMENT_NOT_FOUND", "航段不存在: " + segmentId));
    }
}