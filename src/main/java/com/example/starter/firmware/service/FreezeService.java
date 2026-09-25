package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchCreateFreezeRequest;
import com.example.starter.firmware.api.BatchFreezeResponse;
import com.example.starter.firmware.api.CreateFreezeRequest;
import com.example.starter.firmware.api.EmergencyException;
import com.example.starter.firmware.api.ExceptionRecordView;
import com.example.starter.firmware.api.FreezeHitResponse;
import com.example.starter.firmware.api.FreezeOrderView;
import com.example.starter.firmware.api.ReviseFreezeRequest;
import com.example.starter.firmware.domain.FreezeOrder;
import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FreezeApproverRepository;
import com.example.starter.firmware.repo.FreezeExceptionRepository;
import com.example.starter.firmware.repo.FreezeGuardRepository;
import com.example.starter.firmware.repo.FreezeOrderRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 固件发布冻结令：创建（版本从1开始）、批量创建（全部不写入或全部写入）、修订（expectedVersion 乐观校验）、
 * 撤销（只解冻撤销后尚未开始的命中任务）。冻结、撤销、发布启动、拉取在同一事务内先取冻结裁决行锁，
 * 按提交顺序裁决。冻结开始时命中范围的 PENDING 任务转 RELEASE_FROZEN 并固化冻结令快照；
 * 已完成回执由条件更新保证不被改写。
 */
@Service
public class FreezeService {

    private final FreezeOrderRepository freezeRepository;
    private final FreezeApproverRepository approverRepository;
    private final FreezeExceptionRepository exceptionRepository;
    private final FreezeGuardRepository guardRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FreezeService(FreezeOrderRepository freezeRepository, FreezeApproverRepository approverRepository,
                         FreezeExceptionRepository exceptionRepository, FreezeGuardRepository guardRepository,
                         TaskRepository taskRepository, IdempotencyService idempotency,
                         ObjectMapper objectMapper, Clock clock) {
        this.freezeRepository = freezeRepository;
        this.approverRepository = approverRepository;
        this.exceptionRepository = exceptionRepository;
        this.guardRepository = guardRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建冻结令：freezeKey 为幂等键，指纹含冻结版本、规范化范围、窗口、例外事件号和确认人。
     */
    public FreezeOrderView create(CreateFreezeRequest request) {
        PreparedFreeze prepared = prepare(request);
        return idempotency.execute(request.freezeKey(), "freeze.create", prepared.fingerprint(), () -> {
            guardRepository.lock();
            long id = freezeRepository.insert(request.freezeKey(), csv(prepared.models()),
                    csvLong(prepared.releaseIds()), prepared.start().toString(), prepared.end().toString(),
                    prepared.exception() == null ? null : prepared.exception().incidentId(),
                    prepared.exception() == null ? null : String.join(",", prepared.exception().approvers()));
            FreezeOrder order = findOrder(id);
            freezeHitPendingTasksIfActive(order);
            return toView(order);
        }, FreezeOrderView.class);
    }

    /**
     * 批量创建：先按最终冻结范围预校验全部条目，任一冲突或例外不全则整批 422，全部不写入。
     */
    public BatchFreezeResponse createBatch(BatchCreateFreezeRequest request) {
        List<PreparedFreeze> prepared = request.freezes().stream()
                .map(this::prepare)
                .toList();
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < prepared.size(); i++) {
            String key = request.freezes().get(i).freezeKey();
            if (!seenKeys.add(key)) {
                throw ApiException.unprocessable("FREEZE_KEY_DUPLICATE",
                        "批量内 freezeKey 重复: " + key);
            }
            if (freezeRepository.findByFreezeKey(key).isPresent()) {
                throw ApiException.unprocessable("FREEZE_KEY_EXISTS", "freezeKey 已存在: " + key);
            }
        }
        String fingerprint = String.join("|", prepared.stream().map(PreparedFreeze::fingerprint).toList());
        return idempotency.execute(request.requestId(), "freeze.createBatch", fingerprint, () -> {
            guardRepository.lock();
            List<FreezeOrderView> views = new ArrayList<>();
            for (int i = 0; i < prepared.size(); i++) {
                PreparedFreeze item = prepared.get(i);
                long id = freezeRepository.insert(request.freezes().get(i).freezeKey(), csv(item.models()),
                        csvLong(item.releaseIds()), item.start().toString(), item.end().toString(),
                        item.exception() == null ? null : item.exception().incidentId(),
                        item.exception() == null ? null : String.join(",", item.exception().approvers()));
                FreezeOrder order = findOrder(id);
                freezeHitPendingTasksIfActive(order);
                views.add(toView(order));
            }
            return new BatchFreezeResponse(views);
        }, BatchFreezeResponse.class);
    }

    /**
     * 修订冻结令：仅 ACTIVE 可修订；expectedVersion 校验通过后版本加一；
     * 新窗口已生效时立即冻结命中范围的未开始任务。
     */
    public FreezeOrderView revise(long freezeId, ReviseFreezeRequest request) {
        PreparedFreeze prepared = prepare(request.models(), request.releaseIds(), request.startUtc(),
                request.endUtc(), request.exception());
        String fingerprint = String.join("|", "freeze.revise", String.valueOf(freezeId),
                String.valueOf(request.expectedVersion()), prepared.fingerprint());
        return idempotency.execute(request.requestId(), "freeze.revise", fingerprint, () -> {
            guardRepository.lock();
            FreezeOrder order = freezeRepository.findByIdForUpdate(freezeId)
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + freezeId));
            if (order.status() == FreezeStatus.REVOKED) {
                throw ApiException.conflict("FREEZE_REVOKED", "冻结令已撤销，不能修订");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            freezeRepository.revise(freezeId, request.expectedVersion(), csv(prepared.models()),
                    csvLong(prepared.releaseIds()), prepared.start().toString(), prepared.end().toString(),
                    prepared.exception() == null ? null : prepared.exception().incidentId(),
                    prepared.exception() == null ? null : String.join(",", prepared.exception().approvers()));
            FreezeOrder revised = findOrder(freezeId);
            freezeHitPendingTasksIfActive(revised);
            return toView(revised);
        }, FreezeOrderView.class);
    }

    /**
     * 撤销冻结令：只影响撤销后尚未开始的命中任务（RELEASE_FROZEN 回 PENDING 并清除冻结快照）；
     * 已完成回执与已取消任务不受影响。重复撤销幂等返回当前状态。
     */
    public FreezeOrderView revoke(long freezeId, String requestId) {
        String fingerprint = String.join("|", "freeze.revoke", String.valueOf(freezeId));
        return idempotency.execute(requestId, "freeze.revoke", fingerprint, () -> {
            guardRepository.lock();
            FreezeOrder order = freezeRepository.findByIdForUpdate(freezeId)
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + freezeId));
            if (order.status() == FreezeStatus.REVOKED) {
                return toView(order);
            }
            List<Long> affected = taskRepository.unfreezeByFreezeOrder(freezeId);
            freezeRepository.revoke(freezeId, Instant.now(clock).toString(), csvLong(affected));
            return toView(findOrder(freezeId));
        }, FreezeOrderView.class);
    }

    /**
     * 冻结令列表；effective=true 时只返回当前生效（ACTIVE 且处于窗口内）的冻结令。
     */
    public List<FreezeOrderView> list(boolean effectiveOnly) {
        Instant now = Instant.now(clock);
        return freezeRepository.findAll().stream()
                .filter(order -> !effectiveOnly || isWindowActive(order, now))
                .map(order -> toView(order, now))
                .toList();
    }

    public FreezeOrderView get(long freezeId) {
        return toView(findOrder(freezeId));
    }

    /**
     * 范围命中查询：返回范围命中的 ACTIVE 冻结令；hit 表示此刻存在窗口生效中的命中冻结令。
     */
    public FreezeHitResponse hit(String model, Long releaseId) {
        if ((model == null || model.isBlank()) && releaseId == null) {
            throw ApiException.badRequest("HIT_SCOPE_REQUIRED", "model 与 releaseId 至少提供一个");
        }
        Instant now = Instant.now(clock);
        List<FreezeOrder> matches = freezeRepository.findByStatus(FreezeStatus.ACTIVE).stream()
                .filter(order -> scopeHits(order, model, releaseId))
                .toList();
        boolean hit = matches.stream().anyMatch(order -> isWindowActive(order, now));
        List<FreezeOrderView> views = matches.stream().map(order -> toView(order, now)).toList();
        return new FreezeHitResponse(hit, views);
    }

    /**
     * 紧急例外放行记录（只读）。
     */
    public List<ExceptionRecordView> exceptions() {
        return exceptionRepository.findAll().stream().map(ExceptionRecordView::of).toList();
    }

    /**
     * 发布启动冻结预校验：命中生效窗口内的冻结范围时，必须携带完整紧急例外。
     * 返回规范化后的例外（未命中或无例外返回 null），调用方在业务写入成功后记录放行。
     */
    public EmergencyException assertReleaseStartAllowed(String model, EmergencyException exception) {
        guardRepository.lock();
        List<FreezeOrder> hits = matchingActiveFreezes(model, null, Instant.now(clock));
        if (hits.isEmpty()) {
            return null;
        }
        return requireValidException(exception);
    }

    /**
     * 新任务拉取冻结预校验：与发布启动同一裁决规则。调用方须先持有冻结裁决行锁。
     * 返回规范化后的例外（未命中或无例外返回 null），调用方在任务创建成功后记录放行。
     */
    public EmergencyException assertPullAllowed(String model, long releaseId, EmergencyException exception) {
        List<FreezeOrder> hits = matchingActiveFreezes(model, releaseId, Instant.now(clock));
        if (hits.isEmpty()) {
            return null;
        }
        return requireValidException(exception);
    }

    /**
     * 记录一次紧急例外放行（与业务写入同事务提交）。
     */
    public void recordExceptionUse(String api, String ref, EmergencyException exception) {
        exceptionRepository.insert(api, exception.incidentId(), String.join(",", exception.approvers()),
                ref, Instant.now(clock).toString());
    }

    /**
     * 获取冻结裁决行锁（持有至当前事务提交），冻结相关写操作与发布启动、拉取共用。
     */
    public void lockGuard() {
        guardRepository.lock();
    }

    public FreezeOrder findOrder(long freezeId) {
        return freezeRepository.findById(freezeId)
                .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + freezeId));
    }

    private PreparedFreeze prepare(CreateFreezeRequest request) {
        return prepare(request.models(), request.releaseIds(), request.startUtc(), request.endUtc(),
                request.exception());
    }

    /**
     * 预校验并规范化：窗口结束必须晚于开始（422），范围至少一项（422），例外不全（422）。
     */
    private PreparedFreeze prepare(List<String> models, List<Long> releaseIds,
                                   String startUtc, String endUtc, EmergencyException exception) {
        Instant start = parseUtc(startUtc);
        Instant end = parseUtc(endUtc);
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("INVALID_WINDOW", "窗口结束必须晚于开始: " + startUtc + " ~ " + endUtc);
        }
        List<String> normalizedModels = normalizeModels(models);
        List<Long> normalizedReleaseIds = normalizeReleaseIds(releaseIds);
        if (normalizedModels.isEmpty() && normalizedReleaseIds.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_FREEZE_SCOPE", "硬件型号集合与发布单集合至少指定一项");
        }
        EmergencyException normalizedException = exception == null ? null : requireValidException(exception);
        String fingerprint = String.join("|", "freeze.create", "v1", csv(normalizedModels),
                csvLong(normalizedReleaseIds), start.toString(), end.toString(),
                normalizedException == null ? "-" : normalizedException.incidentId(),
                normalizedException == null ? "-" : String.join(",", normalizedException.approvers()));
        return new PreparedFreeze(normalizedModels, normalizedReleaseIds, start, end,
                normalizedException, fingerprint);
    }

    /**
     * 校验紧急例外完整性：事件号非空、恰好两名不同确认人、确认人均已登记。
     */
    private EmergencyException requireValidException(EmergencyException exception) {
        if (exception == null) {
            throw ApiException.unprocessable("RELEASE_FROZEN", "命中生效中的冻结令，且未提供紧急例外");
        }
        if (exception.incidentId() == null || exception.incidentId().isBlank()
                || exception.approvers() == null || exception.approvers().size() != 2) {
            throw ApiException.unprocessable("EXCEPTION_INCOMPLETE",
                    "紧急例外必须同时提供事件号和两名确认人");
        }
        List<String> approvers = exception.approvers().stream().map(String::trim).sorted().toList();
        if (approvers.get(0).isEmpty() || approvers.get(0).equals(approvers.get(1))) {
            throw ApiException.unprocessable("EXCEPTION_APPROVER_DUPLICATE", "两名确认人必须不同");
        }
        for (String approver : approvers) {
            if (!approverRepository.exists(approver)) {
                throw ApiException.unprocessable("EXCEPTION_APPROVER_UNKNOWN", "确认人未登记: " + approver);
            }
        }
        return new EmergencyException(exception.incidentId().trim(), approvers);
    }

    private List<FreezeOrder> matchingActiveFreezes(String model, Long releaseId, Instant now) {
        return freezeRepository.findByStatus(FreezeStatus.ACTIVE).stream()
                .filter(order -> isWindowActive(order, now))
                .filter(order -> scopeHits(order, model, releaseId))
                .toList();
    }

    private boolean scopeHits(FreezeOrder order, String model, Long releaseId) {
        return (model != null && order.models().contains(model))
                || (releaseId != null && order.releaseIds().contains(releaseId));
    }

    private boolean isWindowActive(FreezeOrder order, Instant now) {
        return order.status() == FreezeStatus.ACTIVE
                && !now.isBefore(Instant.parse(order.startUtc()))
                && now.isBefore(Instant.parse(order.endUtc()));
    }

    /**
     * 窗口生效中时，将命中范围的 PENDING 任务转为 RELEASE_FROZEN 并固化冻结令快照。
     * 条件更新保证与回执并发时已完成任务不被改写，返回本次冻结的任务ID。
     */
    private List<Long> freezeHitPendingTasksIfActive(FreezeOrder order) {
        if (!isWindowActive(order, Instant.now(clock))) {
            return List.of();
        }
        String snapshot = snapshotJson(order);
        List<Long> frozen = new ArrayList<>();
        for (TaskRepository.PendingTaskWithModel pending : taskRepository.findPendingWithModel()) {
            if (scopeHits(order, pending.model(), pending.releaseId())
                    && taskRepository.freezeIfPending(pending.taskId(), order.id(), snapshot) == 1) {
                frozen.add(pending.taskId());
            }
        }
        return frozen;
    }

    /**
     * 冻结令快照：冻结时刻的版本、规范化范围与窗口，固化到任务上。
     */
    private String snapshotJson(FreezeOrder order) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("freezeOrderId", order.id());
            snapshot.put("freezeKey", order.freezeKey());
            snapshot.put("version", order.version());
            snapshot.put("models", order.models());
            snapshot.put("releaseIds", order.releaseIds());
            snapshot.put("startUtc", order.startUtc());
            snapshot.put("endUtc", order.endUtc());
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("冻结令快照序列化失败", e);
        }
    }

    private FreezeOrderView toView(FreezeOrder order) {
        return toView(order, Instant.now(clock));
    }

    private FreezeOrderView toView(FreezeOrder order, Instant now) {
        return FreezeOrderView.of(order, isWindowActive(order, now),
                taskRepository.findFrozenTaskIds(order.id()));
    }

    private Instant parseUtc(String raw) {
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_UTC_FORMAT", "UTC 时间格式非法: " + raw);
        }
    }

    private static List<String> normalizeModels(List<String> models) {
        if (models == null) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(models.stream().map(String::trim).toList()));
    }

    private static List<Long> normalizeReleaseIds(List<Long> releaseIds) {
        if (releaseIds == null) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(releaseIds));
    }

    private static String csv(List<String> values) {
        return String.join(",", values);
    }

    private static String csvLong(List<Long> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }

    /**
     * 预校验后的冻结令内容：规范化范围、窗口、例外与幂等指纹。
     */
    private record PreparedFreeze(List<String> models, List<Long> releaseIds, Instant start, Instant end,
                                  EmergencyException exception, String fingerprint) {
    }
}
