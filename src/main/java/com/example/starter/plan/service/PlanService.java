package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PlatformBooking;
import com.example.starter.plan.model.PlatformCapability;
import com.example.starter.plan.model.PowerSupplyType;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConsistSpec;
import com.example.starter.plan.web.dto.ConsistView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询。
 *
 * <p>含编组的计划在发布/改签时还需通过站台适配：长度不超站台上限、供电制式兼容、
 * 无障碍需求满足，且每个停靠站台的 UTC 到发窗口与其他已发布登记不重叠；
 * 全部通过后才在同一事务内一次写入站台占用登记并快照当时能力版本，任一失败整体回滚、
 * 不留下部分占位。取消计划不删登记，来源计划转 CANCELLED 后窗口立即释放。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参（含编组或时段变更）返回 409；发布与改签经全局发布锁串行化，
 * 同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422）不缓存、可修正后重试。
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
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        Consist consist = validateConsist(req.consist());
        validateOccupancyParams(req.occupancies(), consist != null);
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
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT,
                        consist == null ? null : consist.lengthM(),
                        consist == null ? null : consist.powerSupplyType(),
                        consist == null ? null : consist.accessibleRequired(), now);
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
     * 编组属性在计划生命周期内不可变，本接口只替换停靠时段与站台。
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
                validateOccupancyParams(req.occupancies(), plan.hasConsist());
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段重叠与站台适配/窗口，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突明细，站台不适配含具体原因）。
     * 全部通过后同一事务内更新状态并一次写入全部站台占用登记。
     * 幂等判定在锁内按计划当前编组与时段内容进行：同键同内容重放首次结果，
     * 同键但编组或时段已变更返回 409。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        // 事务前快速裁决：同键存在记录时，按计划当前编组/时段内容比对，
        // 一致回放首次结果，不一致（改编组或时段）直接 409；权威裁决在锁内再做一次。
        Optional<IdempotencyRepository.Record> existing = idemRepo.find(OP_PUBLISH, requestKey);
        if (existing.isPresent()) {
            DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
            String currentHash = hashPublish(plan, planRepo.findOccupancies(plan.id()));
            if (!currentHash.equals(existing.get().requestHash())) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他编组或时段: " + requestKey);
            }
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                String hash = hashPublish(plan, occupancies);
                Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                conflicts.addAll(findPlatformConflicts(plan, occupancies, List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突或站台不适配，计划保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
                registerPlatformBookings(plan, occupancies, now);
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveActionDuplicate(OP_PUBLISH, scheduleKey, requestKey);
        }
    }

    /**
     * 取消已发布计划：区段时隙与站台窗口立即释放（登记行历史保留），计划与占用保留不改写。
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
     * 原子改签：全局发布锁内按新草稿最终时段与编组一次性校验列车内部重叠、
     * 跨计划区段/站台窗口冲突与站台适配（仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划、写入新计划站台登记并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，不产生任何站台占位与关联。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        // 事务前快速裁决：同键记录存在且新旧计划可按当前编组/时段重算出相同哈希时放行到锁内回放，
        // 哈希不一致（改编组或时段）直接 409；计划缺失等其他情形交给锁内事务判定。
        idemRepo.find(OP_RESCHEDULE, req.requestKey()).ifPresent(record -> {
            Optional<DayPlan> oldPlan = planRepo.findByKey(oldScheduleKey);
            Optional<DayPlan> newPlan = planRepo.findByKey(req.newScheduleKey());
            // 成功改签的重放必然能找到新旧计划；记录存在却缺计划，说明并非同一次重放
            if (oldPlan.isEmpty() || newPlan.isEmpty()) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数（编组或时段不一致）: " + req.requestKey());
            }
            String currentHash = hashRescheduleState(oldPlan.get(), newPlan.get(), req,
                    planRepo.findOccupancies(newPlan.get().id()));
            if (!currentHash.equals(record.requestHash())) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他编组或时段: " + req.requestKey());
            }
        });
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                // 哈希覆盖新旧计划、期望版本与新计划最终编组/时段内容：同键改时段或编组判定 409
                String hash = hashRescheduleState(oldPlan, newPlan, req, occupancies);
                Optional<RescheduleResponse> replay =
                        replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
                if (replay.isPresent()) {
                    return replay.get();
                }
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
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                List<Long> excludePlanIds = List.of(newPlan.id(), oldPlan.id());
                conflicts.addAll(findSectionConflicts(newPlan, occupancies, excludePlanIds));
                conflicts.addAll(findPlatformConflicts(newPlan, occupancies, excludePlanIds));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突或站台不适配，改签未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                registerPlatformBookings(newPlan, occupancies, now);
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveRescheduleDuplicate(oldScheduleKey, req.requestKey());
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
     * 按计划业务键查询明细（含历史占用与站台登记快照），不存在返回 404。
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

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.platformId(),
                        o.startUtc(), o.endUtc()))
                .toList();
        ConsistView consist = plan.hasConsist()
                ? new ConsistView(plan.consistLengthM(), plan.powerSupplyType().name(),
                        plan.accessibleRequired())
                : null;
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), consist, views);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(),
                    normalizePlatform(r.platformId()), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    /**
     * 编组请求解析与校验：三属性必须同时提供；供电制式代码非法返回 400。
     * 返回 null 表示该计划为无编组的纯区段占用。
     */
    private Consist validateConsist(ConsistSpec spec) {
        if (spec == null) {
            return null;
        }
        PowerSupplyType powerType;
        try {
            powerType = PowerSupplyType.valueOf(spec.powerSupplyType());
        } catch (IllegalArgumentException e) {
            throw badRequest("未知供电制式代码: " + spec.powerSupplyType());
        }
        return new Consist(spec.lengthM(), powerType, spec.accessibleRequired());
    }

    /**
     * 参数级校验：结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内。
     * 含编组时每条占用必须携带非空站台；无编组时不允许携带站台。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies, boolean hasConsist) {
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
            boolean platformPresent = o.platformId() != null && !o.platformId().isBlank();
            if (hasConsist && !platformPresent) {
                throw badRequest("第 " + i + " 条到发占用必须携带申请停靠站台 platformId");
            }
            if (!hasConsist && platformPresent) {
                throw badRequest("第 " + i + " 条区段占用不允许携带 platformId（计划未登记编组）");
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
                .collect(Collectors.toCollection(TreeSet::new))
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
     * 含编组计划的站台适配与到发窗口重叠检测。无编组计划直接返回空。
     * 对每个申请站台取当前有效能力版本：能力缺失、长度超限、制式不兼容、无障碍不满足
     * 记 PLATFORM_MISMATCH（含具体原因代码）；窗口与其他已发布登记重叠记 PLATFORM_WINDOW_CONFLICT。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findPlatformConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                            List<Long> excludePlanIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (!plan.hasConsist()) {
            return conflicts;
        }
        List<String> platformIds = occupancies.stream()
                .map(Occupancy::platformId)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        Map<String, PlatformCapability> capabilities = new LinkedHashMap<>();
        for (String platformId : platformIds) {
            Optional<PlatformCapability> capability = planRepo.findLatestCapability(platformId);
            if (capability.isPresent()) {
                capabilities.put(platformId, capability.get());
            } else {
                Occupancy usage = occupancies.stream()
                        .filter(o -> o.platformId().equals(platformId))
                        .findFirst()
                        .orElse(occupancies.get(0));
                conflicts.add(mismatchDetail(plan, usage, platformId,
                        List.of("PLATFORM_NOT_FOUND")));
            }
        }
        List<PlatformBooking> activeBookings = planRepo.findActiveBookings(platformIds, excludePlanIds);
        // 同一站台在同一窗口只能接一列车：本计划内任意两条到发（可为不同列车）同站台重叠也判冲突
        Map<String, List<Occupancy>> byPlatform = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byPlatform.computeIfAbsent(o.platformId(), k -> new ArrayList<>()).add(o);
        }
        byPlatform.forEach((platformId, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "PLATFORM_WINDOW_CONFLICT");
                    detail.put("platformId", platformId);
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", plan.scheduleKey());
                    detail.put("trainNo", cur.trainNo());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        for (Occupancy o : occupancies) {
            PlatformCapability capability = capabilities.get(o.platformId());
            if (capability != null) {
                List<String> reasons = PlatformFit.mismatchReasons(capability,
                        plan.consistLengthM(), plan.powerSupplyType(), plan.accessibleRequired());
                if (!reasons.isEmpty()) {
                    conflicts.add(mismatchDetail(plan, o, o.platformId(), reasons));
                }
            }
            for (PlatformBooking booking : activeBookings) {
                if (!booking.platformId().equals(o.platformId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(booking.endUtc())
                        && booking.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "PLATFORM_WINDOW_CONFLICT");
                    detail.put("platformId", o.platformId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", booking.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private Map<String, Object> mismatchDetail(DayPlan plan, Occupancy occupancy, String platformId,
                                               List<String> reasons) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "PLATFORM_MISMATCH");
        detail.put("platformId", platformId);
        detail.put("scheduleKey", plan.scheduleKey());
        detail.put("trainNo", occupancy.trainNo());
        detail.put("reasons", reasons);
        return detail;
    }

    /**
     * 将含编组计划的全部到发占用一次登记到 rail_platform_booking，
     * 每条登记快照对应站台当前有效能力版本；调用前已完成全部适配与窗口校验。
     */
    private void registerPlatformBookings(DayPlan plan, List<Occupancy> occupancies, long nowMillis) {
        if (!plan.hasConsist()) {
            return;
        }
        List<PlatformBooking> bookings = new ArrayList<>(occupancies.size());
        for (Occupancy o : occupancies) {
            PlatformCapability capability = planRepo.findLatestCapability(o.platformId())
                    .orElseThrow(() -> unprocessable("PLATFORM_NOT_FOUND",
                            "站台能力不存在: " + o.platformId()));
            bookings.add(new PlatformBooking(0L, plan.id(), o.seq(), o.trainNo(), o.platformId(),
                    o.startUtc(), o.endUtc(), capability.version(), PlanStatus.DRAFT,
                    plan.scheduleKey()));
        }
        planRepo.insertPlatformBookings(plan.id(), bookings);
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
                        "requestKey 已用于其他参数（编组或时段不一致）: " + requestKey);
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
        appendConsist(sb, req.consist());
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

    /**
     * 发布幂等哈希：覆盖计划业务键、编组三属性与全部到发时段内容，
     * 同一 requestKey 改编组或任一时段（含站台、起止）都会得到不同哈希，裁决为 409。
     */
    private String hashPublish(DayPlan plan, List<Occupancy> occupancies) {
        StringBuilder sb = new StringBuilder(OP_PUBLISH).append('\n')
                .append(plan.scheduleKey()).append('\n').append(plan.opDate());
        appendPlanConsist(sb, plan);
        appendModelOccupancies(sb, occupancies);
        return sha256(sb.toString());
    }

    /**
     * 改签幂等哈希：覆盖新旧计划、期望版本与新计划最终编组/时段内容，
     * 同一 requestKey 改编组或时段返回 409。
     */
    private String hashRescheduleState(DayPlan oldPlan, DayPlan newPlan, RescheduleRequest req,
                                       List<Occupancy> newOccupancies) {
        StringBuilder sb = new StringBuilder(OP_RESCHEDULE).append('\n')
                .append(oldPlan.scheduleKey()).append('\n').append(newPlan.scheduleKey()).append('\n')
                .append(req.expectedOldVersion()).append('\n').append(req.expectedNewVersion());
        appendPlanConsist(sb, newPlan);
        appendModelOccupancies(sb, newOccupancies);
        return sha256(sb.toString());
    }

    /**
     * 并发下发布幂等键唯一冲突裁决：另一事务已用同键提交，回放其首次结果。
     */
    private PlanResponse resolveActionDuplicate(String opType, String scheduleKey, String requestKey) {
        return idemRepo.find(opType, requestKey)
                .map(record -> fromJson(record.responseJson(), PlanResponse.class))
                .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT",
                        "计划状态已被并发事务改变: " + scheduleKey));
    }

    /**
     * 并发下改签唯一键冲突裁决：幂等键冲突回放首次结果；否则为前后继关联冲突。
     */
    private RescheduleResponse resolveRescheduleDuplicate(String oldScheduleKey, String requestKey) {
        return idemRepo.find(OP_RESCHEDULE, requestKey)
                .map(record -> fromJson(record.responseJson(), RescheduleResponse.class))
                .orElseThrow(() -> conflict("LINK_CONFLICT",
                        "改签前后继关联冲突或计划已被并发改签: " + oldScheduleKey));
    }

    private void appendPlanConsist(StringBuilder sb, DayPlan plan) {
        if (!plan.hasConsist()) {
            sb.append('\n').append("NO_CONSIST");
            return;
        }
        sb.append('\n').append(plan.consistLengthM()).append('|')
                .append(plan.powerSupplyType().name()).append('|').append(plan.accessibleRequired());
    }

    private void appendModelOccupancies(StringBuilder sb, List<Occupancy> occupancies) {
        for (Occupancy o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(normalizePlatform(o.platformId())).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
        }
    }

    private void appendConsist(StringBuilder sb, ConsistSpec consist) {
        if (consist == null) {
            sb.append('\n').append("NO_CONSIST");
            return;
        }
        sb.append('\n').append(consist.lengthM()).append('|').append(consist.powerSupplyType())
                .append('|').append(consist.accessibleRequired());
    }

    private void appendOccupancies(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(normalizePlatform(o.platformId())).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
        }
    }

    private static String normalizePlatform(String platformId) {
        return platformId == null || platformId.isBlank() ? "" : platformId.trim();
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

    private ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 编组内部值对象，持有解析后的供电制式枚举。 */
    private record Consist(Integer lengthM, PowerSupplyType powerSupplyType,
                           Boolean accessibleRequired) {
    }
}
