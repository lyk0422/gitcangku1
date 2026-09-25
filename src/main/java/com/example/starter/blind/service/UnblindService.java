package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.repo.AdverseEventRepository;
import com.example.starter.blind.repo.AdverseEventRepository.AdverseEventRow;
import com.example.starter.blind.repo.AllocationRepository;
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
    private final AllocationRepository allocationRepository;
    private final AdverseEventRepository adverseEventRepository;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          ExperimentService experimentService,
                          ExperimentRepository experimentRepository,
                          AllocationRepository allocationRepository,
                          AdverseEventRepository adverseEventRepository,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.experimentService = experimentService;
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.adverseEventRepository = adverseEventRepository;
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
                experimentService.mustLockAllocationRow(experimentId, participantId);
        if (allocation.unblindedAt() != null) {
            // 另一通道（紧急揭盲）已先完成：后续常规操作一律 409。
            throw ApiException.conflict("该分配已揭盲");
        }
        UnblindRequestRow pending =
                unblindRequestRepository.findPendingByAllocation(allocation.id());
        if (pending != null) {
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, "PENDING", null, now, null,
                "REGULAR", null);
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
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustLockAllocationRow(row.experimentId(), row.participantId());
        if (allocation.unblindedAt() != null) {
            // 紧急揭盲已先完成：同一分配只成功揭盲一次。
            throw ApiException.conflict("该分配已揭盲");
        }
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now);
        // 常规批准同样完成揭盲：写入分配揭盲时间，紧急通道后续操作将返回 409。
        allocationRepository.completeUnblind(allocation.id(), now);
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now, "REGULAR", null);
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
                row.createdAt(), row.reviewedAt(), row.requestType(), row.eventKey());
    }

    /**
     * 紧急揭盲：仅 REVIEWER 可调用（控制器校验），分配须处于 URGENT_REVIEW 且未退组、未揭盲；
     * eventKey 须对应该参与者的 SEVERE 报告，否则 422。成功后原子完成揭盲、
     * 复位 URGENT_REVIEW 标记，并写入 EMERGENCY 类型的不可变揭盲记录。
     * 不受实验 CLOSED 限制；与常规批准互斥，同一分配只成功揭盲一次。
     */
    @Transactional
    public UnblindRequestView emergency(String experimentId, String participantId,
                                        String eventKey, String reason, String reviewerActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        if (eventKey == null || eventKey.isBlank()) {
            throw ApiException.badRequest("eventKey 不能为空");
        }
        AllocationRow allocation =
                experimentService.mustLockAllocationRow(experimentId, participantId);
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("退组分配不可发起紧急揭盲");
        }
        if (allocation.unblindedAt() != null) {
            // 常规通道已先完成揭盲：同一分配只成功揭盲一次。
            throw ApiException.conflict("该分配已揭盲");
        }
        if (!"Y".equals(allocation.urgentReview())) {
            throw ApiException.conflict("该分配未处于 URGENT_REVIEW 状态，不可紧急揭盲");
        }
        AdverseEventRow event = adverseEventRepository.findByEventKey(experimentId, eventKey);
        if (event == null || event.allocationId() != allocation.id()
                || !"SEVERE".equals(event.severity())) {
            throw ApiException.unprocessable("eventKey 对应的报告不存在或严重度非 SEVERE");
        }
        SeatRow seat = experimentRepository.findSeat(experimentId,
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        // 原子完成揭盲并复位 URGENT_REVIEW；并发下仅一个事务生效。
        int updated = allocationRepository.completeUnblind(allocation.id(), now);
        if (updated == 0) {
            throw ApiException.conflict("该分配已揭盲");
        }
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, reviewerActor, reviewerActor, "APPROVED",
                seat.treatment(), now, now, "EMERGENCY", eventKey);
        unblindRequestRepository.insertEmergency(row);
        return toView(row);
    }
}
