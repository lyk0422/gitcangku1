package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorDetailView;
import com.example.starter.api.dto.MirrorResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.LockMirrorRow;
import com.example.starter.repo.RepositoryDao.MirrorRow;
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
    private static final String OP_REGISTER_MIRROR = "REGISTER_MIRROR";
    private static final String OP_MIRROR_UNAVAILABLE = "MIRROR_UNAVAILABLE";
    private static final String OP_MIRROR_AVAILABLE = "MIRROR_AVAILABLE";

    private static final int MAX_MIRRORS = 3;

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
                currentRequestId.get(), now);
        Map<String, List<MirrorView>> mirrorsByEntry = new TreeMap<>();
        solution.forEach((n, v) -> {
            repositoryDao.insertLockEntry(lockFileId, n, v);
            // 与快照同一事务：读取锁定版本的当前可用镜像并固化，可用性事后变更不追溯。
            long artifactId = snapshot.artifacts().get(n).stream()
                    .filter(a -> a.version() == v)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("锁定版本缺少制品记录: " + n + ":" + v))
                    .id();
            List<MirrorView> mirrors = new ArrayList<>();
            for (MirrorRow mirror : repositoryDao.listAvailableMirrors(artifactId)) {
                repositoryDao.insertLockMirror(lockFileId, n, v,
                        mirror.mirrorId(), mirror.priority());
                mirrors.add(new MirrorView(mirror.mirrorId(), mirror.priority()));
            }
            mirrorsByEntry.put(n, List.copyOf(mirrors));
        });

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) ->
                entries.add(new LockEntryResponse(n, v, mirrorsByEntry.get(n))));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    @Override
    public MirrorResponse registerMirror(String requestId, String name, int version,
                                         RegisterMirrorRequest request) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String trimmedName = name.trim();
        String mirrorId = request.mirrorId().trim();
        int priority = request.priority();
        String hash = sha256(OP_REGISTER_MIRROR + "|" + trimmedName + "|" + version + "|"
                + mirrorId + "|" + priority);
        return executeIdempotent(requestId, OP_REGISTER_MIRROR, hash, 201,
                () -> doRegisterMirror(trimmedName, version, mirrorId, priority),
                MirrorResponse.class);
    }

    @Override
    public MirrorResponse setMirrorAvailability(String requestId, String name, int version,
                                                String mirrorId, boolean available) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (mirrorId == null || mirrorId.isBlank()) {
            throw ApiException.badRequest("mirrorId 不能为空");
        }
        String trimmedName = name.trim();
        String trimmedMirrorId = mirrorId.trim();
        String operation = available ? OP_MIRROR_AVAILABLE : OP_MIRROR_UNAVAILABLE;
        String hash = sha256(operation + "|" + trimmedName + "|" + version + "|" + trimmedMirrorId);
        return executeIdempotent(requestId, operation, hash, 200,
                () -> doSetMirrorAvailability(trimmedName, version, trimmedMirrorId, available),
                MirrorResponse.class);
    }

    @Override
    public List<MirrorDetailView> listMirrors(String name, int version) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        ArtifactVersion artifact = repositoryDao.loadArtifact(name.trim(), version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name.trim() + ":" + version);
        }
        return repositoryDao.listMirrors(artifact.id()).stream()
                .map(m -> new MirrorDetailView(m.mirrorId(), m.priority(),
                        m.available(), m.createdAt()))
                .toList();
    }

    @Override
    public MirrorView resolveMirror(long lockId, String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        // 只读事务：锁单行仓库状态串行化并发写，锁文件、锁定条目与镜像可用性在同一事务读取，
        // 保证故障切换基于一致快照，绝不选中已在本事务前标记不可用的镜像。
        return transactionTemplate.execute(status -> {
            repositoryDao.lockRepositoryState();
            if (repositoryDao.getLockFile(lockId) == null) {
                throw ApiException.notFound("锁文件不存在: " + lockId);
            }
            LockEntryRow entry = repositoryDao.findLockEntry(lockId, name.trim());
            if (entry == null) {
                throw ApiException.notFound(
                        "制品名称不在锁文件解析集合中: lock=" + lockId + ", name=" + name.trim());
            }
            ArtifactVersion artifact = repositoryDao.loadArtifact(name.trim(), entry.version());
            if (artifact == null) {
                // 记录被物理删除不属于正常流程；防御性按 422 处理。
                throw ApiException.unprocessable(
                        "锁定版本的制品记录不存在: " + name.trim() + ":" + entry.version());
            }
            List<MirrorRow> available = repositoryDao.listAvailableMirrors(artifact.id());
            if (available.isEmpty()) {
                throw ApiException.unprocessable(
                        "制品 " + name.trim() + ":" + entry.version() + " 当前没有可用镜像源");
            }
            MirrorRow chosen = available.get(0);
            return new MirrorView(chosen.mirrorId(), chosen.priority());
        });
    }

    // ------------------------------------------------------------------
    // 镜像业务操作（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    private MirrorResponse doRegisterMirror(String name, int version, String mirrorId, int priority) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (repositoryDao.findMirror(artifact.id(), mirrorId) != null) {
            throw ApiException.conflict(
                    "镜像源已登记: " + name + ":" + version + " mirror=" + mirrorId);
        }
        if (repositoryDao.countMirrors(artifact.id()) >= MAX_MIRRORS) {
            throw ApiException.unprocessable(
                    "制品 " + name + ":" + version + " 的镜像源数量已达上限 " + MAX_MIRRORS);
        }
        boolean priorityDuplicate = repositoryDao.listMirrors(artifact.id()).stream()
                .anyMatch(m -> m.priority() == priority);
        if (priorityDuplicate) {
            throw ApiException.unprocessable(
                    "镜像源优先级 " + priority + " 在制品 " + name + ":" + version + " 上已被占用");
        }

        Instant now = Instant.now(clock);
        repositoryDao.insertMirror(artifact.id(), mirrorId, priority, now);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new MirrorResponse(name, version, mirrorId, priority, true, repositoryVersion, now);
    }

    private MirrorResponse doSetMirrorAvailability(String name, int version,
                                                   String mirrorId, boolean available) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        MirrorRow mirror = repositoryDao.findMirror(artifact.id(), mirrorId);
        if (mirror == null) {
            throw ApiException.notFound(
                    "镜像源未登记: " + name + ":" + version + " mirror=" + mirrorId);
        }
        if (mirror.available() == available) {
            throw ApiException.conflict(available
                    ? "镜像源已处于可用状态: " + mirrorId
                    : "镜像源已处于不可用状态: " + mirrorId);
        }
        int affected = repositoryDao.updateMirrorAvailability(artifact.id(), mirrorId, available);
        if (affected == 0) {
            // 并发抢先切换（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict(available
                    ? "镜像源已处于可用状态: " + mirrorId
                    : "镜像源已处于不可用状态: " + mirrorId);
        }
        Instant now = Instant.now(clock);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new MirrorResponse(name, version, mirrorId, mirror.priority(),
                available, repositoryVersion, now);
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
        Map<String, List<com.example.starter.api.dto.MirrorView>> mirrorsByName = new TreeMap<>();
        repositoryDao.listLockMirrors(row.id()).forEach(m ->
                mirrorsByName.computeIfAbsent(m.name(), k -> new ArrayList<>())
                        .add(new com.example.starter.api.dto.MirrorView(m.mirrorId(), m.priority())));
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        List.copyOf(mirrorsByName.getOrDefault(e.name(), List.of()))))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
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
