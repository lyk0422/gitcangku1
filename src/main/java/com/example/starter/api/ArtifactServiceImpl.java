package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockMirrorView;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorFailoverResponse;
import com.example.starter.api.dto.MirrorSpec;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorsRequest;
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
import com.fasterxml.jackson.databind.JavaType;
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
    private static final int MAX_MIRRORS_PER_VERSION = 3;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_REGISTER_MIRROR = "REGISTER_MIRROR";
    private static final String OP_MIRROR_AVAILABILITY = "SET_MIRROR_AVAILABILITY";

    private static final JavaType MIRROR_VIEW_LIST = com.fasterxml.jackson.databind.type.TypeFactory
            .defaultInstance().constructCollectionType(List.class, MirrorView.class);

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
    public List<MirrorView> registerMirrors(String requestId, String name, int version,
                                            RegisterMirrorsRequest request) {
        requireRequestId(requestId);
        validateMirrorRequest(request);
        String hash = sha256(OP_REGISTER_MIRROR + "|" + name.trim() + "|" + version + "|"
                + canonicalMirrors(request));
        return executeIdempotent(requestId, OP_REGISTER_MIRROR, hash, 201,
                () -> doRegisterMirrors(name.trim(), version, request), MIRROR_VIEW_LIST);
    }

    @Override
    public MirrorView setMirrorAvailability(String requestId, String name, int version,
                                            String mirrorId, boolean available) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (mirrorId == null || mirrorId.isBlank()) {
            throw ApiException.badRequest("mirrorId 不能为空");
        }
        String hash = sha256(OP_MIRROR_AVAILABILITY + "|" + name.trim() + "|" + version + "|"
                + mirrorId.trim() + "|" + available);
        return executeIdempotent(requestId, OP_MIRROR_AVAILABILITY, hash, 200,
                () -> doSetMirrorAvailability(name.trim(), version, mirrorId.trim(), available),
                MirrorView.class);
    }

    @Override
    public List<MirrorView> listMirrors(String name, int version) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        return toMirrorViews(repositoryDao.listMirrorsByArtifact(artifact.id()));
    }

    @Override
    public MirrorFailoverResponse failoverMirror(long lockFileId, String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String trimmed = name.trim();
        if (repositoryDao.getLockFile(lockFileId) == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        LockEntryRow entry = repositoryDao.findLockEntry(lockFileId, trimmed);
        if (entry == null) {
            throw ApiException.notFound(
                    "制品名称不在锁文件解析集合中: " + trimmed);
        }
        // 单条 SQL 读取锁定版本当前登记的镜像（含可用性），语句级一致快照。
        List<MirrorRow> mirrors = repositoryDao.listMirrorsOfLockedVersion(lockFileId, trimmed);
        MirrorRow selected = null;
        for (MirrorRow row : mirrors) {
            if (row.available()) {
                selected = row;
                break;
            }
        }
        if (selected == null) {
            throw ApiException.unprocessable(
                    "制品 " + trimmed + " 锁定版本登记的全部镜像当前均不可用");
        }
        return new MirrorFailoverResponse(lockFileId, trimmed, entry.version(),
                selected.mirrorId(), selected.priority(), toMirrorViews(mirrors));
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

    private List<MirrorView> doRegisterMirrors(String name, int version,
                                               RegisterMirrorsRequest request) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        int existing = repositoryDao.countMirrors(artifact.id());
        if (existing + request.mirrors().size() > MAX_MIRRORS_PER_VERSION) {
            throw ApiException.unprocessable(
                    "制品版本 " + name + ":" + version + " 的镜像源数量将超过上限 "
                            + MAX_MIRRORS_PER_VERSION);
        }
        Instant now = Instant.now(clock);
        for (MirrorSpec spec : request.mirrors()) {
            String mirrorId = spec.mirrorId().trim();
            if (repositoryDao.findMirror(artifact.id(), mirrorId) != null) {
                throw ApiException.conflict(
                        "镜像源已登记: " + name + ":" + version + " -> " + mirrorId);
            }
            repositoryDao.insertMirror(artifact.id(), mirrorId, spec.priority(), now);
        }
        return toMirrorViews(repositoryDao.listMirrorsByArtifact(artifact.id()));
    }

    private MirrorView doSetMirrorAvailability(String name, int version,
                                               String mirrorId, boolean available) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        MirrorRow mirror = repositoryDao.findMirror(artifact.id(), mirrorId);
        if (mirror == null) {
            throw ApiException.notFound(
                    "镜像源未登记: " + name + ":" + version + " -> " + mirrorId);
        }
        repositoryDao.updateMirrorAvailability(artifact.id(), mirrorId, available);
        return new MirrorView(mirrorId, mirror.priority(), available);
    }

    private ArtifactResponse doWithdraw(String name, int version) {        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
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

        // 固化镜像快照：每个锁定版本按优先级升序、仅保留当前可用镜像；
        // 过滤后为空不影响锁定成功，后续可用性变更不回写本快照。
        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> {
            List<LockMirrorView> mirrors = lockTimeMirrors(lockFileId, n, v);
            entries.add(new LockEntryResponse(n, v, mirrors));
        });
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /**
     * 计算并持久化某锁定版本的镜像快照，返回按优先级升序的可用镜像清单。
     * 须在持有仓库行锁的事务内调用，与锁定读取的仓库状态一致。
     */
    private List<LockMirrorView> lockTimeMirrors(long lockFileId, String name, int version) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            return List.of();
        }
        List<LockMirrorView> mirrors = new ArrayList<>();
        for (MirrorRow row : repositoryDao.listMirrorsByArtifact(artifact.id())) {
            if (!row.available()) {
                continue;
            }
            repositoryDao.insertLockMirror(lockFileId, name, row.mirrorId(), row.priority());
            mirrors.add(new LockMirrorView(row.mirrorId(), row.priority()));
        }
        return List.copyOf(mirrors);
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
        return executeIdempotent(requestId, operation, requestHash, httpStatus, action,
                objectMapper.getTypeFactory().constructType(responseType));
    }

    private <T> T executeIdempotent(String requestId, String operation, String requestHash,
                                    int httpStatus, Supplier<T> action, JavaType responseType) {
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
                                  JavaType responseType) {
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
        Map<String, List<LockMirrorView>> mirrorsByName = new java.util.HashMap<>();
        for (LockMirrorRow mirror : repositoryDao.listLockMirrors(row.id())) {
            mirrorsByName.computeIfAbsent(mirror.name(), k -> new ArrayList<>())
                    .add(new LockMirrorView(mirror.mirrorId(), mirror.priority()));
        }
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        mirrorsByName.getOrDefault(e.name(), List.of())))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private void validateMirrorRequest(RegisterMirrorsRequest request) {
        if (request.mirrors() == null || request.mirrors().isEmpty()
                || request.mirrors().size() > MAX_MIRRORS_PER_VERSION) {
            throw ApiException.badRequest(
                    "一次登记 1～" + MAX_MIRRORS_PER_VERSION + " 个镜像源");
        }
        Set<String> ids = new HashSet<>();
        for (MirrorSpec spec : request.mirrors()) {
            if (spec.mirrorId() == null || spec.mirrorId().isBlank()) {
                throw ApiException.badRequest("镜像源标识不能为空");
            }
            if (spec.priority() < 1) {
                throw ApiException.badRequest("镜像优先级必须为正整数，1 为最高");
            }
            // 同优先级允许共存，按镜像标识字典序确定稳定次序。
            if (!ids.add(spec.mirrorId().trim())) {
                throw ApiException.badRequest("镜像源标识重复: " + spec.mirrorId().trim());
            }
        }
    }

    private String canonicalMirrors(RegisterMirrorsRequest request) {
        return request.mirrors().stream()
                .map(m -> m.mirrorId().trim() + ":" + m.priority())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private List<MirrorView> toMirrorViews(List<MirrorRow> rows) {
        return rows.stream()
                .map(r -> new MirrorView(r.mirrorId(), r.priority(), r.available()))
                .toList();
    }

    private void requireRequestId(String requestId) {        if (requestId == null || requestId.isBlank()) {
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

    private <T> T readJson(String json, JavaType type) {
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
