package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformOccupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.Consist;
import com.example.starter.plan.model.RiskSnapshot;
import com.example.starter.plan.repo.ConsistRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PlatformRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.AdjustPlatformRequest;
import com.example.starter.plan.web.dto.BatchPublishRequest;
import com.example.starter.plan.web.dto.BatchPublishResponse;
import com.example.starter.plan.web.dto.ConsistView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlatformAdjustResponse;
import com.example.starter.plan.web.dto.PlatformOccupancyView;
import com.example.starter.plan.web.dto.PlatformView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterConsistRequest;
import com.example.starter.plan.web.dto.RegisterPlatformRequest;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.RiskSnapshotView;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与改签经全局发布锁串行化，同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_CONSIST = "CONSIST";
    private static final String OP_PLATFORM_REGISTER = "PLATFORM_REGISTER";
    private static final String OP_PLATFORM_ADJUST = "PLATFORM_ADJUST";
    private static final String OP_BATCH_PUBLISH = "BATCH_PUBLISH";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final PlatformRepository platformRepo;
    private final ConsistRepository consistRepo;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       PlatformRepository platformRepo, ConsistRepository consistRepo,
                       TimeService timeService, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.platformRepo = platformRepo;
        this.consistRepo = consistRepo;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = hashCreate(req);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = timeService.millis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT, now);
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.occupancies()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用清单，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        validateOccupancyParams(req.occupancies());
        String hash = hashUpdate(scheduleKey, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改占用，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());
                long now = timeService.millis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_UPDATE, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突区段与计划）。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_PUBLISH, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                // 站台联合复核：编组长度不得超过停靠站台有效长度，同站台占用不得重叠
                conflicts.addAll(validatePlatforms(List.of(plan), List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                long now = timeService.millis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与占用保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_CANCEL, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = timeService.millis();
                planRepo.updateStatus(plan.id(), PlanStatus.CANCELLED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CANCEL, requestKey, hash);
        }
    }

    /**
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠与跨计划区段冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String hash = hashReschedule(oldScheduleKey, req);
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                if (oldPlan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
                }
                if (oldPlan.platformRisk()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLATFORM_RISK_OPEN",
                            "旧计划存在未消除的 PLATFORM_RISK，须先替换合格站台或缩短编组",
                            List.of(Map.of("type", "PLATFORM_RISK_OPEN",
                                    "scheduleKey", oldScheduleKey)));
                }
                if (newPlan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "改签新计划必须为草稿，当前状态: " + newPlan.status());
                }
                if (req.expectedOldVersion() != oldPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedOldVersion=" + req.expectedOldVersion()
                                    + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
                }
                if (req.expectedNewVersion() != newPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedNewVersion=" + req.expectedNewVersion()
                                    + " 与新计划当前版本 " + newPlan.version() + " 不一致");
                }
                if (!oldPlan.opDate().equals(newPlan.opDate())) {
                    throw conflict("OP_DATE_MISMATCH",
                            "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
                }
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                // 站台联合复核：新草稿最终编组与最终站台占用（排除新旧计划自身占用）
                conflicts.addAll(validatePlatforms(List.of(newPlan),
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                long now = timeService.millis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
        }
    }

    /**
     * 查询包含指定计划在内的完整有序改签链（从最前驱到最后继）；
     * 无改签历史的计划链中仅含自身。计划不存在返回 404。
     */
    public RescheduleChainResponse getRescheduleChain(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<DayPlan> predecessors = new ArrayList<>();
        DayPlan cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkBySuccessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链前驱缺失: " + link.get()));
            predecessors.add(cursor);
        }
        Collections.reverse(predecessors);
        List<DayPlan> chain = new ArrayList<>(predecessors);
        chain.add(plan);
        cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkByPredecessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链后继缺失: " + link.get()));
            chain.add(cursor);
        }
        List<RescheduleChainItem> items = chain.stream()
                .map(p -> new RescheduleChainItem(p.scheduleKey(), p.opDate(), p.version(),
                        p.status().name()))
                .toList();
        return new RescheduleChainResponse(scheduleKey, items);
    }

    /**
     * 按计划业务键查询明细（含历史占用），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    /**
     * 登记停靠站台（版本 1，有效长度单位米，必须为正）。
     */
    public PlatformView registerPlatform(RegisterPlatformRequest req) {
        String hash = hashPlatformRegister(req);
        Optional<PlatformView> replay =
                replayIfPresent(OP_PLATFORM_REGISTER, req.requestKey(), hash, PlatformView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = timeService.millis();
                platformRepo.insertPlatform(req.platformCode(), req.effectiveLength(), now);
                PlatformView response = new PlatformView(req.platformCode(),
                        req.effectiveLength(), 1);
                idemRepo.insert(OP_PLATFORM_REGISTER, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PLATFORM_REGISTER, req.requestKey(), hash, PlatformView.class)
                    .orElseThrow(() -> conflict("PLATFORM_CODE_EXISTS",
                            "站台代码已存在: " + req.platformCode()));
        }
    }

    /**
     * 查询站台当前有效长度与版本，不存在返回 404。
     */
    public PlatformView getPlatform(String platformCode) {
        Platform platform = platformRepo.findByCode(platformCode)
                .orElseThrow(() -> platformNotFound(platformCode));
        return new PlatformView(platform.platformCode(), platform.effectiveLength(),
                platform.version());
    }

    /**
     * 调整站台有效长度（expectedVersion 乐观校验）。下调时在同一事务回查当日及以后
     * 已发布且未带风险标记的计划：编组超长则标记 PLATFORM_RISK 并固化下调前原长度快照，
     * 不自动取消、不重写历史发布；不下调则无受影响计划。
     */
    public PlatformAdjustResponse adjustPlatform(String platformCode, AdjustPlatformRequest req) {
        String hash = hashPlatformAdjust(platformCode, req);
        Optional<PlatformAdjustResponse> replay =
                replayIfPresent(OP_PLATFORM_ADJUST, req.requestKey(), hash,
                        PlatformAdjustResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 与发布/批量/改签共用全局锁，保证长度调整与发布按事务提交顺序裁决
                planRepo.acquirePublishLock();
                Platform platform = platformRepo.findByCodeForUpdate(platformCode)
                        .orElseThrow(() -> platformNotFound(platformCode));
                if (req.expectedVersion() != platform.version()) {
                    // 并发下同键请求后提交时版本已前进：若为同键同参重放返回首次结果
                    Optional<PlatformAdjustResponse> raced = replayIfPresent(
                            OP_PLATFORM_ADJUST, req.requestKey(), hash,
                            PlatformAdjustResponse.class);
                    if (raced.isPresent()) {
                        return raced.get();
                    }
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与站台当前版本 "
                                    + platform.version() + " 不一致");
                }
                if (req.effectiveLength() == platform.effectiveLength()) {
                    throw conflict("PLATFORM_LENGTH_UNCHANGED",
                            "新有效长度与当前长度相同: " + platform.effectiveLength());
                }
                long now = timeService.millis();
                platformRepo.updateLength(platform.id(), req.effectiveLength(),
                        platform.version() + 1, now);
                List<RiskSnapshotView> affected = new ArrayList<>();
                if (req.effectiveLength() < platform.effectiveLength()) {
                    LocalDate today = timeService.today(OPERATION_ZONE);
                    List<DayPlan> futures = platformRepo.findFuturePublishedPlansForUpdate(
                            today, List.of(platformCode));
                    for (DayPlan plan : futures) {
                        Consist consist = consistRepo.findConsist(plan.id()).orElse(null);
                        if (consist == null
                                || consist.trainLength() <= req.effectiveLength()) {
                            continue;
                        }
                        planRepo.updatePlatformRisk(plan.id(), true, now);
                        consistRepo.insertRiskSnapshot(plan.id(), platformCode,
                                consist.trainLength(), platform.effectiveLength(),
                                plan.version(), now);
                        affected.add(new RiskSnapshotView(plan.scheduleKey(), platformCode,
                                consist.trainLength(), platform.effectiveLength(),
                                plan.version()));
                    }
                }
                PlatformAdjustResponse response = new PlatformAdjustResponse(
                        new PlatformView(platformCode, req.effectiveLength(),
                                platform.version() + 1),
                        affected);
                idemRepo.insert(OP_PLATFORM_ADJUST, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PLATFORM_ADJUST, req.requestKey(), hash,
                    PlatformAdjustResponse.class)
                    .orElseThrow(() -> conflict("PLATFORM_RISK_CONFLICT",
                            "风险快照冲突，计划已被标记: " + platformCode));
        }
    }

    /**
     * 登记/替换计划编组：车厢按编号去重并规范化升序，长度必须为正、站台必须存在，
     * expectedVersion 必须匹配，已取消计划不可改。已发布的风险计划只能替换为合格站台或
     * 缩短编组：整改合格后清除 PLATFORM_RISK 与快照；未达标保持风险并返回 422。
     */
    public PlanResponse registerConsist(String scheduleKey, RegisterConsistRequest req) {
        List<String> cars = normalizeCars(req.cars());
        String hash = hashConsist(scheduleKey, req, cars);
        Optional<PlanResponse> replay = replayIfPresent(OP_CONSIST, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() == PlanStatus.CANCELLED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "已取消计划不可登记编组，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    // 并发下同键请求后提交时版本已前进：若为同键同参重放返回首次结果
                    Optional<PlanResponse> raced =
                            replayIfPresent(OP_CONSIST, req.requestKey(), hash);
                    if (raced.isPresent()) {
                        return raced.get();
                    }
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                    + plan.version() + " 不一致");
                }
                Platform platform = platformRepo.findByCode(req.platformCode())
                        .orElseThrow(() -> platformNotFound(req.platformCode()));
                if (plan.status() == PlanStatus.PUBLISHED
                        && req.trainLength() > platform.effectiveLength()) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "PLATFORM_TOO_LONG");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("platformCode", req.platformCode());
                    detail.put("trainLength", req.trainLength());
                    detail.put("platformLength", platform.effectiveLength());
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLATFORM_CONFLICT",
                            "风险计划只能替换为合格站台或缩短编组", List.of(detail));
                }
                if (plan.status() == PlanStatus.PUBLISHED) {
                    // 已发布计划换站台会移动停靠占用：按新站台与其他已发布计划逐区间校验重叠
                    List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                    Instant dayStart = plan.opDate().atStartOfDay(OPERATION_ZONE).toInstant();
                    Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE)
                            .toInstant();
                    List<PlatformOccupancy> published = platformRepo.findPlatformOccupancies(
                            plan.opDate(), List.of(req.platformCode()), List.of(plan.id()),
                            dayStart, dayEnd);
                    List<Map<String, Object>> overlaps = new ArrayList<>();
                    for (PlatformOccupancy other : published) {
                        for (Occupancy o : occupancies) {
                            if (o.startUtc().isBefore(other.endUtc())
                                    && other.startUtc().isBefore(o.endUtc())) {
                                Map<String, Object> d = new LinkedHashMap<>();
                                d.put("type", "PLATFORM_OVERLAP");
                                d.put("platformCode", req.platformCode());
                                d.put("scheduleKey", scheduleKey);
                                d.put("conflictingScheduleKey", other.scheduleKey());
                                d.put("sectionId", o.sectionId());
                                overlaps.add(d);
                            }
                        }
                    }
                    if (!overlaps.isEmpty()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "PLATFORM_CONFLICT", "新站台与其他已发布计划停靠重叠", overlaps);
                    }
                }
                long now = timeService.millis();
                int newVersion = plan.version() + 1;
                consistRepo.replaceConsist(plan.id(), newVersion, req.trainLength(),
                        req.platformCode(), req.operator(), cars, now);
                planRepo.updateVersionAndStatus(plan.id(), newVersion, plan.status(), now);
                if (plan.platformRisk() && req.trainLength() <= platform.effectiveLength()) {
                    planRepo.updatePlatformRisk(plan.id(), false, now);
                    consistRepo.deleteRiskSnapshot(plan.id());
                }
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CONSIST, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CONSIST, req.requestKey(), hash);
        }
    }

    /**
     * 批量发布：全局发布锁内按最终站台占用和最终编组联合裁决多个计划，
     * 同时保留既有列车内/跨计划区段时隙校验。任一超长、同站台重叠或时隙冲突即 422，
     * details 列出全部计划与站台，整批不写入。
     */
    public BatchPublishResponse batchPublish(BatchPublishRequest req) {
        List<String> duplicateKeys = req.scheduleKeys().stream()
                .collect(java.util.stream.Collectors.groupingBy(k -> k, LinkedHashMap::new,
                        java.util.stream.Collectors.counting())).entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!duplicateKeys.isEmpty()) {
            throw badRequest("批量发布内计划键重复: " + duplicateKeys);
        }
        String hash = hashBatch(req);
        Optional<BatchPublishResponse> replay =
                replayIfPresent(OP_BATCH_PUBLISH, req.requestKey(), hash,
                        BatchPublishResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                List<DayPlan> plans = planRepo.findByKeysForUpdate(req.scheduleKeys());
                if (plans.size() != req.scheduleKeys().size()) {
                    List<String> found = plans.stream().map(DayPlan::scheduleKey).toList();
                    List<String> missing = req.scheduleKeys().stream()
                            .filter(k -> !found.contains(k)).toList();
                    throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                            "批量内存在不存在的计划: " + missing,
                            missing.stream().map(k -> {
                                Map<String, Object> d = new LinkedHashMap<>();
                                d.put("type", "PLAN_NOT_FOUND");
                                d.put("scheduleKey", k);
                                return d;
                            }).toList());
                }
                // 保持请求顺序
                Map<String, DayPlan> byKey = new LinkedHashMap<>();
                plans.forEach(p -> byKey.put(p.scheduleKey(), p));
                List<DayPlan> ordered = req.scheduleKeys().stream().map(byKey::get).toList();
                List<Long> batchIds = ordered.stream().map(DayPlan::id).toList();

                List<Map<String, Object>> slotConflicts = new ArrayList<>();
                for (DayPlan plan : ordered) {
                    if (plan.status() != PlanStatus.DRAFT) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("type", "PLAN_STATE_CONFLICT");
                        d.put("scheduleKey", plan.scheduleKey());
                        d.put("status", plan.status().name());
                        slotConflicts.add(d);
                        continue;
                    }
                    List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                    slotConflicts.addAll(findTrainOverlaps(plan.scheduleKey(), occupancies));
                    slotConflicts.addAll(findSectionConflicts(plan, occupancies, batchIds));
                }
                List<Map<String, Object>> platformConflicts =
                        validatePlatforms(ordered, batchIds);

                if (!slotConflicts.isEmpty() || !platformConflicts.isEmpty()) {
                    List<Map<String, Object>> all = new ArrayList<>();
                    all.addAll(slotConflicts);
                    all.addAll(platformConflicts);
                    String code = slotConflicts.isEmpty() ? "PLATFORM_CONFLICT" : "SLOT_CONFLICT";
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                            "批量发布联合校验失败，整批不写入", all);
                }

                long now = timeService.millis();
                for (DayPlan plan : ordered) {
                    planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                }
                List<PlanResponse> responses = req.scheduleKeys().stream()
                        .map(this::loadPlan).toList();
                BatchPublishResponse response = new BatchPublishResponse(responses);
                idemRepo.insert(OP_BATCH_PUBLISH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_PUBLISH, req.requestKey(), hash,
                    BatchPublishResponse.class)
                    .orElseThrow(() -> conflict("BATCH_PUBLISH_CONFLICT", "批量发布幂等冲突"));
        }
    }

    /**
     * 查询指定运营日某站台当前已发布计划的停靠占用时段（按开始时刻升序）。
     */
    public List<PlatformOccupancyView> getPlatformOccupancies(LocalDate opDate,
                                                               String platformCode) {
        if (platformRepo.findByCode(platformCode).isEmpty()) {
            throw platformNotFound(platformCode);
        }
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        return platformRepo.findOccupancyByPlatform(opDate, platformCode, dayStart, dayEnd)
                .stream()
                .map(o -> new PlatformOccupancyView(o.scheduleKey(), o.platformCode(),
                        o.startUtc(), o.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        ConsistView consistView = consistRepo.findConsist(plan.id())
                .map(c -> new ConsistView(c.version(), c.trainLength(), c.platformCode(),
                        c.operator(), c.cars()))
                .orElse(null);
        RiskSnapshotView riskView = consistRepo.findRiskSnapshot(plan.id())
                .map(s -> new RiskSnapshotView(scheduleKey, s.platformCode(), s.trainLength(),
                        s.platformLengthSnapshot(), s.planVersion()))
                .orElse(null);
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.platformRisk(), views, consistView, riskView);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    /**
     * 参数级校验：结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
            }
            LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
            LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
            if (!startDay.equals(endDay)) {
                throw badRequest("第 " + i + " 条占用必须落在同一运营日（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 占用必须落在计划运营日内：[start, end) 完全包含于运营日 [dayStart, dayEnd)。
     */
    private void validateWithinOperationDay(List<OccupancyRequest> occupancies, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                throw badRequest("第 " + i + " 条占用不在运营日 " + opDate + "（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(String scheduleKey, List<Occupancy> occupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> byTrain = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        byTrain.forEach((trainNo, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TRAIN_OVERLAP");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", trainNo);
                    detail.put("sectionId", cur.sectionId());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        return conflicts;
    }

    /**
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 车厢按编号去重并规范化升序（自然序）。
     */
    private List<String> normalizeCars(List<String> cars) {
        return cars.stream().collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    /**
     * 对给定计划集合按"最终编组 + 最终站台占用"做站台联合校验：
     * 编组长度不得超过站台有效长度；同站台时间占用两两不得重叠（左闭右开，相邻合法）。
     *
     * @param plans          最终状态计划（发布/批量为草稿，改签为新草稿）
     * @param excludePlanIds 与库内已发布占用比对时排除的计划 id（批量/改签涉及的全部计划）
     * @return 冲突明细（type 区分 PLATFORM_TOO_LONG / PLATFORM_OVERLAP）
     */
    private List<Map<String, Object>> validatePlatforms(List<DayPlan> plans,
                                                        List<Long> excludePlanIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        // 计划 id -> 最终编组、最终站台、最终占用包络与占用清单
        Map<Long, Consist> consistById = new LinkedHashMap<>();
        Map<Long, List<Occupancy>> occupanciesById = new LinkedHashMap<>();
        for (DayPlan plan : plans) {
            Consist consist = consistRepo.findConsist(plan.id()).orElse(null);
            List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
            consistById.put(plan.id(), consist);
            occupanciesById.put(plan.id(), occupancies);
            if (consist == null) {
                continue;
            }
            Platform platform = platformRepo.findByCode(consist.platformCode()).orElse(null);
            if (platform == null) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", "PLATFORM_NOT_FOUND");
                d.put("scheduleKey", plan.scheduleKey());
                d.put("platformCode", consist.platformCode());
                conflicts.add(d);
                continue;
            }
            if (consist.trainLength() > platform.effectiveLength()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", "PLATFORM_TOO_LONG");
                d.put("scheduleKey", plan.scheduleKey());
                d.put("platformCode", consist.platformCode());
                d.put("trainLength", consist.trainLength());
                d.put("platformLength", platform.effectiveLength());
                conflicts.add(d);
            }
        }

        // 批次内同站台两两重叠（最终态，按区段占用逐区间判定，左闭右开相邻合法）
        List<DayPlan> withConsist = plans.stream()
                .filter(p -> consistById.get(p.id()) != null).toList();
        for (int i = 0; i < withConsist.size(); i++) {
            for (int j = i + 1; j < withConsist.size(); j++) {
                DayPlan a = withConsist.get(i);
                DayPlan b = withConsist.get(j);
                Consist ca = consistById.get(a.id());
                Consist cb = consistById.get(b.id());
                if (!ca.platformCode().equals(cb.platformCode())) {
                    continue;
                }
                Occupancy hit = firstOverlap(occupanciesById.get(a.id()),
                        occupanciesById.get(b.id()));
                if (hit != null) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("type", "PLATFORM_OVERLAP");
                    d.put("platformCode", ca.platformCode());
                    d.put("scheduleKey", a.scheduleKey());
                    d.put("conflictingScheduleKey", b.scheduleKey());
                    d.put("sectionId", hit.sectionId());
                    d.put("startUtc", hit.startUtc().toString());
                    d.put("endUtc", hit.endUtc().toString());
                    conflicts.add(d);
                }
            }
        }

        // 与库内其他已发布计划的最终站台占用按区间比对（运营日、站台、时段）
        for (DayPlan plan : withConsist) {
            Consist consist = consistById.get(plan.id());
            List<Occupancy> occupancies = occupanciesById.get(plan.id());
            Instant dayStart = plan.opDate().atStartOfDay(OPERATION_ZONE).toInstant();
            Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
            List<PlatformOccupancy> published = platformRepo.findPlatformOccupancies(
                    plan.opDate(), List.of(consist.platformCode()), excludePlanIds,
                    dayStart, dayEnd);
            for (PlatformOccupancy other : published) {
                for (Occupancy o : occupancies) {
                    if (o.startUtc().isBefore(other.endUtc())
                            && other.startUtc().isBefore(o.endUtc())) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("type", "PLATFORM_OVERLAP");
                        d.put("platformCode", consist.platformCode());
                        d.put("scheduleKey", plan.scheduleKey());
                        d.put("conflictingScheduleKey", other.scheduleKey());
                        d.put("sectionId", o.sectionId());
                        d.put("startUtc", o.startUtc().toString());
                        d.put("endUtc", o.endUtc().toString());
                        conflicts.add(d);
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * 两组占用区间是否存在重叠（左闭右开，相邻合法）；返回首个命中的左侧区间，无则 null。
     */
    private Occupancy firstOverlap(List<Occupancy> left, List<Occupancy> right) {
        for (Occupancy a : left) {
            for (Occupancy b : right) {
                if (a.startUtc().isBefore(b.endUtc()) && b.startUtc().isBefore(a.endUtc())) {
                    return a;
                }
            }
        }
        return null;
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class);
    }

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 scheduleKey 冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String hashCreate(CreatePlanRequest req) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        StringBuilder sb = new StringBuilder(OP_UPDATE).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String scheduleKey) {
        return sha256(opType + '\n' + scheduleKey);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return sha256(OP_RESCHEDULE + '\n' + oldScheduleKey + '\n' + req.newScheduleKey()
                + '\n' + req.expectedOldVersion() + '\n' + req.expectedNewVersion());
    }

    private String hashPlatformRegister(RegisterPlatformRequest req) {
        return sha256(OP_PLATFORM_REGISTER + '\n' + req.platformCode() + '\n'
                + req.effectiveLength());
    }

    private String hashPlatformAdjust(String platformCode, AdjustPlatformRequest req) {
        return sha256(OP_PLATFORM_ADJUST + '\n' + platformCode + '\n' + req.expectedVersion()
                + '\n' + req.effectiveLength());
    }

    private String hashConsist(String scheduleKey, RegisterConsistRequest req, List<String> cars) {
        StringBuilder sb = new StringBuilder(OP_CONSIST).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion()).append('\n')
                .append(req.trainLength()).append('\n').append(req.platformCode()).append('\n')
                .append(req.operator()).append('\n').append(String.join(",", cars));
        // 指纹含时段：追加计划版本对应的占用包络（计划不存在时省略，随后在事务内 404）
        planRepo.findByKey(scheduleKey).ifPresent(plan -> {
            List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
            if (!occupancies.isEmpty()) {
                long minStart = occupancies.stream().mapToLong(o -> o.startUtc().toEpochMilli())
                        .min().orElseThrow();
                long maxEnd = occupancies.stream().mapToLong(o -> o.endUtc().toEpochMilli())
                        .max().orElseThrow();
                sb.append('\n').append(minStart).append('|').append(maxEnd);
            }
        });
        return sha256(sb.toString());
    }

    private String hashBatch(BatchPublishRequest req) {
        return sha256(OP_BATCH_PUBLISH + '\n' + String.join(",", req.scheduleKeys()));
    }

    private void appendOccupancies(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
        }
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

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException platformNotFound(String platformCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND",
                "站台不存在: " + platformCode);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
