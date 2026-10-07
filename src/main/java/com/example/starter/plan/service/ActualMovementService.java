package com.example.starter.plan.service;

import com.example.starter.plan.model.ActualEvent;
import com.example.starter.plan.model.ActualLock;
import com.example.starter.plan.model.ActualStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.OccupancyActual;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.repo.ActualRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualLockView;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.OccupancyActualView;
import com.example.starter.plan.web.dto.PlanActualResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 实际进出确认：进入/退出与发布、取消、改签共用全局发布锁串行校验。
 *
 * <p>进入要求计划已发布、期望版本匹配、占用未进入且服务端 UTC 时刻落在原左闭右开时隙内，
 * 同区段至多一条 INSIDE；退出必须对应当前 INSIDE，记录服务端时刻与原计划版本。
 * 确认时刻一律取自可注入 Clock，调用方不可指定。写操作按 (操作类型, requestId) 幂等：
 * 同参重放返回首次完整响应，异参 409，失败不占键，业务与去重同事务提交。
 */
@Service
public class ActualMovementService {

    private static final String OP_ENTER = "ENTER";
    private static final String OP_EXIT = "EXIT";

    private final PlanRepository planRepo;
    private final ActualRepository actualRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ActualMovementService(PlanRepository planRepo, ActualRepository actualRepo,
                                 IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                                 Clock clock, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.actualRepo = actualRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 确认进入：占用由 NOT_ENTERED 推进为 INSIDE 并追加 ENTER 轨迹，同一事务写入幂等记录。
     */
    public ActualMovementResponse enter(String scheduleKey, int seq, ActualActionRequest req) {
        String hash = hashActual(OP_ENTER, scheduleKey, seq, req);
        Optional<ActualMovementResponse> replay = replayIfPresent(OP_ENTER, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查：并发下同键请求等待锁期间首次请求可能已提交，直接重放其响应
                Optional<ActualMovementResponse> innerReplay =
                        replayIfPresent(OP_ENTER, req.requestId(), hash);
                if (innerReplay.isPresent()) {
                    return innerReplay.get();
                }
                DayPlan plan = lockPublishedPlan(scheduleKey, req.expectedVersion(), "进入");
                OccupancyActual occ = lockOccupancy(plan.id(), scheduleKey, seq);
                if (occ.actualStatus() != ActualStatus.NOT_ENTERED) {
                    throw conflict("ACTUAL_STATE_CONFLICT",
                            "占用当前实际状态不允许进入: " + occ.actualStatus());
                }
                Instant now = clock.instant();
                if (now.isBefore(occ.startUtc()) || !now.isBefore(occ.endUtc())) {
                    throw conflict("OUTSIDE_SLOT_WINDOW",
                            "服务端时刻 " + now + " 不在占用时隙 [" + occ.startUtc() + ", "
                                    + occ.endUtc() + ") 内");
                }
                Optional<ActualLock> blocking =
                        actualRepo.findInsideLocksBySection(occ.sectionId()).stream().findFirst();
                if (blocking.isPresent()) {
                    ActualLock lock = blocking.get();
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_OCCUPIED");
                    detail.put("sectionId", occ.sectionId());
                    detail.put("holdingScheduleKey", lock.scheduleKey());
                    detail.put("holdingTrainNo", lock.trainNo());
                    detail.put("enteredAt", lock.enteredAt().toString());
                    throw new ApiException(HttpStatus.CONFLICT, "SECTION_OCCUPIED",
                            "区段存在未退出列车: " + occ.sectionId(), List.of(detail));
                }
                actualRepo.updateActualStatus(occ.id(), ActualStatus.INSIDE);
                actualRepo.insertEvent(plan.id(), occ.id(), seq, OP_ENTER, now.toEpochMilli(),
                        plan.version(), req.operator(), req.requestId(), now.toEpochMilli());
                ActualMovementResponse response = new ActualMovementResponse(scheduleKey, seq,
                        occ.trainNo(), occ.sectionId(), ActualStatus.INSIDE.name(), now,
                        plan.version());
                idemRepo.insert(OP_ENTER, req.requestId(), hash, toJson(response),
                        now.toEpochMilli());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_ENTER, req.requestId(), hash);
        }
    }

    /**
     * 确认退出：占用必须由 INSIDE 推进为 EXITED，退出轨迹与释放区段实际锁同一事务提交；
     * 迟到退出不回改计划时隙。
     */
    public ActualMovementResponse exit(String scheduleKey, int seq, ActualActionRequest req) {
        String hash = hashActual(OP_EXIT, scheduleKey, seq, req);
        Optional<ActualMovementResponse> replay = replayIfPresent(OP_EXIT, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查：并发下同键请求等待锁期间首次请求可能已提交，直接重放其响应
                Optional<ActualMovementResponse> innerReplay =
                        replayIfPresent(OP_EXIT, req.requestId(), hash);
                if (innerReplay.isPresent()) {
                    return innerReplay.get();
                }
                DayPlan plan = lockPublishedPlan(scheduleKey, req.expectedVersion(), "退出");
                OccupancyActual occ = lockOccupancy(plan.id(), scheduleKey, seq);
                if (occ.actualStatus() != ActualStatus.INSIDE) {
                    throw conflict("ACTUAL_STATE_CONFLICT",
                            "占用当前实际状态不允许退出: " + occ.actualStatus());
                }
                Instant now = clock.instant();
                actualRepo.updateActualStatus(occ.id(), ActualStatus.EXITED);
                actualRepo.insertEvent(plan.id(), occ.id(), seq, OP_EXIT, now.toEpochMilli(),
                        plan.version(), req.operator(), req.requestId(), now.toEpochMilli());
                ActualMovementResponse response = new ActualMovementResponse(scheduleKey, seq,
                        occ.trainNo(), occ.sectionId(), ActualStatus.EXITED.name(), now,
                        plan.version());
                idemRepo.insert(OP_EXIT, req.requestId(), hash, toJson(response),
                        now.toEpochMilli());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_EXIT, req.requestId(), hash);
        }
    }

    /**
     * 查询计划每条占用的实际状态与进出轨迹时刻；查询不自动退出，不存在返回 404。
     */
    public PlanActualResponse getPlanActual(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        Map<Long, List<ActualEvent>> eventsByOccupancy = actualRepo.findEventsByPlan(plan.id())
                .stream().collect(Collectors.groupingBy(ActualEvent::occupancyId));
        List<OccupancyActualView> views = actualRepo.findOccupancyActuals(plan.id()).stream()
                .map(o -> {
                    List<ActualEvent> events =
                            eventsByOccupancy.getOrDefault(o.id(), List.of());
                    Instant enteredAt = events.stream()
                            .filter(e -> OP_ENTER.equals(e.eventType()))
                            .map(ActualEvent::serverUtc).findFirst().orElse(null);
                    Instant exitedAt = events.stream()
                            .filter(e -> OP_EXIT.equals(e.eventType()))
                            .map(ActualEvent::serverUtc).findFirst().orElse(null);
                    return new OccupancyActualView(o.seq(), o.trainNo(), o.sectionId(),
                            o.startUtc(), o.endUtc(), o.actualStatus().name(), enteredAt, exitedAt);
                })
                .toList();
        return new PlanActualResponse(plan.scheduleKey(), plan.status().name(), plan.version(),
                views);
    }

    /**
     * 查询指定区段当前的实际锁（INSIDE 占用），与计划占用查询分列。
     */
    public List<ActualLockView> getActualLocks(String sectionId) {
        return actualRepo.findInsideLocksBySection(sectionId).stream()
                .map(l -> new ActualLockView(l.scheduleKey(), l.seq(), l.trainNo(), l.sectionId(),
                        l.enteredAt()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private DayPlan lockPublishedPlan(String scheduleKey, int expectedVersion, String action) {
        DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可确认" + action + "，当前状态: " + plan.status());
        }
        if (expectedVersion != plan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedVersion=" + expectedVersion + " 与当前版本 " + plan.version() + " 不一致");
        }
        return plan;
    }

    private OccupancyActual lockOccupancy(long planId, String scheduleKey, int seq) {
        return actualRepo.findOccupancyActualForUpdate(planId, seq)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OCCUPANCY_NOT_FOUND",
                        "占用不存在: " + scheduleKey + "#" + seq));
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次完整响应；参数不一致抛 409。
     */
    private Optional<ActualMovementResponse> replayIfPresent(String opType, String requestId,
                                                             String hash) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：同键同参返回首次结果，否则 409，不泄漏唯一键异常。
     */
    private ActualMovementResponse resolveDuplicate(String opType, String requestId, String hash) {
        return replayIfPresent(opType, requestId, hash)
                .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId));
    }

    /**
     * 结构化参数摘要：操作、资源（计划+占用号）、全部参数与期望版本；文本原样拼接。
     */
    private String hashActual(String opType, String scheduleKey, int seq, ActualActionRequest req) {
        return sha256(opType + '\n' + scheduleKey + '\n' + seq + '\n' + req.operator()
                + '\n' + req.expectedVersion());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
