package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.NightPair;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanPairResponse;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishPairRequest;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签、
 * 夜间跨零点计划对联合发布/取消与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布、改签与计划对联合发布经全局发布锁串行化，
 * 同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 *
 * <p>夜间跨零点约定：声明 overnight 的草稿占用允许落在运营日 22:00 至次日 06:00
 * （Asia/Shanghai）窗口内，单条时长不超过 8 小时，区间左闭右开；
 * 夜间计划必须与次日草稿组成 nightPairKey 声明的计划对，一次联合发布，
 * 跨零点占用同时参与当日与次日的区段判定。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 夜间窗口起始时刻（运营日当地 22:00，含）。 */
    public static final int NIGHT_WINDOW_START_HOUR = 22;

    /** 夜间窗口结束时刻（次日当地 06:00，含上界，即占用 end 不得晚于该时刻）。 */
    public static final int NIGHT_WINDOW_END_HOUR = 6;

    /** 单条占用最大时长（8 小时），毫秒。 */
    public static final long MAX_OCCUPANCY_MILLIS = 8L * 60 * 60 * 1000;

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_PAIR_PUBLISH = "PAIR_PUBLISH";
    private static final String OP_PAIR_CANCEL = "PAIR_CANCEL";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。overnight 草稿必须声明 nightPairKey，
     * 占用须落在运营日 22:00 至次日 06:00 窗口内且单条不超过 8 小时。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        boolean overnight = Boolean.TRUE.equals(req.overnight());
        String nightPairKey = req.nightPairKey();
        if (nightPairKey != null && nightPairKey.isBlank()) {
            throw badRequest("nightPairKey 不能为空串");
        }
        if (overnight && nightPairKey == null) {
            throw badRequest("夜间（overnight）草稿必须声明 nightPairKey");
        }
        validateOccupancyParams(req.occupancies(), overnight);
        if (overnight) {
            validateWithinNightWindow(req.occupancies(), req.opDate());
        } else {
            validateWithinOperationDay(req.occupancies(), req.opDate());
        }
        String hash = hashCreate(req, overnight);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                if (nightPairKey != null && planRepo.countDraftsByNightPairKey(nightPairKey) >= 2) {
                    throw conflict("NIGHT_PAIR_KEY_CONFLICT",
                            "nightPairKey 已存在两张待发布草稿: " + nightPairKey);
                }
                long now = System.currentTimeMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT,
                        overnight, nightPairKey, now);
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
     * 占用按计划的 overnight 声明校验（夜间窗口或单一运营日）。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
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
                validateOccupancyParams(req.occupancies(), plan.overnight());
                if (plan.overnight()) {
                    validateWithinNightWindow(req.occupancies(), plan.opDate());
                } else {
                    validateWithinOperationDay(req.occupancies(), plan.opDate());
                }
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突区段与计划）。
     * 声明了 nightPairKey 的计划（含夜间计划）须通过计划对联合发布。
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
                if (plan.nightPairKey() != null) {
                    throw conflict("PAIR_PUBLISH_REQUIRED",
                            "声明了 nightPairKey 的计划须通过计划对联合发布: " + scheduleKey);
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(plan, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
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
     * 计划对成员取消仅释放其自身占用，另一张保持已发布。
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
     * 夜间计划对联合发布：全局发布锁内一个事务重查两张草稿的版本与状态、
     * 各自列车内部重叠、两个运营日上与其他已发布计划的区段重叠以及两张成员之间的重叠；
     * 跨零点占用按时刻交叠同时参与当日与次日判定，仅端点相接不算冲突。
     * 任一校验失败两张都保持草稿；成功则两张同时发布并写入不可变计划对记录。
     */
    public PlanPairResponse publishPair(PublishPairRequest req) {
        String hash = hashPairPublish(req);
        Optional<PlanPairResponse> replay =
                replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash, PlanPairResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 发布锁内重查幂等记录：并发同键请求在此重放首次结果而非误判状态冲突
                Optional<PlanPairResponse> replayed =
                        replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash,
                                PlanPairResponse.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                List<DayPlan> drafts = planRepo.findDraftsByNightPairKey(req.nightPairKey());
                if (drafts.isEmpty()) {
                    if (planRepo.findNightPairByKey(req.nightPairKey()).isPresent()) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "计划对已发布，无待发布草稿: " + req.nightPairKey());
                    }
                    throw new ApiException(HttpStatus.NOT_FOUND, "NIGHT_PAIR_NOT_FOUND",
                            "计划对不存在: " + req.nightPairKey());
                }
                if (drafts.size() != 2) {
                    throw conflict("NIGHT_PAIR_SHAPE_INVALID",
                            "计划对须恰好包含两张草稿，当前 " + drafts.size() + " 张: "
                                    + req.nightPairKey());
                }
                DayPlan first = drafts.get(0);
                DayPlan second = drafts.get(1);
                if (!first.overnight()) {
                    throw conflict("NIGHT_PAIR_SHAPE_INVALID",
                            "首计划必须为夜间跨零点计划: " + first.scheduleKey());
                }
                if (second.overnight()) {
                    throw conflict("NIGHT_PAIR_SHAPE_INVALID",
                            "次日计划不能为夜间计划: " + second.scheduleKey());
                }
                if (!second.opDate().equals(first.opDate().plusDays(1))) {
                    throw conflict("NIGHT_PAIR_OP_DATE_MISMATCH",
                            "计划对运营日必须相邻: 首=" + first.opDate() + " 次=" + second.opDate());
                }
                // 行锁内重查两张草稿的状态与版本
                DayPlan lockedFirst = lockDraft(first.scheduleKey());
                DayPlan lockedSecond = lockDraft(second.scheduleKey());
                if (req.expectedFirstVersion() != lockedFirst.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedFirstVersion=" + req.expectedFirstVersion()
                                    + " 与首计划当前版本 " + lockedFirst.version() + " 不一致");
                }
                if (req.expectedSecondVersion() != lockedSecond.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedSecondVersion=" + req.expectedSecondVersion()
                                    + " 与次日计划当前版本 " + lockedSecond.version() + " 不一致");
                }
                List<Occupancy> firstOcc = planRepo.findOccupancies(lockedFirst.id());
                List<Occupancy> secondOcc = planRepo.findOccupancies(lockedSecond.id());
                List<Long> memberIds = List.of(lockedFirst.id(), lockedSecond.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(lockedFirst, firstOcc));
                conflicts.addAll(findTrainOverlaps(lockedSecond, secondOcc));
                conflicts.addAll(findSectionConflicts(lockedFirst, firstOcc, memberIds));
                conflicts.addAll(findSectionConflicts(lockedSecond, secondOcc, memberIds));
                conflicts.addAll(findInterMemberConflicts(lockedFirst, firstOcc,
                        lockedSecond, secondOcc));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划对两张草稿均保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(lockedFirst.id(), PlanStatus.PUBLISHED, now);
                planRepo.updateStatus(lockedSecond.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertNightPair(req.nightPairKey(), lockedFirst.id(), lockedSecond.id(),
                        lockedFirst.opDate(), lockedSecond.opDate(), now);
                PlanPairResponse response = new PlanPairResponse(req.nightPairKey(),
                        loadPlan(lockedFirst.scheduleKey()), loadPlan(lockedSecond.scheduleKey()));
                idemRepo.insert(OP_PAIR_PUBLISH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash, PlanPairResponse.class)
                    .orElseThrow(() -> conflict("NIGHT_PAIR_KEY_CONFLICT",
                            "nightPairKey 已被其他计划对使用: " + req.nightPairKey()));
        }
    }

    /**
     * 取消计划对：取消其中仍处于已发布状态的成员，各自仅释放自身占用；
     * 已取消的成员保持不变。计划对无可取消成员时返回 409。
     */
    public PlanPairResponse cancelPair(String nightPairKey, String requestKey) {
        String hash = hashAction(OP_PAIR_CANCEL, nightPairKey);
        Optional<PlanPairResponse> replay =
                replayIfPresent(OP_PAIR_CANCEL, requestKey, hash, PlanPairResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 发布锁内重查幂等记录：并发同键请求在此重放首次结果
                Optional<PlanPairResponse> replayed =
                        replayIfPresent(OP_PAIR_CANCEL, requestKey, hash, PlanPairResponse.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                NightPair pair = planRepo.findNightPairByKey(nightPairKey)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "NIGHT_PAIR_NOT_FOUND", "计划对不存在: " + nightPairKey));
                DayPlan first = planRepo.findByKeyForUpdate(planKeyOf(pair.firstPlanId()))
                        .orElseThrow(() -> new IllegalStateException("计划对首计划缺失"));
                DayPlan second = planRepo.findByKeyForUpdate(planKeyOf(pair.secondPlanId()))
                        .orElseThrow(() -> new IllegalStateException("计划对次日计划缺失"));
                boolean anyCancelled = false;
                long now = System.currentTimeMillis();
                if (first.status() == PlanStatus.PUBLISHED) {
                    planRepo.updateStatus(first.id(), PlanStatus.CANCELLED, now);
                    anyCancelled = true;
                }
                if (second.status() == PlanStatus.PUBLISHED) {
                    planRepo.updateStatus(second.id(), PlanStatus.CANCELLED, now);
                    anyCancelled = true;
                }
                if (!anyCancelled) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "计划对无可取消的已发布计划: " + nightPairKey);
                }
                PlanPairResponse response = new PlanPairResponse(nightPairKey,
                        loadPlan(first.scheduleKey()), loadPlan(second.scheduleKey()));
                idemRepo.insert(OP_PAIR_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PAIR_CANCEL, requestKey, hash, PlanPairResponse.class)
                    .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT", "计划对取消冲突"));
        }
    }

    /**
     * 按计划对业务键查询计划对明细（含两张计划当前状态），不存在返回 404。
     */
    public PlanPairResponse getPair(String nightPairKey) {
        NightPair pair = planRepo.findNightPairByKey(nightPairKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NIGHT_PAIR_NOT_FOUND",
                        "计划对不存在: " + nightPairKey));
        return new PlanPairResponse(nightPairKey,
                loadPlan(planKeyOf(pair.firstPlanId())), loadPlan(planKeyOf(pair.secondPlanId())));
    }

    /**
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠与跨计划区段冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用与关联均不改变。
     * 夜间计划改签后的新草稿须保持同一 nightPairKey。
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
                if (!Objects.equals(oldPlan.nightPairKey(), newPlan.nightPairKey())) {
                    throw conflict("NIGHT_PAIR_KEY_MISMATCH",
                            "改签新草稿须保持同一 nightPairKey: 旧=" + oldPlan.nightPairKey()
                                    + " 新=" + newPlan.nightPairKey());
                }
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(newPlan, occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                long now = System.currentTimeMillis();
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
     * 跨零点占用落在该运营日的部分一并返回（起止裁剪到该运营日，
     * 即当日 00:00 前部分归入前一日、次日 00:00 后部分归入次日）。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        return planRepo.findPublishedSlotsOverlapping(List.of(sectionId), dayStart, dayEnd,
                        List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc().isBefore(dayStart) ? dayStart : s.startUtc(),
                        s.endUtc().isAfter(dayEnd) ? dayEnd : s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.overnight(), plan.nightPairKey(), views);
    }

    /**
     * 行锁内重查草稿：必须为 DRAFT，否则抛 409 状态冲突。
     */
    private DayPlan lockDraft(String scheduleKey) {
        DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        if (plan.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "计划对成员必须为草稿，当前状态: " + plan.status());
        }
        return plan;
    }

    private String planKeyOf(long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + planId))
                .scheduleKey();
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
     * 参数级校验：结束必须晚于开始；非夜间占用起止须落在同一 Asia/Shanghai 日历日内，
     * 夜间占用单条时长不得超过 8 小时。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies, boolean overnight) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
            }
            if (overnight) {
                if (o.endUtc().toEpochMilli() - o.startUtc().toEpochMilli()
                        > MAX_OCCUPANCY_MILLIS) {
                    throw badRequest("第 " + i + " 条夜间占用时长超过 8 小时");
                }
            } else {
                LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
                LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
                if (!startDay.equals(endDay)) {
                    throw badRequest("第 " + i + " 条占用必须落在同一运营日（Asia/Shanghai）内");
                }
            }
        }
    }

    /**
     * 非夜间占用必须落在计划运营日内：[start, end) 完全包含于运营日 [dayStart, dayEnd)。
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
     * 夜间占用必须落在夜间窗口内：[start, end) 完全包含于
     * [运营日 22:00, 次日 06:00]（Asia/Shanghai，end 允许恰为次日 06:00）。
     */
    private void validateWithinNightWindow(List<OccupancyRequest> occupancies, LocalDate opDate) {
        Instant windowStart = opDate.atTime(NIGHT_WINDOW_START_HOUR, 0)
                .atZone(OPERATION_ZONE).toInstant();
        Instant windowEnd = opDate.plusDays(1).atTime(NIGHT_WINDOW_END_HOUR, 0)
                .atZone(OPERATION_ZONE).toInstant();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (o.startUtc().isBefore(windowStart) || o.endUtc().isAfter(windowEnd)) {
                throw badRequest("第 " + i + " 条夜间占用不在运营日 " + opDate
                        + " 22:00 至次日 06:00（Asia/Shanghai）窗口内");
            }
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(DayPlan plan, List<Occupancy> occupancies) {
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
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("opDate", plan.opDate().toString());
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
     * 与其他已发布计划在同区段上的重叠检测（左闭右开，相邻合法）。
     * 按时刻交叠判定，跨零点占用同时参与当日与次日两个运营日的区段判定。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划；计划对为两张成员）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        Instant minStart = occupancies.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder()).orElseThrow();
        Instant maxEnd = occupancies.stream().map(Occupancy::endUtc)
                .max(Comparator.naturalOrder()).orElseThrow();
        List<PublishedSlot> published = planRepo.findPublishedSlotsOverlapping(sectionIds,
                minStart, maxEnd, excludePlanIds);
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
                    detail.put("opDate", plan.opDate().toString());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("conflictingOpDate", slot.opDate().toString());
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
     * 计划对两张成员之间的区段重叠检测（左闭右开，相邻合法）：
     * 夜间成员的跨零点占用与次日成员在同区段上不得交叠。
     */
    private List<Map<String, Object>> findInterMemberConflicts(DayPlan first,
                                                               List<Occupancy> firstOcc,
                                                               DayPlan second,
                                                               List<Occupancy> secondOcc) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o1 : firstOcc) {
            for (Occupancy o2 : secondOcc) {
                if (!o1.sectionId().equals(o2.sectionId())) {
                    continue;
                }
                boolean overlap = o1.startUtc().isBefore(o2.endUtc())
                        && o2.startUtc().isBefore(o1.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o1.sectionId());
                    detail.put("scheduleKey", first.scheduleKey());
                    detail.put("opDate", first.opDate().toString());
                    detail.put("conflictingScheduleKey", second.scheduleKey());
                    detail.put("conflictingOpDate", second.opDate().toString());
                    detail.put("trainNo", o1.trainNo());
                    detail.put("startUtc", o1.startUtc().toString());
                    detail.put("endUtc", o1.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
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

    private String hashCreate(CreatePlanRequest req, boolean overnight) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate()).append('\n')
                .append(overnight).append('\n')
                .append(req.nightPairKey() == null ? "" : req.nightPairKey());
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

    private String hashPairPublish(PublishPairRequest req) {
        return sha256(OP_PAIR_PUBLISH + '\n' + req.nightPairKey()
                + '\n' + req.expectedFirstVersion() + '\n' + req.expectedSecondVersion());
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
