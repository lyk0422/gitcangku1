package com.example.starter.work.service;

import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.work.model.WorkCancelRecord;
import com.example.starter.work.model.WorkOrder;
import com.example.starter.work.model.WorkStatus;
import com.example.starter.work.model.WorkWindow;
import com.example.starter.work.repo.WorkOrderRepository;
import com.example.starter.work.web.dto.AffectedPlanView;
import com.example.starter.work.web.dto.CancelRecordView;
import com.example.starter.work.web.dto.CreateSectionRequest;
import com.example.starter.work.web.dto.CreateWorkOrderRequest;
import com.example.starter.work.web.dto.SectionView;
import com.example.starter.work.web.dto.UpdateWorkOrderRequest;
import com.example.starter.work.web.dto.WorkOrderActionRequest;
import com.example.starter.work.web.dto.WorkOrderResponse;
import com.example.starter.work.web.dto.WorkWindowView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * 铁路施工占用窗口核心业务：区段注册、施工单创建/修改/取消与查询。
 *
 * <p>并发与幂等约定：施工单创建/修改/取消按 (操作类型, requestKey) 幂等，同键同参重放返回
 * 首次成功结果，同键不同参返回 409；写操作经全局发布锁串行化，与计划发布/改签按事务提交顺序裁决；
 * 仅成功结果写入幂等记录，失败（含 409/422）不缓存、可修正后重试。
 */
@Service
public class WorkOrderService {

    private static final String OP_WORK_CREATE = "WORK_CREATE";
    private static final String OP_WORK_UPDATE = "WORK_UPDATE";
    private static final String OP_WORK_CANCEL = "WORK_CANCEL";

    private final WorkOrderRepository workRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WorkOrderService(WorkOrderRepository workRepo, PlanRepository planRepo,
                            IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                            Clock clock, PlatformTransactionManager txManager) {
        this.workRepo = workRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 注册区段；已存在时直接返回既有区段（注册本身幂等）。
     */
    public SectionView registerSection(CreateSectionRequest req) {
        if (workRepo.sectionExists(req.sectionId())) {
            return new SectionView(req.sectionId());
        }
        try {
            workRepo.insertSection(req.sectionId(), System.currentTimeMillis());
        } catch (DuplicateKeyException e) {
            // 并发注册同一区段：以先提交者为准
        }
        return new SectionView(req.sectionId());
    }

    /**
     * 创建施工单（版本 1，状态 ACTIVE）。所有区段必须已注册，起点必须早于终点；
     * 与生效施工单在同区段时间窗相交时返回 409 并稳定列出冲突 workKey。
     */
    public WorkOrderResponse create(CreateWorkOrderRequest req) {
        List<String> sections = validateAndNormalize(req.startUtc(), req.endUtc(), req.sectionIds());
        String hash = hashCreate(req, sections);
        Optional<WorkOrderResponse> replay = replayIfPresent(OP_WORK_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 全局锁内复查：并发下同键请求可能已在等待期间提交
                Optional<WorkOrderResponse> committed =
                        replayIfPresent(OP_WORK_CREATE, req.requestKey(), hash);
                if (committed.isPresent()) {
                    return committed.get();
                }
                if (workRepo.findByKey(req.workKey()).isPresent()) {
                    throw conflict("WORK_KEY_EXISTS", "workKey 已存在: " + req.workKey());
                }
                ensureNoWindowOverlap(sections, req.startUtc(), req.endUtc(), List.of());
                long now = System.currentTimeMillis();
                long workOrderId = workRepo.insertWorkOrder(req.workKey(), req.operator(),
                        req.startUtc(), req.endUtc(), now);
                workRepo.replaceSections(workOrderId, sections);
                WorkOrderResponse response = loadWorkOrder(req.workKey());
                idemRepo.insert(OP_WORK_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_WORK_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 修改施工单：整体替换窗口与区段集合，版本加一。
     * 重校验全部已发布计划，若新窗口与任一已发布占用相交则 422 且不部分生效。
     */
    public WorkOrderResponse update(String workKey, UpdateWorkOrderRequest req) {
        List<String> sections = validateAndNormalize(req.startUtc(), req.endUtc(), req.sectionIds());
        String hash = hashUpdate(workKey, req, sections);
        Optional<WorkOrderResponse> replay = replayIfPresent(OP_WORK_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 全局锁内复查：并发下同键请求可能已在等待期间提交
                Optional<WorkOrderResponse> committed =
                        replayIfPresent(OP_WORK_UPDATE, req.requestKey(), hash);
                if (committed.isPresent()) {
                    return committed.get();
                }
                WorkOrder order = workRepo.findByKeyForUpdate(workKey)
                        .orElseThrow(() -> notFound(workKey));
                if (order.status() != WorkStatus.ACTIVE) {
                    throw conflict("WORK_STATE_CONFLICT",
                            "仅生效中施工单可修改，当前状态: " + order.status());
                }
                if (req.expectedVersion() != order.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + order.version() + " 不一致");
                }
                ensureNoWindowOverlap(sections, req.startUtc(), req.endUtc(), List.of(order.id()));
                ensureNoPublishedPlanConflict(workKey, sections, req.startUtc(), req.endUtc());
                long now = System.currentTimeMillis();
                workRepo.updateWorkOrder(order.id(), order.version() + 1, req.operator(),
                        req.startUtc(), req.endUtc(), now);
                workRepo.replaceSections(order.id(), sections);
                WorkOrderResponse response = loadWorkOrder(workKey);
                idemRepo.insert(OP_WORK_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_WORK_UPDATE, req.requestKey(), hash);
        }
    }

    /**
     * 取消未开始施工单：立即释放占用（状态置 CANCELLED）并追加不可变取消记录；
     * 已开始（当前时刻不早于窗口起点）施工单不可取消。
     */
    public WorkOrderResponse cancel(String workKey, WorkOrderActionRequest req) {
        String hash = hashAction(OP_WORK_CANCEL, workKey, req.operator());
        Optional<WorkOrderResponse> replay = replayIfPresent(OP_WORK_CANCEL, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 全局锁内复查：并发下同键请求可能已在等待期间提交
                Optional<WorkOrderResponse> committed =
                        replayIfPresent(OP_WORK_CANCEL, req.requestKey(), hash);
                if (committed.isPresent()) {
                    return committed.get();
                }
                WorkOrder order = workRepo.findByKeyForUpdate(workKey)
                        .orElseThrow(() -> notFound(workKey));
                if (order.status() != WorkStatus.ACTIVE) {
                    throw conflict("WORK_STATE_CONFLICT",
                            "仅生效中施工单可取消，当前状态: " + order.status());
                }
                if (!clock.instant().isBefore(order.startUtc())) {
                    throw conflict("WORK_ALREADY_STARTED",
                            "施工单已开始，不可取消: " + workKey);
                }
                long now = System.currentTimeMillis();
                workRepo.updateStatus(order.id(), WorkStatus.CANCELLED, now);
                workRepo.insertCancelRecord(workKey, order.version(), req.operator(), now);
                WorkOrderResponse response = loadWorkOrder(workKey);
                idemRepo.insert(OP_WORK_CANCEL, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_WORK_CANCEL, req.requestKey(), hash);
        }
    }

    /**
     * 按业务键查询施工单明细，不存在返回 404。
     */
    public WorkOrderResponse getWorkOrder(String workKey) {
        if (workRepo.findByKey(workKey).isEmpty()) {
            throw notFound(workKey);
        }
        return loadWorkOrder(workKey);
    }

    /**
     * 查询指定区段上当前生效的施工窗口。
     */
    public List<WorkWindowView> getWorkWindows(String sectionId) {
        return workRepo.findActiveWindows(List.of(sectionId), List.of()).stream()
                .map(w -> new WorkWindowView(w.workKey(), w.startUtc(), w.endUtc(),
                        workRepo.findSectionIds(
                                workRepo.findByKey(w.workKey()).orElseThrow().id())))
                .toList();
    }

    /**
     * 查询受施工单窗口影响的已发布计划（占用与窗口相交者），携带相交占用明细。
     * 施工单不存在返回 404；已取消施工单窗口不再生效，返回空列表。
     */
    public List<AffectedPlanView> getAffectedPlans(String workKey) {
        WorkOrder order = workRepo.findByKey(workKey).orElseThrow(() -> notFound(workKey));
        if (order.status() != WorkStatus.ACTIVE) {
            return List.of();
        }
        List<String> sections = workRepo.findSectionIds(order.id());
        List<PublishedSlot> slots = planRepo.findPublishedOccupanciesIntersecting(
                sections, order.startUtc(), order.endUtc());
        Map<String, List<OccupancyView>> byPlan = new LinkedHashMap<>();
        for (PublishedSlot slot : slots) {
            byPlan.computeIfAbsent(slot.scheduleKey(), k -> new ArrayList<>())
                    .add(new OccupancyView(slot.trainNo(), slot.sectionId(),
                            slot.startUtc(), slot.endUtc()));
        }
        return byPlan.entrySet().stream()
                .map(e -> {
                    var plan = planRepo.findByKey(e.getKey()).orElseThrow();
                    return new AffectedPlanView(e.getKey(), plan.opDate(), plan.status().name(),
                            e.getValue());
                })
                .toList();
    }

    /**
     * 查询指定施工单的取消记录；施工单不存在或未取消返回 404。
     */
    public CancelRecordView getCancellation(String workKey) {
        WorkCancelRecord record = workRepo.findCancelRecord(workKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CANCELLATION_NOT_FOUND",
                        "取消记录不存在: " + workKey));
        return toCancelView(record);
    }

    /**
     * 查询全部取消记录（按取消时刻与业务键升序）。
     */
    public List<CancelRecordView> getCancellations() {
        return workRepo.findAllCancelRecords().stream().map(this::toCancelView).toList();
    }

    // ---------- 内部实现 ----------

    private WorkOrderResponse loadWorkOrder(String workKey) {
        WorkOrder order = workRepo.findByKey(workKey)
                .orElseThrow(() -> notFound(workKey));
        return new WorkOrderResponse(order.workKey(), order.version(), order.status().name(),
                order.operator(), order.startUtc(), order.endUtc(),
                workRepo.findSectionIds(order.id()));
    }

    private CancelRecordView toCancelView(WorkCancelRecord record) {
        return new CancelRecordView(record.workKey(), record.version(), record.operator(),
                record.cancelledAt());
    }

    /**
     * 参数级校验与规范化：起点必须早于终点；区段去重排序（换序同参）且必须全部已注册。
     */
    private List<String> validateAndNormalize(Instant startUtc, Instant endUtc,
                                              List<String> sectionIds) {
        if (!endUtc.isAfter(startUtc)) {
            throw badRequest("施工窗口结束时刻必须晚于开始时刻");
        }
        List<String> sections = new ArrayList<>(new TreeSet<>(sectionIds));
        List<String> existing = workRepo.findSections(sections);
        if (existing.size() != sections.size()) {
            List<String> missing = new ArrayList<>(sections);
            missing.removeAll(existing);
            List<Map<String, Object>> details = new ArrayList<>();
            for (String sectionId : missing) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SECTION_NOT_FOUND");
                detail.put("sectionId", sectionId);
                details.add(detail);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "SECTION_NOT_FOUND",
                    "区段未注册: " + missing, details);
        }
        return sections;
    }

    /**
     * 与其他生效施工单在同区段上的窗口重叠检测（左闭右开，相邻合法）；
     * 重叠时抛出 409 并稳定列出冲突 workKey（字典序）。
     */
    private void ensureNoWindowOverlap(List<String> sections, Instant startUtc, Instant endUtc,
                                       List<Long> excludeWorkOrderIds) {
        List<WorkWindow> windows = workRepo.findActiveWindows(sections, excludeWorkOrderIds);
        TreeSet<String> conflicting = new TreeSet<>();
        for (WorkWindow w : windows) {
            boolean overlap = startUtc.isBefore(w.endUtc()) && w.startUtc().isBefore(endUtc);
            if (overlap) {
                conflicting.add(w.workKey());
            }
        }
        if (!conflicting.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "WORK_WINDOW_OVERLAP");
            detail.put("conflictingWorkKeys", new ArrayList<>(conflicting));
            throw new ApiException(HttpStatus.CONFLICT, "WORK_WINDOW_OVERLAP",
                    "与生效施工单窗口重叠: " + conflicting, List.of(detail));
        }
    }

    /**
     * 施工单版本修改后的全量已发布计划重校验：新窗口与任一已发布占用相交即 422，
     * 给出首个冲突区段与计划，事务回滚不部分生效。
     */
    private void ensureNoPublishedPlanConflict(String workKey, List<String> sections,
                                               Instant startUtc, Instant endUtc) {
        List<PublishedSlot> conflicts = planRepo.findPublishedOccupanciesIntersecting(
                sections, startUtc, endUtc);
        if (!conflicts.isEmpty()) {
            PublishedSlot first = conflicts.get(0);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "WORK_PLAN_CONFLICT");
            detail.put("workKey", workKey);
            detail.put("scheduleKey", first.scheduleKey());
            detail.put("sectionId", first.sectionId());
            detail.put("startUtc", first.startUtc().toString());
            detail.put("endUtc", first.endUtc().toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORK_PLAN_CONFLICT",
                    "修改后与已发布计划冲突，施工单未变更", List.of(detail));
        }
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<WorkOrderResponse> replayIfPresent(String opType, String requestKey,
                                                        String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 workKey 冲突。
     */
    private WorkOrderResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("WORK_KEY_EXISTS", "workKey 已存在"));
    }

    private String hashCreate(CreateWorkOrderRequest req, List<String> sections) {
        StringBuilder sb = new StringBuilder(OP_WORK_CREATE).append('\n')
                .append(req.operator()).append('\n')
                .append(req.workKey()).append('\n')
                .append(req.startUtc().toEpochMilli()).append('\n')
                .append(req.endUtc().toEpochMilli());
        appendSections(sb, sections);
        return sha256(sb.toString());
    }

    private String hashUpdate(String workKey, UpdateWorkOrderRequest req, List<String> sections) {
        StringBuilder sb = new StringBuilder(OP_WORK_UPDATE).append('\n')
                .append(req.operator()).append('\n')
                .append(workKey).append('\n')
                .append(req.expectedVersion()).append('\n')
                .append(req.startUtc().toEpochMilli()).append('\n')
                .append(req.endUtc().toEpochMilli());
        appendSections(sb, sections);
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String workKey, String operator) {
        return sha256(opType + '\n' + operator + '\n' + workKey);
    }

    private void appendSections(StringBuilder sb, List<String> sections) {
        for (String sectionId : sections) {
            sb.append('\n').append(sectionId);
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

    private WorkOrderResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, WorkOrderResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String workKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "WORK_NOT_FOUND",
                "施工单不存在: " + workKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
