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
 * 揭盲申请/裁决/结果业务：
 * 协调员为已分配参与者提出带原因、带有效期（默认 30 分钟，1~60）的申请，
 * 同一参与者始终至多一份有效待审；须由另一名 REVIEWER 批准，申请人不能自批/自拒；
 * 申请人可撤销自己的待审申请；达到 expiresAt 即过期，批准/拒绝/撤销过期均返回 409。
 * 到期按查询时钟展示 EXPIRED，普通查询不写库；重新申请时在同一事务归档旧过期占位。
 * CANCELLED/REJECTED/EXPIRED/APPROVED 均为终态，不可互转；终态不写入盲底（除批准外）。
 * 实验关闭、参与者退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    /** 申请默认有效期分钟数；历史 PENDING（expires_at 为空）也按该值计算。 */
    static final int DEFAULT_VALID_MINUTES = 30;
    static final long MILLIS_PER_MINUTE = 60_000L;

    static final String PENDING = "PENDING";
    static final String APPROVED = "APPROVED";
    static final String CANCELLED = "CANCELLED";
    static final String REJECTED = "REJECTED";
    static final String EXPIRED = "EXPIRED";
    static final String EXPIRED_REASON = "申请已过期";

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
     * 协调员提出揭盲申请；若存在已到期的旧 PENDING 占位，在同一事务内归档为 EXPIRED，
     * 再生成新编号创建新 PENDING。重新申请不复用旧 ID，旧历史不可覆盖。
     */
    @Transactional
    public UnblindRequestView apply(String experimentId, String participantId,
                                    String reason, Integer validMinutes, String applicantActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        int valid = validMinutes == null ? DEFAULT_VALID_MINUTES : validMinutes;
        if (valid < 1 || valid > 60) {
            throw ApiException.badRequest("validMinutes 取值范围为 1~60");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        long now = clock.nowMillis();
        // 行锁串行化同一分配上的并发新申请；查不到行时仍由唯一占位列兜底。
        UnblindRequestRow existing =
                unblindRequestRepository.lockPendingByAllocation(allocation.id());
        if (existing != null) {
            long existingExpiresAt = effectiveExpiresAt(existing);
            if (now >= existingExpiresAt) {
                // 旧申请已到期：同事务归档，终止时间固定为 expiresAt，不伪造处理人。
                int archived = unblindRequestRepository.archiveExpired(existing.id(),
                        existingExpiresAt);
                if (archived == 0) {
                    // 并发裁决抢先形成终态：按当前冲突处理，占位由对方事务释放/保留。
                    throw ApiException.conflict("该分配已存在待审揭盲申请");
                }
            } else {
                throw ApiException.conflict("该分配已存在待审揭盲申请");
            }
        }
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        long expiresAt = now + valid * MILLIS_PER_MINUTE;
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, PENDING, null, now, null,
                expiresAt, null, null, null);
        try {
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            // 并发申请同一分配：唯一待审占位兜底，最多一个成功。
            throw ApiException.conflict("该分配已存在待审揭盲申请");
        }
        return toView(row, now);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人；仅未到期的 PENDING 可批准。
     * 事务内时钟裁决，与拒绝/撤销竞争只形成一个终态；失败不写入盲底。
     */
    @Transactional
    public UnblindRequestView approve(String unblindRequestId, String reviewerActor) {
        UnblindRequestRow row = lockPendingOrFail(unblindRequestId, reviewerActor, true);
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        long fallbackExpiresAt = fallbackExpiresAt(row);
        int updated = unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now, fallbackExpiresAt);
        if (updated == 0) {
            // 并发终态竞争落败：条件更新 0 行，盲底从未写入。
            throw ApiException.conflict("揭盲申请已形成终态，不可批准");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, APPROVED,
                row.createdAt(), now, effectiveExpiresAt(row),
                reviewerActor, now, null);
    }

    /**
     * 另一名 REVIEWER 填写非空原因拒绝；申请人不能自拒；仅未到期 PENDING 可拒绝。
     */
    @Transactional
    public UnblindRequestView reject(String unblindRequestId, String rejectReason,
                                     String reviewerActor) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw ApiException.badRequest("拒绝原因不能为空");
        }
        if (rejectReason.length() > 500) {
            throw ApiException.badRequest("拒绝原因最长 500 字符");
        }
        UnblindRequestRow row = lockPendingOrFail(unblindRequestId, reviewerActor, true);
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.reject(unblindRequestId, reviewerActor,
                rejectReason, now, fallbackExpiresAt(row));
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已形成终态，不可拒绝");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), null, REJECTED,
                row.createdAt(), null, effectiveExpiresAt(row),
                reviewerActor, now, rejectReason);
    }

    /**
     * 申请人本人（COORDINATOR）撤销自己的 PENDING 申请；仅未到期可撤销。
     */
    @Transactional
    public UnblindRequestView cancel(String unblindRequestId, String applicantActor) {
        UnblindRequestRow row = lockPendingOrFail(unblindRequestId, applicantActor, false);
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.cancel(unblindRequestId, applicantActor,
                now, fallbackExpiresAt(row));
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已形成终态，不可撤销");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), applicantActor, null, CANCELLED,
                row.createdAt(), null, effectiveExpiresAt(row),
                applicantActor, now, "申请人主动撤销");
    }

    /**
     * 查询揭盲结果：仅申请人本人、且申请已批准时可获得处理代码。
     * 到期/拒绝/撤销后申请人查询返回 409（不泄露处理代码），非申请人一律 403。
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
        if (!APPROVED.equals(row.status())) {
            // 申请人本人查询但未批准（待审/到期/拒绝/撤销）：409，不附带处理代码。
            throw ApiException.conflict("揭盲申请未获批准，无法查看揭盲结果");
        }
        return new UnblindResultView(row.id(), row.experimentId(), row.participantId(),
                row.treatment(), row.status(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码）；申请人与处理人（批准人/拒绝人）可查看。
     * PENDING 到达 expiresAt 时按查询时钟展示 EXPIRED：不写库，
     * 终止时间固定为 expiresAt，处理人为 null，不伪造人工处理。
     */
    public UnblindRequestView getRequest(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        boolean isHandler = row.reviewerActor() != null && row.reviewerActor().equals(actorId)
                || row.handlerActor() != null && row.handlerActor().equals(actorId);
        if (!row.applicantActor().equals(actorId) && !isHandler) {
            throw ApiException.forbidden("无权查看该揭盲申请");
        }
        return toView(row, clock.nowMillis());
    }

    /**
     * 锁定申请并统一裁决前置校验：404 不存在 → 409 终态 → 403 身份 → 409 到期。
     *
     * @param actorId     操作者
     * @param asReviewer  true=裁决人必须是不同于申请人的 REVIEWER；false=必须是申请人本人
     */
    private UnblindRequestRow lockPendingOrFail(String unblindRequestId, String actorId,
                                                boolean asReviewer) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!PENDING.equals(row.status())) {
            // 所有终态不可互转。
            throw ApiException.conflict("揭盲申请已形成终态: " + row.status());
        }
        if (asReviewer) {
            if (row.applicantActor().equals(actorId)) {
                throw ApiException.forbidden("裁决人必须是不同于申请人的另一名 REVIEWER");
            }
        } else if (!row.applicantActor().equals(actorId)) {
            throw ApiException.forbidden("仅揭盲申请人本人可撤销自己的申请");
        }
        if (clock.nowMillis() >= effectiveExpiresAt(row)) {
            // 到期：批准/拒绝/撤销均 409，不得变成批准；不写库。
            throw ApiException.conflict("揭盲申请已过期");
        }
        return row;
    }

    /**
     * 到期时刻：新申请取 expires_at；历史 PENDING（expires_at 为空）按创建时间 + 30 分钟。
     */
    private long effectiveExpiresAt(UnblindRequestRow row) {
        return row.expiresAt() != null ? row.expiresAt()
                : row.createdAt() + (long) DEFAULT_VALID_MINUTES * MILLIS_PER_MINUTE;
    }

    /** 裁决写库时为历史空行回填到期时刻使用的值。 */
    private long fallbackExpiresAt(UnblindRequestRow row) {
        return effectiveExpiresAt(row);
    }

    private UnblindRequestView toView(UnblindRequestRow row, long now) {
        long expiresAt = effectiveExpiresAt(row);
        if (PENDING.equals(row.status()) && now >= expiresAt) {
            // 查询时钟裁决为到期：仅展示，不落库；终止时间固定 expiresAt，无人工处理人。
            return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                    row.reason(), row.applicantActor(), null, EXPIRED,
                    row.createdAt(), null, expiresAt, null, expiresAt, EXPIRED_REASON);
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), row.status(),
                row.createdAt(), row.reviewedAt(), expiresAt,
                row.handlerActor(), row.terminatedAt(), row.terminalReason());
    }
}
