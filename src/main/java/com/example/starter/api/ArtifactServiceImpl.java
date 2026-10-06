package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.EdgeView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ScopedLockFileResponse;
import com.example.starter.api.dto.ScopeView;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.DependencyScope;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.ScopedLockResolver;
import com.example.starter.domain.ScopedLockSolution;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.ScopedEdgeRow;
import com.example.starter.repo.RepositoryDao.ScopedEntryRow;
import com.example.starter.repo.RepositoryDao.ScopedLockRow;
import com.example.starter.repo.RepositoryDao.ScopedScopeRow;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_SCOPED_LOCK = "CREATE_SCOPED_LOCK";

    private static final String ANONYMOUS_OPERATOR = "anonymous";

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
    public ArtifactResponse registerArtifact(String requestId, String operator,
                                             RegisterArtifactRequest request) {
        requireRequestId(requestId);
        validateRegisterRequest(request);
        Map<String, Object> identity = new TreeMap<>();
        identity.put("name", request.name().trim());
        identity.put("version", request.version());
        identity.put("dependencies", canonicalDependencies(request));
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        String hash = identityHash(OP_REGISTER, operator, identity);
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version,
                                             Long expectedRepositoryVersion) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        Map<String, Object> identity = new TreeMap<>();
        identity.put("name", name.trim());
        identity.put("version", version);
        identity.put("expectedRepositoryVersion", expectedRepositoryVersion);
        String hash = identityHash(OP_WITHDRAW, operator, identity);
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version, expectedRepositoryVersion), ArtifactResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String hash = identityHash(OP_LOCK, operator, lockIdentity(request));
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public ScopedLockFileResponse createScopedLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String hash = identityHash(OP_SCOPED_LOCK, operator, lockIdentity(request));
        return executeIdempotent(requestId, OP_SCOPED_LOCK, hash, 201,
                () -> doScopedLock(request), ScopedLockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row, repositoryDao.listLockEntries(row.id())));
        }
        return result;
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row, repositoryDao.listLockEntries(id));
    }

    @Override
    public List<ScopedLockFileResponse> listScopedLocks() {
        List<ScopedLockFileResponse> result = new ArrayList<>();
        for (ScopedLockRow row : repositoryDao.listScopedLocks()) {
            result.add(toScopedLockResponse(row));
        }
        return result;
    }

    @Override
    public ScopedLockFileResponse getScopedLock(long id) {
        ScopedLockRow row = repositoryDao.getScopedLock(id);
        if (row == null) {
            throw ApiException.notFound("作用域锁文件不存在: " + id);
        }
        return toScopedLockResponse(row);
    }

    // ------------------------------------------------------------------
    // 业务操作（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    private ArtifactResponse doRegister(RegisterArtifactRequest request) {
        String name = request.name().trim();
        int version = request.version();
        checkExpectedRepositoryVersion(request.expectedRepositoryVersion());

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
                    dep.minimumVersion(), dep.maximumVersion(), parseScope(dep.scope()).name());
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, repositoryVersion, now,
                toDependencyViews(request.dependencies()));
    }

    private ArtifactResponse doWithdraw(String name, int version, Long expectedRepositoryVersion) {
        checkExpectedRepositoryVersion(expectedRepositoryVersion);
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

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    private ScopedLockFileResponse doScopedLock(LockRequest request) {
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
        ScopedLockSolution solution;
        try {
            solution = ScopedLockResolver.resolve(snapshot, rootName, rootVersion);
        } catch (ScopedLockResolver.ScopeDepthExceededException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        // 业务与幂等记录同事务提交：任何一步失败整体回滚，不保存部分锁。
        Instant now = Instant.now(clock);
        long lockId = repositoryDao.insertScopedLock(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        long[] scopeIds = new long[solution.scopes().size()];
        for (ScopedLockSolution.ResolvedScope scope : solution.scopes()) {
            Long parentId = scope.parentIndex() < 0 ? null : scopeIds[scope.parentIndex()];
            scopeIds[scope.index()] = repositoryDao.insertScopedLockScope(
                    lockId, parentId, scope.parentArtifactName(), scope.depth());
        }
        for (ScopedLockSolution.ResolvedScope scope : solution.scopes()) {
            long scopeId = scopeIds[scope.index()];
            scope.entries().forEach((n, v) -> repositoryDao.insertScopedLockEntry(scopeId, n, v));
        }
        for (ScopedLockSolution.ResolvedEdge edge : solution.edges()) {
            repositoryDao.insertScopedLockEdge(lockId, scopeIds[edge.fromScopeIndex()],
                    edge.fromName(), edge.toName(), edge.toVersion(), edge.scope().name(),
                    scopeIds[edge.toScopeIndex()]);
        }
        return toScopedLockResponse(lockId, rootName, rootVersion, currentVersion, now,
                solution, scopeIds);
    }

    /**
     * 期望仓库版本一致性校验：为 null 表示不校验；非 null 时与当前版本不一致返回 409。
     * 须在已持有 repository_state 行锁的写事务内调用。
     */
    private void checkExpectedRepositoryVersion(Long expectedRepositoryVersion) {
        if (expectedRepositoryVersion == null) {
            return;
        }
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedRepositoryVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedRepositoryVersion + ", actual=" + currentVersion);
        }
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
            parseScope(dep.scope());
            if (!names.add(depName)) {
                throw ApiException.badRequest("依赖名称重复: " + depName);
            }
        }
    }

    /** 解析依赖作用域：缺省 PUBLIC，大小写不敏感；非法值返回 400。 */
    private static DependencyScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return DependencyScope.PUBLIC;
        }
        try {
            return DependencyScope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("依赖作用域不合法: " + scope + "（仅支持 PUBLIC/PRIVATE）");
        }
    }

    /** 操作者归一化：缺省按 anonymous 处理。 */
    private static String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return ANONYMOUS_OPERATOR;
        }
        return operator.trim();
    }

    /**
     * 结构化幂等身份：操作、操作者、资源及全部参数和期望版本组成有序 JSON 后取摘要，
     * 避免分隔符拼接造成的碰撞。
     */
    private String identityHash(String operation, String operator, Map<String, Object> identity) {
        Map<String, Object> canonical = new TreeMap<>(identity);
        canonical.put("operation", operation);
        canonical.put("operator", normalizeOperator(operator));
        return sha256(writeJson(canonical));
    }

    /** 锁定请求的幂等身份参数：根名称、根精确版本与期望仓库版本。 */
    private Map<String, Object> lockIdentity(LockRequest request) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("rootName", request.rootName().trim());
        identity.put("rootVersion", request.rootVersion());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return identity;
    }

    /** 依赖列表的规范化结构：按名称排序，作用域归一化为 PUBLIC/PRIVATE。 */
    private List<Map<String, Object>> canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> {
                    Map<String, Object> dep = new TreeMap<>();
                    dep.put("name", d.name().trim());
                    dep.put("minimumVersion", d.minimumVersion());
                    dep.put("maximumVersion", d.maximumVersion());
                    dep.put("scope", parseScope(d.scope()).name());
                    return dep;
                })
                .sorted(Comparator.comparing(d -> (String) d.get("name")))
                .toList();
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof com.example.starter.api.dto.DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion(),
                        parseScope(spec.scope()).name()));
            } else if (o instanceof DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion(),
                        range.scope().name()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    /** 由解析解构建作用域锁响应（创建路径，作用域 ID 为刚写入的数据库主键）。 */
    private ScopedLockFileResponse toScopedLockResponse(long lockId, String rootName, int rootVersion,
                                                        long repositoryVersion, Instant createdAt,
                                                        ScopedLockSolution solution, long[] scopeIds) {
        List<ScopeView> scopes = new ArrayList<>();
        for (ScopedLockSolution.ResolvedScope scope : solution.scopes()) {
            List<LockEntryResponse> entries = new ArrayList<>();
            scope.entries().forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
            Long parentId = scope.parentIndex() < 0 ? null : scopeIds[scope.parentIndex()];
            scopes.add(new ScopeView(scopeIds[scope.index()], parentId, scope.parentArtifactName(),
                    scope.depth(), scope.path(), entries));
        }
        List<EdgeView> edges = new ArrayList<>();
        for (ScopedLockSolution.ResolvedEdge edge : solution.edges()) {
            edges.add(new EdgeView(scopeIds[edge.fromScopeIndex()], edge.fromName(), edge.toName(),
                    edge.toVersion(), edge.scope().name(), scopeIds[edge.toScopeIndex()]));
        }
        return new ScopedLockFileResponse(lockId, rootName, rootVersion, repositoryVersion,
                createdAt, scopes, edges);
    }

    /** 由数据库行重建作用域锁响应（查询路径，路径沿父指针结构化还原）。 */
    private ScopedLockFileResponse toScopedLockResponse(ScopedLockRow row) {
        List<ScopedScopeRow> scopeRows = repositoryDao.listScopedLockScopes(row.id());
        Map<Long, ScopedScopeRow> byId = new HashMap<>();
        for (ScopedScopeRow scopeRow : scopeRows) {
            byId.put(scopeRow.id(), scopeRow);
        }
        List<ScopeView> scopes = new ArrayList<>();
        for (ScopedScopeRow scopeRow : scopeRows) {
            List<LockEntryResponse> entries = new ArrayList<>();
            for (ScopedEntryRow entry : repositoryDao.listScopedLockEntries(scopeRow.id())) {
                entries.add(new LockEntryResponse(entry.name(), entry.version()));
            }
            scopes.add(new ScopeView(scopeRow.id(), scopeRow.parentScopeId(),
                    scopeRow.parentArtifactName(), scopeRow.depth(),
                    resolveScopePath(scopeRow, byId), entries));
        }
        List<EdgeView> edges = new ArrayList<>();
        for (ScopedEdgeRow edge : repositoryDao.listScopedLockEdges(row.id())) {
            edges.add(new EdgeView(edge.fromScopeId(), edge.fromName(), edge.toName(),
                    edge.toVersion(), edge.scopeType(), edge.toScopeId()));
        }
        return new ScopedLockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), scopes, edges);
    }

    /** 沿父作用域指针还原结构化路径（父制品名称列表，根为空列表）。 */
    private static List<String> resolveScopePath(ScopedScopeRow scopeRow,
                                                 Map<Long, ScopedScopeRow> byId) {
        List<String> reversed = new ArrayList<>();
        ScopedScopeRow current = scopeRow;
        while (current.parentScopeId() != null) {
            reversed.add(current.parentArtifactName());
            current = byId.get(current.parentScopeId());
            if (current == null) {
                throw new IllegalStateException("作用域父指针悬空: " + scopeRow.id());
            }
        }
        List<String> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
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
