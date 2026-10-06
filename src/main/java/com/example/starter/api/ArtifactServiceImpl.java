package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEdgeResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.ScopedLockResolver;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEdgeRow;
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
import java.time.Instant;
import java.util.ArrayList;
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
 * <p>幂等身份为结构化参数（操作、操作者、资源、全部参数与期望版本）的 JSON 摘要，
 * 不依赖分隔符拼接，避免名称/参数中的分隔符造成身份碰撞。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_SCOPED_LOCK = "CREATE_SCOPED_LOCK";

    private static final String MODE_FLAT = "FLAT";
    private static final String MODE_SCOPED = "SCOPED";

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
        Map<String, Object> identity = baseIdentity(OP_REGISTER, operator, request.name().trim());
        identity.put("version", request.version());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        identity.put("dependencies", canonicalDependencies(request));
        return executeIdempotent(requestId, OP_REGISTER, structuredHash(identity), 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String operator, String name,
                                             int version, Long expectedRepositoryVersion) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        Map<String, Object> identity = baseIdentity(OP_WITHDRAW, operator, name.trim());
        identity.put("version", version);
        identity.put("expectedRepositoryVersion", expectedRepositoryVersion);
        return executeIdempotent(requestId, OP_WITHDRAW, structuredHash(identity), 200,
                () -> doWithdraw(name.trim(), version, expectedRepositoryVersion),
                ArtifactResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        validateLockRequest(request);
        Map<String, Object> identity = lockIdentity(OP_LOCK, operator, request);
        return executeIdempotent(requestId, OP_LOCK, structuredHash(identity), 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public LockFileResponse createScopedLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        validateLockRequest(request);
        Map<String, Object> identity = lockIdentity(OP_SCOPED_LOCK, operator, request);
        return executeIdempotent(requestId, OP_SCOPED_LOCK, structuredHash(identity), 201,
                () -> doScopedLock(request), LockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row, repositoryDao.listLockEntries(row.id()),
                    repositoryDao.listLockEdges(row.id())));
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
                repositoryDao.listLockEdges(id));
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
                    dep.minimumVersion(), dep.maximumVersion(), dep.scope());
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

    /** 旧单根扁平锁定：全部依赖按 PUBLIC 处理，保持原有解析规则。 */
    private LockFileResponse doLock(LockRequest request) {
        String rootName = request.rootName().trim();
        int rootVersion = request.rootVersion();

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
                currentRequestId.get(), MODE_FLAT, now);
        String rootScopePath = writeJson(List.of());
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, rootScopePath, n, v));
        solution.forEach((n, v) -> {
            ArtifactVersion artifact = findArtifact(snapshot, n, v);
            for (DependencyRange dep : artifact.dependencies()) {
                repositoryDao.insertLockEdge(lockFileId, rootScopePath, n, dep.name(),
                        dep.scope(), dep.minimumVersion(), dep.maximumVersion());
            }
        });

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                MODE_FLAT, entries, toEdgeViews(repositoryDao.listLockEdges(lockFileId)));
    }

    /** 新锁入口：启用私有作用域解析，冻结结构化作用域路径、精确版本与边。 */
    private LockFileResponse doScopedLock(LockRequest request) {
        String rootName = request.rootName().trim();
        int rootVersion = request.rootVersion();

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
        Map<List<String>, Map<String, ArtifactVersion>> solution =
                ScopedLockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合（含作用域嵌套与环约束），无法锁定");
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), MODE_SCOPED, now);
        List<LockEntryResponse> entries = new ArrayList<>();
        for (Map.Entry<List<String>, Map<String, ArtifactVersion>> scope : solution.entrySet()) {
            String scopePathJson = writeJson(scope.getKey());
            for (Map.Entry<String, ArtifactVersion> assignment : scope.getValue().entrySet()) {
                repositoryDao.insertLockEntry(lockFileId, scopePathJson,
                        assignment.getKey(), assignment.getValue().version());
                entries.add(new LockEntryResponse(assignment.getKey(),
                        assignment.getValue().version(), scope.getKey()));
                for (DependencyRange dep : assignment.getValue().dependencies()) {
                    repositoryDao.insertLockEdge(lockFileId, scopePathJson, assignment.getKey(),
                            dep.name(), dep.scope(), dep.minimumVersion(), dep.maximumVersion());
                }
            }
        }
        entries.sort((a, b) -> {
            int cmp = comparePaths(a.scopePath(), b.scopePath());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                MODE_SCOPED, entries, toEdgeViews(repositoryDao.listLockEdges(lockFileId)));
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
            // 同键并发：赢家已提交则重放其结果，否则报告冲突；不向调用方泄露唯一键异常。
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

    private void validateLockRequest(LockRequest request) {
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
    }

    /** 携带期望仓库版本时与当前版本比对（当前事务已持有行锁），不一致 409。 */
    private void checkExpectedRepositoryVersion(Long expectedRepositoryVersion) {
        if (expectedRepositoryVersion == null) {
            return;
        }
        long currentVersion = repositoryDao.getRepositoryVersion();
        if (currentVersion != expectedRepositoryVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedRepositoryVersion + ", actual=" + currentVersion);
        }
    }

    /** 结构化幂等身份：操作 + 操作者 + 资源名。 */
    private Map<String, Object> baseIdentity(String operation, String operator, String resource) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        return identity;
    }

    private Map<String, Object> lockIdentity(String operation, String operator, LockRequest request) {
        Map<String, Object> identity = baseIdentity(operation, operator, request.rootName().trim());
        identity.put("rootVersion", request.rootVersion());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return identity;
    }

    /** 依赖列表的规范化结构（按名称排序），供身份摘要使用。 */
    private List<Map<String, Object>> canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> {
                    Map<String, Object> dep = new LinkedHashMap<String, Object>();
                    dep.put("name", d.name().trim());
                    dep.put("minimumVersion", d.minimumVersion());
                    dep.put("maximumVersion", d.maximumVersion());
                    dep.put("scope", d.scope().name());
                    return dep;
                })
                .sorted((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")))
                .toList();
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion(),
                        spec.scope()));
            } else if (o instanceof DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion(),
                        range.scope()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<LockEdgeRow> edges) {
        List<LockEntryResponse> entryViews = new ArrayList<>(entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(), readScopePath(e.scopePath())))
                .toList());
        entryViews.sort((a, b) -> {
            int cmp = comparePaths(a.scopePath(), b.scopePath());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), row.lockMode(), entryViews,
                toEdgeViews(edges));
    }

    private List<LockEdgeResponse> toEdgeViews(List<LockEdgeRow> edges) {
        List<LockEdgeResponse> views = new ArrayList<>(edges.stream()
                .map(e -> new LockEdgeResponse(readScopePath(e.scopePath()), e.fromName(),
                        e.name(), com.example.starter.domain.DependencyScope.valueOf(e.scope()),
                        e.minimumVersion(), e.maximumVersion()))
                .toList());
        views.sort((a, b) -> {
            int cmp = comparePaths(a.scopePath(), b.scopePath());
            if (cmp != 0) {
                return cmp;
            }
            cmp = a.fromName().compareTo(b.fromName());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
        return views;
    }

    private static ArtifactVersion findArtifact(RepositorySnapshot snapshot, String name, int version) {
        return snapshot.artifacts().getOrDefault(name, List.of()).stream()
                .filter(a -> a.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("锁定结果在快照中缺失: " + name + ":" + version));
    }

    private static int comparePaths(List<String> left, List<String> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
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

    /** 解析作用域路径 JSON 数组为结构化名称链（根作用域为空表）。 */
    private List<String> readScopePath(String scopePathJson) {
        try {
            return List.copyOf(objectMapper.readValue(scopePathJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            throw new IllegalStateException("作用域路径反序列化失败", e);
        }
    }

    /** 结构化身份的 JSON 摘要：键序固定、值经 JSON 转义，避免分隔符碰撞。 */
    private String structuredHash(Map<String, Object> identity) {
        return sha256(writeJson(identity));
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
