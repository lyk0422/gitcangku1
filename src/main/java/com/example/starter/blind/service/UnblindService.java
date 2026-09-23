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
 * 协调员为已分配参与者提出带原因与有效期（默认 30 分钟，1~60）的申请，
 * 同一参与者始终至多一份有效待审申请；到期按查询时钟展示 EXPIRED，普通查询不写库，
 * 仅在新建申请的同一事务内归档旧过期占位并创建新 PENDING。
 * 批准须由另一名 REVIEWER 完成；拒绝须由另一名 REVIEWER 填写非空原因；
 * 撤销仅限申请人本人（COORDINATOR）。批准/拒绝/撤销只能在未过期时提交，到期返回 409。
 * 终态不可互转；实验关闭、参与者退组不撤销已批准的揭盲。
 */
@Service
public class UnblindService {

    /** 申请默认有效时长（分钟）。 */
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
     * 协调员提出揭盲申请；validMinutes 缺省 30，取值 1~60。
     * 若该参与者存在已到期的 PENDING 占位，在同一事务内归档为 EXPIRED 后再创建新申请；
     * 重新申请生成新 ID，旧历史不可覆盖。
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
        int valid = normalizeValidMinutes(validMinutes);
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        long now = clock.nowMillis();
        // 行级锁定当前待审占位，串行化同一参与者的并发新申请与归档。
        UnblindRequestRow holder =
                unblindRequestRepository.lockPendingHolderByAllocation(allocation.id());
        if (holder != null) {
            if (!"PENDING".equals(holder.status()) || now < holder.expiresAt()) {
                // 未到期的有效待审始终拒绝；终态行理论上不占占位，防御性一并冲突。
                throw ApiException.conflict("该参与者已存在待审揭盲申请");
            }
            // 到期占位：同一事务内归档（终态时间固定为 expires_at），释放占位给新申请。
            int archived = unblindRequestRepository.archiveExpired(holder.id(), now);
            if (archived == 0) {
                // 并发竞争下回滚（占位被其他事务抢先处理）。
                throw ApiException.conflict("该参与者已存在待审揭盲申请");
            }
        }
        long expiresAt = now + (long) valid * MILLIS_PER_MINUTE;
        String requestId = "UB-" + UUID.randomUUID().toString().replace("-", "");
        UnblindRequestRow row = new UnblindRequestRow(requestId, experimentId, participantId,
                allocation.id(), reason, applicantActor, null, "PENDING", null,
                now, null, expiresAt, valid, null, null, null);
        try {
            unblindRequestRepository.insertPending(row);
        } catch (DuplicateKeyException e) {
            // 并发新申请：唯一待审占位兜底，最多一个成功。
            throw ApiException.conflict("该参与者已存在待审揭盲申请");
        }
        return toView(row, now);
    }

    /**
     * REVIEWER 批准申请；批准人不得是申请人本人；只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView approve(String unblindRequestId, String reviewerActor) {
        UnblindRequestRow row = lockAndCheckActionable(unblindRequestId);
        if (row.applicantActor().equals(reviewerActor)) {
            throw ApiException.forbidden("批准人必须是不同于申请人的另一名 REVIEWER");
        }
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
            // 并发终态竞争兜底：只能形成一个终态。
            throw ApiException.conflict("揭盲申请已终止，无法批准");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "APPROVED",
                row.createdAt(), now, row.expiresAt(), row.validMinutes(),
                null, null, null);
    }

    /**
     * 另一名 REVIEWER 拒绝申请；须填写非空原因；申请人不能自拒；只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView reject(String unblindRequestId, String reason, String reviewerActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        UnblindRequestRow row = lockAndCheckActionable(unblindRequestId);
        if (row.applicantActor().equals(reviewerActor)) {
            throw ApiException.forbidden("拒绝人必须是不同于申请人的另一名 REVIEWER");
        }
        long now = clock.nowMillis();
        int updated = unblindRequestRepository.reject(unblindRequestId, reviewerActor, reason, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已终止，无法拒绝");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), reviewerActor, "REJECTED",
                row.createdAt(), now, row.expiresAt(), row.validMinutes(),
                now, reason, reviewerActor);
    }

    /**
     * 申请人本人（COORDINATOR）撤销自己的 PENDING 申请；只能在未过期时提交。
     */
    @Transactional
    public UnblindRequestView cancel(String unblindRequestId, String reason, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            // 非申请人不得撤销；授权先于到期/状态冲突判定。
            throw ApiException.forbidden("仅揭盲申请人本人可撤销该申请");
        }
        long now = clock.nowMillis();
        if (isExpired(row, now)) {
            throw ApiException.conflict("揭盲申请已到期，无法撤销");
        }
        if (!"PENDING".equals(row.status())) {
            throw ApiException.conflict("揭盲申请已终止，无法撤销");
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        int updated = unblindRequestRepository.cancel(unblindRequestId, actorId,
                normalizedReason, now);
        if (updated == 0) {
            throw ApiException.conflict("揭盲申请已终止，无法撤销");
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), null, "CANCELLED",
                row.createdAt(), null, row.expiresAt(), row.validMinutes(),
                now, normalizedReason, actorId);
    }

    /**
     * 查询揭盲结果：仅申请人本人、且申请已批准时可获得处理代码。
     * 到期/拒绝/撤销后申请人得到 409 且不泄露处理代码；非申请人一律 403。
     */
    public UnblindResultView getResult(String unblindRequestId, String actorId) {
        UnblindRequestRow row = unblindRequestRepository.findById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        if (!row.applicantActor().equals(actorId)) {
            // 其他人（含批准人/拒绝人）一律 403，且不泄露当前所处终态。
            throw ApiException.forbidden("仅揭盲申请人本人可查询揭盲结果");
        }
        if (!"APPROVED".equals(row.status())) {
            // 申请人本人查询但未获批准（含 PENDING/到期/拒绝/撤销）：409。
            throw ApiException.conflict("揭盲申请未获批准");
        }
        return new UnblindResultView(row.id(), row.experimentId(), row.participantId(),
                row.treatment(), row.status(), row.reviewedAt());
    }

    /**
     * 查询申请状态（不含处理代码）；仅申请人与终态处理人（批准人/拒绝人）可查看。
     * 到期按查询时钟展示 EXPIRED，不写库；EXPIRED 展示的终止时间固定为 expiresAt，
     * terminateActor 为 null，不伪造人工处理人。
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
        return toView(row, clock.nowMillis());
    }

    /**
     * 行级锁定并校验裁决（批准/拒绝）前提：存在、未到期、仍为 PENDING。
     */
    private UnblindRequestRow lockAndCheckActionable(String unblindRequestId) {
        UnblindRequestRow row = unblindRequestRepository.lockById(unblindRequestId);
        if (row == null) {
            throw ApiException.notFound("揭盲申请不存在: " + unblindRequestId);
        }
        long now = clock.nowMillis();
        if (isExpired(row, now)) {
            // 到期提交裁决：409，不得变成批准/拒绝；事务回滚不写任何终态。
            throw ApiException.conflict("揭盲申请已到期，无法裁决");
        }
        if (!"PENDING".equals(row.status())) {
            // 所有终态不可互转。
            throw ApiException.conflict("揭盲申请已终止，无法再次裁决");
        }
        return row;
    }

    private boolean isExpired(UnblindRequestRow row, long now) {
        return "PENDING".equals(row.status()) && now >= row.expiresAt();
    }

    private int normalizeValidMinutes(Integer validMinutes) {
        if (validMinutes == null) {
            return DEFAULT_VALID_MINUTES;
        }
        if (validMinutes < MIN_VALID_MINUTES || validMinutes > MAX_VALID_MINUTES) {
            throw ApiException.badRequest(
                    "validMinutes 取值范围为 " + MIN_VALID_MINUTES + "~" + MAX_VALID_MINUTES);
        }
        return validMinutes;
    }

    private UnblindRequestView toView(UnblindRequestRow row, long now) {
        if (isExpired(row, now)) {
            // 查询时钟下的到期展示：不写库；终止时间固定为 expiresAt，无处理人与原因。
            return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                    row.reason(), row.applicantActor(), row.reviewerActor(), "EXPIRED",
                    row.createdAt(), row.reviewedAt(), row.expiresAt(), row.validMinutes(),
                    row.expiresAt(), null, null);
        }
        return new UnblindRequestView(row.id(), row.experimentId(), row.participantId(),
                row.reason(), row.applicantActor(), row.reviewerActor(), row.status(),
                row.createdAt(), row.reviewedAt(), row.expiresAt(), row.validMinutes(),
                row.terminatedAt(), row.terminateReason(), row.terminateActor());
    }
}
