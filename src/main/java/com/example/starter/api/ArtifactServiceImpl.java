package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.EdgeView;
import com.example.starter.api.dto.JointLockFileResponse;
import com.example.starter.api.dto.JointLockRequest;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RootSpec;
import com.example.starter.api.dto.RootView;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
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
import java.util.Comparator;
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
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_JOINT_LOCK = "CREATE_JOINT_LOCK";

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
        Map<String, Object> identity = new TreeMap<>();
        identity.put("name", request.name().trim());
        identity.put("version", request.version());
        identity.put("dependencies", canonicalDependencies(request));
        return executeIdempotent(requestId, OP_REGISTER, structuredHash(OP_REGISTER, identity), 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        Map<String, Object> identity = new TreeMap<>();
        identity.put("name", name.trim());
        identity.put("version", version);
        return executeIdempotent(requestId, OP_WITHDRAW, structuredHash(OP_WITHDRAW, identity), 200,
                () -> doWithdraw(name.trim(), version), ArtifactResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        Map<String, Object> identity = new TreeMap<>();
        identity.put("rootName", request.rootName().trim());
        identity.put("rootVersion", request.rootVersion());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_LOCK, structuredHash(OP_LOCK, identity), 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public JointLockFileResponse createJointLock(String requestId, JointLockRequest request) {
        requireRequestId(requestId);
        TreeMap<String, Integer> roots = validateJointLockRequest(request);
        List<Map<String, Object>> rootIdentity = new ArrayList<>();
        roots.forEach((name, version) -> {
            Map<String, Object> root = new TreeMap<>();
            root.put("name", name);
            root.put("version", version);
            rootIdentity.add(root);
        });
        Map<String, Object> identity = new TreeMap<>();
        identity.put("roots", rootIdentity);
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_JOINT_LOCK, structuredHash(OP_JOINT_LOCK, identity), 201,
                () -> doJointLock(roots, request.expectedRepositoryVersion()), JointLockFileResponse.class);
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
    public List<JointLockFileResponse> listJointLocks() {
        List<JointLockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listJointLockFiles()) {
            result.add(toJointLockResponse(row));
        }
        return result;
    }

    @Override
    public JointLockFileResponse getJointLock(long id) {
        LockFileRow row = repositoryDao.getJointLockFile(id);
        if (row == null) {
            throw ApiException.notFound("联合锁文件不存在: " + id);
        }
        return toJointLockResponse(row);
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
                currentRequestId.get(), now, false);
        repositoryDao.insertLockRoot(lockFileId, rootName, rootVersion);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        for (EdgeView edge : computeEdges(snapshot, solution)) {
            repositoryDao.insertLockEdge(lockFileId, edge.fromName(), edge.toName(),
                    edge.minimumVersion(), edge.maximumVersion());
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /**
     * 多根联合锁定：全部根固定精确版本，汇合全部根的区间约束后联合求解，
     * 成功时冻结全部根、共享依赖、依赖边及仓库版本。
     */
    private JointLockFileResponse doJointLock(TreeMap<String, Integer> roots,
                                              long expectedRepositoryVersion) {
        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedRepositoryVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedRepositoryVersion + ", actual=" + currentVersion);
        }

        for (Map.Entry<String, Integer> root : roots.entrySet()) {
            ArtifactVersion artifact = repositoryDao.loadArtifact(root.getKey(), root.getValue());
            if (artifact == null) {
                throw ApiException.unprocessable(
                        "根制品版本不存在: " + root.getKey() + ":" + root.getValue());
            }
            if (artifact.withdrawn()) {
                throw ApiException.unprocessable(
                        "根制品版本已撤回: " + root.getKey() + ":" + root.getValue());
            }
        }

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, Integer> solution = LockResolver.resolveJoint(snapshot, roots);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部根与依赖区间的未撤回版本组合，无法联合锁定");
        }

        Instant now = Instant.now(clock);
        String firstRoot = roots.firstKey();
        long lockFileId = repositoryDao.insertLockFile(firstRoot, roots.get(firstRoot),
                currentVersion, currentRequestId.get(), now, true);
        roots.forEach((n, v) -> repositoryDao.insertLockRoot(lockFileId, n, v));
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        List<EdgeView> edges = computeEdges(snapshot, solution);
        for (EdgeView edge : edges) {
            repositoryDao.insertLockEdge(lockFileId, edge.fromName(), edge.toName(),
                    edge.minimumVersion(), edge.maximumVersion());
        }

        List<RootView> rootViews = new ArrayList<>();
        roots.forEach((n, v) -> rootViews.add(new RootView(n, v)));
        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new JointLockFileResponse(lockFileId, rootViews, currentVersion, now, entries, edges);
    }

    /**
     * 从快照中提取解集合内部的依赖边（按 fromName、toName 升序）。
     * 解完备时被锁制品声明的每条依赖都指向集合内名称，因此边覆盖完整锁图。
     */
    private List<EdgeView> computeEdges(RepositorySnapshot snapshot, Map<String, Integer> solution) {
        List<EdgeView> edges = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            ArtifactVersion artifact = snapshot.artifacts()
                    .getOrDefault(entry.getKey(), List.of())
                    .stream()
                    .filter(a -> a.version() == entry.getValue())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "解中的制品不在快照内: " + entry.getKey() + ":" + entry.getValue()));
            for (DependencyRange dep : artifact.dependencies()) {
                if (solution.containsKey(dep.name())) {
                    edges.add(new EdgeView(entry.getKey(), dep.name(),
                            dep.minimumVersion(), dep.maximumVersion()));
                }
            }
        }
        edges.sort(Comparator.comparing(EdgeView::fromName).thenComparing(EdgeView::toName));
        return edges;
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

    /**
     * 校验联合锁定请求并返回按名称排序的根集合（名称 -> 精确版本）。
     * 根数量须为 2 至 4；同名根（无论版本是否相同）视为重复，返回 400。
     */
    private TreeMap<String, Integer> validateJointLockRequest(JointLockRequest request) {
        if (request == null || request.roots() == null
                || request.roots().size() < 2 || request.roots().size() > 4) {
            throw ApiException.badRequest("根数量必须为 2 至 4 个");
        }
        if (request.expectedRepositoryVersion() == null || request.expectedRepositoryVersion() < 0) {
            throw ApiException.badRequest("expectedRepositoryVersion 必须为非负整数");
        }
        TreeMap<String, Integer> roots = new TreeMap<>();
        for (RootSpec root : request.roots()) {
            if (root == null || root.name() == null || root.name().isBlank()) {
                throw ApiException.badRequest("根名称不能为空");
            }
            String rootName = root.name().trim();
            if (root.version() <= 0) {
                throw ApiException.badRequest("根版本必须为正整数: " + rootName);
            }
            if (roots.put(rootName, root.version()) != null) {
                throw ApiException.badRequest("根名称重复: " + rootName);
            }
        }
        return roots;
    }

    private List<Map<String, Object>> canonicalDependencies(RegisterArtifactRequest request) {
        List<Map<String, Object>> deps = new ArrayList<>();
        for (DependencySpec dep : request.dependencies()) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("name", dep.name().trim());
            entry.put("minimumVersion", dep.minimumVersion());
            entry.put("maximumVersion", dep.maximumVersion());
            deps.add(entry);
        }
        deps.sort(Comparator.comparing(m -> (String) m.get("name")));
        return deps;
    }

    /**
     * 结构化幂等身份：操作类型与规范化参数组成有序结构，序列化为 JSON 后取 SHA-256，
     * 避免字符串拼接带来的分隔符碰撞。
     */
    private String structuredHash(String operation, Map<String, Object> identity) {
        Map<String, Object> payload = new TreeMap<>(identity);
        payload.put("operation", operation);
        try {
            return sha256(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("幂等身份序列化失败", e);
        }
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

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private JointLockFileResponse toJointLockResponse(LockFileRow row) {
        List<RootView> roots = repositoryDao.listLockRoots(row.id()).stream()
                .map(r -> new RootView(r.name(), r.version()))
                .toList();
        List<LockEntryResponse> entries = repositoryDao.listLockEntries(row.id()).stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<EdgeView> edges = repositoryDao.listLockEdges(row.id()).stream()
                .map(e -> new EdgeView(e.fromName(), e.toName(),
                        e.minimumVersion(), e.maximumVersion()))
                .toList();
        return new JointLockFileResponse(row.id(), roots, row.repositoryVersion(),
                row.createdAt(), entries, edges);
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
