package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationView;
import com.example.starter.api.dto.ChecksumDetailResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockDiagnosticsResponse;
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
import com.example.starter.support.RequestFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * <p>摘要登记、见证、撤销、撤回与锁定均推进仓库版本号，按提交顺序裁决；
 * 锁定时在持锁事务内逐一验证闭包版本的摘要与双人见证状态，并把当时证据固化进锁文件。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    /** 锁定要求的有效见证人数（两名不同发布角色）。 */
    static final int REQUIRED_ATTESTATIONS = 2;

    private static final String SUPPORTED_ALGORITHM = "SHA-256";
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_REGISTER_CHECKSUM = "REGISTER_CHECKSUM";
    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_REVOKE_ATTESTATION = "REVOKE_ATTESTATION";

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
        String op = normalizeOperatorOrDefault(operator);
        List<String> parts = new ArrayList<>(List.of(op, OP_REGISTER,
                request.name().trim(), String.valueOf(request.version())));
        parts.addAll(canonicalDependencyParts(request));
        String hash = RequestFingerprint.of(parts.toArray(new String[0]));
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String operator,
                                             String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String op = normalizeOperatorOrDefault(operator);
        String hash = RequestFingerprint.of(op, OP_WITHDRAW, name.trim(), String.valueOf(version));
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version), ArtifactResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String op = normalizeOperatorOrDefault(operator);
        String hash = RequestFingerprint.of(op, OP_LOCK, request.rootName().trim(),
                String.valueOf(request.rootVersion()),
                String.valueOf(request.expectedRepositoryVersion()));
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public ChecksumResponse registerChecksum(String requestId, String operator, String name, int version,
                                             RegisterChecksumRequest request) {
        requireRequestId(requestId);
        String op = requireOperator(operator);
        requireName(name);
        String algorithm = normalizeAlgorithm(request == null ? null : request.algorithm());
        String digest = normalizeDigest(request == null ? null : request.digest());
        String hash = RequestFingerprint.of(op, OP_REGISTER_CHECKSUM, name.trim(),
                String.valueOf(version), algorithm, digest);
        return executeIdempotent(requestId, OP_REGISTER_CHECKSUM, hash, 201,
                () -> doRegisterChecksum(op, name.trim(), version, algorithm, digest),
                ChecksumResponse.class);
    }

    @Override
    public AttestationResponse attest(String requestId, String operator, String name, int version,
                                      AttestRequest request) {
        requireRequestId(requestId);
        String op = requireOperator(operator);
        requireName(name);
        String digest = normalizeDigest(request == null ? null : request.digest());
        String hash = RequestFingerprint.of(op, OP_ATTEST, name.trim(),
                String.valueOf(version), digest);
        return executeIdempotent(requestId, OP_ATTEST, hash, 201,
                () -> doAttest(op, name.trim(), version, digest), AttestationResponse.class);
    }

    @Override
    public AttestationResponse revokeAttestation(String requestId, String operator, String name,
                                                 int version, String targetOperator) {
        requireRequestId(requestId);
        String op = requireOperator(operator);
        requireName(name);
        if (targetOperator == null || targetOperator.isBlank()) {
            throw ApiException.badRequest("targetOperator 不能为空");
        }
        String target = targetOperator.trim();
        String hash = RequestFingerprint.of(op, OP_REVOKE_ATTESTATION, name.trim(),
                String.valueOf(version), target);
        return executeIdempotent(requestId, OP_REVOKE_ATTESTATION, hash, 200,
                () -> doRevokeAttestation(name.trim(), version, target), AttestationResponse.class);
    }

    @Override
    public ChecksumDetailResponse getChecksumDetail(String name, int version) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        ChecksumRow checksum = repositoryDao.loadChecksum(artifact.id());
        List<AttestationRow> attestations = repositoryDao.listAttestations(artifact.id());
        List<AttestationView> views = new ArrayList<>();
        int active = 0;
        for (AttestationRow row : attestations) {
            if (!row.revoked()) {
                active++;
            }
            views.add(new AttestationView(row.operator(), row.digest(), row.revoked(),
                    row.createdAt(), row.revokedAt()));
        }
        return new ChecksumDetailResponse(artifact.name(), artifact.version(), artifact.withdrawn(),
                checksum == null ? null : checksum.algorithm(),
                checksum == null ? null : checksum.digest(),
                checksum == null ? null : checksum.registeredBy(),
                checksum == null ? null : checksum.createdAt(),
                List.copyOf(views), active, REQUIRED_ATTESTATIONS, missingAttestations(active));
    }

    @Override
    public LockDiagnosticsResponse diagnoseLock(String rootName, int rootVersion) {
        ArtifactVersion root = repositoryDao.loadArtifact(rootName, rootVersion);
        if (root == null) {
            throw ApiException.notFound("根制品版本不存在: " + rootName + ":" + rootVersion);
        }
        long repositoryVersion = repositoryDao.getRepositoryVersion();
        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, Integer> solution = root.withdrawn()
                ? null
                : LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            return new LockDiagnosticsResponse(rootName, rootVersion, repositoryVersion,
                    false, false, List.of());
        }
        List<LockDiagnosticsResponse.Entry> entries = new ArrayList<>();
        boolean ready = true;
        for (Map.Entry<String, Integer> selected : solution.entrySet()) {
            ArtifactVersion artifact = findVersion(snapshot, selected.getKey(), selected.getValue());
            ChecksumRow checksum = repositoryDao.loadChecksum(artifact.id());
            List<String> attesters = repositoryDao.listActiveAttesters(artifact.id());
            int missing = missingAttestations(attesters.size());
            if (checksum == null || missing > 0) {
                ready = false;
            }
            entries.add(new LockDiagnosticsResponse.Entry(selected.getKey(), selected.getValue(),
                    checksum != null, attesters.size(), REQUIRED_ATTESTATIONS, missing,
                    List.copyOf(attesters)));
        }
        return new LockDiagnosticsResponse(rootName, rootVersion, repositoryVersion,
                true, ready, List.copyOf(entries));
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

    private ChecksumResponse doRegisterChecksum(String operator, String name, int version,
                                                String algorithm, String digest) {
        ArtifactVersion artifact = requireArtifact(name, version);
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，无法登记摘要: " + name + ":" + version);
        }
        if (repositoryDao.loadChecksum(artifact.id()) != null) {
            throw ApiException.conflict("制品版本已登记摘要: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        repositoryDao.insertChecksum(artifact.id(), algorithm, digest, operator, now);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ChecksumResponse(name, version, algorithm, digest, operator, now,
                repositoryVersion);
    }

    private AttestationResponse doAttest(String operator, String name, int version, String digest) {
        ArtifactVersion artifact = requireArtifact(name, version);
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，无法见证: " + name + ":" + version);
        }
        ChecksumRow checksum = repositoryDao.loadChecksum(artifact.id());
        if (checksum == null) {
            throw ApiException.unprocessable(
                    "制品版本未登记内容校验摘要，无法见证: " + name + ":" + version);
        }
        if (!checksum.digest().equals(digest)) {
            throw ApiException.conflict("见证摘要与已登记摘要不一致: " + name + ":" + version);
        }
        AttestationRow existing = repositoryDao.loadAttestation(artifact.id(), operator);
        if (existing != null && !existing.revoked()) {
            throw ApiException.conflict(
                    "发布角色 " + operator + " 已见证该版本，同角色重复见证: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        if (existing != null) {
            // 撤销后再次见证：重新激活并刷新摘要与时间。
            repositoryDao.reactivateAttestation(artifact.id(), operator, digest, now);
        } else {
            repositoryDao.insertAttestation(artifact.id(), operator, digest, now);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        int active = repositoryDao.countActiveAttestations(artifact.id());
        return new AttestationResponse(name, version, operator, digest, false, now, null,
                active, REQUIRED_ATTESTATIONS, missingAttestations(active), repositoryVersion);
    }

    private AttestationResponse doRevokeAttestation(String name, int version, String targetOperator) {
        ArtifactVersion artifact = requireArtifact(name, version);
        AttestationRow existing = repositoryDao.loadAttestation(artifact.id(), targetOperator);
        if (existing == null) {
            throw ApiException.notFound(
                    "发布角色 " + targetOperator + " 对该版本的见证不存在: " + name + ":" + version);
        }
        if (existing.revoked()) {
            throw ApiException.conflict(
                    "发布角色 " + targetOperator + " 的见证已撤销: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        repositoryDao.revokeAttestation(artifact.id(), targetOperator, now);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        int active = repositoryDao.countActiveAttestations(artifact.id());
        return new AttestationResponse(name, version, targetOperator, existing.digest(), true,
                existing.createdAt(), now, active, REQUIRED_ATTESTATIONS,
                missingAttestations(active), repositoryVersion);
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

        // 先完整校验闭包内每个版本（含根与全部间接依赖）的摘要与双人见证状态，
        // 全部通过后才在同一事务写入锁文件；按名称升序验证保证失败原因稳定。
        record FrozenEntry(String name, int version, String algorithm, String digest,
                           List<String> attesters) {
        }
        List<FrozenEntry> frozen = new ArrayList<>();
        for (Map.Entry<String, Integer> selected : solution.entrySet()) {
            String name = selected.getKey();
            int version = selected.getValue();
            ArtifactVersion artifact = findVersion(snapshot, name, version);
            ChecksumRow checksum = repositoryDao.loadChecksum(artifact.id());
            if (checksum == null) {
                throw ApiException.unprocessable(
                        "制品版本 " + name + ":" + version + " 未登记内容校验摘要，无法锁定");
            }
            List<String> attesters = repositoryDao.listActiveAttesters(artifact.id());
            if (attesters.size() < REQUIRED_ATTESTATIONS) {
                throw ApiException.unprocessable(
                        "制品版本 " + name + ":" + version + " 见证不足：实际 " + attesters.size()
                                + " 人，要求 " + REQUIRED_ATTESTATIONS + " 人，差额 "
                                + (REQUIRED_ATTESTATIONS - attesters.size()) + " 人，无法锁定");
            }
            frozen.add(new FrozenEntry(name, version, checksum.algorithm(), checksum.digest(),
                    List.copyOf(attesters)));
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        for (FrozenEntry entry : frozen) {
            repositoryDao.insertLockEntry(lockFileId, entry.name(), entry.version(),
                    entry.algorithm(), entry.digest(), String.join(",", entry.attesters()),
                    entry.attesters().size());
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        for (FrozenEntry entry : frozen) {
            entries.add(new LockEntryResponse(entry.name(), entry.version(), entry.algorithm(),
                    entry.digest(), entry.attesters(), entry.attesters().size()));
        }
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

    private ArtifactVersion requireArtifact(String name, int version) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        return artifact;
    }

    private static ArtifactVersion findVersion(RepositorySnapshot snapshot, String name, int version) {
        for (ArtifactVersion candidate : snapshot.artifacts().getOrDefault(name, List.of())) {
            if (candidate.version() == version) {
                return candidate;
            }
        }
        throw new IllegalStateException("闭包版本不在快照中: " + name + ":" + version);
    }

    private static int missingAttestations(int activeAttestations) {
        return Math.max(0, REQUIRED_ATTESTATIONS - activeAttestations);
    }

    /** 规范化摘要算法：去空白并转大写，仅支持 SHA-256，否则 422。 */
    private static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            throw ApiException.unprocessable("摘要算法不能为空");
        }
        String normalized = algorithm.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED_ALGORITHM.equals(normalized)) {
            throw ApiException.unprocessable(
                    "不支持的摘要算法: " + algorithm.trim() + "，要求 " + SUPPORTED_ALGORITHM);
        }
        return normalized;
    }

    /** 规范化摘要串：去空白并转小写，必须为 64 位十六进制，否则 422。 */
    private static String normalizeDigest(String digest) {
        if (digest == null || digest.isBlank()) {
            throw ApiException.unprocessable("摘要不能为空");
        }
        String normalized = digest.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(normalized).matches()) {
            throw ApiException.unprocessable(
                    "摘要格式非法：要求 64 位十六进制字符（SHA-256），实际长度 "
                            + normalized.length());
        }
        return normalized;
    }

    private static String normalizeOperatorOrDefault(String operator) {
        return (operator == null || operator.isBlank()) ? "anonymous" : operator.trim();
    }

    /** 摘要/见证类写操作必须显式携带操作者；逗号会破坏锁文件见证人列表编码，一并拒绝。 */
    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Operator");
        }
        String trimmed = operator.trim();
        if (trimmed.indexOf(',') >= 0) {
            throw ApiException.badRequest("操作者不能包含逗号");
        }
        if (trimmed.length() > 64) {
            throw ApiException.badRequest("操作者长度不能超过 64 个字符");
        }
        return trimmed;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
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

    /** 依赖集合规范化：每条依赖独立编码为一个字段，排序后逐一进入指纹。 */
    private List<String> canonicalDependencyParts(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> d.name().trim() + "/" + d.minimumVersion() + "/" + d.maximumVersion())
                .sorted()
                .toList();
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
                .map(e -> new LockEntryResponse(e.name(), e.version(), e.checksumAlgorithm(),
                        e.checksumDigest(), List.of(e.attesters().split(",")),
                        e.attestationCount()))
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
}
