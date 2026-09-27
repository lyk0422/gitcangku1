package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.AttestationView;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.FrozenAttestationView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.Attestation;
import com.example.starter.domain.AttestationGate;
import com.example.starter.domain.ChecksumPolicy;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.AttestationRow;
import com.example.starter.repo.RepositoryDao.FrozenAttestationRow;
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
 * <p>幂等指纹为规范化 JSON（键排序、集合排序，JSON 自身负责转义，
 * 不做裸分隔符拼接）的 SHA-256，含操作者、操作类型、规范化集合与全部请求参数。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_REGISTER_CHECKSUM = "REGISTER_CHECKSUM";
    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_REVOKE = "REVOKE_ATTESTATION";
    private static final String OP_LOCK = "CREATE_LOCK";

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
        String fingerprint = fingerprint(params("operation", OP_REGISTER,
                "name", request.name().trim(),
                "version", request.version(),
                "dependencies", canonicalDependencies(request)));
        return executeIdempotent(requestId, OP_REGISTER, fingerprint, 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String normalizedName = name.trim();
        String fingerprint = fingerprint(params("operation", OP_WITHDRAW,
                "name", normalizedName, "version", version));
        return executeIdempotent(requestId, OP_WITHDRAW, fingerprint, 200,
                () -> doWithdraw(normalizedName, version), ArtifactResponse.class);
    }

    @Override
    public ChecksumResponse registerChecksum(String requestId, String name, int version,
                                             RegisterChecksumRequest request, String operator) {
        requireRequestId(requestId);
        String normalizedName = requireName(name);
        String normalizedOperator = requireOperator(operator);
        String checksum = requireChecksumFormat(request == null ? null : request.checksum());
        String fingerprint = fingerprint(params("operation", OP_REGISTER_CHECKSUM,
                "name", normalizedName, "version", version,
                "checksum", checksum, "operator", normalizedOperator));
        return executeIdempotent(requestId, OP_REGISTER_CHECKSUM, fingerprint, 201,
                () -> doRegisterChecksum(normalizedName, version, checksum, normalizedOperator),
                ChecksumResponse.class);
    }

    @Override
    public AttestationResponse attest(String requestId, String name, int version,
                                      AttestRequest request, String operator) {
        requireRequestId(requestId);
        String normalizedName = requireName(name);
        String normalizedOperator = requireOperator(operator);
        String checksum = requireChecksumFormat(request == null ? null : request.checksum());
        String fingerprint = fingerprint(params("operation", OP_ATTEST,
                "name", normalizedName, "version", version,
                "checksum", checksum, "operator", normalizedOperator, "role", normalizedOperator));
        return executeIdempotent(requestId, OP_ATTEST, fingerprint, 201,
                () -> doAttest(normalizedName, version, checksum, normalizedOperator),
                AttestationResponse.class);
    }

    @Override
    public AttestationResponse revokeAttestation(String requestId, String name, int version,
                                                 String role, String operator) {
        requireRequestId(requestId);
        String normalizedName = requireName(name);
        String normalizedOperator = requireOperator(operator);
        String normalizedRole = requireName(role, "role 不能为空");
        String fingerprint = fingerprint(params("operation", OP_REVOKE,
                "name", normalizedName, "version", version,
                "role", normalizedRole, "operator", normalizedOperator));
        return executeIdempotent(requestId, OP_REVOKE, fingerprint, 200,
                () -> doRevoke(normalizedName, version, normalizedRole, normalizedOperator),
                AttestationResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String rootName = request.rootName().trim();
        String fingerprint = fingerprint(params("operation", OP_LOCK,
                "rootName", rootName,
                "rootVersion", request.rootVersion(),
                "expectedRepositoryVersion", request.expectedRepositoryVersion()));
        return executeIdempotent(requestId, OP_LOCK, fingerprint, 201,
                () -> doLock(rootName, request.rootVersion(), request.expectedRepositoryVersion()),
                LockFileResponse.class);
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
    public AttestationStatusResponse getAttestationStatus(String name, int version) {
        String normalizedName = requireName(name);
        ArtifactVersion artifact = repositoryDao.loadArtifact(normalizedName, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + normalizedName + ":" + version);
        }
        List<String> reasons = AttestationGate.violationReasons(artifact);
        long activeRoles = artifact.attestations().stream()
                .filter(Attestation::active)
                .map(Attestation::role)
                .distinct()
                .count();
        return new AttestationStatusResponse(normalizedName, version,
                artifact.checksum() != null, artifact.checksum(), artifact.withdrawn(),
                (int) activeRoles, ChecksumPolicy.REQUIRED_WITNESSES,
                reasons.isEmpty(), List.copyOf(reasons), toDomainAttestationViews(artifact.attestations()));
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
                    "仓库名称数量已达上限：实际 " + MAX_NAMES + " 个，要求不超过 " + MAX_NAMES + " 个");
        }
        if (existingVersions >= MAX_VERSIONS_PER_NAME) {
            throw ApiException.unprocessable(
                    "制品 " + name + " 的版本数量已达上限：实际 " + existingVersions + " 个，要求不超过 "
                            + MAX_VERSIONS_PER_NAME + " 个");
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

    private ChecksumResponse doRegisterChecksum(String name, int version,
                                                String checksum, String operator) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，不能登记摘要: " + name + ":" + version);
        }
        if (artifact.checksum() != null) {
            if (artifact.checksum().equals(checksum)) {
                throw ApiException.conflict("内容校验摘要已登记: " + name + ":" + version);
            }
            throw ApiException.conflict("内容校验摘要已登记且不可改写：已登记 " + artifact.checksum()
                    + "，提交 " + checksum);
        }
        Instant now = Instant.now(clock);
        try {
            repositoryDao.insertChecksum(artifact.id(), checksum, operator, now);
        } catch (DuplicateKeyException e) {
            // 并发登记抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("内容校验摘要已登记: " + name + ":" + version);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ChecksumResponse(checksum, operator, now, repositoryVersion);
    }

    private AttestationResponse doAttest(String name, int version, String checksum, String role) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        if (artifact.withdrawn()) {
            throw ApiException.conflict("制品版本已撤回，不能提交见证: " + name + ":" + version);
        }
        if (artifact.checksum() == null) {
            throw ApiException.unprocessable("版本 " + name + ":" + version
                    + " 尚未登记内容校验摘要：实际 0 条，要求 1 条，差额 1 条");
        }
        if (!artifact.checksum().equals(checksum)) {
            throw ApiException.unprocessable("见证摘要与登记摘要不一致：提交 " + checksum
                    + "，要求 " + artifact.checksum());
        }
        AttestationRow existing = repositoryDao.findAttestation(artifact.id(), role);
        if (existing != null) {
            if (existing.active()) {
                throw ApiException.conflict("角色 " + role + " 已对版本 " + name + ":" + version
                        + " 提交过见证，同一角色不能重复见证");
            }
            throw ApiException.conflict("角色 " + role + " 对版本 " + name + ":" + version
                    + " 的见证已撤销，撤销为终态，不能再次见证");
        }
        Instant now = Instant.now(clock);
        try {
            repositoryDao.insertAttestation(artifact.id(), role, checksum, now,
                    currentRequestId.get());
        } catch (DuplicateKeyException e) {
            // 并发同角色见证抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("角色 " + role + " 已对版本 " + name + ":" + version
                    + " 提交过见证，同一角色不能重复见证");
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new AttestationResponse(name, version, artifact.checksum(),
                toAttestationViews(repositoryDao.listAttestations(artifact.id())),
                repositoryVersion, now);
    }

    private AttestationResponse doRevoke(String name, int version, String role, String operator) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        AttestationRow existing = repositoryDao.findAttestation(artifact.id(), role);
        if (existing == null) {
            throw ApiException.notFound("角色 " + role + " 对版本 " + name + ":" + version
                    + " 的见证不存在");
        }
        if (!existing.active()) {
            throw ApiException.conflict("角色 " + role + " 对版本 " + name + ":" + version
                    + " 的见证已撤销，不能重复撤销");
        }
        Instant now = Instant.now(clock);
        int affected = repositoryDao.revokeAttestation(existing.id(), operator, now);
        if (affected == 0) {
            // 并发撤销抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("角色 " + role + " 对版本 " + name + ":" + version
                    + " 的见证已撤销，不能重复撤销");
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new AttestationResponse(name, version, artifact.checksum(),
                toAttestationViews(repositoryDao.listAttestations(artifact.id())),
                repositoryVersion, now);
    }

    private LockFileResponse doLock(String rootName, int rootVersion, long expectedVersion) {
        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedVersion + ", actual=" + currentVersion
                    + ", 差额=" + (currentVersion - expectedVersion));
        }

        ArtifactVersion root = repositoryDao.loadArtifact(rootName, rootVersion);
        if (root == null) {
            throw ApiException.notFound("根制品版本不存在: " + rootName + ":" + rootVersion);
        }
        if (root.withdrawn()) {
            throw ApiException.conflict("根制品版本已撤回: " + rootName + ":" + rootVersion);
        }

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, ArtifactVersion> chosen = resolveClosure(snapshot, rootName, rootVersion);

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        // 闭包内每个版本的摘要与见证状态已在 resolveClosure 逐一校验通过；
        // 此处按名称升序固化：先校验完整最终状态，再逐对象写入，任一失败整体回滚，不留半成品。
        for (ArtifactVersion artifact : chosen.values()) {
            long entryId = repositoryDao.insertLockEntry(lockFileId,
                    artifact.name(), artifact.version(), artifact.checksum());
            // 固化锁定时刻合格的全部见证（有效且摘要与登记摘要一致），按角色升序。
            List<Attestation> witnesses = qualifyingWitnesses(artifact);
            for (Attestation witness : witnesses) {
                repositoryDao.insertLockEntryAttestation(entryId, witness.role(),
                        witness.checksumValue(), witness.attestedAt());
            }
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        for (ArtifactVersion artifact : chosen.values()) {
            entries.add(new LockEntryResponse(artifact.name(), artifact.version(),
                    artifact.checksum(), toFrozenViews(qualifyingWitnesses(artifact))));
        }
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /**
     * 在一致快照上解析依赖闭包并逐一验证摘要与见证状态。
     *
     * <p>先以见证闸门为候选资格求解：未撤回、区间匹配且“摘要一致+双人见证”的版本
     * 才允许进入闭包，求解带回溯，高版本未见证时自动回退到合格的低版本。
     * 无合格闭包时，再以“仅排除撤回”的口径求区间闭包做诊断：区间本身不可行报区间原因，
     * 否则按名称升序稳定列出闭包成员的见证差距（含实际值/要求值/差额），整次锁定失败。
     */
    private Map<String, ArtifactVersion> resolveClosure(RepositorySnapshot snapshot,
                                                        String rootName, int rootVersion) {
        Map<String, Integer> attestedSolution;
        try {
            attestedSolution = LockResolver.resolve(
                    snapshot, rootName, rootVersion, AttestationGate::isLockEligible);
        } catch (IllegalArgumentException e) {
            // 根版本不存在、已撤回或未满足见证要求：转入下方区间闭包诊断。
            attestedSolution = null;
        }
        if (attestedSolution != null) {
            return materialize(snapshot, attestedSolution);
        }

        Map<String, Integer> rangeOnly;
        try {
            rangeOnly = LockResolver.resolve(snapshot, rootName, rootVersion);
        } catch (IllegalArgumentException e) {
            // 区间口径下根版本同样不成立：不存在或已撤回（撤回已在 doLock 前置判过）。
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定: " + e.getMessage());
        }
        if (rangeOnly == null) {
            throw ApiException.unprocessable("不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        Map<String, ArtifactVersion> closure = materialize(snapshot, rangeOnly);
        List<String> reasons = new ArrayList<>();
        for (ArtifactVersion artifact : closure.values()) {
            // 逐一验证闭包内版本（含根与全部间接依赖）的摘要登记、双人见证与摘要一致性。
            reasons.addAll(AttestationGate.violationReasons(artifact));
        }
        if (reasons.isEmpty()) {
            // 理论上不可达：闸门口径失败必然存在闸门差距。
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间且见证完整的版本组合，无法锁定");
        }
        throw ApiException.unprocessable("依赖闭包存在未满足见证要求的版本，整次锁定失败: "
                + String.join("; ", reasons));
    }

    private Map<String, ArtifactVersion> materialize(RepositorySnapshot snapshot,
                                                     Map<String, Integer> solution) {
        Map<String, ArtifactVersion> result = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            List<ArtifactVersion> versions = snapshot.artifacts().get(entry.getKey());
            ArtifactVersion matched = versions.stream()
                    .filter(a -> a.version() == entry.getValue())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "解析结果缺少版本数据: " + entry.getKey() + ":" + entry.getValue()));
            result.put(entry.getKey(), matched);
        }
        return result;
    }

    private List<Attestation> qualifyingWitnesses(ArtifactVersion artifact) {
        return artifact.attestations().stream()
                .filter(Attestation::active)
                .filter(a -> a.checksumValue().equals(artifact.checksum()))
                .toList();
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
        // 快速路径：已提交的成功记录直接重放首次完整响应快照。
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
     * 存在成功记录时：同操作同参返回原结果快照；异参（含异操作）返回 409。不存在返回 null。
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
            throw ApiException.badRequest("每个制品版本最多声明 10 条依赖：实际 "
                    + deps.size() + " 条，要求不超过 10 条");
        }
        Set<String> names = new HashSet<>();
        for (DependencySpec dep : deps) {
            if (dep.name() == null || dep.name().isBlank()) {
                throw ApiException.badRequest("依赖名称不能为空");
            }
            String depName = dep.name().trim();
            if (dep.minimumVersion() > dep.maximumVersion()) {
                throw ApiException.badRequest(
                        "依赖 " + depName + " 的最低版本不能高于最高版本：最低 "
                                + dep.minimumVersion() + "，最高 " + dep.maximumVersion());
            }
            if (!names.add(depName)) {
                throw ApiException.badRequest("依赖名称重复: " + depName);
            }
        }
    }

    private List<Map<String, Object>> canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> {
                    // 键排序由 TreeMap 保证，规范化集合按名称升序。
                    Map<String, Object> item = new TreeMap<>();
                    item.put("name", d.name().trim());
                    item.put("minimumVersion", d.minimumVersion());
                    item.put("maximumVersion", d.maximumVersion());
                    return item;
                })
                .sorted((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()))
                .toList();
    }

    private List<DependencyView> toDependencyViews(List<?> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (Object o : raw) {
            if (o instanceof DependencySpec spec) {
                sorted.put(spec.name().trim(), new DependencyView(
                        spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
            } else if (o instanceof DependencyRange range) {
                sorted.put(range.name(), new DependencyView(
                        range.name(), range.minimumVersion(), range.maximumVersion()));
            }
        }
        return List.copyOf(sorted.values());
    }

    private List<AttestationView> toAttestationViews(List<AttestationRow> rows) {
        return rows.stream()
                .map(r -> new AttestationView(r.role(), r.checksumValue(), r.active(),
                        r.attestedAt(), r.revokedAt(), r.revokedBy()))
                .toList();
    }

    private List<AttestationView> toDomainAttestationViews(List<Attestation> rows) {
        return rows.stream()
                .map(r -> new AttestationView(r.role(), r.checksumValue(), r.active(),
                        r.attestedAt(), r.revokedAt(), r.revokedBy()))
                .toList();
    }

    private List<FrozenAttestationView> toFrozenViews(List<Attestation> witnesses) {
        return witnesses.stream()
                .map(w -> new FrozenAttestationView(w.role(), w.checksumValue(), w.attestedAt()))
                .toList();
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = new ArrayList<>();
        for (LockEntryRow entry : entries) {
            List<FrozenAttestationRow> frozen =
                    repositoryDao.listLockEntryAttestations(entry.id());
            List<FrozenAttestationView> witnessViews = frozen.stream()
                    .map(f -> new FrozenAttestationView(f.role(), f.checksum(), f.attestedAt()))
                    .toList();
            entryViews.add(new LockEntryResponse(entry.name(), entry.version(),
                    entry.checksum(), witnessViews));
        }
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private String requireName(String name) {
        return requireName(name, "name 不能为空");
    }

    private String requireName(String name, String message) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return name.trim();
    }

    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Operator");
        }
        return operator.trim();
    }

    private String requireChecksumFormat(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            throw ApiException.badRequest("checksum 不能为空");
        }
        String normalized = checksum.trim();
        if (!ChecksumPolicy.isValid(normalized)) {
            throw ApiException.unprocessable(
                    "摘要格式非法：要求 sha256: 加 64 位小写十六进制字符，实际 " + normalized);
        }
        return normalized;
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    /** 交替键值构造键有序的参数 Map。 */
    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 规范化 JSON（键升序）的 SHA-256；JSON 序列化负责全部转义，不做裸分隔符拼接。 */
    private String fingerprint(Map<String, Object> params) {
        String canonical = writeJson(params);
        return sha256(canonical);
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
