package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolationResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetPolicyRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LicensePolicyChecker;
import com.example.starter.domain.LicenseViolation;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.NamespacePolicy;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.PolicyDetailRow;
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
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 * 锁定、撤回、许可证登记与策略修改均串行于同一行锁，按事务提交顺序裁决。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_SET_LICENSE = "SET_LICENSE";
    private static final String OP_SET_POLICY = "SET_POLICY";

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
    public LicenseResponse setLicense(String requestId, String name, int version, String license) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String normalized = normalizeLicense(license);
        if (normalized != null && normalized.length() > 64) {
            throw ApiException.badRequest("许可证标识最长 64 字符");
        }
        String hash = sha256(OP_SET_LICENSE + "|" + name.trim() + "|" + version + "|"
                + (normalized == null ? "" : normalized));
        return executeIdempotent(requestId, OP_SET_LICENSE, hash, 200,
                () -> doSetLicense(name.trim(), version, normalized), LicenseResponse.class);
    }

    @Override
    public PolicyResponse setPolicy(String requestId, SetPolicyRequest request) {
        requireRequestId(requestId);
        validatePolicyRequest(request);
        String namespace = request.namespace().trim();
        Set<String> allowed = new TreeSet<>();
        for (String license : request.allowedLicenses()) {
            allowed.add(license.trim());
        }
        String hash = sha256(OP_SET_POLICY + "|" + namespace + "|" + request.expectedVersion()
                + "|" + request.rejectUnknown() + "|" + String.join(",", allowed));
        return executeIdempotent(requestId, OP_SET_POLICY, hash, 200,
                () -> doSetPolicy(namespace, request.expectedVersion(),
                        request.rejectUnknown(), allowed),
                PolicyResponse.class);
    }

    @Override
    public PolicyResponse getPolicy(String namespace) {
        PolicyDetailRow detail = repositoryDao.loadPolicyDetail(namespace);
        if (detail == null) {
            return null;
        }
        NamespacePolicy policy = repositoryDao.loadPolicy(namespace);
        return new PolicyResponse(detail.namespace(), detail.version(), detail.rejectUnknown(),
                policy.sortedAllowedLicenses(), detail.updatedAt());
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
    public List<LicenseViolationResponse> diagnoseLock(LockRequest request) {
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        return transactionTemplate.execute(status -> {
            // 与正式锁定同样持有行锁，保证诊断基于一致快照；只读，提交时不产生任何写入。
            repositoryDao.lockRepositoryState();
            String rootName = request.rootName().trim();
            ArtifactVersion root = repositoryDao.loadArtifact(rootName, request.rootVersion());
            if (root == null) {
                throw ApiException.notFound("根制品版本不存在: " + rootName + ":" + request.rootVersion());
            }
            if (root.withdrawn()) {
                throw ApiException.conflict("根制品版本已撤回: " + rootName + ":" + request.rootVersion());
            }
            RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
            Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, request.rootVersion());
            if (solution == null) {
                throw ApiException.unprocessable(
                        "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
            }
            Map<String, String> licenses = new HashMap<>();
            snapshot.artifacts().forEach((name, versions) -> versions.forEach(v -> {
                if (v.license() != null) {
                    licenses.put(name + ":" + v.version(), v.license());
                }
            }));
            return LicensePolicyChecker.check(solution, licenses, snapshot.policies()).stream()
                    .map(v -> new LicenseViolationResponse(v.name(), v.version(), v.license(), v.reason()))
                    .toList();
        });
    }

    @Override
    public List<LockFileResponse> listLocks() {        List<LockFileResponse> result = new ArrayList<>();
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

    private LicenseResponse doSetLicense(String name, int version, String license) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，不可修改许可证: " + name + ":" + version);
        }
        int affected = repositoryDao.updateLicense(name, version, license);
        if (affected == 0) {
            // 并发撤回抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("制品版本已撤回，不可修改许可证: " + name + ":" + version);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new LicenseResponse(name, version, license, false, repositoryVersion,
                Instant.now(clock));
    }

    private PolicyResponse doSetPolicy(String namespace, long expectedVersion,
                                       boolean rejectUnknown, Set<String> allowed) {
        Instant now = Instant.now(clock);
        NamespacePolicy current = repositoryDao.loadPolicy(namespace);
        long newVersion;
        if (current == null) {
            if (expectedVersion != 0) {
                throw ApiException.conflict("命名空间策略不存在，expectedVersion 必须为 0: " + namespace);
            }
            repositoryDao.insertPolicy(namespace, rejectUnknown, now);
            newVersion = 1;
        } else {
            if (current.version() != expectedVersion) {
                throw ApiException.conflict("策略版本不匹配：expected=" + expectedVersion
                        + ", actual=" + current.version());
            }
            int updated = repositoryDao.updatePolicy(namespace, expectedVersion, rejectUnknown, now);
            if (updated == 0) {
                throw ApiException.conflict("策略版本不匹配：expected=" + expectedVersion);
            }
            newVersion = expectedVersion + 1;
        }
        repositoryDao.deletePolicyLicenses(namespace);
        for (String license : allowed) {
            repositoryDao.insertPolicyLicense(namespace, license);
        }
        repositoryDao.incrementRepositoryVersion();
        return new PolicyResponse(namespace, newVersion, rejectUnknown,
                List.copyOf(allowed), now);
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

        // 依赖解析成功后，对完整闭包校验命名空间许可证策略；任一违规整次锁定 422。
        Map<String, String> licenses = new HashMap<>();
        snapshot.artifacts().forEach((name, versions) -> versions.forEach(v -> {
            if (v.license() != null) {
                licenses.put(name + ":" + v.version(), v.license());
            }
        }));
        List<LicenseViolation> violations = LicensePolicyChecker.check(
                solution, licenses, snapshot.policies());
        if (!violations.isEmpty()) {
            throw ApiException.policyViolation(violations);
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        // 固化每个解析版本在锁定时的许可证与策略版本；后续修改不改写历史。
        solution.forEach((name, version) -> {
            String license = licenses.get(name + ":" + version);
            NamespacePolicy policy = snapshot.policies().get(name);
            repositoryDao.insertLockEntry(lockFileId, name, version, license,
                    policy == null ? 0L : policy.version());
        });

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((name, version) -> {
            String license = licenses.get(name + ":" + version);
            NamespacePolicy policy = snapshot.policies().get(name);
            entries.add(new LockEntryResponse(name, version, license,
                    policy == null ? 0L : policy.version()));
        });
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
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

    private void validatePolicyRequest(SetPolicyRequest request) {
        if (request.namespace() == null || request.namespace().isBlank()) {
            throw ApiException.badRequest("namespace 不能为空");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.badRequest("expectedVersion 必须为非负整数");
        }
        if (request.rejectUnknown() == null) {
            throw ApiException.badRequest("rejectUnknown 不能为空");
        }
        if (request.allowedLicenses().size() > 50) {
            throw ApiException.badRequest("允许许可证集合最多 50 个标识");
        }
        for (String license : request.allowedLicenses()) {
            if (license == null || license.isBlank()) {
                throw ApiException.badRequest("允许许可证标识不能为空");
            }
            if (license.trim().length() > 64) {
                throw ApiException.badRequest("许可证标识最长 64 字符: " + license.trim());
            }
        }
    }

    /** 空白许可证归一化为 null（UNKNOWN），其余去首尾空白。 */
    private static String normalizeLicense(String license) {
        if (license == null || license.isBlank()) {
            return null;
        }
        return license.trim();
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
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(), e.license(), e.policyVersion()))
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
