package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationViolationView;
import com.example.starter.api.dto.DeprecationWarningView;
import com.example.starter.api.dto.DeprecationWindowRequest;
import com.example.starter.api.dto.DeprecationWindowResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.DeprecationPolicy;
import com.example.starter.domain.DeprecationWindow;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.DeprecationRow;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.WarningRow;
import com.example.starter.support.ApiException;
import com.example.starter.support.DeprecationViolationException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 * 弃用登记、撤销、替代版本撤回与锁定共用该行锁，按提交顺序裁决；
 * 锁定在同一事务内读取一致闭包并以可注入时钟给出统一时间口径（UTC，左闭右开）。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_DEPRECATION_BATCH = 20;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_DEPRECATE = "REGISTER_DEPRECATION";
    private static final String OP_REVOKE_DEPRECATION = "REVOKE_DEPRECATION";
    private static final String OP_RE_RESOLVE = "RE_RESOLVE_LOCK";

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
                () -> doRegister(request), json -> readJson(json, ArtifactResponse.class));
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_WITHDRAW + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version), json -> readJson(json, ArtifactResponse.class));
    }

    @Override
    public List<DeprecationWindowResponse> registerDeprecations(String requestId,
                                                                DeprecationBatchRequest request) {
        requireRequestId(requestId);
        validateDeprecationBatch(request);
        String hash = sha256(OP_DEPRECATE + "|" + canonicalDeprecationBatch(request));
        return executeIdempotent(requestId, OP_DEPRECATE, hash, 201,
                () -> doRegisterDeprecations(request),
                json -> readJsonList(json, DeprecationWindowResponse.class));
    }

    @Override
    public DeprecationWindowResponse revokeDeprecation(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_REVOKE_DEPRECATION + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_REVOKE_DEPRECATION, hash, 200,
                () -> doRevokeDeprecation(name.trim(), version),
                json -> readJson(json, DeprecationWindowResponse.class));
    }

    @Override
    public List<DeprecationWindowResponse> listDeprecations() {
        return repositoryDao.listDeprecations().stream()
                .map(ArtifactServiceImpl::toDeprecationResponse)
                .toList();
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
                () -> doLock(request.rootName().trim(), request.rootVersion(),
                        request.expectedRepositoryVersion()),
                json -> readJson(json, LockFileResponse.class));
    }

    @Override
    public LockFileResponse reResolveLock(String requestId, long lockId) {
        requireRequestId(requestId);
        String hash = sha256(OP_RE_RESOLVE + "|" + lockId);
        return executeIdempotent(requestId, OP_RE_RESOLVE, hash, 201,
                () -> doReResolve(lockId), json -> readJson(json, LockFileResponse.class));
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row, repositoryDao.listLockEntries(row.id()),
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
        return toLockResponse(row, repositoryDao.listLockEntries(id),
                repositoryDao.listLockWarnings(id));
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

    private List<DeprecationWindowResponse> doRegisterDeprecations(DeprecationBatchRequest request) {
        Instant now = Instant.now(clock);

        // 先完整校验后态：存在性、生效时刻、替代版本、替代环、活动窗口冲突，全部通过才写入。
        Map<String, Map<Integer, DeprecationWindow>> activeByName = repositoryDao.loadDeprecationMap();
        Map<String, Map<Integer, Integer>> mergedGraph = new LinkedHashMap<>();
        activeByName.forEach((name, perVersion) -> {
            Map<Integer, Integer> edges = new LinkedHashMap<>();
            perVersion.forEach((v, w) -> edges.put(v, w.replacementVersion()));
            mergedGraph.put(name, edges);
        });

        List<DeprecationWindowRequest> windows = request.windows();
        for (DeprecationWindowRequest window : windows) {
            String name = window.name().trim();
            int version = window.version();
            int replacement = window.replacementVersion();

            ArtifactVersion deprecated = repositoryDao.loadArtifact(name, version);
            if (deprecated == null) {
                throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
            }
            if (window.effectiveAt().isBefore(now)) {
                throw ApiException.unprocessable("弃用生效时刻早于当前时钟：effectiveAt="
                        + window.effectiveAt() + ", 要求不早于 now=" + now);
            }
            if (replacement == version) {
                throw ApiException.unprocessable(
                        "替代版本不能是被弃用版本自身: " + name + ":" + version);
            }
            ArtifactVersion replacementArtifact = repositoryDao.loadArtifact(name, replacement);
            if (replacementArtifact == null) {
                throw ApiException.unprocessable(
                        "替代版本不存在: " + name + ":" + replacement);
            }
            if (replacementArtifact.withdrawn()) {
                throw ApiException.unprocessable(
                        "替代版本已撤回: " + name + ":" + replacement);
            }
            Map<Integer, DeprecationWindow> active = activeByName.get(name);
            if (active != null && active.containsKey(version)) {
                throw ApiException.conflict(
                        "该版本已存在生效中的弃用窗口: " + name + ":" + version);
            }
            mergedGraph.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(version, replacement);
        }
        // 完整替代图（存量有效窗口 + 本批）不得成环。
        for (Map.Entry<String, Map<Integer, Integer>> entry : mergedGraph.entrySet()) {
            if (DeprecationPolicy.graphHasCycle(entry.getValue())) {
                throw ApiException.unprocessable(
                        "替代版本形成替代环: " + entry.getKey());
            }
        }

        // 校验全部通过，同事务内一次性写入。
        List<DeprecationWindowResponse> result = new ArrayList<>();
        for (DeprecationWindowRequest window : windows) {
            String name = window.name().trim();
            long id = repositoryDao.insertDeprecation(name, window.version(),
                    window.effectiveAt(), window.replacementVersion(), window.reason(),
                    currentRequestId.get(), now);
            result.add(new DeprecationWindowResponse(id, name, window.version(),
                    window.effectiveAt(), window.replacementVersion(), window.reason(),
                    false, null, now));
        }
        repositoryDao.incrementRepositoryVersion();
        return List.copyOf(result);
    }

    private DeprecationWindowResponse doRevokeDeprecation(String name, int version) {
        DeprecationRow active = repositoryDao.findActiveDeprecation(name, version);
        if (active == null) {
            throw ApiException.notFound("该版本没有生效中的弃用窗口: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        // 左闭右开：now >= effectiveAt 视为已生效，只允许撤销尚未生效的窗口。
        if (!now.isBefore(active.effectiveAt())) {
            throw ApiException.conflict("弃用窗口已生效，不能撤销: " + name + ":" + version
                    + "（effectiveAt=" + active.effectiveAt() + ", now=" + now + "）");
        }
        int affected = repositoryDao.markDeprecationRevoked(active.id(), now);
        if (affected == 0) {
            throw ApiException.conflict("弃用窗口已被撤销: " + name + ":" + version);
        }
        repositoryDao.incrementRepositoryVersion();
        return new DeprecationWindowResponse(active.id(), name, version, active.effectiveAt(),
                active.replacementVersion(), active.reason(), true, now, active.createdAt());
    }

    private LockFileResponse doLock(String rootName, int rootVersion, long expectedRepositoryVersion) {
        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedRepositoryVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedRepositoryVersion + ", actual=" + currentVersion);
        }
        return doLockAtCurrentVersion(rootName, rootVersion, currentVersion);
    }

    /**
     * 在已持有行锁、版本号已确认的事务内执行解析与弃用裁决，并固化锁文件。
     */
    private LockFileResponse doLockAtCurrentVersion(String rootName, int rootVersion,
                                                    long currentVersion) {
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

        // 以解析时刻（可注入时钟，UTC）对闭包内命中的弃用窗口统一裁决。
        Instant now = Instant.now(clock);
        DeprecationPolicy.Adjudication adjudication =
                DeprecationPolicy.adjudicate(snapshot, solution, now);
        if (!adjudication.violations().isEmpty()) {
            List<DeprecationViolationView> violations = adjudication.violations().stream()
                    .map(w -> new DeprecationViolationView(w.name(), w.version(),
                            w.replacementVersion(), w.effectiveAt()))
                    .toList();
            throw new DeprecationViolationException(violations);
        }

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));

        // 固化命中的弃用告警与替代建议快照；之后窗口调整或替代撤回不回写。
        List<DeprecationWarningView> warnings = new ArrayList<>();
        for (DeprecationWindow w : adjudication.warnings()) {
            repositoryDao.insertLockWarning(lockFileId, w.name(), w.version(),
                    w.replacementVersion(), w.effectiveAt(), w.reason());
            warnings.add(new DeprecationWarningView(DeprecationWarningView.CODE, w.name(),
                    w.version(), w.replacementVersion(), w.effectiveAt(), w.reason()));
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                entries, List.copyOf(warnings));
    }

    private LockFileResponse doReResolve(long lockId) {
        LockFileRow previous = repositoryDao.getLockFile(lockId);
        if (previous == null) {
            throw ApiException.notFound("锁文件不存在: " + lockId);
        }
        // 重解析按当前时刻与当前仓库版本重新裁决，生成新锁文件；历史锁不改写。
        long currentVersion = repositoryDao.lockRepositoryState();
        return doLockAtCurrentVersion(previous.rootName(), previous.rootVersion(), currentVersion);
    }

    // ------------------------------------------------------------------
    // 幂等控制
    // ------------------------------------------------------------------

    /**
     * 当前写事务使用的 requestId，供同事务内的记录写入引用。
     */
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private <T> T executeIdempotent(String requestId, String operation, String requestHash,
                                    int httpStatus, Supplier<T> action,
                                    Function<String, T> responseReader) {
        // 快速路径：已提交的成功记录直接重放。
        T replay = replayIfPresent(requestId, operation, requestHash, responseReader);
        if (replay != null) {
            return replay;
        }

        try {
            return transactionTemplate.execute(status -> {
                // 锁单行仓库版本，串行化全部写事务，保证快照与版本号一致。
                repositoryDao.lockRepositoryState();
                // 等待行锁期间可能已有同键事务提交，再次检查。
                T existing = replayIfPresent(requestId, operation, requestHash, responseReader);
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
            T replayAfterRace = replayIfPresent(requestId, operation, requestHash, responseReader);
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
                                  Function<String, T> responseReader) {
        IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
        if (record == null) {
            return null;
        }
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict(
                    "requestId 已用于不同参数的请求: " + requestId);
        }
        return responseReader.apply(record.responseJson());
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

    private void validateDeprecationBatch(DeprecationBatchRequest request) {
        if (request.windows() == null || request.windows().isEmpty()) {
            throw ApiException.badRequest("弃用窗口列表不能为空");
        }
        if (request.windows().size() > MAX_DEPRECATION_BATCH) {
            throw ApiException.badRequest(
                    "单批弃用窗口数量已达上限 " + MAX_DEPRECATION_BATCH
                            + "：实际=" + request.windows().size());
        }
        Set<String> seen = new HashSet<>();
        for (DeprecationWindowRequest window : request.windows()) {
            if (window.name() == null || window.name().isBlank()) {
                throw ApiException.badRequest("name 不能为空");
            }
            if (window.effectiveAt() == null) {
                throw ApiException.badRequest("effectiveAt 不能为空");
            }
            if (window.version() <= 0 || window.replacementVersion() <= 0) {
                throw ApiException.badRequest("版本号必须为正整数");
            }
            if (window.reason() != null && window.reason().length() > 512) {
                throw ApiException.badRequest("弃用说明长度超过 512");
            }
            String key = window.name().trim() + " " + window.version();
            if (!seen.add(key)) {
                throw ApiException.badRequest(
                        "同一批内弃用窗口重复: " + window.name().trim() + ":" + window.version());
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

    private String canonicalDeprecationBatch(DeprecationBatchRequest request) {
        return request.windows().stream()
                .map(w -> w.name().trim() + ":" + w.version() + ":" + w.effectiveAt()
                        + ":" + w.replacementVersion() + ":" + (w.reason() == null ? "" : w.reason()))
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof com.example.starter.api.dto.DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
            } else if (o instanceof DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private static DeprecationWindowResponse toDeprecationResponse(DeprecationRow row) {
        return new DeprecationWindowResponse(row.id(), row.name(), row.version(),
                row.effectiveAt(), row.replacementVersion(), row.reason(),
                row.revoked(), row.revokedAt(), row.createdAt());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<WarningRow> warnings) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<DeprecationWarningView> warningViews = warnings.stream()
                .map(w -> new DeprecationWarningView(DeprecationWarningView.CODE, w.name(),
                        w.version(), w.replacementVersion(), w.effectiveAt(), w.reason()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, warningViews);
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

    private <T> List<T> readJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
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
