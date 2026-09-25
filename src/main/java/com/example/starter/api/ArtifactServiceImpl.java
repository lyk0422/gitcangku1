package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.DiagnoseLockRequest;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolation;
import com.example.starter.api.dto.LockDiagnosisResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockLicenseEntryResponse;
import com.example.starter.api.dto.LockLicenseSnapshotResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetLicenseRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.PolicyRow;
import com.example.starter.support.ApiException;
import com.example.starter.support.LicenseViolationException;
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
 *
 * <p>制品登记、撤回、许可证登记、策略修改与锁定共享同一仓库版本轴：
 * 任一写操作提交都推进仓库版本号，锁定以 expectedRepositoryVersion 做乐观校验，
 * 因此并发写按事务提交顺序裁决，锁定始终基于一致的依赖闭包、许可证与策略版本。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_ALLOWED_LICENSES = 50;
    private static final int MAX_LICENSE_LENGTH = 64;

    /** 未登记许可证的制品版本在锁定与诊断中的语义标识。 */
    static final String UNKNOWN_LICENSE = "UNKNOWN";
    /** 违规原因：许可证已登记但不在策略允许集合内。 */
    static final String REASON_NOT_ALLOWED = "LICENSE_NOT_ALLOWED";
    /** 违规原因：许可证未登记（UNKNOWN）且策略拒绝 UNKNOWN。 */
    static final String REASON_UNKNOWN_REJECTED = "UNKNOWN_LICENSE_REJECTED";

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_SET_LICENSE = "SET_LICENSE";
    private static final String OP_UPSERT_POLICY = "UPSERT_POLICY";

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
    public LicenseResponse setLicense(String requestId, String name, int version,
                                      SetLicenseRequest request) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String license = normalizeLicense(request == null ? null : request.license());
        String hash = sha256(OP_SET_LICENSE + "|" + name.trim() + "|" + version + "|" + license);
        return executeIdempotent(requestId, OP_SET_LICENSE, hash, 200,
                () -> doSetLicense(name.trim(), version, license), LicenseResponse.class);
    }

    @Override
    public PolicyResponse upsertPolicy(String requestId, String namespace, PolicyRequest request) {
        requireRequestId(requestId);
        if (namespace == null || namespace.isBlank()) {
            throw ApiException.badRequest("namespace 不能为空");
        }
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.badRequest("expectedVersion 必须为不小于 0 的整数");
        }
        List<String> allowed = canonicalLicenses(request.allowedLicenses());
        String hash = sha256(OP_UPSERT_POLICY + "|" + namespace.trim() + "|" + request.expectedVersion()
                + "|" + String.join(",", allowed) + "|" + request.rejectUnknown());
        return executeIdempotent(requestId, OP_UPSERT_POLICY, hash, 200,
                () -> doUpsertPolicy(namespace.trim(), request.expectedVersion(), allowed,
                        request.rejectUnknown()),
                PolicyResponse.class);
    }

    @Override
    public PolicyResponse getPolicy(String namespace) {
        PolicyRow row = repositoryDao.findPolicy(namespace);
        if (row == null) {
            throw ApiException.notFound("命名空间未配置许可证策略: " + namespace);
        }
        return new PolicyResponse(row.namespace(), row.version(),
                repositoryDao.listAllowedLicenses(namespace), row.rejectUnknown(), row.updatedAt());
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

    @Override
    public LockLicenseSnapshotResponse getLockLicenseSnapshot(long lockId) {
        LockFileRow row = repositoryDao.getLockFile(lockId);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + lockId);
        }
        List<LockLicenseEntryResponse> entries = repositoryDao.listLockEntries(lockId).stream()
                .map(e -> new LockLicenseEntryResponse(e.name(), e.version(), e.license()))
                .toList();
        return new LockLicenseSnapshotResponse(row.id(), row.policyVersion(), entries);
    }

    @Override
    public LockDiagnosisResponse diagnoseLock(DiagnoseLockRequest request) {
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String rootName = request.rootName().trim();
        int rootVersion = request.rootVersion();

        ArtifactVersion root = repositoryDao.loadArtifact(rootName, rootVersion);
        if (root == null) {
            throw ApiException.notFound("根制品版本不存在: " + rootName + ":" + rootVersion);
        }
        if (root.withdrawn()) {
            throw ApiException.conflict("根制品版本已撤回: " + rootName + ":" + rootVersion);
        }

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        PolicyRow policy = repositoryDao.findPolicy(rootName);
        Long policyVersion = policy == null ? null : policy.version();

        Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            return new LockDiagnosisResponse(rootName, rootVersion, snapshot.repositoryVersion(),
                    false, policyVersion, List.of(), List.of());
        }
        Map<String, String> licenses = loadLicenses(snapshot, solution);
        List<LicenseViolation> violations = evaluateViolations(solution, licenses, policy);
        List<LockLicenseEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockLicenseEntryResponse(n, v, licenses.get(n))));
        return new LockDiagnosisResponse(rootName, rootVersion, snapshot.repositoryVersion(),
                true, policyVersion, List.copyOf(entries), violations);
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
            throw ApiException.conflict("制品版本已撤回，禁止登记或修订许可证: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        repositoryDao.upsertLicense(artifact.id(), license, now);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new LicenseResponse(name, version, license, repositoryVersion, now);
    }

    private PolicyResponse doUpsertPolicy(String namespace, long expectedVersion,
                                          List<String> allowed, boolean rejectUnknown) {
        PolicyRow existing = repositoryDao.findPolicy(namespace);
        Instant now = Instant.now(clock);
        if (existing == null) {
            if (expectedVersion != 0) {
                throw ApiException.conflict("命名空间策略不存在，创建时 expectedVersion 须为 0: " + namespace);
            }
            repositoryDao.insertPolicy(namespace, rejectUnknown, now);
        } else {
            int affected = repositoryDao.updatePolicy(namespace, expectedVersion, rejectUnknown, now);
            if (affected == 0) {
                throw ApiException.conflict("策略版本不匹配：expected=" + expectedVersion
                        + ", actual=" + existing.version());
            }
        }
        repositoryDao.replaceAllowedLicenses(namespace, allowed);
        // 策略修改与制品写操作共享仓库版本轴，使并发锁定能按提交顺序裁决。
        repositoryDao.incrementRepositoryVersion();
        PolicyRow saved = repositoryDao.findPolicy(namespace);
        return new PolicyResponse(namespace, saved.version(), allowed, rejectUnknown, now);
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

        // 许可证与策略评估与依赖解析基于同一行锁事务内的一致性快照。
        PolicyRow policy = repositoryDao.findPolicy(rootName);
        Map<String, String> licenses = loadLicenses(snapshot, solution);
        List<LicenseViolation> violations = evaluateViolations(solution, licenses, policy);
        if (!violations.isEmpty()) {
            throw new LicenseViolationException(
                    "锁定违反命名空间 " + rootName + " 的许可证策略，未生成锁文件", violations);
        }

        Long policyVersion = policy == null ? null : policy.version();
        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                policyVersion, currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v, licenses.get(n)));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v, licenses.get(n))));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion,
                policyVersion, now, entries);
    }

    // ------------------------------------------------------------------
    // 许可证与策略评估
    // ------------------------------------------------------------------

    /**
     * 加载解析闭包中每个名称在锁定时点的许可证：artifactId -> license 批量查询，
     * 未登记的版本语义上为 UNKNOWN。
     */
    private Map<String, String> loadLicenses(RepositorySnapshot snapshot, Map<String, Integer> solution) {
        Map<String, Long> artifactIds = new HashMap<>();
        solution.forEach((name, version) -> {
            ArtifactVersion artifact = snapshot.artifacts().getOrDefault(name, List.of()).stream()
                    .filter(a -> a.version() == version)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "解析结果不在快照中: " + name + ":" + version));
            artifactIds.put(name, artifact.id());
        });
        Map<Long, String> byId = repositoryDao.findLicenses(artifactIds.values());
        Map<String, String> licenses = new HashMap<>();
        artifactIds.forEach((name, id) -> licenses.put(name, byId.getOrDefault(id, UNKNOWN_LICENSE)));
        return licenses;
    }

    /**
     * 按策略评估解析闭包：无策略时不设限制；违规列表按（名称, 版本）稳定升序。
     */
    private List<LicenseViolation> evaluateViolations(Map<String, Integer> solution,
                                                      Map<String, String> licenses,
                                                      PolicyRow policy) {
        if (policy == null) {
            return List.of();
        }
        Set<String> allowed = new TreeSet<>(repositoryDao.listAllowedLicenses(policy.namespace()));
        List<LicenseViolation> violations = new ArrayList<>();
        solution.forEach((name, version) -> {
            String license = licenses.get(name);
            if (UNKNOWN_LICENSE.equals(license)) {
                if (policy.rejectUnknown()) {
                    violations.add(new LicenseViolation(name, version, license,
                            REASON_UNKNOWN_REJECTED));
                }
            } else if (!allowed.contains(license)) {
                violations.add(new LicenseViolation(name, version, license, REASON_NOT_ALLOWED));
            }
        });
        violations.sort(Comparator.comparing(LicenseViolation::name)
                .thenComparingInt(LicenseViolation::version));
        return List.copyOf(violations);
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

    /** 规范化许可证标识：去空白、非空、长度上限。 */
    private String normalizeLicense(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("license 不能为空");
        }
        String license = raw.trim();
        if (license.length() > MAX_LICENSE_LENGTH) {
            throw ApiException.badRequest("license 长度不能超过 " + MAX_LICENSE_LENGTH);
        }
        return license;
    }

    /** 规范化策略允许集合：去空白、去重（重复报 400）、字典序升序。 */
    private List<String> canonicalLicenses(List<String> licenses) {
        if (licenses.size() > MAX_ALLOWED_LICENSES) {
            throw ApiException.badRequest("允许许可证数量不能超过 " + MAX_ALLOWED_LICENSES);
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String raw : licenses) {
            String license = normalizeLicense(raw);
            if (!sorted.add(license)) {
                throw ApiException.badRequest("允许许可证标识重复: " + license);
            }
        }
        return List.copyOf(sorted);
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
                .map(e -> new LockEntryResponse(e.name(), e.version(), e.license()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.policyVersion(), row.createdAt(), entryViews);
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
