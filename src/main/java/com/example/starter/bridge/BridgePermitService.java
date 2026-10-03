package com.example.starter.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 便桥通行调度服务：签发、进桥、出桥、撤销与查询。
 * 签发通过对桥梁配置行加锁串行化，保证同一窗口最多一个许可获准；
 * 进桥/出桥/撤销通过对许可行加锁串行化，保证竞争操作只产生一个终态。
 */
@Service
public class BridgePermitService {

    private final BridgeConfigRepository configRepository;
    private final PermitRepository permitRepository;
    private final RejectionRepository rejectionRepository;
    private final Clock clock;

    public BridgePermitService(BridgeConfigRepository configRepository,
            PermitRepository permitRepository,
            RejectionRepository rejectionRepository,
            Clock clock) {
        this.configRepository = configRepository;
        this.permitRepository = permitRepository;
        this.rejectionRepository = rejectionRepository;
        this.clock = clock;
    }

    /**
     * 签发许可。同 requestId 同参重放返回原许可；同 requestId 异参抛 409；
     * 超重、时间倒退、窗口重叠记录拒绝依据且不生成许可（拒绝不占幂等键）。
     */
    @Transactional
    public ApplyResult apply(String requestId, String plateNo, long massKg, Instant plannedEntryAt) {
        BridgeConfig config = configRepository.getForUpdate();
        var existing = permitRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            BridgePermit permit = existing.get();
            if (!permit.matchesApplication(plateNo, massKg, plannedEntryAt)) {
                throw new BridgeApiException(409, "REQUEST_ID_CONFLICT",
                        "requestId 已用于其他申请参数，拒绝重放");
            }
            return new ApplyResult.Approved(permit, true);
        }
        Instant now = Instant.now(clock);
        if (massKg > config.maxMassKg()) {
            return reject(requestId, plateNo, massKg, plannedEntryAt, RejectReason.OVERWEIGHT,
                    "总质量 " + massKg + " 千克超过桥梁上限 " + config.maxMassKg() + " 千克", now);
        }
        if (plannedEntryAt.isBefore(now)) {
            return reject(requestId, plateNo, massKg, plannedEntryAt, RejectReason.TIME_REGRESSION,
                    "预计入桥时刻 " + plannedEntryAt + " 早于当前时刻 " + now, now);
        }
        Instant windowEnd = plannedEntryAt.plus(Duration.ofMinutes(config.windowMinutes()));
        List<BridgePermit> conflicts = permitRepository.findActiveOverlapping(plannedEntryAt, windowEnd);
        if (!conflicts.isEmpty()) {
            BridgePermit first = conflicts.get(0);
            return reject(requestId, plateNo, massKg, plannedEntryAt, RejectReason.WINDOW_OVERLAP,
                    "窗口 [" + plannedEntryAt + ", " + windowEnd + ") 与许可 #" + first.id()
                            + "（" + first.plateNo() + "，状态 " + first.status() + "）占用区间相交",
                    now);
        }
        BridgePermit permit = new BridgePermit(0L, requestId, plateNo, massKg, plannedEntryAt,
                plannedEntryAt, windowEnd, PermitStatus.APPROVED, null, null, null, null, null, now, now);
        return new ApplyResult.Approved(permitRepository.insert(permit), false);
    }

    /**
     * 确认进桥：仅允许在锁定窗口 [windowStart, windowEnd) 内进桥。
     * 同 requestId 重放返回当前状态；异参或非法状态抛 409。
     */
    @Transactional
    public BridgePermit enter(long permitId, String requestId, Instant at) {
        BridgePermit permit = lockPermit(permitId);
        if (permit.status() == PermitStatus.ENTERED) {
            if (requestId.equals(permit.enterRequestId())) {
                return permit;
            }
            throw new BridgeApiException(409, "ALREADY_ENTERED", "许可已进桥，requestId 与首次进桥不一致");
        }
        if (permit.status() != PermitStatus.APPROVED) {
            throw new BridgeApiException(409, "INVALID_STATE",
                    "许可状态 " + permit.status() + " 不允许进桥");
        }
        if (at.isBefore(permit.windowStart()) || !at.isBefore(permit.windowEnd())) {
            throw new BridgeApiException(409, "OUTSIDE_WINDOW",
                    "进桥时刻 " + at + " 不在许可窗口 [" + permit.windowStart() + ", "
                            + permit.windowEnd() + ") 内");
        }
        permitRepository.markEntered(permitId, requestId, at, Instant.now(clock));
        return permitRepository.findById(permitId).orElseThrow();
    }

    /**
     * 出桥释放：记录实际出桥时刻；出桥前占用不因预计窗口结束而自动释放。
     */
    @Transactional
    public BridgePermit exit(long permitId, String requestId, Instant at) {
        BridgePermit permit = lockPermit(permitId);
        if (permit.status() == PermitStatus.EXITED) {
            if (requestId.equals(permit.exitRequestId())) {
                return permit;
            }
            throw new BridgeApiException(409, "ALREADY_EXITED", "许可已出桥，requestId 与首次出桥不一致");
        }
        if (permit.status() != PermitStatus.ENTERED) {
            throw new BridgeApiException(409, "INVALID_STATE",
                    "许可状态 " + permit.status() + " 不允许出桥，须先进桥");
        }
        if (at.isBefore(permit.enteredAt())) {
            throw new BridgeApiException(409, "TIME_REGRESSION",
                    "出桥时刻 " + at + " 早于实际进桥时刻 " + permit.enteredAt());
        }
        permitRepository.markExited(permitId, requestId, at, Instant.now(clock));
        return permitRepository.findById(permitId).orElseThrow();
    }

    /**
     * 撤销许可：仅未进桥许可可撤销并释放预计窗口；已进桥许可只能出桥。
     */
    @Transactional
    public BridgePermit cancel(long permitId, String requestId) {
        BridgePermit permit = lockPermit(permitId);
        if (permit.status() == PermitStatus.CANCELLED) {
            if (requestId.equals(permit.cancelRequestId())) {
                return permit;
            }
            throw new BridgeApiException(409, "ALREADY_CANCELLED", "许可已撤销，requestId 与首次撤销不一致");
        }
        if (permit.status() != PermitStatus.APPROVED) {
            throw new BridgeApiException(409, "INVALID_STATE",
                    "许可状态 " + permit.status() + " 不允许撤销，已进桥许可只能出桥");
        }
        permitRepository.markCancelled(permitId, requestId, Instant.now(clock));
        return permitRepository.findById(permitId).orElseThrow();
    }

    /**
     * 查询桥梁配置。
     */
    @Transactional(readOnly = true)
    public BridgeConfig getConfig() {
        return configRepository.get();
    }

    /**
     * 查询全部许可（占用时间轴的数据来源）。
     */
    @Transactional(readOnly = true)
    public List<BridgePermit> listPermits() {
        return permitRepository.findAll();
    }

    /**
     * 查询全部拒绝记录（拒绝依据）。
     */
    @Transactional(readOnly = true)
    public List<RejectionRecord> listRejections() {
        return rejectionRepository.findAll();
    }

    private BridgePermit lockPermit(long permitId) {
        return permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> new BridgeApiException(404, "PERMIT_NOT_FOUND", "许可 #" + permitId + " 不存在"));
    }

    private ApplyResult reject(String requestId, String plateNo, long massKg, Instant plannedEntryAt,
            RejectReason reason, String detail, Instant now) {
        rejectionRepository.insert(requestId, plateNo, massKg, plannedEntryAt, reason, detail, now);
        return new ApplyResult.Rejected(reason, detail);
    }
}