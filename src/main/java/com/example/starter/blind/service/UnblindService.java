package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
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
 * 实验关闭、退组不撤销已批准的揭盲；揭盲处理代码按受试者登记时的协议版本解析。
 */
@Service
public class UnblindService {

    private final UnblindRequestRepository unblindRequestRepository;
    private final ExperimentService experimentService;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          ExperimentService experimentService,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.experimentService = experimentService;
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
        // 提交顺序裁决：揭盲申请与协议修订生效互斥地按实验行锁排序，
        // 保证“生效时存在待处理揭盲申请”的判定不漏判并发申请。
        experimentService.lockExperimentForOrdering(experimentId);
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
        // 从数据库读取处理映射（盲底）：按分配登记时的协议版本解析，修订不改变既有揭盲归属。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        String treatment = experimentService.resolveTreatment(allocation);
        long now = clock.nowMillis();
        unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                treatment, now);
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
