package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformCapabilityHistory;
import com.example.starter.plan.model.PlatformOccupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PlatformRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CandidatePlatformView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.IncompatibilityView;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlatformCapabilityView;
import com.example.starter.plan.web.dto.PlatformOccupancyView;
import com.example.starter.plan.web.dto.PlatformRegistrationRequest;
import com.example.starter.plan.web.dto.PlatformView;
import com.example.starter.plan.web.dto.PlatformWindowConflictView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询，
 * 以及编组到发站台的能力适配、UTC 占用窗口裁决与站台能力版本管理。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与改签经全局发布锁串行化，发布哈希覆盖占用快照（含编组三要素与
 * UTC 时段），改编组或时段后同一 requestKey 重放即判定 409；仅成功结果写入幂等记录，
 * 失败（含 422 适配不通过/窗口重叠）不缓存、不留下部分占位、可修正后重试。
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
    private static final String OP_PLATFORM_REGISTER = "PLAT_REGISTER";

    private final PlanRepository planRepo;
    private final PlatformRepository platformRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, PlatformRepository platformRepo,
                       IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.platformRepo = platformRepo;
        this.idemRepo = idemRepo;
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
                long now = System.currentTimeMillis();
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
                long now = System.currentTimeMillis();
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段重叠以及每条编组到发占用的
     * 站台能力适配（长度/供电/无障碍）与同站台 UTC 窗口重叠。任一冲突则整张计划保持草稿、
     * 不写入任何站台占用并抛出 422（携带全部冲突明细）；全部通过才翻转状态并为站台占用
     * 固化当时站台能力版本。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        // 事务外按当前占用快照先做幂等重放：已发布计划的同键重放在此直接返回首次结果，
        // 不会落到锁内的“仅草稿可发布”状态校验而误报 409。
        DayPlan current = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        String hash = hashPublish(scheduleKey, planRepo.findOccupancies(current.id()));
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
                // 锁内按最终占用快照再裁决一次，覆盖预读后的并发改写
                String lockedHash = hashPublish(scheduleKey, occupancies);
                Optional<PlanResponse> lockedReplay =
                        replayIfPresent(OP_PUBLISH, requestKey, lockedHash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                Map<String, Integer> capabilityVersions = new HashMap<>();
                collectPlatformConflicts(plan, occupancies, List.of(plan.id()), conflicts,
                        capabilityVersions);
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突或站台不适配，计划保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
                stampCapabilityVersions(occupancies, capabilityVersions);
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, lockedHash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolvePublishDuplicate(scheduleKey, requestKey);
        }
    }

    /**
     * 取消已发布计划：时隙与站台占用立即随状态释放，历史计划、占用与其能力版本保留不改写。
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
                long now = System.currentTimeMillis();
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
     * 原子改签：全局发布锁内先按新草稿的最终时段与编组完成全部校验（列车内部重叠、
     * 跨计划区段冲突、站台能力适配与同站台窗口冲突，仅排除旧计划与自身占用），
     * 通过后同一事务取消旧计划、发布新计划、盖章能力版本并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用、能力盖章与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        // 事务外先做幂等重放：改签成功后旧计划已取消，同键重放在此直接返回首次结果，
        // 不会落到锁内状态校验而误报 409。
        Optional<DayPlan> preOld = planRepo.findByKey(oldScheduleKey);
        Optional<DayPlan> preNew = planRepo.findByKey(req.newScheduleKey());
        if (idemRepo.find(OP_RESCHEDULE, req.requestKey()).isPresent()) {
            // 该 requestKey 已被一次成功改签占用：两计划均在则按哈希裁决重放/409；
            // 任一计划缺失说明参数必然不同，直接 409（优先于计划不存在的 404）。
            if (preOld.isPresent() && preNew.isPresent()) {
                String preHash = hashReschedule(oldScheduleKey, req,
                        planRepo.findOccupancies(preNew.get().id()));
                return replayIfPresent(OP_RESCHEDULE, req.requestKey(), preHash,
                        RescheduleResponse.class)
                        .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                                "requestKey 已用于其他参数: " + req.requestKey()));
            }
            throw conflict("IDEMPOTENT_KEY_REUSED",
                    "requestKey 已用于其他参数: " + req.requestKey());
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
                String hash = hashReschedule(oldScheduleKey, req, occupancies);
                Optional<RescheduleResponse> replay =
                        replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
                if (replay.isPresent()) {
                    return replay.get();
                }
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                Map<String, Integer> capabilityVersions = new HashMap<>();
                collectPlatformConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id()), conflicts, capabilityVersions);
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突或站台不适配，改签未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                stampCapabilityVersions(occupancies, capabilityVersions);
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            DayPlan newPlan = planRepo.findByKey(req.newScheduleKey())
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
            String hash = hashReschedule(oldScheduleKey, req,
                    planRepo.findOccupancies(newPlan.id()));
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
     * 按计划业务键查询明细（含历史占用与其发布时固化的站台能力版本），不存在返回 404。
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
     * 站台管理方登记/变更站台能力（按路径中的站台业务键）。首次登记产生版本 1 与首条历史；
     * 能力（名称/长度上限/供电集合/无障碍）任一变化则版本加一并追加不可变历史快照，
     * 完全相同则幂等无变更。同 requestKey 同参重放返回首次结果，不同参返回 409。
     */
    public PlatformView registerPlatform(String platformId, PlatformRegistrationRequest req) {
        String normalizedPowerSystems =
                PlatformRepository.normalizePowerSystems(req.powerSystems());
        String hash = hashPlatform(platformId, req, normalizedPowerSystems);
        Optional<PlatformView> replay =
                replayIfPresent(OP_PLATFORM_REGISTER, req.requestKey(), hash, PlatformView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                Optional<Platform> current = platformRepo.findByIdForUpdate(platformId);
                Platform result;
                if (current.isEmpty()) {
                    result = platformRepo.insert(platformId, req.platformName(),
                            req.maxLengthMeters(), normalizedPowerSystems, req.accessible(), now);
                } else if (sameCapability(current.get(), req, normalizedPowerSystems)) {
                    // 能力无变化的重复登记：不升版本、不写历史
                    result = current.get();
                } else {
                    result = platformRepo.appendVersion(current.get(), req.platformName(),
                            req.maxLengthMeters(), normalizedPowerSystems, req.accessible(), now);
                }
                PlatformView view = toPlatformView(result);
                idemRepo.insert(OP_PLATFORM_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PLATFORM_REGISTER, req.requestKey(), hash, PlatformView.class)
                    .orElseThrow(() -> conflict("PLATFORM_REGISTER_CONFLICT",
                            "站台能力登记并发冲突: " + platformId));
        }
    }

    /**
     * 查询站台当前能力，站台未登记返回 404。
     */
    public PlatformView getPlatform(String platformId) {
        return toPlatformView(platformRepo.findById(platformId)
                .orElseThrow(() -> platformNotFound(platformId)));
    }

    /**
     * 查询站台能力的指定历史版本（回溯历史发布当时能力），站台或版本不存在返回 404。
     */
    public PlatformCapabilityView getPlatformCapabilityVersion(String platformId, int version) {
        if (platformRepo.findById(platformId).isEmpty()) {
            throw platformNotFound(platformId);
        }
        PlatformCapabilityHistory history = platformRepo.findHistoryVersion(platformId, version)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "PLATFORM_CAPABILITY_VERSION_NOT_FOUND",
                        "站台能力版本不存在: " + platformId + " v" + version));
        return new PlatformCapabilityView(history.platformId(), history.version(),
                history.maxLengthMeters(), history.powerSystems(), history.accessible());
    }

    /**
     * 站台管理方查询指定站台当前生效（已发布、未取消）的全部到发占用，按开始时刻升序。
     * 站台未登记返回 404。
     */
    public List<PlatformOccupancyView> getPlatformOccupancies(String platformId) {
        if (platformRepo.findById(platformId).isEmpty()) {
            throw platformNotFound(platformId);
        }
        return planRepo.findAllPlatformOccupancies(platformId).stream()
                .map(this::toPlatformOccupancyView)
                .toList();
    }

    /**
     * 调度员候选站台查询：按编组需求（长度米、供电制式、无障碍）与 UTC 窗口 [startUtc, endUtc)
     * 枚举全部站台，返回每个站台当前能力版本、逐条不适配原因与窗口重叠占用，
     * available=TRUE 即能力适配且窗口空闲。指定 platformId 时仅评估该站台，
     * 站台未登记也返回一条 PLATFORM_NOT_FOUND 原因记录。
     */
    public List<CandidatePlatformView> findCandidates(String platformId, int consistLengthMeters,
                                                      String powerSystem,
                                                      boolean accessibleRequired,
                                                      Instant startUtc, Instant endUtc) {
        if (consistLengthMeters <= 0) {
            throw badRequest("编组长度必须为正整数（米）");
        }
        if (powerSystem == null || powerSystem.isBlank()) {
            throw badRequest("供电制式不能为空");
        }
        if (!endUtc.isAfter(startUtc)) {
            throw badRequest("窗口结束时刻必须晚于开始时刻");
        }
        boolean specificPlatform = platformId != null && !platformId.isBlank();
        List<Platform> platforms;
        if (!specificPlatform) {
            platforms = platformRepo.findAll();
        } else {
            platforms = platformRepo.findById(platformId)
                    .map(List::of)
                    .orElse(List.of());
        }
        List<CandidatePlatformView> views = new ArrayList<>();
        for (Platform platform : platforms) {
            views.add(buildCandidate(platform, consistLengthMeters, powerSystem.trim(),
                    accessibleRequired, startUtc, endUtc));
        }
        if (specificPlatform && platforms.isEmpty()) {
            views.add(buildMissingCandidate(platformId, consistLengthMeters, powerSystem.trim(),
                    accessibleRequired, startUtc, endUtc));
        }
        return views;
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc(),
                        o.platformId(), o.consistLengthMeters(), o.powerSystem(),
                        o.accessibleRequired(), o.capabilityVersion()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            String platformId = r.platformId() == null || r.platformId().isBlank()
                    ? null : r.platformId().trim();
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(),
                    r.endUtc(), platformId, r.consistLengthMeters(),
                    r.powerSystem() == null ? null : r.powerSystem().trim(),
                    r.accessibleRequired(), null));
        }
        return result;
    }

    /**
     * 参数级校验：结束必须晚于开始，起止落在同一 Asia/Shanghai 日历日内；
     * 站台到发占用的站台键与编组三要素必须同时提供（全有或全无）。
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
            boolean hasPlatform = o.platformId() != null && !o.platformId().isBlank();
            boolean hasConsist = o.consistLengthMeters() != null && o.consistLengthMeters() > 0;
            boolean hasPower = o.powerSystem() != null && !o.powerSystem().isBlank();
            boolean hasAccessible = o.accessibleRequired() != null;
            if (hasPlatform != (hasConsist && hasPower && hasAccessible)
                    || (!hasPlatform && (hasConsist || hasPower || hasAccessible))) {
                throw badRequest("第 " + i + " 条站台到发占用必须同时提供站台与编组长度、供电制式、"
                        + "无障碍需求；普通区段占用则四项均须为空");
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
     * 收集全部编组到发站台占用的不适配与窗口冲突明细，并记录各站台本次可用的当前能力版本。
     * 窗口检测覆盖：本计划内同站台占用两两重叠、与其他已发布计划同站台占用重叠
     * （左闭右开，相邻合法；excludePlanIds 中的计划不参与裁决）。
     */
    private void collectPlatformConflicts(DayPlan plan, List<Occupancy> occupancies,
                                          Collection<Long> excludePlanIds,
                                          List<Map<String, Object>> conflicts,
                                          Map<String, Integer> capabilityVersions) {
        List<Occupancy> platformOccs = occupancies.stream()
                .filter(Occupancy::platformOccupancy)
                .toList();
        // 本计划内同站台窗口重叠
        for (int i = 0; i < platformOccs.size(); i++) {
            for (int j = i + 1; j < platformOccs.size(); j++) {
                Occupancy a = platformOccs.get(i);
                Occupancy b = platformOccs.get(j);
                if (a.platformId().equals(b.platformId())
                        && a.startUtc().isBefore(b.endUtc())
                        && b.startUtc().isBefore(a.endUtc())) {
                    Map<String, Object> detail = basePlatformDetail(
                            "PLATFORM_WINDOW_CONFLICT", plan.scheduleKey(), a);
                    detail.put("conflictingScheduleKey", plan.scheduleKey());
                    detail.put("conflictingTrainNo", b.trainNo());
                    detail.put("conflictingStartUtc", b.startUtc().toString());
                    detail.put("conflictingEndUtc", b.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        for (Occupancy o : platformOccs) {
            Platform platform = platformRepo.findById(o.platformId()).orElse(null);
            List<IncompatibilityView> reasons = PlatformCompatibility.evaluate(platform,
                    o.consistLengthMeters(), o.powerSystem(), o.accessibleRequired());
            for (IncompatibilityView reason : reasons) {
                Map<String, Object> detail = basePlatformDetail(
                        "PLATFORM_INCOMPATIBLE", plan.scheduleKey(), o);
                detail.put("reasonCode", reason.reasonCode());
                detail.put("reasonMessage", reason.message());
                conflicts.add(detail);
            }
            // 与其他已发布计划的同站台窗口重叠
            List<PlatformOccupancy> active = planRepo.findPlatformOccupancies(
                    o.platformId(), excludePlanIds, o.startUtc(), o.endUtc());
            for (PlatformOccupancy other : active) {
                Map<String, Object> detail = basePlatformDetail(
                        "PLATFORM_WINDOW_CONFLICT", plan.scheduleKey(), o);
                detail.put("conflictingScheduleKey", other.scheduleKey());
                detail.put("conflictingTrainNo", other.trainNo());
                detail.put("conflictingStartUtc", other.startUtc().toString());
                detail.put("conflictingEndUtc", other.endUtc().toString());
                conflicts.add(detail);
            }
            if (platform != null && reasons.isEmpty() && active.isEmpty()) {
                capabilityVersions.put(platform.platformId(), platform.version());
            }
        }
    }

    private Map<String, Object> basePlatformDetail(String type, String scheduleKey, Occupancy o) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("scheduleKey", scheduleKey);
        detail.put("platformId", o.platformId());
        detail.put("trainNo", o.trainNo());
        detail.put("seq", o.seq());
        detail.put("sectionId", o.sectionId());
        detail.put("startUtc", o.startUtc().toString());
        detail.put("endUtc", o.endUtc().toString());
        return detail;
    }

    /**
     * 发布/改签成功后为站台占用固化当时能力版本；同一站台多条占用盖同一版本。
     */
    private void stampCapabilityVersions(List<Occupancy> occupancies,
                                         Map<String, Integer> capabilityVersions) {
        for (Occupancy o : occupancies) {
            if (!o.platformOccupancy()) {
                continue;
            }
            Integer version = capabilityVersions.get(o.platformId());
            if (version != null) {
                planRepo.stampCapabilityVersion(o.id(), version);
            }
        }
    }

    private boolean sameCapability(Platform current, PlatformRegistrationRequest req,
                                   String normalizedPowerSystems) {
        return current.platformName().equals(req.platformName())
                && current.maxLengthMeters() == req.maxLengthMeters()
                && PlatformRepository.normalizePowerSystems(current.powerSystems())
                .equals(normalizedPowerSystems)
                && current.accessible() == req.accessible();
    }

    private PlatformView toPlatformView(Platform platform) {
        return new PlatformView(platform.platformId(), platform.platformName(),
                platform.maxLengthMeters(), platform.powerSystems(), platform.accessible(),
                platform.version(), Instant.ofEpochMilli(platform.updatedAt()));
    }

    private PlatformOccupancyView toPlatformOccupancyView(PlatformOccupancy o) {
        return new PlatformOccupancyView(o.platformId(), o.scheduleKey(), o.trainNo(),
                o.sectionId(), o.startUtc(), o.endUtc(), o.consistLengthMeters(),
                o.powerSystem(), o.accessibleRequired(), o.capabilityVersion());
    }

    private CandidatePlatformView buildCandidate(Platform platform, int consistLengthMeters,
                                                 String powerSystem, boolean accessibleRequired,
                                                 Instant startUtc, Instant endUtc) {
        List<IncompatibilityView> reasons = PlatformCompatibility.evaluate(platform,
                consistLengthMeters, powerSystem, accessibleRequired);
        List<PlatformWindowConflictView> windowConflicts = reasons.isEmpty()
                ? planRepo.findPlatformOccupancies(platform.platformId(), List.of(),
                        startUtc, endUtc).stream()
                        .map(o -> new PlatformWindowConflictView(o.scheduleKey(), o.trainNo(),
                                o.startUtc(), o.endUtc()))
                        .toList()
                : List.of();
        boolean available = reasons.isEmpty() && windowConflicts.isEmpty();
        return new CandidatePlatformView(platform.platformId(), platform.platformName(),
                platform.maxLengthMeters(), platform.powerSystems(), platform.accessible(),
                platform.version(), reasons.isEmpty(), reasons, windowConflicts, available);
    }

    private CandidatePlatformView buildMissingCandidate(String platformId, int consistLengthMeters,
                                                        String powerSystem,
                                                        boolean accessibleRequired,
                                                        Instant startUtc, Instant endUtc) {
        List<IncompatibilityView> reasons = PlatformCompatibility.evaluate(null,
                consistLengthMeters, powerSystem, accessibleRequired);
        return new CandidatePlatformView(platformId, null, 0, List.of(), false, 0, false,
                reasons, List.of(), false);
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

    /**
     * 并发发布撞幂等唯一键后，按当前占用快照重算哈希再裁决同键重放/参数冲突。
     */
    private PlanResponse resolvePublishDuplicate(String scheduleKey, String requestKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        String hash = hashPublish(scheduleKey, planRepo.findOccupancies(plan.id()));
        return replayIfPresent(OP_PUBLISH, requestKey, hash)
                .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey));
    }

    private String hashCreate(CreatePlanRequest req) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate());
        appendOccupancyRequests(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        StringBuilder sb = new StringBuilder(OP_UPDATE).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion());
        appendOccupancyRequests(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String scheduleKey) {
        return sha256(opType + '\n' + scheduleKey);
    }

    /**
     * 发布哈希覆盖计划全部占用快照（区段、UTC 时段与编组三要素），
     * 同一 requestKey 下改编组或时段即视为不同参数返回 409。
     */
    private String hashPublish(String scheduleKey, List<Occupancy> occupancies) {
        StringBuilder sb = new StringBuilder(OP_PUBLISH).append('\n').append(scheduleKey);
        appendOccupancyRows(sb, occupancies);
        return sha256(sb.toString());
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req,
                                  List<Occupancy> newOccupancies) {
        StringBuilder sb = new StringBuilder(OP_RESCHEDULE).append('\n')
                .append(oldScheduleKey).append('\n').append(req.newScheduleKey())
                .append('\n').append(req.expectedOldVersion())
                .append('\n').append(req.expectedNewVersion());
        appendOccupancyRows(sb, newOccupancies);
        return sha256(sb.toString());
    }

    private String hashPlatform(String platformId, PlatformRegistrationRequest req,
                                String normalizedPowerSystems) {
        return sha256(OP_PLATFORM_REGISTER + '\n' + platformId + '\n' + req.platformName()
                + '\n' + req.maxLengthMeters() + '\n' + normalizedPowerSystems
                + '\n' + req.accessible());
    }

    private void appendOccupancyRequests(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|')
                    .append(o.endUtc().toEpochMilli());
            if (o.platformId() != null && !o.platformId().isBlank()) {
                sb.append('|').append(o.platformId().trim()).append('|')
                        .append(o.consistLengthMeters()).append('|')
                        .append(o.powerSystem().trim()).append('|')
                        .append(o.accessibleRequired());
            }
        }
    }

    private void appendOccupancyRows(StringBuilder sb, List<Occupancy> occupancies) {
        for (Occupancy o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|')
                    .append(o.endUtc().toEpochMilli());
            if (o.platformOccupancy()) {
                sb.append('|').append(o.platformId()).append('|')
                        .append(o.consistLengthMeters()).append('|')
                        .append(o.powerSystem()).append('|')
                        .append(o.accessibleRequired());
            }
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

    private ApiException platformNotFound(String platformId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND",
                "站台不存在: " + platformId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
