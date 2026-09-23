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
 * 须由另一名 REVIEWER 批准，且新审核人不得在目标参与者的污染闭包中；
 * 批准时颁发全局唯一 exposureKey 给申请人本人，并登记持密种子边；
 * 仅申请人本人可查询结果（含处理代码与凭据），其他人 403，未批准 409。
 * 实验关闭、退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    private final UnblindRequestRepository unblindRequestRepository;
    private final ExperimentService experimentService;
    private final ExperimentRepository experimentRepository;
    private final ContaminationService contaminationService;
    private final ExposureKeyGenerator exposureKeyGenerator;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          ExperimentService experimentService,
                          ExperimentRepository experimentRepository,
                          ContaminationService contaminationService,
                          ExposureKeyGenerator exposureKeyGenerator,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.experimentService = experimentService;
        this.experimentRepository = experimentRepository;
        this.contaminationService = contaminationService;
        this.exposureKeyGenerator = exposureKeyGenerator;
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
                allocation.id(), reason, applicantActor, null, "PENDING", null, null, now, null);
        try {
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            // 并发申请同一分配：唯一待审占位兜底。
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        return toView(row);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人，且不得在目标参与者污染闭包中。
     * 批准、颁发凭据与持密种子边在同一事务原子提交，任一失败整体回滚。
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
        // 污染闭包门禁：锁定主体后按最新闭包判定，防止用旧闭包成为新审核人。
        contaminationService.assertReviewerNotInClosure(
                row.experimentId(), row.participantId(), reviewerActor);
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        String exposureKey = newUniqueExposureKey();
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), exposureKey, now);
        if (updated == 0) {
            // 并发下被其他批准抢先：唯一待审状态兜底。
            throw ApiException.conflict("揭盲申请已批准");
        }
        UnblindRequestRow approved = new UnblindRequestRow(row.id(), row.experimentId(),
                row.participantId(), row.allocationId(), row.reason(), row.applicantActor(),
                reviewerActor, "APPROVED", seat.treatment(), exposureKey, row.createdAt(), now);
        // 登记申请人持密种子边；与批准同一事务，失败一并回滚。
        contaminationService.seedOnApproval(approved, reviewerActor);
        return toView(approved);
    }

    /**
     * 查询揭盲结果：仅申请人本人、且申请已批准时可获得处理代码与 exposureKey。
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
                row.treatment(), row.status(), row.exposureKey(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码与凭据）；仅申请人与批准人可查看。
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

    /** 凭据随机冲突概率极低，仍由唯一索引兜底并重试。 */
    private String newUniqueExposureKey() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = exposureKeyGenerator.nextKey();
            if (unblindRequestRepository.findApprovedByExposureKey(candidate) == null) {
                return candidate;
            }
        }
        throw ApiException.conflict("exposureKey 生成冲突，请重试");
    }

    private UnblindRequestView toView(UnblindRequestRow row) {
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), row.status(),
                row.createdAt(), row.reviewedAt());
    }
}
