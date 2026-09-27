package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DeprecationEventResponse;
import com.example.starter.api.dto.DeprecationRequest;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.DeprecationWarningView;
import com.example.starter.api.dto.DeprecatedBlockedItem;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.DeprecationWindow;
import com.example.starter.domain.DeprecationEvent;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.ReplacementGraph;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.LockWarningRow;
import com.example.starter.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 *
 * <p>弃用窗口按可注入 {@link Clock} 的当前 UTC 时刻裁决，区间左闭右开：
 * 解析时刻 {@code t >= effectiveAt} 视为已生效（锁定 422），{@code t < effectiveAt}
 * 仅固化 DEPRECATION_WARNING。锁一旦提交，其解析时刻、警告与替代建议均不再受
 * 后续窗口调整、撤销或替代版本撤回影响。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_REGISTER_DEPRECATION = "REGISTER_DEPRECATION";
    private static final String OP_BATCH_DEPRECATION = "BATCH_DEPRECATION";
    private static final String OP_REVOKE_DEPRECATION = "REVOKE_DEPRECATION";

    private static final String EVENT_REGISTERED = "REGISTERED";
    private static final String EVENT_ADJUSTED = "ADJUSTED";
    private static final String EVENT_REVOKED = "REVOKED";

    private final RepositoryDao repositoryDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArtifactServiceImpl(RepositoryDao repositoryDao,
                               TransactionTemplate transactionTemplate,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.repositoryDao = repositoryDao;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request) {
        requireRequestId(requestId);
        validateRegisterRequest(request);
        String hash = sha256(OP_REGISTER + "|" + request.name().trim() + "|" + request.version() + "|"
                + canonicalDependencies(request));
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_WITHDRAW + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version), ArtifactResponse.class);
    }

    @Override
    public DeprecationResponse registerDeprecation(String requestId, DeprecationRequest request) {
        requireRequestId(requestId);
        validateDeprecationRequest(request);
        String hash = sha256(OP_REGISTER_DEPRECATION + "|" + canonicalDeprecation(request));
        return executeIdempotent(requestId, OP_REGISTER_DEPRECATION, hash, 201,
                () -> doRegisterDeprecation(request), DeprecationResponse.class);
    }

    @Override
    public BatchDeprecationResponse batchDeprecations(String requestId, BatchDeprecationRequest request) {
        requireRequestId(requestId);
        if (request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("批量弃用窗口至少包含一项");
        }
        for (DeprecationRequest item : request.items()) {
            validateDeprecationRequest(item);
        }
        String hash = sha256(OP_BATCH_DEPRECATION + "|" + request.items().stream()
                .sorted(Comparator.comparing(DeprecationRequest::name)
                        .thenComparingInt(DeprecationRequest::version))
                .map(this::canonicalDeprecation)
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
        return executeIdempotent(requestId, OP_BATCH_DEPRECATION, hash, 201,
                () -> doBatchDeprecations(request.items()), BatchDeprecationResponse.class);
    }

    @Override
    public DeprecationResponse revokeDeprecation(String requestId, long deprecationId) {
        requireRequestId(requestId);
        if (deprecationId <= 0) {
            throw ApiException.badRequest("deprecationId 必须为正整数");
        }
        String hash = sha256(OP_REVOKE_DEPRECATION + "|" + deprecationId);
        return executeIdempotent(requestId, OP_REVOKE_DEPRECATION, hash, 200,
                () -> doRevokeDeprecation(deprecationId), DeprecationResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String hash = sha256(OP_LOCK + "|" + request.rootName().trim() + "|" + request.rootVersion()
                + "|" + request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row,
                    repositoryDao.listLockEntries(row.id()),
                    repositoryDao.listLockWarnings(row.id())));
        }
        return result;
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row,
                repositoryDao.listLockEntries(id),
                repositoryDao.listLockWarnings(id));
    }

    @Override
    public List<DeprecationResponse> listActiveDeprecations() {
        return repositoryDao.listActiveDeprecations().stream()
                .map(ArtifactServiceImpl::toDeprecationResponse)
                .toList();
    }

    @Override
    public DeprecationResponse getDeprecation(long id) {
        DeprecationWindow window = repositoryDao.getDeprecation(id);
        if (window == null) {
            throw ApiException.notFound("弃用窗口不存在: " + id);
        }
        return toDeprecationResponse(window);
    }

    @Override
    public List<DeprecationEventResponse> listDeprecationHistory() {
        return repositoryDao.listDeprecationEvents().stream()
                .map(ArtifactServiceImpl::toEventResponse)
                .toList();
    }

    // ------------------------------------------------------------------
    // 业务操作（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    private ArtifactResponse doRegister(RegisterArtifactRequest request) {
        String name = request.name().trim();
        int version = request.version();

        if (repositoryDao.artifactExists(name, version)) {
            throw ApiException.conflict("制品版本已存在: " + name + ":" + version);
        }
        int existingVersions = repositoryDao.countVersions(name);
        if (existingVersions == 0 && repositoryDao.countDistinctNames() >= MAX_NAMES) {
            throw ApiException.unprocessable(
                    "仓库名称数量已达上限 " + MAX_NAMES);
        }
        if (existingVersions >= MAX_VERSIONS_PER_NAME) {
            throw ApiException.unprocessable(
                    "制品 " + name + " 的版本数量已达上限 " + MAX_VERSIONS_PER_NAME);
        }

        Instant now = Instant.now(clock);
        long artifactId = repositoryDao.insertArtifact(name, version, now);
        for (var dep : request.dependencies()) {
            repositoryDao.insertDependency(artifactId, dep.name().trim(),
                    dep.minimumVersion(), dep.maximumVersion());
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, repositoryVersion, now,
                toDependencyViews(request.dependencies()));
    }

    private ArtifactResponse doWithdraw(String name, int version) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回: " + name + ":" + version);
        }
        int affected = repositoryDao.markWithdrawn(artifact.id());
        if (affected == 0) {
            // 并发撤回抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("制品版本已撤回: " + name + ":" + version);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ArtifactResponse(name, version, true, repositoryVersion,
                Instant.now(clock), toDependencyViews(artifact.dependencies()));
    }

    private DeprecationResponse doRegisterDeprecation(DeprecationRequest request) {
        Instant now = Instant.now(clock);
        PlannedWindow planned = planSingleWindow(request, now);
        if (repositoryDao.findActiveDeprecation(planned.artifact.id()) != null) {
            throw ApiException.conflict("制品版本已存在有效弃用窗口: "
                    + planned.artifact.name() + ":" + planned.artifact.version());
        }

        // 完整后态：已有全部 ACTIVE 边 + 本次新边，统一做替代环校验。
        List<ReplacementGraph.Edge> edges = collectExistingEdges();
        edges.add(new ReplacementGraph.Edge(planned.artifact.id(), planned.replacement.id()));
        assertNoReplacementCycle(edges);

        Instant createdAt = now;
        long deprecationId = repositoryDao.insertDeprecation(planned.artifact.id(),
                request.effectiveAt(), planned.replacement.id(), request.note(),
                currentRequestId.get(), createdAt);
        repositoryDao.insertDeprecationEvent(deprecationId, planned.artifact.id(),
                planned.replacement.id(), EVENT_REGISTERED, request.effectiveAt(),
                request.note(), currentRequestId.get(), now);
        repositoryDao.incrementRepositoryVersion();

        return toDeprecationResponse(requireWindow(deprecationId));
    }

    private BatchDeprecationResponse doBatchDeprecations(List<DeprecationRequest> items) {
        Instant now = Instant.now(clock);

        // 先逐项校验并规划后态；批量内同一制品版本出现多次直接拒绝，避免后态二义。
        Map<Long, PlannedWindow> plansByArtifactId = new LinkedHashMap<>();
        Set<String> keysInBatch = new HashSet<>();
        for (DeprecationRequest item : items) {
            String key = item.name().trim() + ":" + item.version();
            if (!keysInBatch.add(key)) {
                throw ApiException.unprocessable(
                        "批量请求中同一制品版本出现多次: " + key);
            }
            PlannedWindow planned = planSingleWindow(item, now);
            plansByArtifactId.put(planned.artifact.id(), planned);
        }

        // 合并完整后态：已有 ACTIVE 边，被本次命中的版本以新边替换。
        Map<Long, ReplacementGraph.Edge> mergedEdges = new LinkedHashMap<>();
        for (ReplacementGraph.Edge edge : collectExistingEdges()) {
            mergedEdges.put(edge.deprecatedArtifactId(), edge);
        }
        List<DeprecationWindow> existingWindows = repositoryDao.listActiveDeprecations();
        Map<Long, DeprecationWindow> windowByArtifactId = new HashMap<>();
        for (DeprecationWindow window : existingWindows) {
            windowByArtifactId.put(window.artifactId(), window);
        }
        for (PlannedWindow planned : plansByArtifactId.values()) {
            mergedEdges.put(planned.artifact.id(),
                    new ReplacementGraph.Edge(planned.artifact.id(), planned.replacement.id()));
        }
        assertNoReplacementCycle(List.copyOf(mergedEdges.values()));

        // 校验通过后在同一事务内写入全部窗口（既有调整、新增登记）与事件。
        for (PlannedWindow planned : plansByArtifactId.values()) {
            DeprecationWindow existing = windowByArtifactId.get(planned.artifact.id());
            DeprecationRequest item = planned.request;
            long deprecationId;
            String eventType;
            if (existing != null) {
                deprecationId = existing.id();
                repositoryDao.updateDeprecation(deprecationId, item.effectiveAt(),
                        planned.replacement.id(), item.note(), currentRequestId.get());
                eventType = EVENT_ADJUSTED;
            } else {
                deprecationId = repositoryDao.insertDeprecation(planned.artifact.id(),
                        item.effectiveAt(), planned.replacement.id(), item.note(),
                        currentRequestId.get(), now);
                eventType = EVENT_REGISTERED;
            }
            repositoryDao.insertDeprecationEvent(deprecationId, planned.artifact.id(),
                    planned.replacement.id(), eventType, item.effectiveAt(),
                    item.note(), currentRequestId.get(), now);
        }
        repositoryDao.incrementRepositoryVersion();

        Set<Long> plannedIds = plansByArtifactId.keySet();
        List<DeprecationResponse> windows = repositoryDao.listActiveDeprecations().stream()
                .filter(w -> plannedIds.contains(w.artifactId()))
                .map(ArtifactServiceImpl::toDeprecationResponse)
                .sorted(Comparator.comparing(DeprecationResponse::name)
                        .thenComparingInt(DeprecationResponse::version))
                .toList();
        return new BatchDeprecationResponse(items.size(), items.size(), windows);
    }

    private DeprecationResponse doRevokeDeprecation(long deprecationId) {
        DeprecationWindow window = repositoryDao.getDeprecation(deprecationId);
        if (window == null) {
            throw ApiException.notFound("弃用窗口不存在: " + deprecationId);
        }
        if (window.status() == com.example.starter.domain.DeprecationStatus.REVOKED) {
            throw ApiException.conflict("弃用窗口已撤销: " + deprecationId);
        }
        Instant now = Instant.now(clock);
        // 左闭：now >= effectiveAt 即已生效，不允许撤销。
        if (!now.isBefore(window.effectiveAt())) {
            throw ApiException.unprocessable("弃用窗口已生效，不能撤销: " + deprecationId);
        }
        int affected = repositoryDao.markDeprecationRevoked(deprecationId, now);
        if (affected == 0) {
            throw ApiException.conflict("弃用窗口状态已变化，撤销失败: " + deprecationId);
        }
        // 撤销事件保留撤销前一刻的生效时刻与说明快照，历史可查且不可回写。
        repositoryDao.insertDeprecationEvent(deprecationId, window.artifactId(),
                window.replacementId(), EVENT_REVOKED, window.effectiveAt(),
                window.note(), currentRequestId.get(), now);
        repositoryDao.incrementRepositoryVersion();
        return toDeprecationResponse(requireWindow(deprecationId));
    }

    private LockFileResponse doLock(LockRequest request) {
        String rootName = request.rootName().trim();
        int rootVersion = request.rootVersion();

        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

        ArtifactVersion root = repositoryDao.loadArtifact(rootName, rootVersion);
        if (root == null) {
            throw ApiException.notFound("根制品版本不存在: " + rootName + ":" + rootVersion);
        }
        if (root.withdrawn()) {
            throw ApiException.conflict("根制品版本已撤回: " + rootName + ":" + rootVersion);
        }

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        // 解析时刻是弃用裁决的唯一时间口径，随锁固化。
        Instant resolvedAt = Instant.now(clock);

        // name:version -> artifactId，供闭包命中窗口查找。
        Map<String, Long> artifactIds = new HashMap<>();
        snapshot.artifacts().values().stream()
                .flatMap(List::stream)
                .forEach(a -> artifactIds.put(a.name() + ":" + a.version(), a.id()));
        Map<Long, DeprecationWindow> activeByArtifactId = new HashMap<>();
        for (DeprecationWindow window : repositoryDao.listActiveDeprecations()) {
            activeByArtifactId.put(window.artifactId(), window);
        }

        List<DeprecatedBlockedItem> blocked = new ArrayList<>();
        List<DeprecationWarningView> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            Long artifactId = artifactIds.get(entry.getKey() + ":" + entry.getValue());
            if (artifactId == null) {
                throw new IllegalStateException("解析结果缺少制品 ID: "
                        + entry.getKey() + ":" + entry.getValue());
            }
            DeprecationWindow window = activeByArtifactId.get(artifactId);
            if (window == null) {
                continue;
            }
            // 左闭右开：resolvedAt >= effectiveAt 为已生效；resolvedAt < effectiveAt 为待生效。
            if (!resolvedAt.isBefore(window.effectiveAt())) {
                blocked.add(new DeprecatedBlockedItem(window.name(), window.version(),
                        window.replacementName(), window.replacementVersion(),
                        window.effectiveAt()));
            } else {
                warnings.add(new DeprecationWarningView(window.name(), window.version(),
                        window.replacementName(), window.replacementVersion(),
                        window.effectiveAt(), window.note(), resolvedAt));
            }
        }

        if (!blocked.isEmpty()) {
            // 稳定排序：制品名称、版本升序；整次 422，不写入锁文件、条目或警告。
            blocked.sort(Comparator.comparing(DeprecatedBlockedItem::name)
                    .thenComparingInt(DeprecatedBlockedItem::version));
            throw ApiException.deprecatedInClosure(List.copyOf(blocked));
        }
        warnings.sort(Comparator.comparing(DeprecationWarningView::name)
                .thenComparingInt(DeprecationWarningView::version));

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), resolvedAt, resolvedAt);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        for (DeprecationWarningView warning : warnings) {
            repositoryDao.insertLockWarning(lockFileId, warning.name(), warning.version(),
                    warning.replacementName(), warning.replacementVersion(),
                    warning.effectiveAt(), warning.note(), warning.resolvedAt());
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion,
                resolvedAt, resolvedAt, List.copyOf(warnings), entries);
    }

    // ------------------------------------------------------------------
    // 弃用窗口校验辅助
    // ------------------------------------------------------------------

    /** 单个窗口的校验后计划：被弃用版本、替代版本（同名、存在、未撤回）与生效时刻（不早于当前时钟）。 */
    private record PlannedWindow(DeprecationRequest request,
                                 ArtifactVersion artifact,
                                 ArtifactVersion replacement) {
    }

    private PlannedWindow planSingleWindow(DeprecationRequest request, Instant now) {
        String name = request.name().trim();
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, request.version());
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + request.version());
        }
        if (request.effectiveAt().isBefore(now)) {
            throw ApiException.unprocessable("弃用生效时刻不得早于当前时钟: "
                    + request.effectiveAt());
        }
        ArtifactVersion replacement = repositoryDao.loadArtifact(name, request.replacementVersion());
        if (replacement == null) {
            throw ApiException.unprocessable("替代版本不存在: "
                    + name + ":" + request.replacementVersion());
        }
        if (replacement.withdrawn()) {
            throw ApiException.unprocessable("替代版本已撤回: "
                    + name + ":" + request.replacementVersion());
        }
        return new PlannedWindow(request, artifact, replacement);
    }

    private List<ReplacementGraph.Edge> collectExistingEdges() {
        List<ReplacementGraph.Edge> edges = new ArrayList<>();
        for (DeprecationWindow window : repositoryDao.listActiveDeprecations()) {
            edges.add(new ReplacementGraph.Edge(window.artifactId(), window.replacementId()));
        }
        return edges;
    }

    private void assertNoReplacementCycle(List<ReplacementGraph.Edge> edges) {
        List<Long> cycle = ReplacementGraph.findCycle(edges);
        if (!cycle.isEmpty()) {
            throw ApiException.unprocessable("替代版本关系形成环，环上制品版本 ID: " + cycle);
        }
    }

    private DeprecationWindow requireWindow(long id) {
        DeprecationWindow window = repositoryDao.getDeprecation(id);
        if (window == null) {
            throw new IllegalStateException("刚写入的弃用窗口不存在: " + id);
        }
        return window;
    }

    private void validateDeprecationRequest(DeprecationRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (request.effectiveAt() == null) {
            throw ApiException.badRequest("effectiveAt 不能为空");
        }
        if (request.replacementVersion() <= 0) {
            throw ApiException.badRequest("replacementVersion 必须为正整数");
        }
        if (request.note() != null && request.note().length() > 512) {
            throw ApiException.badRequest("弃用说明最长 512 字符");
        }
    }

    private String canonicalDeprecation(DeprecationRequest request) {
        return request.name().trim() + "|" + request.version() + "|"
                + request.effectiveAt() + "|" + request.replacementVersion() + "|"
                + (request.note() == null ? "" : request.note());
    }

    private static DeprecationResponse toDeprecationResponse(DeprecationWindow window) {
        return new DeprecationResponse(window.id(), window.name(), window.version(),
                window.effectiveAt(), window.replacementName(), window.replacementVersion(),
                window.note(), window.status().name(), window.revokedAt(), window.createdAt());
    }

    private static DeprecationEventResponse toEventResponse(DeprecationEvent event) {
        return new DeprecationEventResponse(event.id(), event.deprecationId(),
                event.name(), event.version(), event.replacementName(),
                event.replacementVersion(), event.eventType(), event.effectiveAt(),
                event.note(), event.requestId(), event.eventAt());
    }

    // ------------------------------------------------------------------
    // 幂等控制
    // ------------------------------------------------------------------

    /**
     * 当前写事务使用的 requestId，供同事务内的记录写入引用。
     */
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private <T> T executeIdempotent(String requestId, String operation, String requestHash,
                                    int httpStatus, Supplier<T> action, Class<T> responseType) {
        // 快速路径：已提交的成功记录直接重放。
        T replay = replayIfPresent(requestId, operation, requestHash, responseType);
        if (replay != null) {
            return replay;
        }

        try {
            return transactionTemplate.execute(status -> {
                // 锁单行仓库版本，串行化全部写事务，保证快照与版本号一致。
                repositoryDao.lockRepositoryState();
                // 等待行锁期间可能已有同键事务提交，再次检查。
                T existing = replayIfPresent(requestId, operation, requestHash, responseType);
                if (existing != null) {
                    return existing;
                }
                repositoryDao.insertPendingIdempotentRequest(
                        requestId, operation, requestHash, Instant.now(clock));
                currentRequestId.set(requestId);
                try {
                    T result = action.get();
                    repositoryDao.completeIdempotentRequest(
                            requestId, httpStatus, writeJson(result));
                    return result;
                } finally {
                    currentRequestId.remove();
                }
            });
        } catch (DuplicateKeyException e) {
            // 同键并发：赢家已提交则重放其结果，否则报告冲突。
            T replayAfterRace = replayIfPresent(requestId, operation, requestHash, responseType);
            if (replayAfterRace != null) {
                return replayAfterRace;
            }
            throw ApiException.conflict("相同 requestId 的请求正在处理中: " + requestId);
        }
    }

    /**
     * 存在成功记录时：同操作同参返回原结果；异参（含异操作）返回 409。不存在返回 null。
     */
    private <T> T replayIfPresent(String requestId, String operation, String requestHash,
                                  Class<T> responseType) {
        IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
        if (record == null) {
            return null;
        }
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict(
                    "requestId 已用于不同参数的请求: " + requestId);
        }
        return readJson(record.responseJson(), responseType);
    }

    // ------------------------------------------------------------------
    // 校验与转换
    // ------------------------------------------------------------------

    private void validateRegisterRequest(RegisterArtifactRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        List<DependencySpec> deps = request.dependencies();
        if (deps.size() > 10) {
            throw ApiException.badRequest("每个制品版本最多声明 10 条依赖");
        }
        Set<String> names = new HashSet<>();
        for (DependencySpec dep : deps) {
            if (dep.name() == null || dep.name().isBlank()) {
                throw ApiException.badRequest("依赖名称不能为空");
            }
            String depName = dep.name().trim();
            if (dep.minimumVersion() > dep.maximumVersion()) {
                throw ApiException.badRequest(
                        "依赖 " + depName + " 的最低版本不能高于最高版本");
            }
            if (!names.add(depName)) {
                throw ApiException.badRequest("依赖名称重复: " + depName);
            }
        }
    }

    private String canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> d.name().trim() + ":" + d.minimumVersion() + ":" + d.maximumVersion())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
            } else if (o instanceof DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<LockWarningRow> warnings) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<DeprecationWarningView> warningViews = warnings.stream()
                .map(w -> new DeprecationWarningView(w.name(), w.version(),
                        w.replacementName(), w.replacementVersion(),
                        w.effectiveAt(), w.note(), w.resolvedAt()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.resolvedAt(), row.createdAt(),
                warningViews, entryViews);
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
