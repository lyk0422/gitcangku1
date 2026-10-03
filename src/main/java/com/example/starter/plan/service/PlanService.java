package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RearrangeRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RearrangeRecordView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.RestrictionImpactSegment;
import com.example.starter.plan.web.dto.RestrictionImpactView;
import com.example.starter.plan.web.dto.RestrictionSnapshotItem;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** 线路参考速度（km/h），占用时长按该速度推算；限速低于该值即需避让。 */
    public static final int REFERENCE_SPEED_KMH = 350;

    /** 线路既有最小间隔（分钟），受限速影响时按该间隔的整数倍整体顺延。 */
    public static final int MIN_INTERVAL_MINUTES = 5;

    /** 单次发布/改签允许的最大顺延轮数（288 轮 x 5 分钟 = 24 小时），超出判定无法避让。 */
    public static final int MAX_SHIFT_ROUNDS = 288;

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final RestrictionRepository restrictionRepo;
    private final RearrangeRepository rearrangeRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       RestrictionRepository restrictionRepo, RearrangeRepository rearrangeRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager,
                       Clock clock) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.restrictionRepo = restrictionRepo;
        this.rearrangeRepo = rearrangeRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
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
                long now = clock.millis();
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
                long now = clock.millis();
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
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划（携带操作者）：全局发布锁内先按生效限速令裁决，若原运行时刻违反限速，
     * 按线路最小间隔整体顺延全部占用（不只调整单段），再校验列车重叠与跨计划区段冲突；
     * 任一冲突整次 422 且计划保持草稿。顺延成功时固化重排记录（限速令版本、原时刻、新时刻），版本加一。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_PUBLISH, scheduleKey, operator,
                restrictionStamp(List.of(scheduleKey)));
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
                List<SpeedRestriction> restrictions = activeRestrictions(occupancies);
                long now = clock.millis();
                int shiftMinutes = resolveShift(plan, occupancies, restrictions, now);
                List<Occupancy> effective = shiftOccupancies(occupancies, shiftMinutes);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, effective));
                conflicts.addAll(findSectionConflicts(plan, effective, List.of(plan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                int version = plan.version();
                if (shiftMinutes > 0) {
                    applyRearrange(plan, occupancies, effective, restrictions, shiftMinutes,
                            operator, now);
                    version = version + 1;
                }
                planRepo.updateVersionAndStatus(plan.id(), version, PlanStatus.PUBLISHED, now);
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
                long now = clock.millis();
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
                List<SpeedRestriction> restrictions = activeRestrictions(occupancies);
                long now = clock.millis();
                int shiftMinutes = resolveShift(newPlan, occupancies, restrictions, now);
                List<Occupancy> effective = shiftOccupancies(occupancies, shiftMinutes);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), effective));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, effective,
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                int newVersion = newPlan.version();
                if (shiftMinutes > 0) {
                    applyRearrange(newPlan, occupancies, effective, restrictions, shiftMinutes,
                            req.operator(), now);
                    newVersion = newVersion + 1;
                }
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateVersionAndStatus(newPlan.id(), newVersion, PlanStatus.PUBLISHED, now);
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

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views);
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

    /**
     * 发布指纹：包含计划业务键、操作者与当前生效限速令版本快照，
     * 限速上下文变化后同键重发判定为异参 409。
     */
    private String hashAction(String opType, String scheduleKey, String operator,
                              String restrictionStamp) {
        return sha256(opType + '\n' + scheduleKey + '\n'
                + (operator == null ? "" : operator) + '\n' + restrictionStamp);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        String stamp = restrictionStamp(List.of(oldScheduleKey, req.newScheduleKey()));
        return sha256(OP_RESCHEDULE + '\n' + oldScheduleKey + '\n' + req.newScheduleKey()
                + '\n' + req.expectedOldVersion() + '\n' + req.expectedNewVersion()
                + '\n' + (req.operator() == null ? "" : req.operator()) + '\n' + stamp);
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
    /**
     * 查询计划的重排历史（只读），按追加顺序升序；计划不存在返回 404。
     */
    public List<RearrangeRecordView> getRearranges(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return rearrangeRepo.findByPlanId(plan.id()).stream()
                .map(r -> new RearrangeRecordView(r.id(), r.scheduleKey(), r.opDate(),
                        r.shiftMinutes(),
                        fromJsonList(r.restrictionSnapshot(), RestrictionSnapshotItem.class),
                        fromJsonList(r.originalOccupancies(), OccupancyView.class),
                        fromJsonList(r.newOccupancies(), OccupancyView.class),
                        r.operator(), r.createdAt()))
                .toList();
    }

    /**
     * 诊断计划受当前生效限速令的影响（只读，不改变任何状态）：
     * 逐段给出受影响运行分钟数与叠加限速的最低速度，并给出整体顺延所需分钟数；
     * requiredShiftMinutes 为 -1 表示在顺延上限内无法避让。
     */
    public RestrictionImpactView getRestrictionImpact(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<SpeedRestriction> restrictions = activeRestrictions(occupancies);
        boolean started = !occupancies.isEmpty() && alreadyStarted(occupancies, clock.millis());
        int requiredShift = findRequiredShiftMinutes(occupancies, restrictions);
        List<RestrictionImpactSegment> segments = occupancies.stream()
                .map(o -> {
                    List<SpeedRestriction> hits = overlappingRestrictions(o, restrictions);
                    long affectedMinutes = unionOverlapMillis(o, hits) / 60000L;
                    Integer effectiveSpeed = hits.stream()
                            .map(SpeedRestriction::maxSpeedKmh)
                            .min(Integer::compareTo)
                            .orElse(null);
                    return new RestrictionImpactSegment(o.trainNo(), o.sectionId(),
                            o.startUtc(), o.endUtc(), affectedMinutes, effectiveSpeed,
                            snapshotItems(hits));
                })
                .toList();
        return new RestrictionImpactView(scheduleKey, requiredShift, started, segments);
    }

    /**
     * 裁决计划是否需要整体顺延：无违限返回 0；已开始运行的计划不可重排抛 409；
     * 超出顺延上限或顺延后越出运营日抛 422（携带实际值与要求值）。
     */
    private int resolveShift(DayPlan plan, List<Occupancy> occupancies,
                             List<SpeedRestriction> restrictions, long nowMillis) {
        if (!hasViolation(occupancies, restrictions)) {
            return 0;
        }
        if (alreadyStarted(occupancies, nowMillis)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("scheduleKey", plan.scheduleKey());
            detail.put("firstStartUtc", minStart(occupancies).toString());
            detail.put("nowUtc", Instant.ofEpochMilli(nowMillis).toString());
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_ALREADY_STARTED",
                    "计划已开始运行，不可按限速重排", List.of(detail));
        }
        int shift = findRequiredShiftMinutes(occupancies, restrictions);
        if (shift < 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("scheduleKey", plan.scheduleKey());
            detail.put("actualShiftMinutes", MAX_SHIFT_ROUNDS * MIN_INTERVAL_MINUTES);
            detail.put("requiredShiftMinutes", "超过顺延上限仍违反限速");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SHIFT_LIMIT_EXCEEDED",
                    "按最小间隔顺延超过上限仍无法避开限速", List.of(detail));
        }
        validateShiftedWithinOperationDay(plan, occupancies, shift);
        return shift;
    }

    /**
     * 计算整体顺延分钟数：从 0 起按线路最小间隔递增，直到所有占用均不违反生效限速；
     * 超过 MAX_SHIFT_ROUNDS 轮仍违限返回 -1。
     */
    private int findRequiredShiftMinutes(List<Occupancy> occupancies,
                                         List<SpeedRestriction> restrictions) {
        for (int rounds = 0; rounds <= MAX_SHIFT_ROUNDS; rounds++) {
            int shift = rounds * MIN_INTERVAL_MINUTES;
            if (!hasViolation(shiftOccupancies(occupancies, shift), restrictions)) {
                return shift;
            }
        }
        return -1;
    }

    /**
     * 顺延后占用必须仍落在计划运营日 [dayStart, dayEnd) 内，否则 422 并给出差额。
     */
    private void validateShiftedWithinOperationDay(DayPlan plan, List<Occupancy> occupancies,
                                                   int shiftMinutes) {
        Instant dayStart = plan.opDate().atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (Occupancy o : shiftOccupancies(occupancies, shiftMinutes)) {
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("sectionId", o.sectionId());
                detail.put("actualEndUtc", o.endUtc().toString());
                detail.put("requiredDayEndUtc", dayEnd.toString());
                detail.put("differenceMinutes",
                        (o.endUtc().toEpochMilli() - dayEnd.toEpochMilli()) / 60000L);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "SHIFT_BEYOND_OPERATION_DAY",
                        "顺延后占用越出运营日 " + plan.opDate(), List.of(detail));
            }
        }
    }

    /**
     * 落库整体顺延结果：替换计划占用并追加不可变重排记录（限速令版本快照、原时刻、新时刻）。
     */
    private void applyRearrange(DayPlan plan, List<Occupancy> original, List<Occupancy> shifted,
                                List<SpeedRestriction> restrictions, int shiftMinutes,
                                String operator, long nowMillis) {
        planRepo.replaceOccupancies(plan.id(), shifted);
        rearrangeRepo.insert(plan.id(), plan.scheduleKey(), plan.opDate(), shiftMinutes,
                toJson(snapshotItems(restrictions)),
                toJson(occupancyViews(original)),
                toJson(occupancyViews(shifted)),
                operator, nowMillis);
    }

    /**
     * 计划占用涉及区段上的全部生效限速令。
     */
    private List<SpeedRestriction> activeRestrictions(List<Occupancy> occupancies) {
        Set<String> sectionIds = new LinkedHashSet<>();
        for (Occupancy o : occupancies) {
            sectionIds.add(o.sectionId());
        }
        return restrictionRepo.findActiveBySections(sectionIds);
    }

    /**
     * 当前生效限速令版本快照（业务键@版本，按业务键排序），用于幂等指纹。
     * 计划不存在时按空快照处理，后续事务内以 404 裁决。
     */
    private String restrictionStamp(List<String> scheduleKeys) {
        Set<String> sectionIds = new LinkedHashSet<>();
        for (String key : scheduleKeys) {
            planRepo.findByKey(key).ifPresent(p -> planRepo.findOccupancies(p.id())
                    .forEach(o -> sectionIds.add(o.sectionId())));
        }
        if (sectionIds.isEmpty()) {
            return "";
        }
        return restrictionRepo.findActiveBySections(sectionIds).stream()
                .map(r -> r.restrictionKey() + "@" + r.version())
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * 是否存在任一占用与生效限速令的区段时段相交（参考速度高于限速即违反）。
     */
    private boolean hasViolation(List<Occupancy> occupancies,
                                 List<SpeedRestriction> restrictions) {
        for (Occupancy o : occupancies) {
            if (!overlappingRestrictions(o, restrictions).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 与指定占用同区段且时段相交（左闭右开）的限速令。
     */
    private List<SpeedRestriction> overlappingRestrictions(Occupancy occupancy,
                                                           List<SpeedRestriction> restrictions) {
        List<SpeedRestriction> hits = new ArrayList<>();
        for (SpeedRestriction r : restrictions) {
            if (!r.sectionId().equals(occupancy.sectionId())) {
                continue;
            }
            boolean overlap = occupancy.startUtc().isBefore(r.endUtc())
                    && r.startUtc().isBefore(occupancy.endUtc());
            if (overlap) {
                hits.add(r);
            }
        }
        return hits;
    }

    /**
     * 占用窗口与限速窗口并集的重叠毫秒数（多条叠加限速不重复计数）。
     */
    private long unionOverlapMillis(Occupancy occupancy, List<SpeedRestriction> restrictions) {
        List<long[]> windows = new ArrayList<>();
        for (SpeedRestriction r : restrictions) {
            long s = Math.max(occupancy.startUtc().toEpochMilli(), r.startUtc().toEpochMilli());
            long e = Math.min(occupancy.endUtc().toEpochMilli(), r.endUtc().toEpochMilli());
            if (s < e) {
                windows.add(new long[] {s, e});
            }
        }
        windows.sort(java.util.Comparator.comparingLong(w -> w[0]));
        long total = 0;
        long curStart = -1;
        long curEnd = -1;
        for (long[] w : windows) {
            if (curEnd < 0) {
                curStart = w[0];
                curEnd = w[1];
            } else if (w[0] <= curEnd) {
                curEnd = Math.max(curEnd, w[1]);
            } else {
                total += curEnd - curStart;
                curStart = w[0];
                curEnd = w[1];
            }
        }
        if (curEnd >= 0) {
            total += curEnd - curStart;
        }
        return total;
    }

    /**
     * 计划是否已开始运行：最早占用开始时刻不晚于当前时刻。
     */
    private boolean alreadyStarted(List<Occupancy> occupancies, long nowMillis) {
        return !minStart(occupancies).isAfter(Instant.ofEpochMilli(nowMillis));
    }

    private Instant minStart(List<Occupancy> occupancies) {
        return occupancies.stream().map(Occupancy::startUtc).min(Instant::compareTo)
                .orElse(Instant.MAX);
    }

    /**
     * 全部占用整体顺延指定分钟数，保持计划内序号与相对间隔不变。
     */
    private List<Occupancy> shiftOccupancies(List<Occupancy> occupancies, int shiftMinutes) {
        if (shiftMinutes == 0) {
            return occupancies;
        }
        java.time.Duration shift = java.time.Duration.ofMinutes(shiftMinutes);
        return occupancies.stream()
                .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc().plus(shift), o.endUtc().plus(shift)))
                .toList();
    }

    /**
     * 限速令快照项（按业务键排序，保证序列化结果稳定）。
     */
    private List<RestrictionSnapshotItem> snapshotItems(List<SpeedRestriction> restrictions) {
        return restrictions.stream()
                .sorted(Comparator.comparing(SpeedRestriction::restrictionKey))
                .map(r -> new RestrictionSnapshotItem(r.restrictionKey(), r.version(),
                        r.sectionId(), r.startUtc(), r.endUtc(), r.maxSpeedKmh()))
                .toList();
    }

    private List<OccupancyView> occupancyViews(List<Occupancy> occupancies) {
        return occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
    }

    private <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            throw new IllegalStateException("重排记录反序列化失败", e);
        }
    }
}
