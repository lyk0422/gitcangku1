package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.DeprecatedClosureItem;
import com.example.starter.api.dto.DeprecationDiagnosticsResponse;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.DeprecationSpec;
import com.example.starter.api.dto.DeprecationWarningView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RevokeDeprecationResponse;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.DeprecationRow;
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
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_DEPRECATE = "REGISTER_DEPRECATION";
    private static final String OP_REVOKE_DEPRECATION = "REVOKE_DEPRECATION";

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
    public BatchDeprecationResponse registerDeprecations(String requestId, BatchDeprecationRequest request) {
        requireRequestId(requestId);
        if (request.windows() == null || request.windows().isEmpty()) {
            throw ApiException.badRequest("windows 不能为空");
        }
        for (DeprecationSpec spec : request.windows()) {
            validateDeprecationSpec(spec);
        }
        String hash = sha256(OP_DEPRECATE + "|" + canonicalDeprecations(request.windows()));
        return executeIdempotent(requestId, OP_DEPRECATE, hash, 201,
                () -> doRegisterDeprecations(request), BatchDeprecationResponse.class);
    }

    @Override
    public RevokeDeprecationResponse revokeDeprecation(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_REVOKE_DEPRECATION + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_REVOKE_DEPRECATION, hash, 200,
                () -> doRevokeDeprecation(name.trim(), version), RevokeDeprecationResponse.class);
    }

    @Override
    public List<DeprecationResponse> listDeprecations() {
        return repositoryDao.listDeprecations().stream()
                .map(ArtifactServiceImpl::toDeprecationResponse)
                .toList();
    }

    @Override
    public DeprecationResponse getDeprecation(String name, int version) {
        DeprecationRow row = repositoryDao.findDeprecation(name, version);
        if (row == null) {
            throw ApiException.notFound("弃用窗口不存在: " + name + ":" + version);
        }
        return toDeprecationResponse(row);
    }

    @Override
    public DeprecationDiagnosticsResponse getDeprecationDiagnostics() {
        Instant now = Instant.now(clock);
        List<DeprecationRow> all = repositoryDao.listDeprecations();
        int pending = 0;
        int effective = 0;
        int revoked = 0;
        List<DeprecationResponse> effectiveWindows = new ArrayList<>();
        for (DeprecationRow row : all) {
            if (row.revoked()) {
                revoked++;
            } else if (now.isBefore(row.effectiveAt())) {
                pending++;
            } else {
                effective++;
                effectiveWindows.add(toDeprecationResponse(row));
            }
        }
        return new DeprecationDiagnosticsResponse(now, all.size(), pending, effective, revoked,
                List.copyOf(effectiveWindows));
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

        // 弃用裁决：与解析使用同一事务内的一致快照和同一时钟读数。
        Instant now = Instant.now(clock);
        Map<String, DeprecationRow> activeWindows = new HashMap<>();
        for (DeprecationRow row : repositoryDao.listActiveDeprecations()) {
            activeWindows.put(row.name() + ":" + row.version(), row);
        }
        List<DeprecatedClosureItem> violations = new ArrayList<>();
        List<DeprecationWarningView> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            DeprecationRow window = activeWindows.get(entry.getKey() + ":" + entry.getValue());
            if (window == null) {
                continue;
            }
            // 左闭右开：now >= effectiveAt 即已生效，整次 422；生效前仍可解析但产生警告。
            if (!now.isBefore(window.effectiveAt())) {
                violations.add(new DeprecatedClosureItem(window.name(), window.version(),
                        window.replacementVersion(), window.effectiveAt()));
            } else {
                warnings.add(new DeprecationWarningView(window.name(), window.version(),
                        window.replacementVersion(), window.effectiveAt(), window.reason()));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.unprocessable(
                    "依赖闭包包含已生效弃用版本，无法锁定：required=全部弃用版本替换为替代版本, "
                            + "actual=" + violations.size() + " 个已生效弃用版本",
                    List.copyOf(violations));
        }

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        // 固化命中的弃用警告与替代建议，后续弃用变更或替代版本撤回不改写历史锁。
        for (DeprecationWarningView warning : warnings) {
            repositoryDao.insertLockWarning(lockFileId, warning.name(), warning.version(),
                    warning.replacementVersion(), warning.effectiveAt(), warning.reason());
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries,
                List.copyOf(warnings));
    }

    private BatchDeprecationResponse doRegisterDeprecations(BatchDeprecationRequest request) {
        Instant now = Instant.now(clock);
        List<DeprecationSpec> specs = request.windows();

        // 批量内重复（同一制品版本仅允许一个窗口）。
        Set<String> seen = new HashSet<>();
        for (DeprecationSpec spec : specs) {
            String key = spec.name().trim() + ":" + spec.version();
            if (!seen.add(key)) {
                throw ApiException.conflict("批量请求内弃用窗口重复: " + key);
            }
        }

        // 先校验完整最终状态：生效时刻、制品存在、窗口未占用、替代版本存在且未撤回。
        for (DeprecationSpec spec : specs) {
            String name = spec.name().trim();
            String key = name + ":" + spec.version();
            if (spec.effectiveAt().isBefore(now)) {
                throw ApiException.unprocessable("弃用生效时刻早于当前时钟: " + key
                        + ", effectiveAt=" + spec.effectiveAt() + ", now=" + now);
            }
            if (repositoryDao.loadArtifact(name, spec.version()) == null) {
                throw ApiException.notFound("制品版本不存在: " + key);
            }
            if (repositoryDao.findDeprecation(name, spec.version()) != null) {
                throw ApiException.conflict("弃用窗口已存在: " + key);
            }
            ArtifactVersion replacement = repositoryDao.loadArtifact(name, spec.replacementVersion());
            if (replacement == null) {
                throw ApiException.unprocessable("替代版本不存在: " + name + ":"
                        + spec.replacementVersion() + "（弃用版本 " + key + " 的替代）");
            }
            if (replacement.withdrawn()) {
                throw ApiException.unprocessable("替代版本已撤回: " + name + ":"
                        + spec.replacementVersion() + "（弃用版本 " + key + " 的替代）");
            }
        }

        // 替代环校验：在既有有效窗口与本次批量合并后的完整替代图上检测。
        Map<String, Map<Integer, Integer>> replacementGraph = new HashMap<>();
        for (DeprecationRow row : repositoryDao.listActiveDeprecations()) {
            replacementGraph.computeIfAbsent(row.name(), k -> new HashMap<>())
                    .put(row.version(), row.replacementVersion());
        }
        for (DeprecationSpec spec : specs) {
            replacementGraph.computeIfAbsent(spec.name().trim(), k -> new HashMap<>())
                    .put(spec.version(), spec.replacementVersion());
        }
        for (DeprecationSpec spec : specs) {
            String name = spec.name().trim();
            Map<Integer, Integer> edges = replacementGraph.get(name);
            Set<Integer> visited = new HashSet<>();
            visited.add(spec.version());
            int next = spec.replacementVersion();
            while (edges.containsKey(next)) {
                if (!visited.add(next)) {
                    throw ApiException.unprocessable("替代版本形成环: " + name
                            + "（弃用版本 " + name + ":" + spec.version() + " 的替代链回到自身）");
                }
                next = edges.get(next);
            }
        }

        // 全部校验通过后在同一事务提交。
        List<DeprecationResponse> windows = new ArrayList<>();
        for (DeprecationSpec spec : specs) {
            String name = spec.name().trim();
            long id = repositoryDao.insertDeprecation(name, spec.version(), spec.effectiveAt(),
                    spec.replacementVersion(), spec.reason(), currentRequestId.get(), now);
            windows.add(new DeprecationResponse(id, name, spec.version(), spec.effectiveAt(),
                    spec.replacementVersion(), spec.reason(), false, null, now));
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new BatchDeprecationResponse(repositoryVersion, List.copyOf(windows));
    }

    private RevokeDeprecationResponse doRevokeDeprecation(String name, int version) {
        DeprecationRow row = repositoryDao.findDeprecation(name, version);
        if (row == null) {
            throw ApiException.notFound("弃用窗口不存在: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        if (row.revoked()) {
            throw ApiException.conflict("弃用窗口已撤销: " + name + ":" + version);
        }
        // 仅允许撤销尚未生效的窗口（左闭：now >= effectiveAt 即已生效）。
        if (!now.isBefore(row.effectiveAt())) {
            throw ApiException.conflict("弃用窗口已生效，不能撤销: " + name + ":" + version
                    + ", effectiveAt=" + row.effectiveAt() + ", now=" + now);
        }
        int affected = repositoryDao.markDeprecationRevoked(row.id(), now);
        if (affected == 0) {
            // 并发撤销抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("弃用窗口已撤销: " + name + ":" + version);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        DeprecationResponse window = new DeprecationResponse(row.id(), row.name(), row.version(),
                row.effectiveAt(), row.replacementVersion(), row.reason(), true, now, row.createdAt());
        return new RevokeDeprecationResponse(repositoryVersion, window);
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

    private void validateDeprecationSpec(DeprecationSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw ApiException.badRequest("弃用窗口的 name 不能为空");
        }
        if (spec.effectiveAt() == null) {
            throw ApiException.badRequest("弃用窗口的 effectiveAt 不能为空");
        }
        if (spec.version() <= 0 || spec.replacementVersion() <= 0) {
            throw ApiException.badRequest("弃用窗口的版本号必须为正整数");
        }
        if (spec.reason() != null && spec.reason().length() > 512) {
            throw ApiException.badRequest("弃用说明最长 512 字符，实际 " + spec.reason().length());
        }
    }

    private String canonicalDeprecations(List<DeprecationSpec> specs) {
        return specs.stream()
                .map(s -> s.name().trim() + ":" + s.version() + ":" + s.effectiveAt()
                        + ":" + s.replacementVersion() + ":" + (s.reason() == null ? "" : s.reason()))
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static DeprecationResponse toDeprecationResponse(DeprecationRow row) {
        return new DeprecationResponse(row.id(), row.name(), row.version(), row.effectiveAt(),
                row.replacementVersion(), row.reason(), row.revoked(), row.revokedAt(),
                row.createdAt());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<DeprecationWarningView> warnings = repositoryDao.listLockWarnings(row.id()).stream()
                .map(w -> new DeprecationWarningView(w.name(), w.version(), w.replacementVersion(),
                        w.effectiveAt(), w.reason()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, warnings);
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
