package com.example.starter.bridge;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.bridge.dto.ApplyPermitRequest;
import com.example.starter.bridge.dto.TimelineResponse;

/**
 * 便桥许可调度服务：签发、进桥确认、出桥释放、撤销与时间轴查询。
 * 签发通过配置单例行 SELECT ... FOR UPDATE 串行化，状态迁移用条件更新保证竞争下只有一个终态。
 */
@Service
public class PermitService {

    private final PermitRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PermitService(PermitRepository repository, Clock bridgeClock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = bridgeClock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 签发许可。同一 requestId 同参重放返回原许可，异参返回 409；
     * 超重、时间倒退、窗口重叠拒绝并记录拒绝依据，不生成许可、不占幂等键。
     */
    public BridgePermit apply(ApplyPermitRequest request) {
        Instant now = clock.instant();
        Optional<BridgePermit> existing = repository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), request, now);
        }
        try {
            return transactionTemplate.execute(status -> issue(request, now));
        } catch (DuplicateKeyException duplicate) {
            BridgePermit raced = repository.findByRequestId(request.requestId())
                    .orElseThrow(() -> duplicate);
            return resolveIdempotent(raced, request, now);
        } catch (PermitConflictException conflict) {
            recordRejection(request, conflict.code(), conflict.getMessage(), now);
            throw conflict;
        }
    }

    /** 事务内签发：锁定配置行后校验并插入，保证并发下同一窗口最多一个获准。 */
    private BridgePermit issue(ApplyPermitRequest request, Instant now) {
        BridgeState state = repository.lockBridgeState();
        Optional<BridgePermit> existing = repository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), request, now);
        }
        if (request.totalMassKg() > state.maxTotalMassKg()) {
            throw new PermitConflictException("OVERWEIGHT",
                    "车辆总质量 " + request.totalMassKg() + " 千克超过桥梁限重 "
                            + state.maxTotalMassKg() + " 千克");
        }
        if (request.plannedEntryAt().isBefore(now)) {
            throw new PermitConflictException("TIME_REGRESSION",
                    "预计入桥时刻 " + request.plannedEntryAt() + " 早于当前时刻 " + now);
        }
        Instant windowStart = request.plannedEntryAt();
        Instant windowEnd = windowStart.plusSeconds(state.crossingMinutes() * 60L);
        for (BridgePermit occupying : repository.findOccupying()) {
            Instant occupiedFrom = occupying.status() == PermitStatus.APPROVED
                    ? occupying.windowStartAt() : occupying.actualEntryAt();
            Instant occupiedTo = occupying.status() == PermitStatus.APPROVED
                    ? occupying.windowEndAt() : null;
            if (TimeWindows.overlaps(occupiedFrom, occupiedTo, windowStart, windowEnd)) {
                throw new PermitConflictException("WINDOW_OVERLAP",
                        "申请窗口 [" + windowStart + ", " + windowEnd + ") 与许可 "
                                + occupying.id() + "（" + occupying.plateNo() + "）的占桥区间相交");
            }
        }
        BridgePermit permit = new BridgePermit(0L, request.requestId(), request.plateNo(),
                request.totalMassKg(), request.plannedEntryAt(), windowStart, windowEnd,
                PermitStatus.APPROVED, null, null, null, null, null, now, now);
        return repository.insert(permit);
    }

    /** 幂等重放判定：参数一致返回原许可，不一致记录拒绝并抛 409。 */
    private BridgePermit resolveIdempotent(BridgePermit permit, ApplyPermitRequest request, Instant now) {
        if (permit.plateNo().equals(request.plateNo())
                && permit.totalMassKg() == request.totalMassKg()
                && permit.plannedEntryAt().equals(request.plannedEntryAt())) {
            return permit;
        }
        PermitConflictException conflict = new PermitConflictException("IDEMPOTENCY_CONFLICT",
                "请求键 " + request.requestId() + " 已存在且申请参数不一致");
        recordRejection(request, conflict.code(), conflict.getMessage(), now);
        throw conflict;
    }

    /** 记录拒绝依据；拒绝记录不占用签发幂等键。 */
    private void recordRejection(ApplyPermitRequest request, String code, String detail, Instant now) {
        repository.insertRejection(new BridgeRejection(0L, request.requestId(), request.plateNo(),
                request.totalMassKg(), request.plannedEntryAt(), code, detail, now));
    }

    /**
     * 进桥确认：仅允许在许可窗口内进桥；与撤销竞争时条件更新保证只有一个终态。
     */
    public BridgePermit confirmEntry(long permitId, String operationRequestId) {
        Instant now = clock.instant();
        BridgePermit permit = repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
        if (permit.status() == PermitStatus.ENTERED) {
            return replayOrConflict(permit, operationRequestId, permit.entryRequestId(), "进桥");
        }
        if (permit.status() != PermitStatus.APPROVED) {
            throw new PermitConflictException("INVALID_STATE",
                    "许可状态为 " + permit.status() + "，不得进桥");
        }
        if (now.isBefore(permit.windowStartAt()) || !now.isBefore(permit.windowEndAt())) {
            throw new PermitConflictException("OUTSIDE_WINDOW",
                    "当前时刻 " + now + " 不在许可窗口 [" + permit.windowStartAt() + ", "
                            + permit.windowEndAt() + ") 内，不得进桥");
        }
        if (repository.markEntered(permitId, now, operationRequestId, now) == 0) {
            return resolveLostRace(permitId, operationRequestId, "进桥");
        }
        return repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
    }

    /**
     * 出桥释放：仅已进桥许可可出桥，记录实际出桥时刻；历史时刻不可覆盖。
     */
    public BridgePermit confirmExit(long permitId, String operationRequestId) {
        Instant now = clock.instant();
        BridgePermit permit = repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
        if (permit.status() == PermitStatus.EXITED) {
            return replayOrConflict(permit, operationRequestId, permit.exitRequestId(), "出桥");
        }
        if (permit.status() != PermitStatus.ENTERED) {
            throw new PermitConflictException("INVALID_STATE",
                    "许可状态为 " + permit.status() + "，未进桥不得出桥");
        }
        if (now.isBefore(permit.actualEntryAt())) {
            throw new PermitConflictException("TIME_REGRESSION",
                    "出桥时刻 " + now + " 早于实际进桥时刻 " + permit.actualEntryAt());
        }
        if (repository.markExited(permitId, now, operationRequestId, now) == 0) {
            return resolveLostRace(permitId, operationRequestId, "出桥");
        }
        return repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
    }

    /**
     * 撤销许可：仅未进桥许可可撤销并释放预计窗口；已进桥许可只能出桥。
     */
    public BridgePermit cancel(long permitId, String operationRequestId) {
        Instant now = clock.instant();
        BridgePermit permit = repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
        if (permit.status() == PermitStatus.CANCELLED) {
            return replayOrConflict(permit, operationRequestId, permit.cancelRequestId(), "撤销");
        }
        if (permit.status() == PermitStatus.ENTERED) {
            throw new PermitConflictException("NOT_CANCELLABLE",
                    "许可已进桥，不得撤销，只能出桥");
        }
        if (permit.status() != PermitStatus.APPROVED) {
            throw new PermitConflictException("INVALID_STATE",
                    "许可状态为 " + permit.status() + "，不得撤销");
        }
        if (repository.markCancelled(permitId, operationRequestId, now) == 0) {
            return resolveLostRace(permitId, operationRequestId, "撤销");
        }
        return repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
    }

    /** 按主键查询许可。 */
    public BridgePermit getPermit(long permitId) {
        return repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
    }

    /** 查询桥梁占用时间轴、许可状态与拒绝依据。 */
    public TimelineResponse timeline() {
        BridgeState state = repository.findBridgeState();
        List<TimelineResponse.Entry> entries = repository.findAllOrderByWindow().stream()
                .map(permit -> {
                    Instant occupiedFrom;
                    Instant occupiedTo;
                    switch (permit.status()) {
                        case APPROVED -> {
                            occupiedFrom = permit.windowStartAt();
                            occupiedTo = permit.windowEndAt();
                        }
                        case ENTERED -> {
                            occupiedFrom = permit.actualEntryAt();
                            occupiedTo = null;
                        }
                        case EXITED -> {
                            occupiedFrom = permit.actualEntryAt();
                            occupiedTo = permit.actualExitAt();
                        }
                        default -> {
                            occupiedFrom = null;
                            occupiedTo = null;
                        }
                    }
                    return new TimelineResponse.Entry(permit.id(), permit.plateNo(),
                            permit.status().name(), permit.windowStartAt(), permit.windowEndAt(),
                            permit.actualEntryAt(), permit.actualExitAt(), occupiedFrom, occupiedTo);
                })
                .toList();
        List<TimelineResponse.Rejection> rejections = repository.findAllRejections().stream()
                .map(rejection -> new TimelineResponse.Rejection(rejection.requestId(),
                        rejection.plateNo(), rejection.reasonCode(), rejection.reasonDetail(),
                        rejection.createdAt()))
                .toList();
        return new TimelineResponse(state.maxTotalMassKg(), state.crossingMinutes(),
                clock.instant(), entries, rejections);
    }

    /** 终态重放判定：操作幂等键一致返回当前记录，不一致抛 409。 */
    private BridgePermit replayOrConflict(BridgePermit permit, String operationRequestId,
            String recordedRequestId, String operation) {
        if (operationRequestId.equals(recordedRequestId)) {
            return permit;
        }
        throw new PermitConflictException("IDEMPOTENCY_CONFLICT",
                operation + "请求键 " + operationRequestId + " 与已记录的 "
                        + recordedRequestId + " 不一致");
    }

    /** 条件更新未命中时重读记录，区分重放成功与竞争失败。 */
    private BridgePermit resolveLostRace(long permitId, String operationRequestId, String operation) {
        BridgePermit current = repository.findById(permitId)
                .orElseThrow(() -> new PermitNotFoundException(permitId));
        String recorded = switch (current.status()) {
            case ENTERED -> current.entryRequestId();
            case EXITED -> current.exitRequestId();
            case CANCELLED -> current.cancelRequestId();
            default -> null;
        };
        if (operationRequestId.equals(recorded)) {
            return current;
        }
        throw new PermitConflictException("STATE_RACE",
                operation + "与另一操作竞争失败，许可当前状态为 " + current.status());
    }

    /** 按签发幂等键查询许可，用于判定重放与响应状态码。 */
    public Optional<BridgePermit> findByRequestId(String requestId) {
        return repository.findByRequestId(requestId);
    }
}