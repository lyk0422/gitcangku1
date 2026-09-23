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
 * 揭盲申请/批准/拒绝/撤销/到期业务：
 * 协调员为已分配参与者提出带原因、带有效期（可选 validMinutes，默认 30，范围 1~60）的申请，
 * 同一参与者始终至多一份有效待审；新申请在同一事务内归档旧过期占位，重新申请生成新编号；
 * 须由另一名 REVIEWER 批准或拒绝，申请人不能自批/自拒；申请人本人可撤销自己的待审申请。
 * 批准/拒绝/撤销只能在未过期时提交，到期一律 409，不得变成批准；终态不可互转。
 * 到期按查询时钟展示 EXPIRED，普通查询不写库；终态时间固定为 expiresAt，不伪造人工处理人。
 * 实验关闭、参与者退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    static final int MIN_VALID_MINUTES = 1;
    static final int MAX_VALID_MINUTES = 60;
    static final int MAX_REASON_LENGTH = 500;

    private final UnblindRequestRepository unblindRequestRepository;
    private final ExperimentService experimentService;
    private final ExperimentRepository experimentRepository;
    private final Clock clock;

    public UnblindService(UnblindRequestRepository unblindRequestRepository,
                          ExperimentService experimentService,
                          ExperimentRepository experimentRepository,
                          Clock clock) {
        this.unblindRequestRepository = unblindRequestRepository;
        this.experimentService = experimentService;
        this.experimentRepository = experimentRepository;
        this.clock = clock;
    }

    /**
     * 协调员提出揭盲申请；同一事务内先归档已到期占位，再创建新的 PENDING。
     */
    @Transactional
    public UnblindRequestView apply(String experimentId, String participantId,
                                    String reason, Integer requestedValidMinutes,
                                    String applicantActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("reason 最长 " + MAX_REASON_LENGTH + " 字符");
        }
        int validMinutes = requestedValidMinutes == null
                ? UnblindRequestRepository.DEFAULT_VALID_MINUTES : requestedValidMinutes;
        if (validMinutes < MIN_VALID_MINUTES || validMinutes > MAX_VALID_MINUTES) {
            throw ApiException.badRequest(
                    "validMinutes 必须在 " + MIN_VALID_MINUTES + "~" + MAX_VALID_MINUTES + " 之间");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        long now = clock.nowMillis();
        // 同一事务归档旧过期占位：失败整体回滚，不会只留终态不建申请。
        unblindRequestRepository.archiveExpiredPending(allocation.id(), now);
        UnblindRequestRow pending =
                unblindRequestRepository.findPendingByAllocation(allocation.id());
        if (pending != null) {
            throw ApiException.conflict("该参与者已存在有效待审揭盲申请");
        }
        long expiresAt = now + validMinutes * 60_000L;
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, "PENDING", null, now, null,
                validMinutes, expiresAt, null, null, null);
        try {
            // 并发新申请由唯一待审占位兜底：最多一个成功，旧申请历史不被覆盖。
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该参与者已存在有效待审揭盲申请");
        }
        return toView(row, now);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人，且只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView approve(String unblindRequestId, String reviewerActor) {
        UnblindRequestRow row = lockPending(unblindRequestId, reviewerActor, "批准");
        long now = clock.nowMillis();
        requireNotExpired(row, now);
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        int updated = unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now, now);
        if (updated == 0) {
            // 并发到期竞争：事务内时钟已裁决为过期，不得变成批准。
            throw ApiException.conflict("揭盲申请已过期，不能批准");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now, row.validMinutes(), effectiveExpiresAt(row),
                null, null, null);
    }

    /**
     * 另一名 REVIEWER 填写非空原因拒绝；申请人不能自拒，终态不可互转。
     */
    @Transactional
    public UnblindRequestView reject(String unblindRequestId, String reviewerActor,
                                    String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw ApiException.badRequest("rejectReason 不能为空");
        }
        if (rejectReason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("rejectReason 最长 " + MAX_REASON_LENGTH + " 字符");
        }
        UnblindRequestRow row = lockPending(unblindRequestId, reviewerActor, "拒绝");
        long now = clock.nowMillis();
        requireNotExpired(row, now);
        int updated = unblindRequestRepository.reject(unblindRequestId, reviewerActor,
                rejectReason.trim(), now, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已过期，不能拒绝");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), null, "REJECTED",
                row.createdAt(), null, row.validMinutes(), effectiveExpiresAt(row),
                rejectReason.trim(), reviewerActor, now);
    }

    /**
     * 申请人（COORDINATOR）撤销自己的待审申请；只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView cancel(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            throw ApiException.forbidden("仅揭盲申请人本人可撤销自己的申请");
        }
        requirePendingInDb(row, "撤销");
        long now = clock.nowMillis();
        requireNotExpired(row, now);
        int updated = unblindRequestRepository.cancel(unblindRequestId, actorId, now, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已过期，不能撤销");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), null, "CANCELLED",
                row.createdAt(), null, row.validMinutes(), effectiveExpiresAt(row),
                null, actorId, now);
    }

    /**
     * 查询揭盲结果：仅申请人本人、且申请已批准时可获得处理代码。
     * 到期或拒绝后仍不得泄露处理代码：非申请人 403，申请人 409。
     */
    public UnblindResultView getResult(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            // 其他人（含批准人/拒绝人）一律 403。
            throw ApiException.forbidden("仅揭盲申请人本人可查询揭盲结果");
        }
        if (!"APPROVED".equals(row.status())) {
            // 申请人本人查询但未批准（含 PENDING/EXPIRED/CANCELLED/REJECTED）：409。
            throw ApiException.conflict("揭盲申请未处于已批准状态");
        }
        return new UnblindResultView(row.id(), row.experimentId(), row.participantId(),
                row.treatment(), row.status(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码）；申请人、批准人或拒绝人可查看。
     * PENDING 到达 expiresAt 时按查询时钟展示 EXPIRED，不写库。
     */
    public UnblindRequestView getRequest(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        String handler = row.reviewerActor() != null
                ? row.reviewerActor() : row.terminatedActor();
        if (!row.applicantActor().equals(actorId)
                && (handler == null || !handler.equals(actorId))) {
            throw ApiException.forbidden("无权查看该揭盲申请");
        }
        return toView(row, clock.nowMillis());
    }

    /** 行级锁定待审申请并完成存在性、自处理、终态与权限校验。 */
    private UnblindRequestRow lockPending(String unblindRequestId, String reviewerActor,
                                          String action) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (row.applicantActor().equals(reviewerActor)) {
            throw ApiException.forbidden("申请人不能自" + action + "，必须由另一名 REVIEWER 处理");
        }
        requirePendingInDb(row, action);
        return row;
    }

    /** 数据库终态不可互转；PENDING 若已按查询时钟到期则由 {@link #requireNotExpired} 裁决。 */
    private void requirePendingInDb(UnblindRequestRow row, String action) {
        if (!"PENDING".equals(row.status())) {
            throw ApiException.conflict("揭盲申请已处于终态 " + row.status() + "，不能" + action);
        }
    }

    /** 当前时刻达到 expiresAt 即过期；到期提交一律 409。 */
    private void requireNotExpired(UnblindRequestRow row, long now) {
        if (effectiveExpiresAt(row) <= now) {
            throw ApiException.conflict("揭盲申请已过期，终止操作不再受理");
        }
    }

    /** 有效期：显式 expires_at 优先；历史申请（NULL）按创建时间加 30 分钟计算。 */
    private long effectiveExpiresAt(UnblindRequestRow row) {
        if (row.expiresAt() != null) {
            return row.expiresAt();
        }
        return row.createdAt() + (long) UnblindRequestRepository.DEFAULT_VALID_MINUTES * 60_000L;
    }

    /**
     * 组装视图：PENDING 到期按时钟展示 EXPIRED，终态时间固定为 expiresAt、处理人为 null；
     * 任何状态均不输出 treatment。
     */
    private UnblindRequestView toView(UnblindRequestRow row, long now) {
        long expiresAt = effectiveExpiresAt(row);
        if ("PENDING".equals(row.status()) && expiresAt <= now) {
            return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                    row.reason(), row.applicantActor(), null, "EXPIRED",
                    row.createdAt(), null, row.validMinutes(), expiresAt,
                    null, null, expiresAt);
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), row.status(),
                row.createdAt(), row.reviewedAt(), row.validMinutes(), expiresAt,
                row.rejectReason(), row.terminatedActor(), row.terminatedAt());
    }
}
