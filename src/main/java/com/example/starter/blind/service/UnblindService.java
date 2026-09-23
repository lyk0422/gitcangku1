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
 * 协调员为已分配参与者提出带原因、带有效期（默认30分钟，1~60）的申请，
 * 同一参与者始终至多一份有效待审；到期只在查询时按时钟只读展示 EXPIRED，
 * 重申时在同一事务归档过期占位并新建 PENDING。
 * 须由另一名 REVIEWER 批准或填写原因拒绝；申请人不能自批/自拒，仅本人可撤销。
 * 批准、拒绝、撤销只能在未过期时提交；所有终态不可互转。
 * 实验关闭、参与者退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    /** 申请有效时长缺省值（分钟）。 */
    public static final int DEFAULT_VALID_MINUTES = 30;
    /** 申请有效时长下限（分钟）。 */
    public static final int MIN_VALID_MINUTES = 1;
    /** 申请有效时长上限（分钟）。 */
    public static final int MAX_VALID_MINUTES = 60;

    private static final long MILLIS_PER_MINUTE = 60_000L;

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
     * 协调员提出揭盲申请；同一事务内归档已过期占位，保证同一参与者至多一份有效待审。
     */
    @Transactional
    public UnblindRequestView apply(String experimentId, String participantId,
                                    String reason, Integer requestedValidMinutes,
                                    String applicantActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        int validMinutes = normalizeValidMinutes(requestedValidMinutes);
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);

        // 行锁串行化同一分配的并发重申；锁内按事务时钟裁决过期占位。
        UnblindRequestRow pending =
                unblindRequestRepository.lockPendingByAllocation(allocation.id());
        if (pending != null) {
            if (!isExpired(pending, clock.nowMillis())) {
                throw ApiException.conflict("该参与者已存在有效待审揭盲申请");
            }
            unblindRequestRepository.markExpired(pending.id());
        }

        long now = clock.nowMillis();
        long expiresAt = now + (long) validMinutes * MILLIS_PER_MINUTE;
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, "PENDING", null, now, null,
                validMinutes, expiresAt, null, null);
        try {
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            // 并发重申兜底：胜出者已占用待审占位；若其已过期则归档后冲突，否则直接冲突。
            UnblindRequestRow concurrent =
                    unblindRequestRepository.lockPendingByAllocation(allocation.id());
            if (concurrent != null && isExpired(concurrent, clock.nowMillis())) {
                unblindRequestRepository.markExpired(concurrent.id());
            }
            throw ApiException.conflict("该参与者已存在有效待审揭盲申请");
        }
        return toView(row);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人，且只能在未过期时批准。
     */
    @Transactional
    public UnblindRequestView approve(String unblindRequestId, String reviewerActor) {
        UnblindRequestRow row = lockAndCheckPending(unblindRequestId, reviewerActor, true);
        // 从数据库读取处理映射（盲底），批准时写入申请记录；处理代码不打日志。
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(row.experimentId(), row.participantId());
        SeatRow seat = experimentRepository.findSeat(row.experimentId(),
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.approve(unblindRequestId, reviewerActor,
                seat.treatment(), now);
        if (updated == 0) {
            // 行锁内理论不可达；兜底保证只能形成一个终态。
            throw ApiException.conflict("揭盲申请已终态，不能批准");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now, effectiveValidMinutes(row), effectiveExpiresAt(row),
                null, now);
    }

    /**
     * 另一名 REVIEWER 填写非空原因拒绝；申请人本人不能自拒，拒绝只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView reject(String unblindRequestId, String reviewerActor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        UnblindRequestRow row = lockAndCheckPending(unblindRequestId, reviewerActor, true);
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.reject(unblindRequestId, reviewerActor, reason, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已终态，不能拒绝");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "REJECTED",
                row.createdAt(), null, effectiveValidMinutes(row), effectiveExpiresAt(row),
                reason, now);
    }

    /**
     * 申请人本人（COORDINATOR）撤销自己的待审申请；只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView cancel(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            throw ApiException.forbidden("仅揭盲申请人本人可撤销该申请");
        }
        requirePendingAndUnexpired(row);
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.cancel(unblindRequestId, actorId, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已终态，不能撤销");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), null, "CANCELLED",
                row.createdAt(), null, effectiveValidMinutes(row), effectiveExpiresAt(row),
                null, now);
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
            // 其他人（含批准/拒绝人）一律 403。
            throw ApiException.forbidden("仅揭盲申请人本人可查询揭盲结果");
        }
        if (!"APPROVED".equals(row.status())) {
            // 申请人本人查询但未批准（含到期、拒绝、撤销）：409。
            throw ApiException.conflict("揭盲申请未获批准，无揭盲结果");
        }
        return new UnblindResultView(row.id(), row.experimentId(), row.participantId(),
                row.treatment(), row.status(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码）；仅申请人与处理人（批准/拒绝人）可查看。
     * PENDING 行按查询时钟只读展示 EXPIRED，不写库、不伪造人工处理人。
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

    /**
     * 批准/拒绝公共前置：存在性、本人回避、终态互转、到期裁决。
     *
     * @param reviewerActor 当前 REVIEWER 操作者编号
     */
    private UnblindRequestRow lockAndCheckPending(String unblindRequestId,
                                                  String reviewerActor, boolean requireOtherPerson) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (requireOtherPerson && row.applicantActor().equals(reviewerActor)) {
            // 申请人不能自批或自拒（即便其携带 REVIEWER 头）。
            throw ApiException.forbidden("处理人必须是不同于申请人的另一名 REVIEWER");
        }
        requirePendingAndUnexpired(row);
        return row;
    }

    /**
     * 终态互转与到期裁决：非 PENDING 一律 409；PENDING 但已到到期时刻同样 409。
     */
    private void requirePendingAndUnexpired(UnblindRequestRow row) {
        if (!"PENDING".equals(row.status())) {
            throw ApiException.conflict("揭盲申请已终态（" + row.status() + "），不能重复处理");
        }
        if (isExpired(row, clock.nowMillis())) {
            // 到期只能形成 EXPIRED，不得变成批准/拒绝/撤销。
            throw ApiException.conflict("揭盲申请已到期，不能处理");
        }
    }

    private int normalizeValidMinutes(Integer requested) {
        if (requested == null) {
            return DEFAULT_VALID_MINUTES;
        }
        if (requested < MIN_VALID_MINUTES || requested > MAX_VALID_MINUTES) {
            throw ApiException.badRequest(
                    "validMinutes 取值范围为 " + MIN_VALID_MINUTES + "~" + MAX_VALID_MINUTES);
        }
        return requested;
    }

    /** 到期判定：当前时刻达到 expiresAt 即过期；历史无有效期列的 PENDING 按创建时间+30分钟。 */
    private boolean isExpired(UnblindRequestRow row, long now) {
        return now >= effectiveExpiresAt(row);
    }

    private int effectiveValidMinutes(UnblindRequestRow row) {
        return row.validMinutes() == null ? DEFAULT_VALID_MINUTES : row.validMinutes();
    }

    private long effectiveExpiresAt(UnblindRequestRow row) {
        if (row.expiresAt() != null) {
            return row.expiresAt();
        }
        // 历史 PENDING 行：以原创建时间加 30 分钟计算有效期。
        return row.createdAt() + (long) DEFAULT_VALID_MINUTES * MILLIS_PER_MINUTE;
    }

    /**
     * 视图映射：PENDING 行按查询时钟只读折算 EXPIRED，终态时间固定为 expiresAt，
     * reviewerActor/rejectReason 保持库内原值（到期不伪造人工处理人）。
     */
    private UnblindRequestView toView(UnblindRequestRow row) {
        int validMinutes = effectiveValidMinutes(row);
        long expiresAt = effectiveExpiresAt(row);
        String status = row.status();
        Long terminatedAt = row.terminatedAt();
        if ("PENDING".equals(status) && clock.nowMillis() >= expiresAt) {
            status = "EXPIRED";
            terminatedAt = expiresAt;
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), status,
                row.createdAt(), row.reviewedAt(), validMinutes, expiresAt,
                row.rejectReason(), terminatedAt);
    }
}
