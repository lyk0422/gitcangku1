package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.PublishedDwell;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.SnapshotDwell;
import com.example.starter.plan.model.StationConfig;
import com.example.starter.plan.model.StationDwell;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.StationRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActiveStationDwellView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.SnapshotDwellView;
import com.example.starter.plan.web.dto.StationConfigRequest;
import com.example.starter.plan.web.dto.StationConfigView;
import com.example.starter.plan.web.dto.StationDiagnosticsView;
import com.example.starter.plan.web.dto.StationDwellRequest;
import com.example.starter.plan.web.dto.StationDwellView;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * 铁路走廊日计划核心业务：草稿创建/整体替换（含中间站到发登记）、发布、取消、原子改签、
 * 站台配置版本化裁决、发布快照固化与查询。
 *
 * <p>发布与改签在同一全局发布锁事务内依次验证：本计划列车重叠、跨计划区段时隙冲突、
 * 站点存在性、到发顺序、最小停站时长与站台容量峰值；任一项失败整体回滚，不留半成品。
 * 容量满返回 429，其余发布期业务违例返回 422，明细携带站点、实际值、要求值与差额。
 *
 * <p>并发与幂等约定：站台配置、发布、改签、取消均经全局发布锁按事务提交顺序裁决；
 * 写操作按 (操作类型, requestKey) 幂等，指纹为规范化 JSON（含操作者、操作类型、
 * 有序集合与全部结果相关字段）的 SHA-256，同键同参重放返回首次成功响应快照，
 * 同键异参返回 409；仅成功结果落幂等记录，失败不占键。
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
    private static final String OP_STATION_CONFIG = "STATION_CONFIG";

    private final PlanRepository planRepo;
    private final StationRepository stationRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, StationRepository stationRepo,
                       IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.stationRepo = stationRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        List<StationDwellRequest> dwells = nullToEmpty(req.stationDwells());
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        validateDwellParams(dwells, req.opDate());
        String hash = hashCreate(req, dwells);
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
                planRepo.insertStationDwells(planId, toDwells(planId, dwells));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用与站停登记，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        List<StationDwellRequest> dwells = nullToEmpty(req.stationDwells());
        validateOccupancyParams(req.occupancies());
        validateDwellParams(dwells, null);
        String hash = hashUpdate(scheduleKey, req, dwells);
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
                validateDwellParams(dwells, plan.opDate());
                long now = System.currentTimeMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.replaceStationDwells(plan.id(), toDwells(plan.id(), dwells));
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
     * 发布计划：全局发布锁内同一事务原子校验区段时隙与全部站台规则，
     * 任一冲突整张计划保持草稿：区段/站点/到发/时长问题抛 422，容量满抛 429。
     * 成功后固化不可变发布快照（站台规则版本、到发时刻、实际停留分钟）。
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
                PreparedPlan prepared = preparePublishable(plan, List.of(plan.id()));
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                writeSnapshot(plan, operator, prepared, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：发布锁内按提交顺序裁决；时隙与站台容量立即释放，
     * 历史计划、占用、登记与既有发布快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operator) {
        String hash = hashAction(OP_CANCEL, scheduleKey, operator);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
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
     * 原子改签：全局发布锁内校验新草稿全部区段与站台规则（仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划、追加不可变前后继关联并固化新计划快照。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用、关联与快照均不改变。
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
                PreparedPlan prepared = preparePublishable(newPlan,
                        List.of(newPlan.id(), oldPlan.id()));
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                writeSnapshot(newPlan, req.operator(), prepared, now);
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
     * 站台配置登记或修订（幂等）。首次登记 expectedVersion 必须为 null（成功版本 1）；
     * 修订必须等于当前版本（成功版本加一）。发布锁内行锁裁决，并发修订按提交顺序生效，
     * 落后提交因版本不匹配得到 409，规则修订不影响既有发布快照。
     */
    public StationConfigView putStationConfig(String stationId, StationConfigRequest req) {
        String hash = hashStationConfig(stationId, req);
        Optional<StationConfigView> replay =
                replayIfPresent(OP_STATION_CONFIG, req.requestKey(), hash, StationConfigView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                StationConfig current = stationRepo.findByStationIdForUpdate(stationId).orElse(null);
                long now = System.currentTimeMillis();
                StationConfigView view;
                if (current == null) {
                    if (req.expectedVersion() != null) {
                        throw conflict("VERSION_CONFLICT",
                                "站点尚未登记，expectedVersion 必须为 null: " + stationId);
                    }
                    long id = stationRepo.insert(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), req.operator(), now);
                    if (id <= 0) {
                        throw new IllegalStateException("站台配置登记未取得主键: " + stationId);
                    }
                    view = new StationConfigView(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), 1, req.operator());
                    assert id > 0;
                } else {
                    if (req.expectedVersion() == null) {
                        throw conflict("STATION_EXISTS",
                                "站点已登记，当前版本 " + current.version() + "，修订须携带 expectedVersion");
                    }
                    if (req.expectedVersion() != current.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                        + current.version() + " 不一致");
                    }
                    stationRepo.update(current, req.minDwellMinutes(), req.platformCapacity(),
                            req.operator(), now);
                    view = new StationConfigView(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), current.version() + 1, req.operator());
                }
                idemRepo.insert(OP_STATION_CONFIG, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_STATION_CONFIG, req.requestKey(), hash, StationConfigView.class)
                    .orElseThrow(() -> conflict("STATION_EXISTS", "站点配置已存在: " + stationId));
        }
    }

    /**
     * 查询站点当前配置；不存在返回 404。只读。
     */
    public StationConfigView getStationConfig(String stationId) {
        StationConfig config = stationRepo.findByStationId(stationId)
                .orElseThrow(() -> stationNotFound(stationId));
        return new StationConfigView(config.stationId(), config.minDwellMinutes(),
                config.platformCapacity(), config.version(), config.updatedBy());
    }

    /**
     * 站台诊断：当前配置、指定运营日全部生效站停（按到达时刻排序）、
     * 涉及该站的发布快照历史（按快照 id 升序）。只读，不改变状态。
     */
    public StationDiagnosticsView getStationDiagnostics(String stationId, LocalDate opDate) {
        StationConfig config = stationRepo.findByStationId(stationId)
                .orElseThrow(() -> stationNotFound(stationId));
        List<PublishedDwell> active = planRepo.findPublishedDwells(opDate, List.of(stationId),
                List.of(-1L));
        List<ActiveStationDwellView> activeViews = active.stream()
                .sorted(Comparator.comparing(PublishedDwell::arrivalUtc)
                        .thenComparing(PublishedDwell::departureUtc)
                        .thenComparing(PublishedDwell::scheduleKey))
                .map(d -> new ActiveStationDwellView(d.scheduleKey(), d.trainNo(), d.stationId(),
                        d.arrivalUtc(), d.departureUtc(),
                        DwellRules.actualDwellMinutes(d.arrivalUtc().toEpochMilli(),
                                d.departureUtc().toEpochMilli())))
                .toList();
        List<PublishSnapshotView> snapshots = loadSnapshotViews(
                planRepo.findSnapshotsByStation(stationId));
        return new StationDiagnosticsView(stationId, true, config.minDwellMinutes(),
                config.platformCapacity(), config.version(), config.updatedBy(), opDate,
                activeViews, snapshots);
    }

    /**
     * 查询计划的全部不可变发布快照（按快照 id 升序，稳定排序）。只读。
     */
    public List<PublishSnapshotView> getSnapshots(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        return loadSnapshotViews(planRepo.findSnapshotsByPlan(plan.id()));
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

    // ---------- 发布期校验 ----------

    /** 发布校验通过后的中间结果：站停登记与发布时配置（用于固化快照）。 */
    private record PreparedPlan(List<StationDwell> dwells, Map<String, StationConfig> configs) {
    }

    /**
     * 在发布锁事务内完成全部发布前校验，返回固化快照所需的站停与配置。
     * 区段类与站台 422 违例收集完整后一次性抛出；全部通过后再做容量（429）扫描。
     *
     * @param excludePlanIds 跨计划检测排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private PreparedPlan preparePublishable(DayPlan plan, List<Long> excludePlanIds) {
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<StationDwell> dwells = planRepo.findStationDwells(plan.id());

        List<Map<String, Object>> conflicts = new ArrayList<>();
        conflicts.addAll(findTrainOverlaps(plan.scheduleKey(), occupancies));
        conflicts.addAll(findSectionConflicts(plan, occupancies, excludePlanIds));
        Map<String, StationConfig> configs = validateDwellRules(plan.scheduleKey(), dwells,
                conflicts);
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "发布校验未通过，计划保持草稿", conflicts);
        }
        List<Map<String, Object>> capacityBreaches =
                findCapacityBreaches(plan, dwells, configs, excludePlanIds);
        if (!capacityBreaches.isEmpty()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLATFORM_CAPACITY_FULL",
                    "站台容量不足，计划保持草稿", capacityBreaches);
        }
        return new PreparedPlan(dwells, configs);
    }

    /**
     * 校验每条站停：站点存在、到发不到置、停留不短于站点下限。
     * 违例以 422 明细追加到 conflicts；返回本批涉及站点的当前配置。
     */
    private Map<String, StationConfig> validateDwellRules(String scheduleKey,
                                                          List<StationDwell> dwells,
                                                          List<Map<String, Object>> conflicts) {
        Map<String, StationConfig> configs = new HashMap<>();
        for (StationDwell d : dwells) {
            StationConfig config = stationRepo.findByStationId(d.stationId()).orElse(null);
            if (config == null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "STATION_NOT_FOUND");
                detail.put("scheduleKey", scheduleKey);
                detail.put("trainNo", d.trainNo());
                detail.put("stationId", d.stationId());
                conflicts.add(detail);
                continue;
            }
            configs.put(d.stationId(), config);
            long arrivalMillis = d.arrivalUtc().toEpochMilli();
            long departureMillis = d.departureUtc().toEpochMilli();
            int actualMinutes = DwellRules.actualDwellMinutes(arrivalMillis, departureMillis);
            if (!d.departureUtc().isAfter(d.arrivalUtc())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "ARRIVAL_DEPARTURE_REVERSED");
                detail.put("scheduleKey", scheduleKey);
                detail.put("trainNo", d.trainNo());
                detail.put("stationId", d.stationId());
                detail.put("arrivalUtc", d.arrivalUtc().toString());
                detail.put("departureUtc", d.departureUtc().toString());
                detail.put("actualDwellMinutes", actualMinutes);
                conflicts.add(detail);
                continue;
            }
            if (actualMinutes < config.minDwellMinutes()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DWELL_TOO_SHORT");
                detail.put("scheduleKey", scheduleKey);
                detail.put("trainNo", d.trainNo());
                detail.put("stationId", d.stationId());
                detail.put("actualDwellMinutes", actualMinutes);
                detail.put("requiredDwellMinutes", config.minDwellMinutes());
                detail.put("shortfallMinutes", config.minDwellMinutes() - actualMinutes);
                conflicts.add(detail);
            }
        }
        return configs;
    }

    /**
     * 按站点合并本批站停与其他已发布计划站停，左闭右开事件扫描峰值并发；
     * 峰值超过容量的站点输出 429 明细（实际并发、容量、超员数、峰值区间）。
     */
    private List<Map<String, Object>> findCapacityBreaches(DayPlan plan, List<StationDwell> dwells,
                                                           Map<String, StationConfig> configs,
                                                           List<Long> excludePlanIds) {
        List<Map<String, Object>> breaches = new ArrayList<>();
        Map<String, List<StationDwell>> ownByStation = dwells.stream()
                .collect(Collectors.groupingBy(StationDwell::stationId, LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<StationDwell>> entry : ownByStation.entrySet()) {
            String stationId = entry.getKey();
            StationConfig config = configs.get(stationId);
            if (config == null) {
                continue; // 站点不存在已作为 422 报告
            }
            List<PublishedDwell> published = planRepo.findPublishedDwells(plan.opDate(),
                    List.of(stationId), excludePlanIds);
            List<DwellRules.Interval> intervals = new ArrayList<>();
            for (StationDwell d : entry.getValue()) {
                intervals.add(new DwellRules.Interval(d.arrivalUtc().toEpochMilli(),
                        d.departureUtc().toEpochMilli()));
            }
            for (PublishedDwell d : published) {
                intervals.add(new DwellRules.Interval(d.arrivalUtc().toEpochMilli(),
                        d.departureUtc().toEpochMilli()));
            }
            DwellRules.Peak peak = DwellRules.peakConcurrent(intervals);
            if (peak.concurrent() > config.platformCapacity()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "PLATFORM_CAPACITY_EXCEEDED");
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("stationId", stationId);
                detail.put("actualConcurrent", peak.concurrent());
                detail.put("platformCapacity", config.platformCapacity());
                detail.put("overBy", peak.concurrent() - config.platformCapacity());
                detail.put("peakStartUtc", Instant.ofEpochMilli(peak.startUtc()).toString());
                detail.put("peakEndUtc", Instant.ofEpochMilli(peak.endUtc()).toString());
                breaches.add(detail);
            }
        }
        return breaches;
    }

    /**
     * 发布成功后固化快照：主记录 + 每条站停的规则版本/规则值/实际分钟。仅追加，不更新。
     */
    private void writeSnapshot(DayPlan plan, String operator, PreparedPlan prepared, long now) {
        long snapshotId = planRepo.insertSnapshot(plan.id(), plan.scheduleKey(), operator, now);
        List<SnapshotDwell> rows = new ArrayList<>(prepared.dwells().size());
        for (StationDwell d : prepared.dwells()) {
            StationConfig config = prepared.configs().get(d.stationId());
            int actualMinutes = DwellRules.actualDwellMinutes(d.arrivalUtc().toEpochMilli(),
                    d.departureUtc().toEpochMilli());
            rows.add(new SnapshotDwell(snapshotId, d.seq(), d.trainNo(), d.stationId(),
                    d.arrivalUtc(), d.departureUtc(), config.version(), config.minDwellMinutes(),
                    config.platformCapacity(), actualMinutes));
        }
        planRepo.insertSnapshotDwells(snapshotId, rows);
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        List<StationDwellView> dwellViews = planRepo.findStationDwells(plan.id()).stream()
                .map(d -> new StationDwellView(d.trainNo(), d.stationId(),
                        d.arrivalUtc(), d.departureUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, dwellViews);
    }

    private List<PublishSnapshotView> loadSnapshotViews(List<PublishSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return List.of();
        }
        List<Long> ids = snapshots.stream().map(PublishSnapshot::id).toList();
        Map<Long, List<SnapshotDwell>> dwellsBySnapshot = planRepo.findSnapshotDwells(ids).stream()
                .collect(Collectors.groupingBy(SnapshotDwell::snapshotId, LinkedHashMap::new,
                        Collectors.toList()));
        List<PublishSnapshotView> views = new ArrayList<>(snapshots.size());
        for (PublishSnapshot s : snapshots) {
            List<SnapshotDwellView> dwellViews = dwellsBySnapshot
                    .getOrDefault(s.id(), List.of()).stream()
                    .sorted(Comparator.comparingInt(SnapshotDwell::seq))
                    .map(d -> new SnapshotDwellView(d.trainNo(), d.stationId(),
                            d.arrivalUtc(), d.departureUtc(), d.stationVersion(),
                            d.minDwellMinutes(), d.platformCapacity(), d.actualDwellMinutes()))
                    .toList();
            views.add(new PublishSnapshotView(s.id(), s.scheduleKey(), s.publishedBy(),
                    Instant.ofEpochMilli(s.publishedAt()), dwellViews));
        }
        return views;
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    private List<StationDwell> toDwells(long planId, List<StationDwellRequest> requests) {
        List<StationDwell> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            StationDwellRequest r = requests.get(i);
            result.add(new StationDwell(0L, planId, i, r.trainNo(), r.stationId(),
                    r.arrivalUtc(), r.departureUtc()));
        }
        return result;
    }

    private static List<StationDwellRequest> nullToEmpty(List<StationDwellRequest> dwells) {
        return dwells == null ? List.of() : dwells;
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
     * 站停登记参数级校验（发布前的站点存在性、时长、容量在发布事务内裁决）：
     * 时刻对齐 UTC 整分钟、落在运营日内，且同一列车同一站只能登记一次。
     *
     * @param opDate 运营日；createDraft 已知非空，UPDATE 在事务内读到计划后再传 null 之外的值
     */
    private void validateDwellParams(List<StationDwellRequest> dwells, LocalDate opDate) {
        TreeSet<String> seen = new TreeSet<>();
        for (int i = 0; i < dwells.size(); i++) {
            StationDwellRequest d = dwells.get(i);
            if (d.arrivalUtc().toEpochMilli() % 60_000L != 0
                    || d.departureUtc().toEpochMilli() % 60_000L != 0) {
                throw badRequest("第 " + i + " 条站停到发时刻必须对齐 UTC 整分钟");
            }
            if (opDate != null) {
                Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
                Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
                if (d.arrivalUtc().isBefore(dayStart) || d.departureUtc().isAfter(dayEnd)) {
                    throw badRequest("第 " + i + " 条站停不在运营日 " + opDate
                            + "（Asia/Shanghai）内");
                }
            }
            String dedupeKey = d.trainNo() + "\u0000" + d.stationId();
            if (!seen.add(dedupeKey)) {
                throw badRequest("第 " + i + " 条站停与前列重复：列车 " + d.trainNo()
                        + " 站点 " + d.stationId());
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

    // ---------- 幂等与指纹 ----------

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
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明业务键冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    /**
     * 规范化指纹：构造键名排序的 JSON 文档再取 SHA-256，杜绝未转义分隔符拼接歧义。
     * 文档固定含 op；集合按请求提交顺序逐项规范化（顺序本身是结果相关字段）。
     */
    private String fingerprint(ObjectNode doc) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(canonicalize(doc));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹规范化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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

    /**
     * 递归按键名字典序排序对象节点；数组顺序保留（有序业务集合）。
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                arr.add(canonicalize(child));
            }
            return arr;
        }
        return node;
    }

    private ObjectNode newFingerprint(String op) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("op", op);
        return doc;
    }

    private void putText(ObjectNode doc, String field, String value) {
        doc.put(field, value == null ? "" : value);
    }

    private ObjectNode occupancyNode(OccupancyRequest o) {
        ObjectNode n = objectMapper.createObjectNode();
        putText(n, "trainNo", o.trainNo());
        putText(n, "sectionId", o.sectionId());
        n.put("startUtc", o.startUtc().toEpochMilli());
        n.put("endUtc", o.endUtc().toEpochMilli());
        return n;
    }

    private ObjectNode dwellNode(StationDwellRequest d) {
        ObjectNode n = objectMapper.createObjectNode();
        putText(n, "trainNo", d.trainNo());
        putText(n, "stationId", d.stationId());
        n.put("arrivalUtc", d.arrivalUtc().toEpochMilli());
        n.put("departureUtc", d.departureUtc().toEpochMilli());
        return n;
    }

    private void appendCollections(ObjectNode doc, List<OccupancyRequest> occupancies,
                                   List<StationDwellRequest> dwells) {
        ArrayNode occArr = doc.putArray("occupancies");
        occupancies.forEach(o -> occArr.add(occupancyNode(o)));
        ArrayNode dwellArr = doc.putArray("stationDwells");
        dwells.forEach(d -> dwellArr.add(dwellNode(d)));
    }

    private String hashCreate(CreatePlanRequest req, List<StationDwellRequest> dwells) {
        ObjectNode doc = newFingerprint(OP_CREATE);
        putText(doc, "scheduleKey", req.scheduleKey());
        doc.put("opDate", req.opDate().toString());
        putText(doc, "operator", req.operator());
        appendCollections(doc, req.occupancies(), dwells);
        return fingerprint(doc);
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req,
                              List<StationDwellRequest> dwells) {
        ObjectNode doc = newFingerprint(OP_UPDATE);
        putText(doc, "scheduleKey", scheduleKey);
        doc.put("expectedVersion", req.expectedVersion());
        putText(doc, "operator", req.operator());
        appendCollections(doc, req.occupancies(), dwells);
        return fingerprint(doc);
    }

    private String hashAction(String opType, String scheduleKey, String operator) {
        ObjectNode doc = newFingerprint(opType);
        putText(doc, "scheduleKey", scheduleKey);
        putText(doc, "operator", operator);
        return fingerprint(doc);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        ObjectNode doc = newFingerprint(OP_RESCHEDULE);
        putText(doc, "oldScheduleKey", oldScheduleKey);
        putText(doc, "newScheduleKey", req.newScheduleKey());
        doc.put("expectedOldVersion", req.expectedOldVersion());
        doc.put("expectedNewVersion", req.expectedNewVersion());
        putText(doc, "operator", req.operator());
        return fingerprint(doc);
    }

    private String hashStationConfig(String stationId, StationConfigRequest req) {
        ObjectNode doc = newFingerprint(OP_STATION_CONFIG);
        putText(doc, "stationId", stationId);
        doc.put("minDwellMinutes", req.minDwellMinutes());
        doc.put("platformCapacity", req.platformCapacity());
        if (req.expectedVersion() == null) {
            doc.putNull("expectedVersion");
        } else {
            doc.put("expectedVersion", req.expectedVersion());
        }
        putText(doc, "operator", req.operator());
        return fingerprint(doc);
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

    private ApiException stationNotFound(String stationId) {
        return new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                "站点不存在: " + stationId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
