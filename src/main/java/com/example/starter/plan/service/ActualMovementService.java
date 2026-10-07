package com.example.starter.plan.service;

import com.example.starter.plan.model.ActualOccupancy;
import com.example.starter.plan.model.ActualStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.repo.ActualIdempotencyRepository;
import com.example.starter.plan.repo.ActualRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualLockView;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.ActualOccupancyView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 实际进出确认：进入要求服务端 UTC 时刻落在原计划左闭右开时隙内，且同区段至多一个 INSIDE；
 * 驶出必须对应当前 INSIDE，记录服务端时刻与原计划版本，迟到驶出不回改计划时隙。
 *
 * <p>进入/驶出与取消/改签共用全局发布锁串行校验；驶出记录与释放实际锁同事务。
 * 写操作按全局 requestId 幂等：同参重放返回首次完整响应，异参 409，失败不占键。
 */
@Service
public class ActualMovementService {

    private static final String OP_ENTER = "ENTER";
    private static final String OP_EXIT = "EXIT";

    private final PlanRepository planRepo;
    private final ActualRepository actualRepo;
    private final ActualIdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ActualMovementService(PlanRepository planRepo, ActualRepository actualRepo,
                                 ActualIdempotencyRepository idemRepo, ObjectMapper objectMapper,
                                 PlatformTransactionManager txManager, Clock clock) {
        this.planRepo = planRepo;
        this.actualRepo = actualRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 确认进入：占用须为 NOT_ENTERED，计划已发布且版本匹配，服务端时刻在 [start, end) 内，
     * 同运营日同区段无其他 INSIDE。通过后占用置 INSIDE 并追加 ENTER 轨迹。
     */
    public ActualMovementResponse enter(String scheduleKey, int seq, ActualActionRequest req) {
        String resource = resource(scheduleKey, seq);
        Optional<ActualMovementResponse> replay = replayIfPresent(OP_ENTER, resource, req);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行点后重查：并发同键请求在锁释放后应命中首次记录而非重复执行
                Optional<ActualMovementResponse> lockedReplay =
                        replayIfPresent(OP_ENTER, resource, req);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                ActualOccupancy occ = actualRepo.findOccupancyForUpdate(plan.id(), seq)
                        .orElseThrow(() -> occupancyNotFound(scheduleKey, seq));
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可确认进入，当前状态: " + plan.status());
                }
                checkVersion(req, plan);
                if (occ.actualStatus() != ActualStatus.NOT_ENTERED) {
                    throw conflict("OCCUPANCY_STATE_CONFLICT",
                            "占用当前实际状态不允许进入: " + occ.actualStatus());
                }
                Instant now = clock.instant();
                if (now.isBefore(occ.startUtc()) || !now.isBefore(occ.endUtc())) {
                    throw conflict("ENTER_OUTSIDE_SLOT",
                            "服务端时刻 " + now + " 不在计划时隙 [" + occ.startUtc() + ", "
                                    + occ.endUtc() + ") 内");
                }
                if (actualRepo.existsInsideOnSection(plan.opDate(), occ.sectionId())) {
                    throw conflict("SECTION_ACTUALLY_LOCKED",
                            "区段 " + occ.sectionId() + " 存在未驶出占用，禁止进入");
                }
                long nowMillis = now.toEpochMilli();
                actualRepo.markInside(occ.id(), nowMillis);
                actualRepo.insertEvent(plan.id(), occ.id(), seq, OP_ENTER, nowMillis,
                        plan.version(), req.operator(), req.requestId(), nowMillis);
                ActualMovementResponse response = new ActualMovementResponse(scheduleKey, seq,
                        occ.trainNo(), occ.sectionId(), ActualStatus.INSIDE.name(),
                        now, null, null, plan.version());
                idemRepo.insert(req.requestId(), OP_ENTER, resource, req.operator(),
                        req.expectedVersion(), paramsJson(OP_ENTER, resource, req),
                        toJson(response), nowMillis);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_ENTER, resource, req);
        }
    }

    /**
     * 确认驶出：占用须为当前 INSIDE，记录服务端时刻与原计划版本；
     * 驶出事件与释放实际锁（状态置 EXITED）同事务提交。
     */
    public ActualMovementResponse exit(String scheduleKey, int seq, ActualActionRequest req) {
        String resource = resource(scheduleKey, seq);
        Optional<ActualMovementResponse> replay = replayIfPresent(OP_EXIT, resource, req);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行点后重查：并发同键请求在锁释放后应命中首次记录而非重复执行
                Optional<ActualMovementResponse> lockedReplay =
                        replayIfPresent(OP_EXIT, resource, req);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                ActualOccupancy occ = actualRepo.findOccupancyForUpdate(plan.id(), seq)
                        .orElseThrow(() -> occupancyNotFound(scheduleKey, seq));
                checkVersion(req, plan);
                if (occ.actualStatus() != ActualStatus.INSIDE) {
                    throw conflict("OCCUPANCY_STATE_CONFLICT",
                            "仅当前 INSIDE 的占用可驶出，当前状态: " + occ.actualStatus());
                }
                Instant now = clock.instant();
                long nowMillis = now.toEpochMilli();
                actualRepo.markExited(occ.id(), nowMillis, plan.version());
                actualRepo.insertEvent(plan.id(), occ.id(), seq, OP_EXIT, nowMillis,
                        plan.version(), req.operator(), req.requestId(), nowMillis);
                ActualMovementResponse response = new ActualMovementResponse(scheduleKey, seq,
                        occ.trainNo(), occ.sectionId(), ActualStatus.EXITED.name(),
                        occ.enteredAt(), now, plan.version(), plan.version());
                idemRepo.insert(req.requestId(), OP_EXIT, resource, req.operator(),
                        req.expectedVersion(), paramsJson(OP_EXIT, resource, req),
                        toJson(response), nowMillis);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_EXIT, resource, req);
        }
    }

    /**
     * 查询计划占用的实际状态（计划时隙与实际时刻分列）；只读，不自动驶出。
     */
    public List<ActualOccupancyView> getActualOccupancies(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return actualRepo.findOccupancies(plan.id()).stream()
                .map(o -> new ActualOccupancyView(o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc(), o.endUtc(), o.actualStatus().name(),
                        o.enteredAt(), o.exitedAt(), o.exitPlanVersion()))
                .toList();
    }

    /**
     * 查询指定运营日与区段上当前 INSIDE 的实际锁；只读，不自动驶出。
     */
    public List<ActualLockView> getActualLocks(LocalDate opDate, String sectionId) {
        return actualRepo.findInsideLocks(opDate, sectionId).stream()
                .map(l -> new ActualLockView(l.scheduleKey(), l.seq(), l.trainNo(), l.sectionId(),
                        l.enteredAt()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private void checkVersion(ActualActionRequest req, DayPlan plan) {
        if (req.expectedVersion() != plan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedVersion=" + req.expectedVersion()
                            + " 与当前版本 " + plan.version() + " 不一致");
        }
    }

    /**
     * 幂等重放：存在记录且操作、资源、全参数（操作者原文）与期望版本（按值）一致返回首次结果，
     * 任一不一致抛 409。
     */
    private Optional<ActualMovementResponse> replayIfPresent(String opType, String resource,
                                                             ActualActionRequest req) {
        return idemRepo.find(req.requestId()).map(record -> {
            if (!record.opType().equals(opType) || !record.resource().equals(resource)
                    || !record.operator().equals(req.operator())
                    || record.expectedVersion() != req.expectedVersion()) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + req.requestId());
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 并发下 requestId 唯一键冲突后的裁决：同参返回首次结果，异参 409。
     */
    private ActualMovementResponse resolveDuplicate(String opType, String resource,
                                                    ActualActionRequest req) {
        return replayIfPresent(opType, resource, req)
                .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 冲突: " + req.requestId()));
    }

    private String resource(String scheduleKey, int seq) {
        return scheduleKey + "#" + seq;
    }

    private String paramsJson(String opType, String resource, ActualActionRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("op", opType);
        params.put("resource", resource);
        params.put("operator", req.operator());
        params.put("expectedVersion", req.expectedVersion());
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("参数序列化失败", e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ActualMovementResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, ActualMovementResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException occupancyNotFound(String scheduleKey, int seq) {
        return new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                "占用不存在: " + scheduleKey + "#" + seq);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
