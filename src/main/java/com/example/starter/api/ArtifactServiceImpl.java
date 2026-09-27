package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationBatchResponse;
import com.example.starter.api.dto.DeprecationBlockedView;
import com.example.starter.api.dto.DeprecationDiagnosisResponse;
import com.example.starter.api.dto.DeprecationEventView;
import com.example.starter.api.dto.DeprecationFailureDetails;
import com.example.starter.api.dto.DeprecationLockBlockedDetails;
import com.example.starter.api.dto.DeprecationSpec;
import com.example.starter.api.dto.DeprecationWindowView;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockDeprecationWarningView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ActiveDeprecation;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DeprecationAdjudicator;
import com.example.starter.domain.DeprecationGraph;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.NameVersion;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.DeprecationEventRow;
import com.example.starter.repo.RepositoryDao.DeprecationWindowRow;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockDeprecationRow;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * 弃用登记、撤销、替代版本撤回与锁定因此按提交顺序裁决，锁定读取一致闭包与时间口径。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_DEPRECATION_BATCH = 20;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_SET_DEPRECATIONS = "SET_DEPRECATIONS";
    private static final String OP_REVOKE_DEPRECATION = "REVOKE_DEPRECATION";

    private static final String WINDOW_REGISTERED = "REGISTERED";
    private static final String WINDOW_REVOKED = "REVOKED";
    private static final String EVENT_REGISTERED = "REGISTERED";
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
            result.add(toLockResponse(row));
        }
        return result;
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row);
    }

    // ------------------------------------------------------------------
    // 弃用窗口
    // ------------------------------------------------------------------

    @Override
    public DeprecationBatchResponse setDeprecations(String requestId, DeprecationBatchRequest request) {
        requireRequestId(requestId);
        List<DeprecationSpec> specs = request.windows();
        if (specs == null || specs.isEmpty()) {
            throw ApiException.badRequest("弃用窗口批次不能为空");
        }
        if (specs.size() > MAX_DEPRECATION_BATCH) {
            throw ApiException.badRequest("每批弃用窗口最多 " + MAX_DEPRECATION_BATCH + " 条，实际 "
                    + specs.size() + " 条");
        }
        String hash = sha256(OP_SET_DEPRECATIONS + "|" + canonicalDeprecations(specs));
        return executeIdempotent(requestId, OP_SET_DEPRECATIONS, hash, 200,
                () -> doSetDeprecations(requestId, specs), DeprecationBatchResponse.class);
    }

    @Override
    public DeprecationWindowView revokeDeprecation(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String trimmedName = name.trim();
        String hash = sha256(OP_REVOKE_DEPRECATION + "|" + trimmedName + "|" + version);
        return executeIdempotent(requestId, OP_REVOKE_DEPRECATION, hash, 200,
                () -> doRevoke(requestId, trimmedName, version), DeprecationWindowView.class);
    }

    @Override
    public List<DeprecationWindowView> listDeprecations() {
        return repositoryDao.listWindows().stream().map(ArtifactServiceImpl::toWindowView).toList();
    }

    @Override
    public DeprecationWindowView getDeprecation(String name, int version) {
        DeprecationWindowRow row = repositoryDao.getWindow(name, version);
        if (row == null) {
            throw ApiException.notFound("弃用窗口不存在: " + name + ":" + version);
        }
        return toWindowView(row);
    }

    @Override
    public List<DeprecationEventView> listDeprecationEvents() {
        return repositoryDao.listEvents().stream().map(ArtifactServiceImpl::toEventView).toList();
    }

    @Override
    public List<DeprecationEventView> getDeprecationEvents(String name, int version) {
        return repositoryDao.listEventsOf(name, version).stream()
                .map(ArtifactServiceImpl::toEventView).toList();
    }

    @Override
    public DeprecationDiagnosisResponse diagnose(String rootName, int rootVersion, Instant at) {
        if (rootName == null || rootName.isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String trimmedRoot = rootName.trim();
        // 只读预演：在单个事务内读取一致闭包与窗口，不写入、不推进仓库版本。
        return transactionTemplate.execute(status -> {
            long repositoryVersion = repositoryDao.getRepositoryVersion();
            Resolution resolution = resolveClosure(trimmedRoot, rootVersion);
            Instant resolvedAt = at != null ? at : Instant.now(clock);
            DeprecationAdjudicator.Adjudication adjudication = adjudicate(
                    resolution.closure(), loadActiveDeprecations(), resolvedAt);
            return new DeprecationDiagnosisResponse(trimmedRoot, rootVersion, repositoryVersion,
                    resolvedAt, toEntryViews(resolution.closure()),
                    toBlockedViews(adjudication.blocked()), toWarningViews(adjudication.warnings()));
        });
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

    private LockFileResponse doLock(LockRequest request) {
        String rootName = request.rootName().trim();
        int rootVersion = request.rootVersion();

        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

        Resolution resolution = resolveClosure(rootName, rootVersion);

        // 解析时刻在闭包求解之后、写入之前取一次；闭包与窗口在同一行锁事务内一致读取。
        Instant resolvedAt = Instant.now(clock);
        DeprecationAdjudicator.Adjudication adjudication =
                adjudicate(resolution.closure(), loadActiveDeprecations(), resolvedAt);
        if (!adjudication.blocked().isEmpty()) {
            // 生效后闭包仍含弃用版本：整次 422，稳定排序返回制品、版本、替代与生效时刻。
            throw ApiException.unprocessable("DEPRECATION_EFFECTIVE",
                    "依赖闭包含 " + adjudication.blocked().size()
                            + " 个已生效弃用版本，无法锁定（resolvedAt=" + resolvedAt + "）",
                    new DeprecationLockBlockedDetails(resolvedAt, adjudication.blocked().size(),
                            toBlockedViews(adjudication.blocked())));
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now, resolvedAt);
        resolution.closure().forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        for (DeprecationAdjudicator.WarningHit warning : adjudication.warnings()) {
            repositoryDao.insertLockDeprecation(lockFileId, warning.name(), warning.version(),
                    warning.replacementVersion(), warning.effectiveAt(), warning.note(),
                    warning.secondsUntilEffective());
        }

        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                resolvedAt, toEntryViews(resolution.closure()),
                toWarningViews(adjudication.warnings()));
    }

    private DeprecationBatchResponse doSetDeprecations(String requestId, List<DeprecationSpec> rawSpecs) {
        Instant now = Instant.now(clock);

        // 名称 trim 后按名称、版本稳定排序，保证错误下标与响应顺序确定。
        List<DeprecationSpec> specs = rawSpecs.stream()
                .map(s -> new DeprecationSpec(s.name().trim(), s.version(), s.replacementVersion(),
                        s.effectiveAt(), s.note()))
                .sorted(Comparator.comparing(DeprecationSpec::name)
                        .thenComparingInt(DeprecationSpec::version))
                .toList();

        // 批次内同一制品版本出现多次：整次拒绝，返回实际条数与去重后条数。
        Set<NameVersion> uniqueKeys = new HashSet<>();
        for (DeprecationSpec spec : specs) {
            if (!uniqueKeys.add(new NameVersion(spec.name(), spec.version()))) {
                throw deprecationFailure("DUPLICATE_ITEM", "批次内同一制品版本出现多次: "
                        + spec.name() + ":" + spec.version(), specs.indexOf(spec), spec, now,
                        specs.size(), uniqueKeys.size(), null);
            }
        }

        // 逐条校验被弃用版本、替代版本与生效时刻；任一失败整次不写入。
        for (int i = 0; i < specs.size(); i++) {
            DeprecationSpec spec = specs.get(i);
            ArtifactVersion subject = repositoryDao.loadArtifact(spec.name(), spec.version());
            if (subject == null) {
                throw deprecationFailure("SUBJECT_MISSING",
                        "被弃用制品版本不存在: " + spec.name() + ":" + spec.version(),
                        i, spec, now, specs.size(), null, null);
            }
            if (spec.effectiveAt().isBefore(now)) {
                // 生效时刻不得早于当前时钟；恰等于是边界（登记即生效，锁定按左闭阻断），允许登记。
                long secondsBehind = Duration.between(spec.effectiveAt(), now).getSeconds();
                throw deprecationFailure("EFFECTIVE_IN_PAST",
                        "弃用生效时刻不得早于当前时钟：effectiveAt=" + spec.effectiveAt()
                                + ", now=" + now + ", 落后 " + secondsBehind + " 秒",
                        i, spec, now, specs.size(), null, secondsBehind);
            }
            ArtifactVersion replacement = repositoryDao.loadArtifact(
                    spec.name(), spec.replacementVersion());
            if (replacement == null) {
                throw deprecationFailure("REPLACEMENT_MISSING",
                        "替代版本不存在: " + spec.name() + ":" + spec.replacementVersion(),
                        i, spec, now, specs.size(), null, null);
            }
            if (replacement.withdrawn()) {
                throw deprecationFailure("REPLACEMENT_WITHDRAWN",
                        "替代版本已撤回: " + spec.name() + ":" + spec.replacementVersion(),
                        i, spec, now, specs.size(), null, null);
            }
        }

        // 合并已有有效窗口与本次窗口，校验完整最终替代图无环（含自环）。
        Map<String, Map<Integer, Integer>> finalGraph = loadFinalGraph(specs);
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(finalGraph);
        if (cycle != null) {
            DeprecationSpec firstCycleSpec = findCycleSpec(specs, cycle);
            Integer index = firstCycleSpec == null ? null : specs.indexOf(firstCycleSpec);
            throw deprecationFailure("REPLACEMENT_CYCLE",
                    "制品 " + cycle.name() + " 的替代版本形成环: " + cycle.versions(),
                    index, firstCycleSpec, now, specs.size(), null, null, cycle.versions());
        }

        // 全部校验通过后才写入：每条窗口 upsert 当前状态并追加登记事件，整批一个事务提交。
        List<DeprecationWindowView> views = new ArrayList<>();
        for (DeprecationSpec spec : specs) {
            long windowId = repositoryDao.upsertWindow(spec.name(), spec.version(),
                    spec.replacementVersion(), spec.effectiveAt(), spec.note(), requestId, now);
            repositoryDao.insertEvent(windowId, EVENT_REGISTERED, spec.name(), spec.version(),
                    spec.replacementVersion(), spec.effectiveAt(), spec.note(), requestId, now);
            views.add(toWindowView(repositoryDao.getWindowById(windowId)));
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new DeprecationBatchResponse(now, specs.size(), specs.size(),
                repositoryVersion, List.copyOf(views));
    }

    private DeprecationWindowView doRevoke(String requestId, String name, int version) {
        Instant now = Instant.now(clock);
        DeprecationWindowRow window = repositoryDao.getWindow(name, version);
        if (window == null) {
            throw ApiException.notFound("弃用窗口不存在: " + name + ":" + version);
        }
        if (WINDOW_REVOKED.equals(window.status())) {
            throw ApiException.conflict("弃用窗口已撤销: " + name + ":" + version);
        }
        if (!window.effectiveAt().isAfter(now)) {
            // 只允许撤销尚未生效窗口；返回已生效时长差额，调用方无需猜测。
            long secondsEffective = Duration.between(window.effectiveAt(), now).getSeconds();
            throw ApiException.unprocessable("DEPRECATION_ALREADY_EFFECTIVE",
                    "弃用窗口已生效，不可撤销：effectiveAt=" + window.effectiveAt()
                            + ", now=" + now + ", 已生效 " + secondsEffective + " 秒",
                    new DeprecationFailureDetails("DEPRECATION_ALREADY_EFFECTIVE", null,
                            name, version, window.replacementVersion(), window.effectiveAt(), now,
                            secondsEffective, null, 0, null));
        }
        int affected = repositoryDao.markWindowRevoked(window.id(), requestId, now);
        if (affected == 0) {
            throw ApiException.conflict("弃用窗口状态已变化: " + name + ":" + version);
        }
        // 撤销事件保留撤销前的替代版本、生效时刻与说明，不隐藏历史证据。
        repositoryDao.insertEvent(window.id(), EVENT_REVOKED, name, version,
                window.replacementVersion(), window.effectiveAt(), window.note(), requestId, now);
        repositoryDao.incrementRepositoryVersion();
        return toWindowView(repositoryDao.getWindowById(window.id()));
    }

    // ------------------------------------------------------------------
    // 解析与裁决辅助
    // ------------------------------------------------------------------

    /** 闭包解析结果：名称 -> 精确版本（名称升序）。 */
    private record Resolution(Map<String, Integer> closure) {
    }

    private Resolution resolveClosure(String rootName, int rootVersion) {
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
        return new Resolution(solution);
    }

    private Map<NameVersion, ActiveDeprecation> loadActiveDeprecations() {
        Map<NameVersion, ActiveDeprecation> active = new HashMap<>();
        for (DeprecationWindowRow row : repositoryDao.listActiveWindows()) {
            active.put(new NameVersion(row.name(), row.version()), new ActiveDeprecation(
                    row.name(), row.version(), row.replacementVersion(),
                    row.effectiveAt(), row.note()));
        }
        return active;
    }

    private static DeprecationAdjudicator.Adjudication adjudicate(
            Map<String, Integer> closure, Map<NameVersion, ActiveDeprecation> active, Instant now) {
        return DeprecationAdjudicator.adjudicate(closure, active, now);
    }

    /**
     * 合并已有有效窗口与本次批次，得到最终完整替代图（按制品名称分组）。
     * 本次批次的窗口覆盖同键的已有窗口；已撤销窗口不参与。
     */
    private Map<String, Map<Integer, Integer>> loadFinalGraph(List<DeprecationSpec> specs) {
        Map<String, Map<Integer, Integer>> graph = new TreeMap<>();
        for (DeprecationWindowRow row : repositoryDao.listActiveWindows()) {
            graph.computeIfAbsent(row.name(), k -> new TreeMap<>())
                    .put(row.version(), row.replacementVersion());
        }
        for (DeprecationSpec spec : specs) {
            graph.computeIfAbsent(spec.name(), k -> new TreeMap<>())
                    .put(spec.version(), spec.replacementVersion());
        }
        return graph;
    }

    /**
     * 在环中定位属于本次批次的首个制品版本对应项；环可能全部来自既有窗口，此时返回 null。
     */
    private DeprecationSpec findCycleSpec(List<DeprecationSpec> specs, DeprecationGraph.Cycle cycle) {
        for (int version : cycle.versions()) {
            for (DeprecationSpec spec : specs) {
                if (spec.name().equals(cycle.name()) && spec.version() == version) {
                    return spec;
                }
            }
        }
        return null;
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

    private String canonicalDeprecations(List<DeprecationSpec> specs) {
        return specs.stream()
                .map(s -> s.name().trim() + ":" + s.version() + ":" + s.replacementVersion()
                        + ":" + s.effectiveAt() + ":" + s.note())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private ApiException deprecationFailure(String reason, String message, Integer itemIndex,
                                            DeprecationSpec spec, Instant now,
                                            int requestedCount, Integer uniqueCount) {
        return deprecationFailure(reason, message, itemIndex, spec, now,
                requestedCount, uniqueCount, null, null);
    }

    private ApiException deprecationFailure(String reason, String message, Integer itemIndex,
                                            DeprecationSpec spec, Instant now,
                                            int requestedCount, Integer uniqueCount,
                                            Long secondsDifference) {
        return deprecationFailure(reason, message, itemIndex, spec, now,
                requestedCount, uniqueCount, secondsDifference, null);
    }

    private ApiException deprecationFailure(String reason, String message, Integer itemIndex,
                                            DeprecationSpec spec, Instant now,
                                            int requestedCount, Integer uniqueCount,
                                            Long secondsDifference, List<Integer> cycle) {
        DeprecationFailureDetails details = new DeprecationFailureDetails(reason, itemIndex,
                spec == null ? null : spec.name(),
                spec == null ? null : spec.version(),
                spec == null ? null : spec.replacementVersion(),
                spec == null ? null : spec.effectiveAt(),
                now, secondsDifference, cycle, requestedCount, uniqueCount);
        return ApiException.unprocessable(reason, message, details);
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
            } else if (o instanceof com.example.starter.domain.DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private static List<LockEntryResponse> toEntryViews(Map<String, Integer> closure) {
        return closure.entrySet().stream()
                .map(e -> new LockEntryResponse(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<DeprecationBlockedView> toBlockedViews(List<ActiveDeprecation> blocked) {
        return blocked.stream()
                .map(d -> new DeprecationBlockedView(d.name(), d.version(),
                        d.replacementVersion(), d.effectiveAt()))
                .toList();
    }

    private static List<LockDeprecationWarningView> toWarningViews(
            List<DeprecationAdjudicator.WarningHit> warnings) {
        return warnings.stream()
                .map(w -> new LockDeprecationWarningView(w.name(), w.version(),
                        w.replacementVersion(), w.effectiveAt(), w.note(),
                        w.secondsUntilEffective()))
                .toList();
    }

    private LockFileResponse toLockResponse(LockFileRow row) {
        List<LockEntryResponse> entryViews = repositoryDao.listLockEntries(row.id()).stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<LockDeprecationWarningView> warningViews = repositoryDao.listLockDeprecations(row.id())
                .stream()
                .map(ArtifactServiceImpl::toLockWarningView)
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), row.resolvedAt(),
                entryViews, warningViews);
    }

    private static LockDeprecationWarningView toLockWarningView(LockDeprecationRow row) {
        return new LockDeprecationWarningView(row.name(), row.version(),
                row.replacementVersion(), row.effectiveAt(), row.note(),
                row.secondsUntilEffective());
    }

    private static DeprecationWindowView toWindowView(DeprecationWindowRow row) {
        return new DeprecationWindowView(row.name(), row.version(), row.replacementVersion(),
                row.effectiveAt(), row.note(), row.status(), row.createdAt(), row.revokedAt());
    }

    private static DeprecationEventView toEventView(DeprecationEventRow row) {
        return new DeprecationEventView(row.id(), row.windowId(), row.action(), row.name(),
                row.version(), row.replacementVersion(), row.effectiveAt(), row.note(),
                row.requestId(), row.createdAt());
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
