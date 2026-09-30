package com.example.starter.service.impl;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.AllocationStatus;
import com.example.starter.domain.Berth;
import com.example.starter.domain.DepthCheck;
import com.example.starter.domain.RejectReason;
import com.example.starter.domain.TideVersion;
import com.example.starter.domain.TideWindow;
import com.example.starter.repository.AllocationRepository;
import com.example.starter.repository.BerthRepository;
import com.example.starter.repository.LockRepository;
import com.example.starter.repository.TideRepository;
import com.example.starter.service.BerthService;
import com.example.starter.service.ConflictStateException;
import com.example.starter.service.IdempotencyConflictException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.UnprocessableRequestException;
import com.example.starter.support.BusinessTime;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.BerthTimelineResponse;
import com.example.starter.web.dto.PublishTideRequest;
import com.example.starter.web.dto.ReviewRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 泊位业务主服务：船舶申请、潮高版本发布、泊位分配与人工复核。
 *
 * <p>所有写操作在全局行锁上串行执行，保证潮高修订与靠泊确认并发时，
 * 后到的事务只能看到已提交的最新潮高版本并据此校核。</p>
 */
@Service
public class BerthServiceImpl implements BerthService {

    private final AllocationRepository allocationRepository;

    private final BerthRepository berthRepository;

    private final TideRepository tideRepository;

    private final LockRepository lockRepository;

    /**
     * @param allocationRepository 申请仓储
     * @param berthRepository      泊位仓储
     * @param tideRepository       潮高仓储
     * @param lockRepository       全局锁仓储
     */
    public BerthServiceImpl(AllocationRepository allocationRepository,
                            BerthRepository berthRepository,
                            TideRepository tideRepository,
                            LockRepository lockRepository) {
        this.allocationRepository = allocationRepository;
        this.berthRepository = berthRepository;
        this.tideRepository = tideRepository;
        this.lockRepository = lockRepository;
    }

    @Override
    @Transactional
    public Allocation apply(ApplyRequest request) {
        validateApplyRequest(request);
        lockRepository.lockGlobal();

        Allocation existing = allocationRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (sameParameters(existing, request)) {
                // 同参重放：直接返回首次分配结果，不重复校核、不重复占用。
                return existing;
            }
            throw new IdempotencyConflictException(
                    "requestId " + request.requestId() + " 已存在但申请参数不一致");
        }

        Berth berth = berthRepository.findByCode(request.berthCode())
                .orElseThrow(() -> new NotFoundException("泊位不存在: " + request.berthCode()));

        TideVersion latest = tideRepository.findLatestVersion()
                .orElseThrow(() -> new UnprocessableRequestException(RejectReason.TIDE_GAP,
                        "尚无任何潮高版本，无法校核申请区间"));

        List<TideWindow> windows = tideRepository.findIntersectingWindows(
                latest.id(), request.startTime(), request.endTime());
        if (!DepthCheck.coversWholeRange(windows, request.startTime(), request.endTime())) {
            // 参数性失败：不落库任何申请或幂等记录。
            throw new UnprocessableRequestException(RejectReason.TIDE_GAP,
                    "潮高区间未完整覆盖申请时段，存在缺口");
        }

        BigDecimal minTide = DepthCheck.minTideHeight(windows);
        BigDecimal availableDepth = berth.staticDepthM().add(minTide);
        BigDecimal requiredDepth = request.draftM().add(request.safetyMarginM());
        BigDecimal clearance = availableDepth.subtract(requiredDepth);

        Allocation allocation = newAllocation(request, berth, latest.id(), minTide,
                availableDepth, requiredDepth, clearance);

        if (clearance.signum() < 0) {
            // 深度不足：返回可区分原因，状态 REJECTED，不占用泊位。
            allocation.setStatus(AllocationStatus.REJECTED);
            allocation.setRejectReason(RejectReason.DEPTH_INSUFFICIENT);
            allocation.setDecidedAt(BusinessTime.now());
            allocationRepository.insert(allocation);
            return allocation;
        }

        if (allocationRepository.existsConfirmedOverlap(
                berth.id(), request.startTime(), request.endTime(), null)) {
            allocation.setStatus(AllocationStatus.REJECTED);
            allocation.setRejectReason(RejectReason.BERTH_CONFLICT);
            allocation.setDecidedAt(BusinessTime.now());
            allocationRepository.insert(allocation);
            return allocation;
        }

        allocation.setStatus(AllocationStatus.CONFIRMED);
        allocation.setRejectReason(null);
        allocation.setDecidedAt(BusinessTime.now());
        allocationRepository.insert(allocation);
        return allocation;
    }

    @Override
    @Transactional
    public int publishTide(PublishTideRequest request) {
        List<PublishTideRequest.WindowDto> sorted = request.windows().stream()
                .sorted(Comparator.comparing(PublishTideRequest.WindowDto::startTime))
                .toList();
        validateWindows(sorted);

        lockRepository.lockGlobal();

        TideVersion latestBefore = tideRepository.findLatestVersion().orElse(null);
        if (latestBefore != null && latestBefore.versionNo() >= request.versionNo()) {
            throw new ConflictStateException(
                    "潮高版本号必须大于当前最新版本 " + latestBefore.versionNo());
        }

        LocalDateTime publishedAt = request.publishedAt() != null
                ? request.publishedAt() : BusinessTime.now();
        TideVersion version = tideRepository.insertVersion(request.versionNo(), publishedAt, request.remark());
        tideRepository.insertWindows(version.id(), sorted);

        // 仅影响已确认但尚未开始的靠泊；已开始的保留原决策快照，不自动改写。
        LocalDateTime now = BusinessTime.now();
        List<Allocation> unstarted = allocationRepository.findConfirmedUnstarted(null, now);
        for (Allocation allocation : unstarted) {
            Berth berth = berthRepository.findById(allocation.getBerthId()).orElseThrow();
            List<TideWindow> windows = tideRepository.findIntersectingWindows(
                    version.id(), allocation.getStartTime(), allocation.getEndTime());
            if (!DepthCheck.coversWholeRange(windows, allocation.getStartTime(), allocation.getEndTime())) {
                markPendingReview(allocation, version.id(), null, null, null, RejectReason.TIDE_GAP);
                allocationRepository.updateRevision(allocation);
                continue;
            }
            BigDecimal minTide = DepthCheck.minTideHeight(windows);
            BigDecimal availableDepth = berth.staticDepthM().add(minTide);
            BigDecimal clearance = availableDepth.subtract(allocation.getRequiredDepthM());
            if (clearance.signum() < 0) {
                // 释放占用：状态置 PENDING_REVIEW，区间不再参与冲突检测。
                markPendingReview(allocation, version.id(), minTide, availableDepth, clearance,
                        RejectReason.DEPTH_INSUFFICIENT);
                allocationRepository.updateRevision(allocation);
            }
        }
        return version.versionNo();
    }

    @Override
    @Transactional
    public Allocation review(ReviewRequest request) {
        lockRepository.lockGlobal();

        Allocation allocation = allocationRepository.findByRequestId(request.requestId())
                .orElseThrow(() -> new NotFoundException(
                        "申请不存在: " + request.requestId()));

        if (allocation.getStatus() == AllocationStatus.CONFIRMED
                || allocation.getStatus() == AllocationStatus.REJECTED) {
            // 同参重放首次复核结果；动作不一致视为冲突。
            if (matchesCompletedAction(allocation, request.action())) {
                return allocation;
            }
            throw new ConflictStateException(
                    "申请当前状态 " + allocation.getStatus() + " 不允许复核动作 " + request.action());
        }

        if (allocation.getStatus() != AllocationStatus.PENDING_REVIEW) {
            throw new ConflictStateException("申请当前状态不允许复核: " + allocation.getStatus());
        }

        if ("REJECT".equals(request.action())) {
            allocation.setStatus(AllocationStatus.REJECTED);
            allocation.setRejectReason(RejectReason.REVIEW_REJECTED);
            allocation.setReviewCount(allocation.getReviewCount() + 1);
            allocation.setDecidedAt(BusinessTime.now());
            allocationRepository.updateReview(allocation);
            return allocation;
        }

        // APPROVE：必须按当前已提交的最新潮高版本重新校核，不能沿用挂起时的旧结论。
        TideVersion latest = tideRepository.findLatestVersion()
                .orElseThrow(() -> new UnprocessableRequestException(RejectReason.TIDE_GAP,
                        "尚无任何潮高版本，无法完成复核校核"));
        Berth berth = berthRepository.findById(allocation.getBerthId()).orElseThrow();
        List<TideWindow> windows = tideRepository.findIntersectingWindows(
                latest.id(), allocation.getStartTime(), allocation.getEndTime());
        if (!DepthCheck.coversWholeRange(windows, allocation.getStartTime(), allocation.getEndTime())) {
            throw new UnprocessableRequestException(RejectReason.TIDE_GAP,
                    "最新潮高版本未完整覆盖申请时段，无法复核通过");
        }
        BigDecimal minTide = DepthCheck.minTideHeight(windows);
        BigDecimal availableDepth = berth.staticDepthM().add(minTide);
        BigDecimal clearance = availableDepth.subtract(allocation.getRequiredDepthM());
        if (clearance.signum() < 0) {
            throw new UnprocessableRequestException(RejectReason.DEPTH_INSUFFICIENT,
                    "最新潮高版本下可用深度仍不足，复核不能通过");
        }
        if (allocationRepository.existsConfirmedOverlap(
                berth.id(), allocation.getStartTime(), allocation.getEndTime(),
                allocation.getRequestId())) {
            throw new ConflictStateException("原窗口已被其他已确认靠泊占用，复核不能通过");
        }

        allocation.setStatus(AllocationStatus.CONFIRMED);
        allocation.setRejectReason(null);
        allocation.setTideVersionId(latest.id());
        allocation.setMinTideHeightM(minTide);
        allocation.setAvailableDepthM(availableDepth);
        allocation.setClearanceM(clearance);
        allocation.setReviewTideVersionId(latest.id());
        allocation.setReviewMinTideHeightM(minTide);
        allocation.setReviewAvailableDepthM(availableDepth);
        allocation.setReviewClearanceM(clearance);
        allocation.setReviewCount(allocation.getReviewCount() + 1);
        allocation.setDecidedAt(BusinessTime.now());
        allocationRepository.updateReview(allocation);
        return allocation;
    }

    @Override
    public Allocation getByRequestId(String requestId) {
        return allocationRepository.findByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("申请不存在: " + requestId));
    }

    @Override
    public BerthTimelineResponse getTimeline(String berthCode) {
        Berth berth = berthRepository.findByCode(berthCode)
                .orElseThrow(() -> new NotFoundException("泊位不存在: " + berthCode));
        Integer latestVersionNo = tideRepository.findLatestVersion().map(TideVersion::versionNo).orElse(null);
        List<BerthTimelineResponse.TimelineItem> items = allocationRepository
                .findByBerthOrderByStart(berth.id()).stream()
                .map(this::toTimelineItem)
                .toList();
        return new BerthTimelineResponse(berth.code(), berth.name(), berth.staticDepthM(),
                latestVersionNo, items);
    }

    private void validateApplyRequest(ApplyRequest request) {
        if (!request.startTime().isBefore(request.endTime())) {
            throw new UnprocessableRequestException("NON_POSITIVE_INTERVAL",
                    "靠泊结束时间必须晚于起始时间");
        }
    }

    private void validateWindows(List<PublishTideRequest.WindowDto> windows) {
        LocalDateTime previousEnd = null;
        for (PublishTideRequest.WindowDto window : windows) {
            if (!window.startTime().isBefore(window.endTime())) {
                throw new UnprocessableRequestException("NON_POSITIVE_INTERVAL",
                        "潮高区间结束时间必须晚于起始时间");
            }
            if (previousEnd != null && !window.startTime().equals(previousEnd)) {
                throw new UnprocessableRequestException(RejectReason.TIDE_GAP,
                        "潮高区间集合存在缺口或重叠，区间必须首尾相接");
            }
            previousEnd = window.endTime();
        }
    }

    private boolean sameParameters(Allocation existing, ApplyRequest request) {
        Berth berth = berthRepository.findById(existing.getBerthId()).orElse(null);
        return Objects.equals(existing.getVesselName(), request.vesselName())
                && existing.getDraftM().compareTo(request.draftM()) == 0
                && existing.getSafetyMarginM().compareTo(request.safetyMarginM()) == 0
                && berth != null && Objects.equals(berth.code(), request.berthCode())
                && Objects.equals(existing.getStartTime(), request.startTime())
                && Objects.equals(existing.getEndTime(), request.endTime());
    }

    private Allocation newAllocation(ApplyRequest request, Berth berth, Long tideVersionId,
                                     BigDecimal minTide, BigDecimal availableDepth,
                                     BigDecimal requiredDepth, BigDecimal clearance) {
        Allocation allocation = new Allocation();
        allocation.setRequestId(request.requestId());
        allocation.setVesselName(request.vesselName());
        allocation.setDraftM(request.draftM());
        allocation.setSafetyMarginM(request.safetyMarginM());
        allocation.setBerthId(berth.id());
        allocation.setStartTime(request.startTime());
        allocation.setEndTime(request.endTime());
        allocation.setTideVersionId(tideVersionId);
        allocation.setMinTideHeightM(minTide);
        allocation.setStaticDepthM(berth.staticDepthM());
        allocation.setAvailableDepthM(availableDepth);
        allocation.setRequiredDepthM(requiredDepth);
        allocation.setClearanceM(clearance);
        allocation.setReviewCount(0);
        return allocation;
    }

    private void markPendingReview(Allocation allocation, Long reviewVersionId, BigDecimal minTide,
                                   BigDecimal availableDepth, BigDecimal clearance, String reason) {
        allocation.setStatus(AllocationStatus.PENDING_REVIEW);
        allocation.setRejectReason(reason);
        allocation.setReviewTideVersionId(reviewVersionId);
        allocation.setReviewMinTideHeightM(minTide);
        allocation.setReviewAvailableDepthM(availableDepth);
        allocation.setReviewClearanceM(clearance);
    }

    private boolean matchesCompletedAction(Allocation allocation, String action) {
        if ("REJECT".equals(action)) {
            return allocation.getStatus() == AllocationStatus.REJECTED
                    && RejectReason.REVIEW_REJECTED.equals(allocation.getRejectReason());
        }
        return allocation.getStatus() == AllocationStatus.CONFIRMED
                && allocation.getReviewCount() > 0;
    }

    private BerthTimelineResponse.TimelineItem toTimelineItem(Allocation a) {
        return new BerthTimelineResponse.TimelineItem(
                a.getRequestId(),
                a.getVesselName(),
                a.getStartTime(),
                a.getEndTime(),
                a.getStatus().name(),
                a.getTideVersionId(),
                a.getMinTideHeightM(),
                a.getAvailableDepthM(),
                a.getClearanceM(),
                a.getRejectReason(),
                a.getReviewCount(),
                a.getReviewTideVersionId(),
                a.getReviewMinTideHeightM(),
                a.getReviewClearanceM());
    }
}
