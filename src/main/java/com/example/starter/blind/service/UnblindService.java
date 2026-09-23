package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.UnblindRequestRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 揭盲申请/批准/结果业务：
 * 协调员为已分配参与者提出带原因申请，同一分配至多一个待审申请；
 * 须由另一名 REVIEWER 批准；仅申请人本人可查询结果，其他人 403，未批准 409。
 * 实验关闭、退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    private final UnblindRequestRepository unblindRequestRepository;
    private final ExperimentService experimentService;
    private final ExperimentRepository experimentRepository;
    private final ContaminationService contaminationService;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          ExperimentService experimentService,
                          ExperimentRepository experimentRepository,
                          ContaminationService contaminationService,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.experimentService = experimentService;
        this.experimentRepository = experimentRepository;
        this.contaminationService = contaminationService;
        this.clock = clock;
    }

    /**
     * 协调员提出揭盲申请。
     */
    @Transactional
    public UnblindRequestView apply(String experimentId, String participantId,
                                    String reason, String applicantActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        UnblindRequestRow pending =
                unblindRequestRepository.findPendingByAllocation(allocation.id());
        if (pending != null) {
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, "PENDING", null, now, null);
        try {
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            // 并发申请同一分配：唯一待审占位兜底。
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        return toView(row);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人。
     */
    @Transactional
    public UnblindRequestView approve(String unblindRequestId, String reviewerActor) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if ("APPROVED".equals(row.status())) {
            throw ApiException.conflict("揭盲申请已批准");
        }
        if (row.applicantActor().equals(reviewerActor)) {
            throw ApiException.forbidden("批准人必须是不同于申请人的另一名 REVIEWER");
        }
        // 审核隔离门禁：申请人或审核人一旦在目标参与者污染闭包内，即不得作为新审核人。
        // 在批准事务内锁定该参与者边集，防止并发新增披露绕过门禁。
        contaminationService.assertReviewerNotContaminated(
                row.experimentId(), row.participantId(), reviewerActor);
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now);
        // 批准即向申请人披露处理代码：写入申请人种子污染边并生成首个 OPEN 闭包版本。
        // 污染记录不扩大查询权限——处理代码仍仅申请人本人可查。
        contaminationService.seedApprovedApplicant(
                row.experimentId(), row.participantId(), row.applicantActor());
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now);
    }

    /**
     * 查询揭盲结果：仅申请人本人、且申请已批准时可获得处理代码。
     */
    public UnblindResultView getResult(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            // 其他人（含批准人）一律 403。
            throw ApiException.forbidden("仅揭盲申请人本人可查询揭盲结果");
        }
        if (!"APPROVED".equals(row.status())) {
            // 申请人本人查询但尚未批准：409。
            throw ApiException.conflict("揭盲申请尚未批准");
        }
        return new UnblindResultView(row.id(), row.experimentId(), row.participantId(),
                row.treatment(), row.status(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码）；仅申请人与批准人可查看。
     */
    public UnblindRequestView getRequest(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)
                && (row.reviewerActor() == null || !row.reviewerActor().equals(actorId))) {
            throw ApiException.forbidden("无权查看该揭盲申请");
        }
        return toView(row);
    }

    private UnblindRequestView toView(UnblindRequestRow row) {
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), row.status(),
                row.createdAt(), row.reviewedAt());
    }
}
