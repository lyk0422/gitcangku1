package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.Severity;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.repo.AdverseEventRepository;
import com.example.starter.blind.repo.AdverseEventRepository.AdverseEventRow;
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
 * 紧急通道：分配处于 URGENT_REVIEW 时 REVIEWER 凭 SEVERE 不良事件直接揭盲，
 * 与常规通道共用存储与查询权限；同一分配两条通道合计只成功揭盲一次。
 */
@Service
public class UnblindService {

    private final UnblindRequestRepository unblindRequestRepository;
    private final AdverseEventRepository adverseEventRepository;
    private final ExperimentService experimentService;
    private final ExperimentRepository experimentRepository;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          AdverseEventRepository adverseEventRepository,
                          ExperimentService experimentService,
                          ExperimentRepository experimentRepository,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.adverseEventRepository = adverseEventRepository;
        this.experimentService = experimentService;
        this.experimentRepository = experimentRepository;
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
        // 行锁分配：与紧急揭盲、常规批准按事务提交顺序裁决终局。
        AllocationRow locked = experimentService.lockAllocationRow(allocation.id());
        if (locked == null) {
            throw new IllegalStateException("分配缺失，数据不一致");
        }
        if (locked.unblinded()) {
            // 另一通道已揭盲：同一分配只成功揭盲一次。
            throw ApiException.conflict("该分配已揭盲，不可再发起常规申请");
        }
        UnblindRequestRow pending =
                unblindRequestRepository.findPendingByAllocation(locked.id());
        if (pending != null) {
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                locked.id(), reason, applicantActor, null, "PENDING", null, now, null,
                "REGULAR");
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
     * 批准与终局揭盲置位原子完成：若紧急通道已先行揭盲，本路径 409。
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
        // 行锁分配，与紧急通道按事务提交顺序互斥裁决。
        AllocationRow allocation = experimentService.lockAllocationRow(row.allocationId());
        if (allocation == null) {
            throw new IllegalStateException("分配缺失，数据不一致");
        }
        if (allocation.unblinded()) {
            throw ApiException.conflict("该分配已通过紧急通道揭盲");
        }
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now);
        experimentService.markAllocationUnblindedOnce(allocation.id());
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now, "REGULAR");
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
                row.treatment(), row.status(), row.reviewedAt(), row.unblindType());
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
                row.createdAt(), row.reviewedAt(), row.unblindType());
    }

    /**
     * 紧急揭盲：仅 REVIEWER、且分配处于 URGENT_REVIEW 时可直接提交，无需协调员常规申请。
     * 校验 eventKey 对应报告确为 SEVERE 且尚未用于揭盲，否则 422；
     * 退组分配 409；已揭盲分配（任一通道先行成功）409；不受实验 CLOSED 限制。
     * 成功后同事务原子完成揭盲并清除 URGENT_REVIEW，写入 EMERGENCY 不可变记录。
     */
    @Transactional
    public UnblindResultView emergencyUnblind(String experimentId, String participantId,
                                              String eventKey, String reason,
                                              String reviewerActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        // 行锁分配：与退组、常规批准按提交顺序裁决。
        AllocationRow locked = experimentService.lockAllocationRow(allocation.id());
        if (locked == null) {
            throw new IllegalStateException("分配缺失，数据不一致");
        }
        if (locked.unblinded()) {
            throw ApiException.conflict("该分配已揭盲");
        }
        if ("WITHDRAWN".equals(locked.status())) {
            throw ApiException.conflict("退组分配不可发起紧急揭盲");
        }
        if (!locked.urgentReview()) {
            throw ApiException.unprocessable("该分配不处于 URGENT_REVIEW，不能紧急揭盲");
        }
        // 行锁报告：校验严重度与是否已揭盲，与并发紧急揭盲互斥。
        AdverseEventRow report =
                adverseEventRepository.lockByAllocationAndEventKey(locked.id(), eventKey);
        if (report == null) {
            throw ApiException.unprocessable("eventKey 对应的不良事件报告不存在");
        }
        if (!Severity.SEVERE.name().equals(report.severity())) {
            throw ApiException.unprocessable("该 eventKey 对应报告严重度不是 SEVERE");
        }
        if (report.unblindRequestId() != null) {
            throw ApiException.unprocessable("该 SEVERE 报告已用于紧急揭盲");
        }
        SeatRow seat = experimentRepository.findSeat(experimentId,
                locked.blockNo(), locked.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        String emergencyId = "UE-" + UUID.randomUUID().toString().replace("-", "");
        UnblindRequestRow row = new UnblindRequestRow(emergencyId, experimentId, participantId,
                locked.id(), reason, reviewerActor, reviewerActor, "APPROVED",
                seat.treatment(), now, now, "EMERGENCY");
        try {
            unblindRequestRepository.insertEmergency(row, seat.treatment());
        } catch (DuplicateKeyException e) {
            // 主键极端冲突，按业务冲突处理。
            throw ApiException.conflict("紧急揭盲请求冲突，请重试");
        }
        // 终局置位：仅未揭盲可成功；并发另一通道先行时 0 行 -> 409。
        if (!experimentService.markAllocationUnblindedOnce(locked.id())) {
            throw ApiException.conflict("该分配已揭盲");
        }
        if (adverseEventRepository.markUsedForUnblind(report.id(), emergencyId) != 1) {
            // 报告在锁定后仍被使用（极端并发），整个紧急揭盲回滚。
            throw ApiException.unprocessable("该 SEVERE 报告已用于紧急揭盲");
        }
        return new UnblindResultView(emergencyId, experimentId, participantId,
                seat.treatment(), "APPROVED", now, "EMERGENCY");
    }
}
