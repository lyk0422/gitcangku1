package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.EffectiveStationStop;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.PublishedStop;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.StationStop;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.PublishedStopView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.StationDiagnosticsResponse;
import com.example.starter.plan.web.dto.StationStopRequest;
import com.example.starter.plan.web.dto.StationStopView;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.example.starter.station.model.StationConfig;
import com.example.starter.station.repo.StationRepository;
import com.example.starter.station.service.StationService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询，
 * 并在发布时登记并校验中间站站停：到发倒置、站点不存在、停留不足返回 422，
 * 站台容量超卖返回 429；相邻区段时隙与站台占用在同一事务、同一把发布全局锁内裁决。
 *
 * <p>发布成功后固化站停快照（到发时刻、最小停站/容量规则、站点版本），
 * 后续站点规则修订不改写历史快照；取消计划后快照行保留为业务证据。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；幂等指纹包含操作者、操作类型、规范化对象集合及全部影响结果的字段。
 * 发布与改签经全局发布锁串行化，站台配置修订共用同一把锁，按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422/429）不缓存、可修正后重试。
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
     * 创建草稿计划（版本 1，状态 DRAFT），可同时登记中间站到发时刻。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        String operatorId = StationService.normalizeOperator(req.operatorId());
        List<StationStopRequest> stops = normalizeStops(req.stationStops());
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        validateStopParams(stops, req.occupancies(), req.opDate());
        String hash = hashCreate(req, stops, operatorId);
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
                planRepo.insertStationStops(planId, toStationStops(planId, stops));
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
        String operatorId = StationService.normalizeOperator(req.operatorId());
        List<StationStopRequest> stops = normalizeStops(req.stationStops());
        validateOccupancyParams(req.occupancies());
        validateStopParams(stops, req.occupancies(), null);
        String hash = hashUpdate(scheduleKey, req, stops, operatorId);
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
                validateStopParams(stops, req.occupancies(), plan.opDate());
                long now = System.currentTimeMillis();
                // 占用与站停在同一事务内先校验后整体替换，失败时两者均不残留
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.replaceStationStops(plan.id(), toStationStops(plan.id(), stops));
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
     * 发布计划：全局发布锁内原子校验本计划列车重叠、跨计划区段重叠与全部中间站站停规则
     * （站点存在、到发不倒置、停留不短于下限、站台容量不超卖），任一冲突整批回滚。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划（显式操作者），校验与快照语义见 {@link #publish(String, String)}。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operatorIdRaw) {
        String operatorId = StationService.normalizeOperator(operatorIdRaw);
        String hash = hashAction(OP_PUBLISH, scheduleKey, operatorId);
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
                List<StationStop> stops = planRepo.findStationStops(plan.id());
                validateForPublish(plan, occupancies, stops, List.of(plan.id()));
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertPublishedStops(buildSnapshots(plan, stops, now));
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙与站台容量立即释放，历史计划、占用与站停快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        return cancel(scheduleKey, requestKey, null);
    }

    /**
     * 取消已发布计划（显式操作者）。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operatorIdRaw) {
        String operatorId = StationService.normalizeOperator(operatorIdRaw);
        String hash = hashAction(OP_CANCEL, scheduleKey, operatorId);
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
     * 原子改签：全局发布锁内对新草稿执行完整最终状态校验
     * （列车内部重叠、跨计划区段/站台占用，仅排除旧计划），
     * 通过后同一事务取消旧计划、发布新计划、固化新计划站停快照并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，无关联与快照残留。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String operatorId = StationService.normalizeOperator(req.operatorId());
        String hash = hashReschedule(oldScheduleKey, req, operatorId);
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
                List<StationStop> stops = planRepo.findStationStops(newPlan.id());
                // 仅排除旧计划与自身，第三方已发布计划照常参与区段与站台裁决
                validateForPublish(newPlan, occupancies, stops,
                        List.of(newPlan.id(), oldPlan.id()));
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertPublishedStops(buildSnapshots(newPlan, stops, now));
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
     * 按计划业务键查询明细（含历史占用与登记站停），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询计划发布时固化的站停快照（按序号升序）；未发布返回空列表；计划不存在 404。
     * 快照字段、排序、时间精度与 null 语义长期稳定，规则修订不改写。
     */
    public List<PublishedStopView> getPublishedStops(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        return planRepo.findPublishedStopsByPlan(plan.id()).stream()
                .map(s -> new PublishedStopView(s.trainNo(), s.stationId(), s.arrivalUtc(),
                        s.departureUtc(), s.stationVersion(), s.minDwellMinutes(),
                        s.platformCapacity(), s.publishedAt()))
                .toList();
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
     * 站台占用诊断：返回指定运营日某站点当前生效站停（稳定排序）、当前站台容量规则、
     * 按左闭右开区间扫出的实际峰值并行停靠数与余量；始发/终到单端区间按运营日边界补全。
     * 站点不存在返回 404。
     */
    public StationDiagnosticsResponse getStationDiagnostics(LocalDate opDate, String stationId) {
        StationConfig config = stationRepo.find(stationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                        "站点不存在: " + stationId));
        List<EffectiveStationStop> effective = planRepo.findEffectiveStationStops(
                opDate, List.of(stationId), List.of(-1L));
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        List<PlatformInterval> intervals = effective.stream()
                .map(s -> new PlatformInterval(s.scheduleKey(), s.trainNo(), s.arrivalUtc(),
                        s.departureUtc(), occupancyInterval(s.arrivalUtc(), s.departureUtc(),
                                dayStart, dayEnd)))
                .sorted(Comparator.comparing((PlatformInterval i) -> i.interval().start())
                        .thenComparing(PlatformInterval::scheduleKey)
                        .thenComparing(PlatformInterval::trainNo))
                .toList();
        int peak = peakConcurrent(intervals.stream().map(PlatformInterval::interval).toList());
        List<StationDiagnosticsResponse.Item> items = intervals.stream()
                .map(i -> new StationDiagnosticsResponse.Item(i.scheduleKey(), i.trainNo(),
                        i.arrivalUtc(), i.departureUtc(), i.interval().start(), i.interval().end()))
                .toList();
        return new StationDiagnosticsResponse(stationId, opDate, config.platformCapacity(),
                config.minDwellMinutes(), config.version(), peak,
                Math.max(0, config.platformCapacity() - peak), items);
    }

    // ---------- 发布校验 ----------

    /**
     * 发布前完整最终状态校验：区段时隙冲突汇总为 422；
     * 站停规则冲突中到发倒置/站点不存在/停留不足为 422，容量超卖为 429。
     * 一次收集本计划全部冲突明细，任一存在即整体失败回滚。
     */
    private void validateForPublish(DayPlan plan, List<Occupancy> occupancies,
                                    List<StationStop> stops, List<Long> excludePlanIds) {
        List<Map<String, Object>> slotConflicts = new ArrayList<>();
        slotConflicts.addAll(findTrainOverlaps(plan.scheduleKey(), occupancies));
        slotConflicts.addAll(findSectionConflicts(plan, occupancies, excludePlanIds));
        if (!slotConflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "存在区段时隙冲突，计划未发布", slotConflicts);
        }

        List<Map<String, Object>> ruleViolations = new ArrayList<>();
        List<CapacityCandidate> capacityCandidates = new ArrayList<>();
        Map<String, StationConfig> configs = loadStationConfigs(stops);
        Instant dayStart = plan.opDate().atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();

        for (StationStop stop : stops) {
            StationConfig config = configs.get(stop.stationId());
            if (config == null) {
                ruleViolations.add(stopViolation("STATION_NOT_FOUND", stop, null,
                        "站点不存在: " + stop.stationId(), 0, 0, 0));
                continue;
            }
            if (stop.arrivalUtc() != null && stop.departureUtc() != null
                    && !stop.departureUtc().isAfter(stop.arrivalUtc())) {
                long actualMinutes = floorMinutes(Duration.between(stop.arrivalUtc(),
                        stop.departureUtc()));
                ruleViolations.add(stopViolation("ARRIVAL_DEPARTURE_INVERTED", stop, config,
                        "发车时刻必须晚于到达时刻", actualMinutes, config.minDwellMinutes(),
                        config.minDwellMinutes() - actualMinutes));
                continue;
            }
            if (stop.arrivalUtc() != null && stop.departureUtc() != null) {
                long actualMinutes = floorMinutes(Duration.between(stop.arrivalUtc(),
                        stop.departureUtc()));
                if (actualMinutes < config.minDwellMinutes()) {
                    ruleViolations.add(stopViolation("DWELL_TOO_SHORT", stop, config,
                            "停站时长不足下限", actualMinutes, config.minDwellMinutes(),
                            config.minDwellMinutes() - actualMinutes));
                    continue;
                }
            }
            capacityCandidates.add(new CapacityCandidate(stop, config,
                    occupancyInterval(stop.arrivalUtc(), stop.departureUtc(), dayStart, dayEnd)));
        }
        if (!ruleViolations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STATION_DWELL_VIOLATION",
                    "存在站停规则冲突，计划未发布", ruleViolations);
        }
        List<Map<String, Object>> capacityViolations = findCapacityViolations(
                plan, excludePlanIds, capacityCandidates, dayStart, dayEnd);
        if (!capacityViolations.isEmpty()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLATFORM_CAPACITY_EXCEEDED",
                    "站台容量超卖，计划未发布", capacityViolations);
        }
    }

    /**
     * 站台容量裁决：将本批候选区间与其他已发布计划的生效区间合并为事件序列，
     * 按左闭右开（同一时刻先释放结束区间、再计入开始区间）扫描并行停靠峰值；
     * 仅在候选起点计数会漏掉“他车在候选区间内才进站”的超卖，因此对整个时间轴扫描，
     * 任一时刻并行数超过站点容量，即标记当时在站的本批候选为超卖（含实际值、要求值、差额）。
     */
    private List<Map<String, Object>> findCapacityViolations(DayPlan plan, List<Long> excludePlanIds,
                                                             List<CapacityCandidate> candidates,
                                                             Instant dayStart, Instant dayEnd) {
        List<Map<String, Object>> violations = new ArrayList<>();
        Map<String, List<CapacityCandidate>> candidateByStation = new LinkedHashMap<>();
        for (CapacityCandidate candidate : candidates) {
            candidateByStation.computeIfAbsent(candidate.stop().stationId(), k -> new ArrayList<>())
                    .add(candidate);
        }
        for (Map.Entry<String, List<CapacityCandidate>> entry : candidateByStation.entrySet()) {
            String stationId = entry.getKey();
            List<CapacityCandidate> batch = entry.getValue();
            StationConfig config = batch.get(0).config();
            List<EffectiveStationStop> others = planRepo.findEffectiveStationStops(
                    plan.opDate(), List.of(stationId), excludePlanIds);

            // 事件：at 时刻、delta（进站 +1 / 出站 -1）；在站区间引用，other 用于冲突归因
            record Event(Instant at, int delta, boolean batch, CapacityCandidate candidate,
                         EffectiveStationStop other) {
            }
            List<Event> events = new ArrayList<>();
            for (CapacityCandidate candidate : batch) {
                events.add(new Event(candidate.interval().start(), 1, true, candidate, null));
                events.add(new Event(candidate.interval().end(), -1, true, candidate, null));
            }
            for (EffectiveStationStop other : others) {
                TimeInterval interval = occupancyInterval(other.arrivalUtc(), other.departureUtc(),
                        dayStart, dayEnd);
                events.add(new Event(interval.start(), 1, false, null, other));
                events.add(new Event(interval.end(), -1, false, null, other));
            }
            // 同时刻先处理出站（-1）再处理进站（+1），体现右端点开区间
            events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::delta));

            // candidate -> 超卖观测（保留最大并行数与首个冲突时刻/冲突计划）
            Map<CapacityCandidate, Map<String, Object>> offending = new LinkedHashMap<>();
            List<EffectiveStationStop> activeOthers = new ArrayList<>();
            List<CapacityCandidate> activeBatch = new ArrayList<>();
            int cursor = 0;
            while (cursor < events.size()) {
                Instant at = events.get(cursor).at();
                while (cursor < events.size() && events.get(cursor).at().equals(at)) {
                    Event event = events.get(cursor);
                    if (event.batch()) {
                        if (event.delta() > 0) {
                            activeBatch.add(event.candidate());
                        } else {
                            activeBatch.remove(event.candidate());
                        }
                    } else {
                        if (event.delta() > 0) {
                            activeOthers.add(event.other());
                        } else {
                            activeOthers.remove(event.other());
                        }
                    }
                    cursor++;
                }
                int concurrent = activeOthers.size() + activeBatch.size();
                if (concurrent > config.platformCapacity()) {
                    String conflictingKey = activeOthers.isEmpty() ? null
                            : activeOthers.get(0).scheduleKey();
                    for (CapacityCandidate candidate : activeBatch) {
                        Map<String, Object> observed = offending.computeIfAbsent(candidate,
                                c -> stopBase("PLATFORM_CAPACITY_EXCEEDED", c.stop(), config,
                                        "站台容量已满"));
                        int prevMax = observed.containsKey("actualConcurrent")
                                ? (int) observed.get("actualConcurrent") : 0;
                        if (concurrent > prevMax) {
                            observed.put("actualConcurrent", concurrent);
                            observed.put("requiredCapacity", config.platformCapacity());
                            observed.put("shortfall", concurrent - config.platformCapacity());
                            observed.put("atUtc", at.toString());
                            if (conflictingKey != null) {
                                observed.put("conflictingScheduleKey", conflictingKey);
                            }
                        }
                    }
                }
            }
            violations.addAll(offending.values());
        }
        return violations;
    }

    /**
     * 构造站停类冲突明细的公共字段：站点、列车、序号、到发时刻（null 稳定输出）与站点版本。
     */
    private Map<String, Object> stopBase(String type, StationStop stop, StationConfig config,
                                         String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("stationId", stop.stationId());
        detail.put("trainNo", stop.trainNo());
        detail.put("seq", stop.seq());
        detail.put("arrivalUtc", stop.arrivalUtc() == null ? null : stop.arrivalUtc().toString());
        detail.put("departureUtc",
                stop.departureUtc() == null ? null : stop.departureUtc().toString());
        if (config != null) {
            detail.put("stationVersion", config.version());
        }
        detail.put("message", message);
        return detail;
    }

    /**
     * 构造停站时长类冲突明细：在公共字段外给出实际分钟数、要求分钟数与差额（分钟）。
     */
    private Map<String, Object> stopViolation(String type, StationStop stop, StationConfig config,
                                              String message, long actualMinutes,
                                              long requiredMinutes, long shortfall) {
        Map<String, Object> detail = stopBase(type, stop, config, message);
        detail.put("actualMinutes", actualMinutes);
        detail.put("requiredMinutes", requiredMinutes);
        detail.put("shortfallMinutes", shortfall);
        return detail;
    }

    private Map<String, StationConfig> loadStationConfigs(List<StationStop> stops) {
        TreeSet<String> stationIds = stops.stream()
                .map(StationStop::stationId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        return stationRepo.findByIds(stationIds);
    }

    private List<PublishedStop> buildSnapshots(DayPlan plan, List<StationStop> stops, long now) {
        Map<String, StationConfig> configs = loadStationConfigs(stops);
        List<PublishedStop> snapshots = new ArrayList<>(stops.size());
        for (StationStop stop : stops) {
            StationConfig config = configs.get(stop.stationId());
            // 校验已先行通过，配置必然存在；防御性缺失按非法状态处理，事务回滚
            if (config == null) {
                throw new IllegalStateException("发布校验后站点配置缺失: " + stop.stationId());
            }
            snapshots.add(new PublishedStop(0L, plan.id(), plan.opDate(), stop.seq(),
                    stop.trainNo(), stop.stationId(), stop.arrivalUtc(), stop.departureUtc(),
                    config.version(), config.minDwellMinutes(), config.platformCapacity(), now));
        }
        return snapshots;
    }

    /**
     * 到发占用区间，左闭右开：双端为 [arrival, departure)；
     * 仅到达（终到）按 [arrival, 运营日结束)；仅发车（始发）按 [运营日开始, departure)。
     */
    private static TimeInterval occupancyInterval(Instant arrival, Instant departure,
                                                  Instant dayStart, Instant dayEnd) {
        Instant start = arrival != null ? arrival : dayStart;
        Instant end = departure != null ? departure : dayEnd;
        return new TimeInterval(start, end);
    }

    private static long floorMinutes(Duration duration) {
        return duration.toSeconds() / 60;
    }

    private static int peakConcurrent(List<TimeInterval> intervals) {
        record Point(Instant at, int delta) {
        }
        List<Point> points = new ArrayList<>();
        for (TimeInterval interval : intervals) {
            points.add(new Point(interval.start(), 1));
            points.add(new Point(interval.end(), -1));
        }
        points.sort(Comparator.comparing(Point::at).thenComparing(p -> p.delta));
        int current = 0;
        int peak = 0;
        for (Point point : points) {
            current += point.delta();
            peak = Math.max(peak, current);
        }
        return peak;
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        List<StationStopView> stopViews = planRepo.findStationStops(plan.id()).stream()
                .map(s -> new StationStopView(s.trainNo(), s.stationId(),
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

    private static List<StationStopRequest> normalizeStops(List<StationStopRequest> stops) {
        return stops == null ? List.of() : List.copyOf(stops);
    }

    /**
     * 站停登记的草稿级校验（仅形态）：到发至少一端非空；站停列车必须存在于本计划占用清单；
     * 非空时刻必须落在运营日内（opDate 非空时）。到发倒置、站点不存在与停留不足均为
     * 发布期才裁决的业务冲突（422/429），草稿允许先行登记，待站点配置就绪后发布。
     */
    private void validateStopParams(List<StationStopRequest> stops,
                                    List<OccupancyRequest> occupancies, LocalDate opDate) {
        java.util.Set<String> trainNos = occupancies.stream()
                .map(OccupancyRequest::trainNo)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        Instant dayStart = null;
        Instant dayEnd = null;
        if (opDate != null) {
            dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
            dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        }
        for (int i = 0; i < stops.size(); i++) {
            StationStopRequest s = stops.get(i);
            if (s.arrivalUtc() == null && s.departureUtc() == null) {
                throw badRequest("第 " + i + " 条站停到发时刻不能同时为空");
            }
            if (!trainNos.contains(s.trainNo())) {
                throw badRequest("第 " + i + " 条站停列车不在占用清单中: " + s.trainNo());
            }
            if (dayStart != null) {
                Instant arrival = s.arrivalUtc();
                Instant departure = s.departureUtc();
                if ((arrival != null && (arrival.isBefore(dayStart) || !arrival.isBefore(dayEnd)))
                        || (departure != null && (departure.isBefore(dayStart)
                        || departure.isAfter(dayEnd)))) {
                    throw badRequest("第 " + i + " 条站停时刻不在运营日 " + opDate
                            + "（Asia/Shanghai）内");
                }
            }
        }
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

    // ---------- 幂等 ----------

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

    private String hashCreate(CreatePlanRequest req, List<StationStopRequest> stops,
                              String operatorId) {
        Map<String, Object> root = RequestFingerprint.orderedMap(
                "op", OP_CREATE,
                "operator", operatorId,
                "scheduleKey", req.scheduleKey(),
                "opDate", req.opDate().toString(),
                "occupancies", canonicalOccupancies(req.occupancies()),
                "stationStops", canonicalStops(stops));
        return fingerprint.sha256(root);
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req,
                              List<StationStopRequest> stops, String operatorId) {
        Map<String, Object> root = RequestFingerprint.orderedMap(
                "op", OP_UPDATE,
                "operator", operatorId,
                "scheduleKey", scheduleKey,
                "expectedVersion", req.expectedVersion(),
                "occupancies", canonicalOccupancies(req.occupancies()),
                "stationStops", canonicalStops(stops));
        return fingerprint.sha256(root);
    }

    private String hashAction(String opType, String scheduleKey, String operatorId) {
        return fingerprint.sha256(RequestFingerprint.orderedMap(
                "op", opType,
                "operator", operatorId,
                "scheduleKey", scheduleKey));
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req,
                                  String operatorId) {
        return fingerprint.sha256(RequestFingerprint.orderedMap(
                "op", OP_RESCHEDULE,
                "operator", operatorId,
                "oldScheduleKey", oldScheduleKey,
                "newScheduleKey", req.newScheduleKey(),
                "expectedOldVersion", req.expectedOldVersion(),
                "expectedNewVersion", req.expectedNewVersion()));
    }

    /**
     * 规范化占用集合：每条为按键排序的独立 JSON 对象（时刻用 epoch 毫秒保证精度稳定），
     * 再按规范化文本排序后返回，杜绝分隔符拼接碰撞与提交顺序差异。
     */
    private List<Map<String, Object>> canonicalOccupancies(List<OccupancyRequest> occupancies) {
        List<Map<String, Object>> items = new ArrayList<>(occupancies.size());
        for (OccupancyRequest o : occupancies) {
            items.add(RequestFingerprint.orderedMap(
                    "trainNo", o.trainNo(),
                    "sectionId", o.sectionId(),
                    "start", o.startUtc().toEpochMilli(),
                    "end", o.endUtc().toEpochMilli()));
        }
        items.sort(Comparator.comparing(this::toJson));
        return items;
    }

    private List<Map<String, Object>> canonicalStops(List<StationStopRequest> stops) {
        List<Map<String, Object>> items = new ArrayList<>(stops.size());
        for (StationStopRequest s : stops) {
            items.add(RequestFingerprint.orderedMap(
                    "trainNo", s.trainNo(),
                    "stationId", s.stationId(),
                    "arrival", s.arrivalUtc() == null ? null : s.arrivalUtc().toEpochMilli(),
                    "departure", s.departureUtc() == null ? null : s.departureUtc().toEpochMilli()));
        }
        items.sort(Comparator.comparing(this::toJson));
        return items;
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

    // ---------- 内部值对象 ----------

    private record TimeInterval(Instant start, Instant end) {
        boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }
    }

    private record CapacityCandidate(StationStop stop, StationConfig config,
                                     TimeInterval interval) {
    }

    private record PlatformInterval(String scheduleKey, String trainNo, Instant arrivalUtc,
                                    Instant departureUtc, TimeInterval interval) {
    }
}
