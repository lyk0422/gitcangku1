package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateFreezeRequest;
import com.example.starter.firmware.api.EmergencyExceptionView;
import com.example.starter.firmware.api.EmergencyGrant;
import com.example.starter.firmware.api.FreezeBatchResponse;
import com.example.starter.firmware.api.FreezeView;
import com.example.starter.firmware.api.RegisterConfirmerRequest;
import com.example.starter.firmware.api.ReviseFreezeRequest;
import com.example.starter.firmware.domain.FreezeOrder;
import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ConfirmerRepository;
import com.example.starter.firmware.repo.EmergencyExceptionRepository;
import com.example.starter.firmware.repo.FreezeRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 固件发布冻结令：创建/批量创建/修订/撤销、生效窗口冻结扫荡、发布启动与新任务拉取的冻结守卫、
 * 紧急双人例外与查询。
 *
 * <p>所有写操作在 {@link IdempotencyService} 的同一事务内执行；守卫事务内先锁定全部 ACTIVE 冻结令
 * （按 id 升序 FOR UPDATE），再按冻结令提交顺序执行扫荡与冲突判定，与发布/拉取形成一致提交顺序。
 * 紧急例外放行的任务记录其针对的冻结令ID，其他冻结令开始时仍可冻结该任务。
 */
@Service
public class FreezeService {

    public static final String OP_RELEASE_START = "RELEASE_START";
    public static final String OP_TASK_PULL = "TASK_PULL";
    public static final String OP_BATCH_RELEASE_START = "BATCH_RELEASE_START";

    private final FreezeRepository freezeRepository;
    private final ConfirmerRepository confirmerRepository;
    private final EmergencyExceptionRepository exceptionRepository;
    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public FreezeService(FreezeRepository freezeRepository, ConfirmerRepository confirmerRepository,
                         EmergencyExceptionRepository exceptionRepository, ReleaseRepository releaseRepository,
                         TaskRepository taskRepository, IdempotencyService idempotency, Clock clock) {
        this.freezeRepository = freezeRepository;
        this.confirmerRepository = confirmerRepository;
        this.exceptionRepository = exceptionRepository;
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ============================ 确认人 ============================

    public RegisterConfirmerRequest.ConfirmerView registerConfirmer(RegisterConfirmerRequest request) {
        String fingerprint = String.join("|", "freeze.confirmer.register", request.name());
        return idempotency.execute(request.requestId(), "freeze.confirmer.register", fingerprint, () -> {
            if (!confirmerRepository.insert(request.name())) {
                throw ApiException.conflict("CONFIRMER_EXISTS", "确认人已登记: " + request.name());
            }
            return new RegisterConfirmerRequest.ConfirmerView(request.name());
        }, RegisterConfirmerRequest.ConfirmerView.class);
    }

    public List<String> listConfirmers() {
        return confirmerRepository.findAll();
    }

    // ============================ 冻结令创建/修订/撤销 ============================

    public FreezeView createFreeze(CreateFreezeRequest request) {
        NormalizedScope scope = validateSpec(request.startUtc(), request.endUtc(),
                request.models(), request.releaseIds(), null);
        String fingerprint = freezeFingerprint("freeze.create", request.startUtc(), request.endUtc(), scope);
        return idempotency.execute(request.requestId(), "freeze.create", fingerprint, () -> {
            freezeRepository.lockGuard();
            long id = insertFreeze(request.startUtc(), request.endUtc(), scope);
            sweepDueFreezes();
            return FreezeView.of(requireFreeze(id));
        }, FreezeView.class);
    }

    /**
     * 批量创建冻结令：先对全部单项按最终规范化范围预校验，任一非法或引用缺失即 422、全部不写入；
     * 全部通过后在同一事务写入并执行冻结扫荡。
     */
    public FreezeBatchResponse createFreezeBatch(CreateFreezeRequest.Batch request) {
        List<NormalizedScope> scopes = new ArrayList<>();
        int index = 0;
        for (CreateFreezeRequest.Spec spec : request.items()) {
            scopes.add(validateSpec(spec.startUtc(), spec.endUtc(), spec.models(), spec.releaseIds(), index));
            index++;
        }
        String fingerprint = "freeze.batch.create|" + request.items().size() + "|"
                + request.items().stream()
                .map(s -> {
                    NormalizedScope n = normalize(s.models(), s.releaseIds());
                    return s.startUtc() + "~" + s.endUtc() + "#" + n.fingerprint();
                }).reduce("", (a, b) -> a + ";" + b);
        return idempotency.execute(request.requestId(), "freeze.batch.create", fingerprint, () -> {
            freezeRepository.lockGuard();
            List<FreezeView> views = new ArrayList<>();
            for (int i = 0; i < request.items().size(); i++) {
                CreateFreezeRequest.Spec spec = request.items().get(i);
                long id = insertFreeze(spec.startUtc(), spec.endUtc(), scopes.get(i));
                views.add(FreezeView.of(requireFreeze(id)));
            }
            sweepDueFreezes();
            return new FreezeBatchResponse(views);
        }, FreezeBatchResponse.class);
    }

    public FreezeView revise(long freezeId, ReviseFreezeRequest request) {
        NormalizedScope scope = validateSpec(request.startUtc(), request.endUtc(),
                request.models(), request.releaseIds(), null);
        String fingerprint = String.join("|", "freeze.revise", String.valueOf(freezeId),
                String.valueOf(request.expectedVersion()), request.startUtc(), request.endUtc(),
                scope.fingerprint());
        return idempotency.execute(request.requestId(), "freeze.revise", fingerprint, () -> {
            freezeRepository.lockGuard();
            FreezeOrder order = freezeRepository.findByIdForUpdate(freezeId)
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + freezeId));
            if (order.status() != FreezeStatus.ACTIVE) {
                throw ApiException.conflict("FREEZE_ALREADY_REVOKED", "冻结令已撤销，不能修订: " + freezeId);
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("FREEZE_VERSION_CONFLICT",
                        "expectedVersion 与当前冻结版本不一致: " + order.version());
            }
            int rows = freezeRepository.revise(freezeId, request.expectedVersion(), request.startUtc(),
                    request.endUtc(), scope.models(), scope.releaseIds());
            if (rows != 1) {
                throw ApiException.conflict("FREEZE_VERSION_CONFLICT", "冻结令修订版本冲突");
            }
            sweepDueFreezes();
            return FreezeView.of(requireFreeze(freezeId));
        }, FreezeView.class);
    }

    /**
     * 撤销冻结令：只影响撤销后新发起的拉取/启动；已转为 RELEASE_FROZEN 的任务不复活。
     * 返回体携带撤销后命中范围、尚未开始（PENDING）的任务数，即撤销影响。
     */
    public RevokeResult revoke(long freezeId, String requestId) {
        String fingerprint = String.join("|", "freeze.revoke", String.valueOf(freezeId));
        return idempotency.execute(requestId, "freeze.revoke", fingerprint, () -> {
            freezeRepository.lockGuard();
            FreezeOrder order = freezeRepository.findByIdForUpdate(freezeId)
                    .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + freezeId));
            String nowUtc = Instant.now(clock).toString();
            if (order.status() == FreezeStatus.ACTIVE
                    && freezeRepository.revoke(freezeId, nowUtc) != 1) {
                throw ApiException.conflict("FREEZE_ALREADY_REVOKED", "冻结令已撤销: " + freezeId);
            }
            FreezeOrder revoked = requireFreeze(freezeId);
            long affected = taskRepository.countPendingByScope(revoked.models(), revoked.releaseIds());
            return new RevokeResult(FreezeView.of(revoked), affected);
        }, RevokeResult.class);
    }

    public record RevokeResult(FreezeView freeze, long affectedPendingTasks) {
    }

    // ============================ 守卫：发布启动 / 任务拉取 ============================

    /**
     * 在调用方事务内锁定全部 ACTIVE 冻结令并执行扫荡，但不做命中拒绝。
     * 供回执、取消等同样与冻结存在提交顺序竞争的写操作调用：先锁冻结令再改任务，
     * 保证“冻结、撤销、发布启动、拉取、确认和回执按提交顺序裁决”。
     */
    public void lockAndSweep() {
        freezeRepository.lockGuard();
        sweepDue(freezeRepository.findActiveForUpdate());
    }

    /**
     * 在调用方事务内执行：锁定 ACTIVE 冻结令、扫荡已到开始时刻的版本，并对单个发布启动做冻结守卫。
     *
     * @return 放行该启动的冻结令ID（紧急例外时非空）；未命中任何冻结窗口返回 null
     */
    public Long guardReleaseStart(String model, EmergencyGrant grant, String requestId) {
        freezeRepository.lockGuard();
        List<FreezeOrder> active = freezeRepository.findActiveForUpdate();
        sweepDue(active);
        FreezeOrder blocking = active.stream()
                .filter(f -> f.activeAt(Instant.now(clock)) && f.hits(model, null))
                .findFirst().orElse(null);
        if (blocking == null) {
            return null;
        }
        validateGrant(blocking, grant, OP_RELEASE_START, model);
        recordException(blocking, grant, OP_RELEASE_START, model, requestId);
        return blocking.id();
    }

    /**
     * 批量发布启动守卫：按最终范围逐项预校验，任一命中且例外不全即 422（调用方事务整体回滚）。
     *
     * @return 命中冻结的项所对应的冻结令ID（去重）；无命中为空列表
     */
    public List<Long> guardBatchReleaseStart(List<String> models, EmergencyGrant grant, String requestId) {
        freezeRepository.lockGuard();
        List<FreezeOrder> active = freezeRepository.findActiveForUpdate();
        sweepDue(active);
        Instant now = Instant.now(clock);
        LinkedHashSet<FreezeOrder> blocking = new LinkedHashSet<>();
        for (String model : models) {
            active.stream().filter(f -> f.activeAt(now) && f.hits(model, null)).findFirst()
                    .ifPresent(blocking::add);
        }
        if (blocking.isEmpty()) {
            return List.of();
        }
        // 同一份凭据必须完整覆盖全部命中项：任一项缺例外即整体拒绝
        for (String model : models) {
            FreezeOrder hit = active.stream()
                    .filter(f -> f.activeAt(now) && f.hits(model, null))
                    .findFirst().orElse(null);
            if (hit != null) {
                validateGrant(hit, grant, OP_BATCH_RELEASE_START, model);
            }
        }
        String reference = String.join(",", new LinkedHashSet<>(models));
        List<Long> freezeIds = new ArrayList<>();
        for (FreezeOrder hit : blocking) {
            recordException(hit, grant, OP_BATCH_RELEASE_START, reference, requestId);
            freezeIds.add(hit.id());
        }
        return freezeIds;
    }

    /**
     * 新任务拉取守卫：命中型号或发布单范围且处于生效窗口时，无完整双人例外即 422。
     *
     * @return 紧急例外放行针对的冻结令ID；正常放行（未冻结）返回 null
     */
    public Long guardTaskPull(String model, long releaseId, String deviceId, EmergencyGrant grant,
                              String requestId) {
        freezeRepository.lockGuard();
        List<FreezeOrder> active = freezeRepository.findActiveForUpdate();
        sweepDue(active);
        Instant now = Instant.now(clock);
        FreezeOrder blocking = active.stream()
                .filter(f -> f.activeAt(now) && f.hits(model, releaseId))
                .findFirst().orElse(null);
        if (blocking == null) {
            return null;
        }
        validateGrant(blocking, grant, OP_TASK_PULL, deviceId);
        recordException(blocking, grant, OP_TASK_PULL, deviceId, requestId);
        return blocking.id();
    }

    // ============================ 查询 ============================

    public List<FreezeView> activeFreezes() {
        return freezeRepository.findActive().stream().map(FreezeView::of).toList();
    }

    public FreezeView get(long freezeId) {
        return FreezeView.of(requireFreeze(freezeId));
    }

    public record FreezeHit(boolean scopeHit, boolean windowActive, boolean blocked, String reason) {
    }

    public FreezeHit checkHit(long freezeId, String model, Long releaseId) {
        FreezeOrder order = requireFreeze(freezeId);
        boolean scopeHit = order.hits(model, releaseId);
        boolean windowActive = order.activeAt(Instant.now(clock));
        return new FreezeHit(scopeHit, windowActive, scopeHit && windowActive,
                scopeHit && windowActive ? "命中生效冻结窗口" : "未命中生效冻结窗口");
    }

    public List<EmergencyExceptionView> exceptions(long freezeId) {
        requireFreeze(freezeId);
        return exceptionRepository.findByFreeze(freezeId).stream().map(EmergencyExceptionView::of).toList();
    }

    // ============================ 内部实现 ============================

    private NormalizedScope validateSpec(String startUtc, String endUtc, List<String> rawModels,
                                         List<Long> rawReleaseIds, Integer batchIndex) {
        String suffix = batchIndex == null ? "" : "（批量第" + batchIndex + "项）";
        Instant start;
        Instant end;
        try {
            start = Instant.parse(startUtc);
            end = Instant.parse(endUtc);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.unprocessable("FREEZE_WINDOW_INVALID", "冻结窗口必须为UTC ISO-8601时刻" + suffix);
        }
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("FREEZE_WINDOW_INVALID", "冻结窗口结束必须晚于开始" + suffix);
        }
        List<String> models = normalizeModels(rawModels, suffix);
        List<Long> releaseIds = normalizeReleaseIds(rawReleaseIds, suffix);
        if (models.isEmpty() && releaseIds.isEmpty()) {
            throw ApiException.unprocessable("FREEZE_SCOPE_EMPTY",
                    "硬件型号集合与发布单集合至少指定一项范围" + suffix);
        }
        for (long releaseId : releaseIds) {
            if (releaseRepository.findById(releaseId).isEmpty()) {
                throw ApiException.unprocessable("FREEZE_RELEASE_NOT_FOUND",
                        "冻结范围引用的发布单不存在: " + releaseId + suffix);
            }
        }
        return new NormalizedScope(models, releaseIds);
    }

    private List<String> normalizeModels(List<String> rawModels, String suffix) {
        Set<String> distinct = new LinkedHashSet<>();
        if (rawModels != null) {
            for (String model : rawModels) {
                if (model == null || model.isBlank()) {
                    throw ApiException.unprocessable("FREEZE_SCOPE_INVALID", "硬件型号范围不能为空" + suffix);
                }
                distinct.add(model.trim());
            }
        }
        return distinct.stream().sorted().toList();
    }

    private List<Long> normalizeReleaseIds(List<Long> rawReleaseIds, String suffix) {
        Set<Long> distinct = new LinkedHashSet<>();
        if (rawReleaseIds != null) {
            for (Long id : rawReleaseIds) {
                if (id == null || id <= 0) {
                    throw ApiException.unprocessable("FREEZE_SCOPE_INVALID", "发布单范围必须为正整数" + suffix);
                }
                distinct.add(id);
            }
        }
        return distinct.stream().sorted().toList();
    }

    private NormalizedScope normalize(List<String> rawModels, List<Long> rawReleaseIds) {
        return new NormalizedScope(normalizeModels(rawModels, ""), normalizeReleaseIds(rawReleaseIds, ""));
    }

    private long insertFreeze(String startUtc, String endUtc, NormalizedScope scope) {
        return freezeRepository.insert(startUtc, endUtc, scope.models(), scope.releaseIds());
    }

    /**
     * 对已在当前事务锁定的 ACTIVE 冻结令执行扫荡：窗口已开始（now≥start）但当前版本尚未扫荡时，
     * 把命中范围的 PENDING 任务（紧急例外针对本冻结令的除外）转为 RELEASE_FROZEN 并固化快照。
     */
    private void sweepDue(List<FreezeOrder> active) {
        Instant now = Instant.now(clock);
        String nowUtc = now.toString();
        for (FreezeOrder order : active) {
            Instant start = Instant.parse(order.startUtc());
            boolean due = !now.isBefore(start)
                    && (order.enforcedVersion() == null || order.enforcedVersion() != order.version());
            if (!due) {
                continue;
            }
            taskRepository.freezePendingByScope(order.id(), order.version(),
                    String.join(",", order.models()),
                    String.join(",", order.releaseIds().stream().map(String::valueOf).toList()),
                    order.startUtc(), order.endUtc(), order.models(), order.releaseIds(), nowUtc);
            freezeRepository.markEnforced(order.id(), order.version());
        }
    }

    /**
     * 锁定全部 ACTIVE 冻结令后扫荡（用于创建/修订冻结令的事务尾部）。
     */
    private void sweepDueFreezes() {
        sweepDue(freezeRepository.findActiveForUpdate());
    }

    /**
     * 紧急例外校验：事件号必填，两名确认人必须不同且均已登记，否则按可区分原因 422。
     */
    private void validateGrant(FreezeOrder blocking, EmergencyGrant grant, String operation, String reference) {
        if (grant == null) {
            throw blocked422(blocking, operation, reference, "EMERGENCY_GRANT_REQUIRED",
                    "命中生效冻结窗口，必须提供紧急双人例外");
        }
        if (grant.eventNo() == null || grant.eventNo().isBlank()) {
            throw blocked422(blocking, operation, reference, "EMERGENCY_EVENT_NO_REQUIRED",
                    "紧急例外必须提供事件号");
        }
        if (grant.confirmer1() == null || grant.confirmer2() == null
                || grant.confirmer1().isBlank() || grant.confirmer2().isBlank()) {
            throw blocked422(blocking, operation, reference, "EMERGENCY_CONFIRMER_REQUIRED",
                    "紧急例外必须提供两名已登记确认人");
        }
        if (grant.confirmer1().equals(grant.confirmer2())) {
            throw blocked422(blocking, operation, reference, "EMERGENCY_CONFIRMERS_SAME",
                    "两名确认人必须不同");
        }
        if (!confirmerRepository.exists(grant.confirmer1())
                || !confirmerRepository.exists(grant.confirmer2())) {
            throw blocked422(blocking, operation, reference, "EMERGENCY_CONFIRMER_NOT_REGISTERED",
                    "两名确认人必须均已登记");
        }
    }

    private ApiException blocked422(FreezeOrder blocking, String operation, String reference,
                                    String code, String reason) {
        return ApiException.unprocessable(code, reason + "；freezeId=" + blocking.id()
                + "，operation=" + operation + "，reference=" + reference);
    }

    private void recordException(FreezeOrder order, EmergencyGrant grant, String operation,
                                 String reference, String requestId) {
        exceptionRepository.insert(order.id(), grant.eventNo(), grant.confirmer1(), grant.confirmer2(),
                operation, reference, requestId, Instant.now(clock).toString());
    }

    private FreezeOrder requireFreeze(long id) {
        return freezeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结令不存在: " + id));
    }

    private String freezeFingerprint(String api, String startUtc, String endUtc, NormalizedScope scope) {
        return String.join("|", api, startUtc, endUtc, scope.fingerprint());
    }

    private record NormalizedScope(List<String> models, List<Long> releaseIds) {
        String fingerprint() {
            return String.join(",", models()) + "/"
                    + String.join(",", releaseIds().stream().map(String::valueOf).toList());
        }
    }
}
