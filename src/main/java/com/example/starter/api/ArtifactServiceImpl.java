package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.AttestationView;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.AttestationRow;
import com.example.starter.repo.RepositoryDao.ChecksumRow;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 *
 * <p>制品校验见证：版本须先登记内容校验摘要（{@code sha256:<64位十六进制>}），
 * 再由两名不同发布角色提交与登记摘要一致的见证；只有满足双人见证的版本
 * 才能进入新锁文件的依赖闭包。锁文件固化每个版本的摘要与见证版本号，
 * 后续见证撤销不改写旧锁，仅影响后续锁定。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    /** 进入锁定闭包所需的不同角色有效见证数量。 */
    static final int REQUIRED_ATTESTATIONS = 2;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_REGISTER_CHECKSUM = "REGISTER_CHECKSUM";
    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_REVOKE_ATTESTATION = "REVOKE_ATTESTATION";

    /** 摘要格式：sha256: 前缀加 64 位十六进制（比较前统一转小写）。 */
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

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
        requireName(name);
        String hash = sha256(OP_WITHDRAW + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version), ArtifactResponse.class);
    }

    @Override
    public ChecksumResponse registerChecksum(String requestId, String name, int version,
                                             RegisterChecksumRequest request) {
        requireRequestId(requestId);
        requireName(name);
        String checksum = normalizeChecksum(request == null ? null : request.checksum());
        // 指纹含操作类型、目标版本与规范化摘要；JSON 数组序列化避免未转义分隔符拼接。
        String hash = fingerprint(OP_REGISTER_CHECKSUM, name.trim(), String.valueOf(version), checksum);
        return executeIdempotent(requestId, OP_REGISTER_CHECKSUM, hash, 201,
                () -> doRegisterChecksum(name.trim(), version, checksum), ChecksumResponse.class);
    }

    @Override
    public AttestationResponse attest(String requestId, String name, int version,
                                      AttestationRequest request) {
        requireRequestId(requestId);
        requireName(name);
        if (request == null || request.role() == null || request.role().isBlank()) {
            throw ApiException.badRequest("role 不能为空");
        }
        String role = request.role().trim();
        String checksum = normalizeChecksum(request.checksum());
        // 指纹含操作者（角色）、操作类型与规范化摘要。
        String hash = fingerprint(OP_ATTEST, name.trim(), String.valueOf(version), role, checksum);
        return executeIdempotent(requestId, OP_ATTEST, hash, 201,
                () -> doAttest(name.trim(), version, role, checksum), AttestationResponse.class);
    }

    @Override
    public AttestationResponse revokeAttestation(String requestId, String name, int version, String role) {
        requireRequestId(requestId);
        requireName(name);
        if (role == null || role.isBlank()) {
            throw ApiException.badRequest("role 不能为空");
        }
        String hash = fingerprint(OP_REVOKE_ATTESTATION, name.trim(), String.valueOf(version), role.trim());
        return executeIdempotent(requestId, OP_REVOKE_ATTESTATION, hash, 200,
                () -> doRevokeAttestation(name.trim(), version, role.trim()), AttestationResponse.class);
    }

    @Override
    public AttestationStatusResponse getAttestationStatus(String name, int version) {
        requireName(name);
        String trimmed = name.trim();
        ArtifactVersion artifact = repositoryDao.loadArtifact(trimmed, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + trimmed + ":" + version);
        }
        ChecksumRow checksum = repositoryDao.loadChecksum(artifact.id());
        List<AttestationRow> rows = repositoryDao.listAttestations(artifact.id());
        int active = 0;
        List<AttestationView> views = new ArrayList<>();
        for (AttestationRow row : rows) {
            boolean valid = !row.revoked() && checksum != null
                    && row.checksum().equals(checksum.checksum());
            if (valid) {
                active++;
            }
            views.add(new AttestationView(row.role(), row.checksum(), row.revoked(),
                    row.createdAt(), row.revokedAt()));
        }
        return new AttestationStatusResponse(trimmed, version,
                checksum == null ? null : checksum.checksum(),
                repositoryDao.getAttestationVersion(artifact.id()),
                REQUIRED_ATTESTATIONS, active, Math.max(0, REQUIRED_ATTESTATIONS - active),
                List.copyOf(views));
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

    private ChecksumResponse doRegisterChecksum(String name, int version, String checksum) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，无法登记摘要: " + name + ":" + version);
        }
        ChecksumRow existing = repositoryDao.loadChecksum(artifact.id());
        if (existing != null) {
            if (existing.checksum().equals(checksum)) {
                // 同值重复登记：幂等无操作，返回当前状态，不改写历史。
                return new ChecksumResponse(name, version, checksum,
                        repositoryDao.getAttestationVersion(artifact.id()), existing.registeredAt());
            }
            throw ApiException.conflict("制品版本已登记不同摘要: " + name + ":" + version
                    + "，已登记=" + existing.checksum() + "，本次=" + checksum);
        }
        Instant now = Instant.now(clock);
        repositoryDao.insertChecksum(artifact.id(), checksum, currentRequestId.get(), now);
        return new ChecksumResponse(name, version, checksum,
                repositoryDao.getAttestationVersion(artifact.id()), now);
    }

    private AttestationResponse doAttest(String name, int version, String role, String checksum) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，无法见证: " + name + ":" + version);
        }
        ChecksumRow registered = repositoryDao.loadChecksum(artifact.id());
        if (registered == null) {
            throw ApiException.unprocessable("制品版本尚未登记摘要，无法见证: " + name + ":" + version);
        }
        if (!registered.checksum().equals(checksum)) {
            throw ApiException.unprocessable("见证摘要与已登记摘要不一致: " + name + ":" + version
                    + "，已登记=" + registered.checksum() + "，见证=" + checksum);
        }
        AttestationRow existing = repositoryDao.findAttestation(artifact.id(), role);
        if (existing != null && !existing.revoked()) {
            throw ApiException.conflict("同角色已存在有效见证: " + name + ":" + version
                    + " 角色=" + role);
        }
        Instant now = Instant.now(clock);
        Instant createdAt;
        if (existing != null) {
            // 同角色曾撤销：重新激活原记录，保留首次见证时间，不改写历史字段。
            repositoryDao.reactivateAttestation(existing.id(), checksum, currentRequestId.get());
            createdAt = existing.createdAt();
        } else {
            repositoryDao.insertAttestation(artifact.id(), role, checksum, currentRequestId.get(), now);
            createdAt = now;
        }
        int attestationVersion = repositoryDao.incrementAttestationVersion(artifact.id());
        return new AttestationResponse(name, version, role, checksum, false,
                attestationVersion, createdAt, null);
    }

    private AttestationResponse doRevokeAttestation(String name, int version, String role) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        AttestationRow existing = repositoryDao.findAttestation(artifact.id(), role);
        if (existing == null) {
            throw ApiException.notFound("见证不存在: " + name + ":" + version + " 角色=" + role);
        }
        if (existing.revoked()) {
            throw ApiException.conflict("见证已撤销: " + name + ":" + version + " 角色=" + role);
        }
        Instant now = Instant.now(clock);
        repositoryDao.markAttestationRevoked(existing.id(), now);
        int attestationVersion = repositoryDao.incrementAttestationVersion(artifact.id());
        return new AttestationResponse(name, version, role, existing.checksum(), true,
                attestationVersion, existing.createdAt(), now);
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

        // 逐一验证闭包内版本的摘要与双人见证状态；按名称升序收集，失败原因稳定。
        Map<String, ArtifactVersion> closure = resolveClosureVersions(snapshot, solution);
        Map<String, String> checksums = new LinkedHashMap<>();
        Map<String, Integer> attestationVersions = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, ArtifactVersion> entry : closure.entrySet()) {
            String label = entry.getKey() + ":" + entry.getValue().version();
            long artifactId = entry.getValue().id();
            ChecksumRow checksum = repositoryDao.loadChecksum(artifactId);
            if (checksum == null) {
                failures.add(label + " 未登记内容校验摘要（要求已登记 sha256 摘要）");
                continue;
            }
            long activeRoles = repositoryDao.listAttestations(artifactId).stream()
                    .filter(a -> !a.revoked() && a.checksum().equals(checksum.checksum()))
                    .map(AttestationRow::role)
                    .distinct()
                    .count();
            if (activeRoles < REQUIRED_ATTESTATIONS) {
                failures.add(label + " 有效见证不足（实际=" + activeRoles
                        + "，要求=" + REQUIRED_ATTESTATIONS
                        + "，差额=" + (REQUIRED_ATTESTATIONS - activeRoles) + "）");
                continue;
            }
            checksums.put(entry.getKey(), checksum.checksum());
            attestationVersions.put(entry.getKey(), repositoryDao.getAttestationVersion(artifactId));
        }
        if (!failures.isEmpty()) {
            throw ApiException.unprocessable(
                    "依赖闭包存在未通过校验见证的版本，锁定失败: " + String.join("；", failures));
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v,
                checksums.get(n), attestationVersions.get(n)));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v,
                checksums.get(n), attestationVersions.get(n))));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /** 按名称升序定位闭包内每个精确版本对应的快照对象。 */
    private static Map<String, ArtifactVersion> resolveClosureVersions(RepositorySnapshot snapshot,
                                                                       Map<String, Integer> solution) {
        Map<String, ArtifactVersion> closure = new TreeMap<>();
        solution.forEach((name, version) -> {
            for (ArtifactVersion candidate : snapshot.artifacts().getOrDefault(name, List.of())) {
                if (candidate.version() == version) {
                    closure.put(name, candidate);
                    return;
                }
            }
        });
        return closure;
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

    /**
     * 校验并规范化摘要：要求 {@code sha256:<64位十六进制>}，统一转小写。
     * 格式非法返回 422。
     */
    private static String normalizeChecksum(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.unprocessable("摘要格式非法：不能为空，要求 sha256:<64位十六进制>");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!CHECKSUM_PATTERN.matcher(value).matches()) {
            throw ApiException.unprocessable("摘要格式非法：要求 sha256:<64位十六进制>，实际=" + raw.trim());
        }
        return value;
    }

    /**
     * 幂等指纹：操作类型 + 规范化字段经 JSON 数组序列化后取 SHA-256，
     * 避免未转义分隔符拼接导致的字段边界歧义。
     */
    private String fingerprint(String operation, String... fields) {
        List<String> parts = new ArrayList<>();
        parts.add(operation);
        parts.addAll(List.of(fields));
        try {
            return sha256(objectMapper.writeValueAsString(parts));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

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
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        e.checksum(), e.attestationVersion()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
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
