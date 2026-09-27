package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.StationConfig;
import com.example.starter.plan.model.StationStop;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.StationRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.StationStopRequest;
import com.example.starter.plan.web.dto.StationStopView;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
 * <p>发布在同一事务内验证：本计划列车重叠、跨计划区段时隙重叠、中间站到发合法性
 * （站点存在、到发不倒置、停留不短于下限）与站台容量（按到发区间左闭右开计数）；
 * 任一失败整次回滚，计划保持草稿。发布成功后站停规则版本与到发时刻固化为不可变快照，
 * 站点配置随后修订不改写历史。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，指纹含操作者、操作类型、
 * 规范化集合与全部结果相关字段（规范化 JSON，不做未转义分隔符拼接）；同键同参重放返回
 * 首次成功结果快照，同键不同参返回 409；发布与改签经全局发布锁串行化，同一计划的
 * 更新/发布/取消/改签经行锁按事务提交顺序生效；仅成功结果写入幂等记录，失败不占键。
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
    private final StationRepository stationRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final RequestFingerprint fingerprint;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, StationRepository stationRepo,
                       IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                       RequestFingerprint fingerprint, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.stationRepo = stationRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.fingerprint = fingerprint;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        validateStationStopParams(req.stationStops(), req.opDate());
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
                stationRepo.replaceStationStops(planId, toStationStops(planId, req.stationStops()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用与站停清单，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        validateOccupancyParams(req.occupancies());
        validateStationStopParams(req.stationStops(), null);
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
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                    + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());
                validateStationStopParams(req.stationStops(), plan.opDate());
                long now = System.currentTimeMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                stationRepo.replaceStationStops(plan.id(), toStationStops(plan.id(), req.stationStops()));
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
     * 发布计划：全局发布锁内、同一事务原子校验本计划列车重叠、跨计划区段重叠、
     * 站停规则（站点存在/到发顺序/最小停留）与站台容量；
     * 任一冲突整张计划保持草稿：规则与时隙类问题返回 422，容量满返回 429。
     * 通过后状态置 PUBLISHED 并追加不可变站停快照。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_PUBLISH, scheduleKey, operator);
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
                List<StationStop> stops = stationRepo.findStationStops(plan.id());

                List<Map<String, Object>> slotConflicts = new ArrayList<>();
                slotConflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                slotConflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));

                Map<String, StationConfig> configs = resolveStationConfigs(stops);
                List<Map<String, Object>> stationConflicts =
                        validateStationStops(plan, scheduleKey, stops, configs);

                List<Map<String, Object>> unprocessable = new ArrayList<>();
                unprocessable.addAll(slotConflicts);
                unprocessable.addAll(stationConflicts);
                if (!unprocessable.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "发布校验失败，计划保持草稿", unprocessable);
                }

                List<Map<String, Object>> capacityViolations = checkPlatformCapacity(
                        plan, scheduleKey, stops, configs, List.of(plan.id()));
                if (!capacityViolations.isEmpty()) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLATFORM_CAPACITY_EXCEEDED",
                            "站台容量不足，计划保持草稿", capacityViolations);
                }

                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                stationRepo.insertPublishedStops(plan.id(), now, stops, configs);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙与站台占用立即释放，历史计划、占用与站停快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_CANCEL, scheduleKey, operator);
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
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠、跨计划区段冲突、站停规则与站台容量，
     * 仅排除旧计划占用与快照），通过后同一事务取消旧计划、发布新计划、写入新计划站停快照
     * 并追加不可变前后继关联。任一步失败整体回滚：旧计划仍发布、新计划仍草稿，
     * 版本、占用、站停、快照与关联均不改变。
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
                            "新旧计划运营日必须相同: 旧=" + oldPlan.opDate()
                                    + " 新=" + newPlan.opDate());
                }
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<StationStop> stops = stationRepo.findStationStops(newPlan.id());

                List<Map<String, Object>> slotConflicts = new ArrayList<>();
                slotConflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                slotConflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));

                Map<String, StationConfig> configs = resolveStationConfigs(stops);
                List<Map<String, Object>> stationConflicts =
                        validateStationStops(newPlan, req.newScheduleKey(), stops, configs);

                List<Map<String, Object>> unprocessable = new ArrayList<>();
                unprocessable.addAll(slotConflicts);
                unprocessable.addAll(stationConflicts);
                if (!unprocessable.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在发布冲突，改签未生效", unprocessable);
                }

                List<Map<String, Object>> capacityViolations = checkPlatformCapacity(
                        newPlan, req.newScheduleKey(), stops, configs,
                        List.of(newPlan.id(), oldPlan.id()));
                if (!capacityViolations.isEmpty()) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLATFORM_CAPACITY_EXCEEDED",
                            "站台容量不足，改签未生效", capacityViolations);
                }

                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                stationRepo.insertPublishedStops(newPlan.id(), now, stops, configs);
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
     * 无改签历史的计划链中仅含自身。计划不存在返回 404。只读，不改变状态。
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
     * 按计划业务键查询明细（含历史占用与站停登记），不存在返回 404。只读。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙。只读。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 站停发布校验 ----------

    /**
     * 批量解析站停涉及站点的当前配置；缺失站点不抛异常，由调用方按登记逐条产生明细。
     */
    private Map<String, StationConfig> resolveStationConfigs(List<StationStop> stops) {
        TreeSet<String> stationIds = stops.stream()
                .map(StationStop::stationId)
                .collect(Collectors.toCollection(TreeSet::new));
        return stationRepo.findStations(stationIds);
    }

    /**
     * 校验站停登记：站点不存在、到发倒置（发车不晚于到达）、停留短于下限，
     * 全部收集为可区分的 422 明细，明细含站点、实际分钟数、要求分钟数与差额。
     */
    private List<Map<String, Object>> validateStationStops(DayPlan plan, String scheduleKey,
                                                           List<StationStop> stops,
                                                           Map<String, StationConfig> configs) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (StationStop stop : stops) {
            StationConfig config = configs.get(stop.stationId());
            if (config == null) {
                Map<String, Object> detail = baseStopDetail("STATION_NOT_FOUND", scheduleKey, stop);
                detail.put("requiredMinutes", null);
                detail.put("actualMinutes", null);
                detail.put("shortfallMinutes", null);
                conflicts.add(detail);
                continue;
            }
            Instant arrival = stop.arrivalUtc();
            Instant departure = stop.departureUtc();
            if (arrival != null && departure != null && !departure.isAfter(arrival)) {
                long actualMinutes = Duration.between(arrival, departure).toMinutes();
                Map<String, Object> detail =
                        baseStopDetail("ARRIVAL_DEPARTURE_REVERSED", scheduleKey, stop);
                detail.put("actualMinutes", actualMinutes);
                detail.put("requiredMinutes", 1);
                detail.put("shortfallMinutes", 1 - actualMinutes);
                conflicts.add(detail);
                continue;
            }
            if (arrival != null && departure != null) {
                long actualMinutes = Duration.between(arrival, departure).toMinutes();
                if (actualMinutes < config.minDwellMinutes()) {
                    Map<String, Object> detail =
                            baseStopDetail("DWELL_TOO_SHORT", scheduleKey, stop);
                    detail.put("actualMinutes", actualMinutes);
                    detail.put("requiredMinutes", config.minDwellMinutes());
                    detail.put("shortfallMinutes", config.minDwellMinutes() - actualMinutes);
                    detail.put("stationVersion", config.version());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 站台容量校验：候选计划站停与其他已发布计划快照在同运营日、同站点合并计数，
     * 区间左闭右开；仅到达（终到）补齐至运营日结束，仅发车（始发）自运营日开始补齐。
     * 超卖明细携带站点、超限时刻、实际并发数、容量要求与差额，按 429 返回。
     *
     * @param excludePlanIds 排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> checkPlatformCapacity(DayPlan plan, String scheduleKey,
                                                            List<StationStop> candidateStops,
                                                            Map<String, StationConfig> configs,
                                                            List<Long> excludePlanIds) {
        Instant dayStart = plan.opDate().atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        TreeSet<String> stationIds = candidateStops.stream()
                .map(StationStop::stationId)
                .collect(Collectors.toCollection(TreeSet::new));
        if (stationIds.isEmpty()) {
            return List.of();
        }

        List<StationCapacityEvaluator.PlatformInterval> intervals = new ArrayList<>();
        Map<String, Integer> capacities = new LinkedHashMap<>();
        for (String stationId : stationIds) {
            StationConfig config = configs.get(stationId);
            if (config != null) {
                capacities.put(stationId, config.platformCapacity());
            }
        }

        // 其他已发布计划的固化快照（容量以发布当时区间为准）
        List<StationRepository.SnapshotRow> active =
                stationRepo.findActiveSnapshots(plan.opDate(), stationIds, excludePlanIds);
        for (StationRepository.SnapshotRow row : active) {
            addInterval(intervals, row.stop().stationId(), row.scheduleKey(),
                    row.stop().trainNo(), row.stop().seq(),
                    row.stop().arrivalUtc(), row.stop().departureUtc(), dayStart, dayEnd);
        }

        // 候选计划登记站停，seq 顺序在候选内稳定；与其他计划间按 scheduleKey 排序裁决
        for (StationStop stop : candidateStops) {
            addInterval(intervals, stop.stationId(), scheduleKey, stop.trainNo(), stop.seq(),
                    stop.arrivalUtc(), stop.departureUtc(), dayStart, dayEnd);
        }

        Map<String, StationCapacityEvaluator.StationResult> results =
                StationCapacityEvaluator.evaluate(intervals, capacities);
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map.Entry<String, StationCapacityEvaluator.StationResult> entry : results.entrySet()) {
            StationConfig config = configs.get(entry.getKey());
            for (StationCapacityEvaluator.CapacityViolation v : entry.getValue().violations()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "PLATFORM_CAPACITY_EXCEEDED");
                detail.put("stationId", entry.getKey());
                detail.put("scheduleKey", v.scheduleKey());
                detail.put("trainNo", v.trainNo());
                detail.put("seq", v.seq());
                detail.put("timeUtc", v.time().toString());
                detail.put("actualConcurrent", v.concurrent());
                detail.put("requiredCapacity", v.capacity());
                detail.put("overflow", v.overflow());
                detail.put("stationVersion", config == null ? null : config.version());
                violations.add(detail);
            }
        }
        return violations;
    }

    private void addInterval(List<StationCapacityEvaluator.PlatformInterval> intervals,
                             String stationId, String scheduleKey, String trainNo, int seq,
                             Instant arrival, Instant departure, Instant dayStart, Instant dayEnd) {
        Instant start = arrival != null ? arrival : dayStart;
        Instant end = departure != null ? departure : dayEnd;
        if (!start.isBefore(end)) {
            return;
        }
        intervals.add(new StationCapacityEvaluator.PlatformInterval(
                stationId, scheduleKey, trainNo, seq, start, end));
    }

    private Map<String, Object> baseStopDetail(String type, String scheduleKey, StationStop stop) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("scheduleKey", scheduleKey);
        detail.put("trainNo", stop.trainNo());
        detail.put("stationId", stop.stationId());
        detail.put("seq", stop.seq());
        detail.put("arrivalUtc", stop.arrivalUtc() == null ? null : stop.arrivalUtc().toString());
        detail.put("departureUtc",
                stop.departureUtc() == null ? null : stop.departureUtc().toString());
        return detail;
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        List<StationStopView> stopViews = stationRepo.findStationStops(plan.id()).stream()
                .map(s -> new StationStopView(s.seq(), s.trainNo(), s.stationId(),
                        s.arrivalUtc(), s.departureUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, stopViews);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    private List<StationStop> toStationStops(long planId, List<StationStopRequest> requests) {
        List<StationStop> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            StationStopRequest r = requests.get(i);
            result.add(new StationStop(0L, planId, i, r.trainNo(), r.stationId(),
                    r.arrivalUtc(), r.departureUtc()));
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
     * 站停登记参数级校验：到发至少填一项；所有提供的时刻必须落在同一运营日
     * [dayStart, dayEnd] 内。到发倒置与停留不足属于发布校验（422），草稿阶段允许登记。
     * opDate 为 null 时（替换请求事务内拿到计划运营日后）跳过运营日校验。
     */
    private void validateStationStopParams(List<StationStopRequest> stops, LocalDate opDate) {
        for (int i = 0; i < stops.size(); i++) {
            StationStopRequest s = stops.get(i);
            if (s.arrivalUtc() == null && s.departureUtc() == null) {
                throw badRequest("第 " + i + " 条站停到发时刻至少填写一项");
            }
            if (opDate != null) {
                Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
                Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
                for (Instant t : new Instant[]{s.arrivalUtc(), s.departureUtc()}) {
                    if (t != null && (t.isBefore(dayStart) || t.isAfter(dayEnd))) {
                        throw badRequest("第 " + i + " 条站停时刻不在运营日 " + opDate
                                + "（Asia/Shanghai）内");
                    }
                }
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

    // ---------- 幂等指纹（规范化 JSON，含操作者与全部结果相关字段） ----------

    private String hashCreate(CreatePlanRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleKey", req.scheduleKey());
        payload.put("opDate", req.opDate().toString());
        payload.put("occupancies", hashOccupancies(req.occupancies()));
        payload.put("stationStops", hashStops(req.stationStops()));
        return fingerprint.sha256(OP_CREATE, req.operator(), payload);
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleKey", scheduleKey);
        payload.put("expectedVersion", req.expectedVersion());
        payload.put("occupancies", hashOccupancies(req.occupancies()));
        payload.put("stationStops", hashStops(req.stationStops()));
        return fingerprint.sha256(OP_UPDATE, req.operator(), payload);
    }

    private String hashAction(String opType, String scheduleKey, String operator) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleKey", scheduleKey);
        return fingerprint.sha256(opType, operator, payload);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oldScheduleKey", oldScheduleKey);
        payload.put("newScheduleKey", req.newScheduleKey());
        payload.put("expectedOldVersion", req.expectedOldVersion());
        payload.put("expectedNewVersion", req.expectedNewVersion());
        return fingerprint.sha256(OP_RESCHEDULE, req.operator(), payload);
    }

    private List<List<Object>> hashOccupancies(List<OccupancyRequest> occupancies) {
        List<List<Object>> rows = new ArrayList<>(occupancies.size());
        for (OccupancyRequest o : occupancies) {
            rows.add(List.of(o.trainNo(), o.sectionId(),
                    o.startUtc().toEpochMilli(), o.endUtc().toEpochMilli()));
        }
        return rows;
    }

    private List<List<Object>> hashStops(List<StationStopRequest> stops) {
        List<List<Object>> rows = new ArrayList<>(stops.size());
        for (StationStopRequest s : stops) {
            // 到发允许 null（始发/终到语义），List.of 不接受 null，故用 ArrayList
            List<Object> row = new ArrayList<>(4);
            row.add(s.trainNo());
            row.add(s.stationId());
            row.add(s.arrivalUtc() == null ? null : s.arrivalUtc().toEpochMilli());
            row.add(s.departureUtc() == null ? null : s.departureUtc().toEpochMilli());
            rows.add(row);
        }
        return rows;
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
