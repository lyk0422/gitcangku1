package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.CreatePolicyRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockMigrationResult;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MigrationCheckRequest;
import com.example.starter.api.dto.MigrationCheckResponse;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceEntryView;
import com.example.starter.api.dto.ProvenanceResponse;
import com.example.starter.api.dto.PublishDiagnosticResponse;
import com.example.starter.api.dto.PublishEntryView;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.Attestation;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.PolicyViolation;
import com.example.starter.domain.ProvenanceChecker;
import com.example.starter.domain.ProvenancePolicy;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.PolicyRow;
import com.example.starter.repo.RepositoryDao.PublishEntryRow;
import com.example.starter.repo.RepositoryDao.PublishRow;
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
 * <p>来源证明：策略版本只增不改写；解析与发布在持有同一行锁的事务内
 * 按当前策略版本校验全部命中坐标，任一违规整体回滚，不留半成品；
 * 发布快照固化策略版本与证明版本，撤销证明不回改已发布结果。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_CREATE_POLICY = "CREATE_POLICY";
    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_REVOKE_ATTEST = "REVOKE_ATTEST";

    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

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
                + canonicalDependencies(request) + "|" + (request.digest() == null ? "" : request.digest()));
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

    @Override
    public PolicyResponse createPolicyVersion(String requestId, CreatePolicyRequest request) {
        requireRequestId(requestId);
        List<String> repos = normalizeRepos(request.allowedRepos());
        String hash = sha256(OP_CREATE_POLICY + "|" + request.minLevel() + "|" + String.join(",", repos));
        return executeIdempotent(requestId, OP_CREATE_POLICY, hash, 201,
                () -> doCreatePolicy(request.minLevel(), repos), PolicyResponse.class);
    }

    @Override
    public List<PolicyResponse> listPolicies() {
        List<PolicyResponse> result = new ArrayList<>();
        for (PolicyRow row : repositoryDao.listPolicies()) {
            result.add(new PolicyResponse(row.version(), row.minLevel(),
                    repositoryDao.listPolicyRepos(row.id()), row.createdAt()));
        }
        return result;
    }

    @Override
    public AttestationResponse attest(String requestId, AttestationRequest request) {
        requireRequestId(requestId);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (request.repoId() == null || request.repoId().isBlank()) {
            throw ApiException.badRequest("repoId 不能为空");
        }
        String digest = normalizeDigest(request.digest());
        String name = request.name().trim();
        String repoId = request.repoId().trim();
        String hash = sha256(OP_ATTEST + "|" + name + "|" + request.version() + "|"
                + repoId + "|" + digest + "|" + request.level());
        return executeIdempotent(requestId, OP_ATTEST, hash, 201,
                () -> doAttest(name, request.version(), repoId, digest, request.level()),
                AttestationResponse.class);
    }

    @Override
    public AttestationResponse revokeAttestation(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_REVOKE_ATTEST + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_REVOKE_ATTEST, hash, 200,
                () -> doRevokeAttestation(name.trim(), version), AttestationResponse.class);
    }

    @Override
    public PublishResponse publishLock(long lockFileId, PublishRequest request) {
        if (request.operator() == null || request.operator().isBlank()) {
            throw ApiException.badRequest("operator 不能为空");
        }
        String operator = request.operator().trim();
        return transactionTemplate.execute(status -> {
            // 与其他写操作共用同一行锁，按提交顺序裁决。
            repositoryDao.lockRepositoryState();
            LockFileRow lock = repositoryDao.getLockFile(lockFileId);
            if (lock == null) {
                throw ApiException.notFound("锁文件不存在: " + lockFileId);
            }
            ProvenancePolicy policy = currentPolicy();
            if (policy == null) {
                throw ApiException.unprocessable("尚未定义来源策略，无法发布锁定图");
            }
            Map<String, Integer> solution = lockSolution(lock.id());
            Map<String, Attestation> attestations = repositoryDao.loadCurrentAttestations();
            List<PolicyViolation> violations = evaluateSolution(
                    policy, solution, attestations, lock.rootName(), lock.rootVersion());
            if (!violations.isEmpty()) {
                throw ApiException.policyViolation(formatViolations(violations));
            }

            String normalized = ProvenanceChecker.normalizedAttestationDigest(solution, attestations);
            String provenanceKey = sha256("PUBLISH|" + lock.id() + "|" + lock.repositoryVersion()
                    + "|" + policy.version() + "|" + normalized + "|" + operator);
            PublishRow existing = repositoryDao.findPublishByKey(provenanceKey);
            if (existing != null) {
                // 同键重放：返回首次发布结果。
                return toPublishResponse(existing);
            }

            Instant now = Instant.now(clock);
            long publishId = repositoryDao.insertPublishRecord(
                    lock.id(), policy.version(), provenanceKey, operator, now);
            List<PublishEntryView> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : new TreeMap<>(solution).entrySet()) {
                Attestation attestation = attestations.get(entry.getKey() + ":" + entry.getValue());
                repositoryDao.insertPublishEntry(publishId, entry.getKey(), entry.getValue(),
                        attestation.id(), attestation.attestationVersion(),
                        attestation.repoId(), attestation.digest(), attestation.level());
                entries.add(new PublishEntryView(entry.getKey(), entry.getValue(),
                        attestation.attestationVersion(), attestation.repoId(),
                        attestation.digest(), attestation.level()));
            }
            return new PublishResponse(publishId, lock.id(), policy.version(), provenanceKey,
                    operator, now, List.copyOf(entries));
        });
    }

    @Override
    public ProvenanceResponse getProvenance(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        Map<String, Integer> solution = lockSolution(lock.id());
        Map<String, String> paths = buildPaths(solution, lock.rootName(), lock.rootVersion());

        PublishRow publish = repositoryDao.findLatestPublishByLock(lockFileId);
        if (publish != null) {
            // 已发布：来源数据来自冻结快照，撤销与策略收紧不倒改。
            List<ProvenanceEntryView> entries = new ArrayList<>();
            for (PublishEntryRow row : repositoryDao.listPublishEntries(publish.id())) {
                entries.add(new ProvenanceEntryView(row.name(), row.version(),
                        paths.getOrDefault(row.name(), row.name() + ":" + row.version()),
                        row.attestationVersion(), row.repoId(), row.digest(), row.level(),
                        null, "OK"));
            }
            return new ProvenanceResponse(lockFileId, true, publish.policyVersion(),
                    List.copyOf(entries));
        }

        ProvenancePolicy policy = currentPolicy();
        Map<String, Attestation> attestations = repositoryDao.loadCurrentAttestations();
        Map<String, String> statusByCoordinate = new TreeMap<>();
        if (policy != null) {
            for (PolicyViolation violation : evaluateSolution(
                    policy, solution, attestations, lock.rootName(), lock.rootVersion())) {
                statusByCoordinate.putIfAbsent(violation.coordinate(), violation.code());
            }
        }
        List<ProvenanceEntryView> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(solution).entrySet()) {
            String coordinate = entry.getKey() + ":" + entry.getValue();
            Attestation attestation = attestations.get(coordinate);
            entries.add(new ProvenanceEntryView(entry.getKey(), entry.getValue(),
                    paths.getOrDefault(entry.getKey(), coordinate),
                    attestation == null ? null : attestation.attestationVersion(),
                    attestation == null ? null : attestation.repoId(),
                    attestation == null ? null : attestation.digest(),
                    attestation == null ? null : attestation.level(),
                    attestation == null ? null : attestation.revoked(),
                    statusByCoordinate.getOrDefault(coordinate, "OK")));
        }
        return new ProvenanceResponse(lockFileId, false,
                policy == null ? null : policy.version(), List.copyOf(entries));
    }

    @Override
    public PublishDiagnosticResponse getPublishDiagnostic(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        boolean published = repositoryDao.findLatestPublishByLock(lockFileId) != null;
        ProvenancePolicy policy = currentPolicy();
        List<PolicyViolation> violations = policy == null
                ? List.of()
                : evaluateSolution(policy, lockSolution(lock.id()),
                        repositoryDao.loadCurrentAttestations(), lock.rootName(), lock.rootVersion());
        return new PublishDiagnosticResponse(lockFileId, published,
                policy == null ? null : policy.version(), violations);
    }

    @Override
    public MigrationCheckResponse checkMigration(MigrationCheckRequest request) {
        List<String> repos = normalizeRepos(request.allowedRepos());
        // 候选策略版本号取“下一版本”，仅用于诊断信息展示，不写入。
        ProvenancePolicy candidate = new ProvenancePolicy(
                repositoryDao.maxPolicyVersion() + 1, request.minLevel(), repos);
        Map<String, Attestation> attestations = repositoryDao.loadCurrentAttestations();
        List<LockMigrationResult> results = new ArrayList<>();
        for (LockFileRow lock : repositoryDao.listLockFiles()) {
            List<PolicyViolation> violations = evaluateSolution(candidate, lockSolution(lock.id()),
                    attestations, lock.rootName(), lock.rootVersion());
            results.add(new LockMigrationResult(lock.id(), lock.rootName(), lock.rootVersion(),
                    violations));
        }
        return new MigrationCheckResponse(request.minLevel(), repos, List.copyOf(results));
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
        long artifactId = repositoryDao.insertArtifact(name, version, request.digest(), now);
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

        // 来源策略门禁：存在策略时，全部直接及传递命中坐标必须满足当前策略版本，
        // 任一违规则整个解析快照不写入（事务回滚）。
        ProvenancePolicy policy = currentPolicy();
        if (policy != null) {
            List<PolicyViolation> violations = evaluateSolution(policy, solution,
                    repositoryDao.loadCurrentAttestations(), rootName, rootVersion);
            if (!violations.isEmpty()) {
                throw ApiException.policyViolation(formatViolations(violations));
            }
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    private PolicyResponse doCreatePolicy(int minLevel, List<String> repos) {
        int version = repositoryDao.maxPolicyVersion() + 1;
        Instant now = Instant.now(clock);
        long policyId = repositoryDao.insertPolicy(version, minLevel, now);
        for (String repo : repos) {
            repositoryDao.insertPolicyRepo(policyId, repo);
        }
        return new PolicyResponse(version, minLevel, repos, now);
    }

    private AttestationResponse doAttest(String name, int version, String repoId,
                                         String digest, int level) {
        if (repositoryDao.loadArtifact(name, version) == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        int attestationVersion = repositoryDao.maxAttestationVersion(name, version) + 1;
        Instant now = Instant.now(clock);
        repositoryDao.insertAttestation(name, version, attestationVersion, repoId, digest, level, now);
        return new AttestationResponse(name, version, attestationVersion, repoId, digest, level,
                false, now);
    }

    private AttestationResponse doRevokeAttestation(String name, int version) {
        Attestation current = repositoryDao.loadCurrentAttestation(name, version);
        if (current == null) {
            throw ApiException.notFound("坐标证明不存在: " + name + ":" + version);
        }
        if (current.revoked()) {
            throw ApiException.conflict("坐标证明已撤销: " + name + ":" + version);
        }
        int affected = repositoryDao.markAttestationRevoked(current.id());
        if (affected == 0) {
            // 并发撤销抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("坐标证明已撤销: " + name + ":" + version);
        }
        return new AttestationResponse(name, version, current.attestationVersion(),
                current.repoId(), current.digest(), current.level(), true, Instant.now(clock));
    }

    // ------------------------------------------------------------------
    // 来源策略辅助
    // ------------------------------------------------------------------

    /** 当前策略版本（最高版本号），无策略返回 null。 */
    private ProvenancePolicy currentPolicy() {
        int maxVersion = repositoryDao.maxPolicyVersion();
        if (maxVersion == 0) {
            return null;
        }
        for (PolicyRow row : repositoryDao.listPolicies()) {
            if (row.version() == maxVersion) {
                return new ProvenancePolicy(row.version(), row.minLevel(),
                        repositoryDao.listPolicyRepos(row.id()));
            }
        }
        return null;
    }

    /** 锁文件的精确版本集合：名称 -> 版本。 */
    private Map<String, Integer> lockSolution(long lockFileId) {
        Map<String, Integer> solution = new TreeMap<>();
        for (LockEntryRow entry : repositoryDao.listLockEntries(lockFileId)) {
            solution.put(entry.name(), entry.version());
        }
        return solution;
    }

    /** 在精确版本集合上按策略校验，返回全部违规（含完整依赖路径）。 */
    private List<PolicyViolation> evaluateSolution(ProvenancePolicy policy,
                                                   Map<String, Integer> solution,
                                                   Map<String, Attestation> attestations,
                                                   String rootName,
                                                   int rootVersion) {
        Map<String, List<DependencyRange>> declared = repositoryDao.loadAllDependencies();
        Map<String, List<String>> dependencyIndex =
                ProvenanceChecker.dependencyIndex(solution, declared);
        return ProvenanceChecker.check(policy, solution, dependencyIndex, attestations,
                repositoryDao.loadArtifactDigests(), rootName, rootVersion);
    }

    /** 从根出发的完整依赖路径：名称 -> 路径串。 */
    private Map<String, String> buildPaths(Map<String, Integer> solution,
                                           String rootName, int rootVersion) {
        Map<String, List<DependencyRange>> declared = repositoryDao.loadAllDependencies();
        Map<String, List<String>> dependencyIndex =
                ProvenanceChecker.dependencyIndex(solution, declared);
        return ProvenanceChecker.buildPaths(solution, dependencyIndex, rootName, rootVersion);
    }

    private static String formatViolations(List<PolicyViolation> violations) {
        StringBuilder sb = new StringBuilder("来源策略校验失败: ");
        for (int i = 0; i < violations.size(); i++) {
            PolicyViolation v = violations.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append('[').append(v.code()).append("] ").append(v.path())
                    .append(' ').append(v.message());
        }
        return sb.toString();
    }

    private static List<String> normalizeRepos(List<String> allowedRepos) {
        if (allowedRepos == null || allowedRepos.isEmpty()) {
            throw ApiException.badRequest("allowedRepos 不能为空");
        }
        Set<String> distinct = new HashSet<>();
        for (String repo : allowedRepos) {
            if (repo == null || repo.isBlank()) {
                throw ApiException.badRequest("allowedRepos 不能包含空白项");
            }
            distinct.add(repo.trim());
        }
        return distinct.stream().sorted().toList();
    }

    private static String normalizeDigest(String digest) {
        if (digest == null || digest.isBlank()) {
            throw ApiException.badRequest("digest 不能为空");
        }
        String normalized = digest.trim().toLowerCase(Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(normalized).matches()) {
            throw ApiException.badRequest("digest 必须是 64 位十六进制 SHA-256 摘要");
        }
        return normalized;
    }

    private PublishResponse toPublishResponse(PublishRow row) {
        List<PublishEntryView> entries = new ArrayList<>();
        for (PublishEntryRow entry : repositoryDao.listPublishEntries(row.id())) {
            entries.add(new PublishEntryView(entry.name(), entry.version(),
                    entry.attestationVersion(), entry.repoId(), entry.digest(), entry.level()));
        }
        return new PublishResponse(row.id(), row.lockFileId(), row.policyVersion(),
                row.provenanceKey(), row.operatorName(), row.createdAt(), List.copyOf(entries));
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
        if (request.digest() != null && !DIGEST_PATTERN.matcher(request.digest()).matches()) {
            throw ApiException.badRequest("digest 必须是 64 位十六进制 SHA-256 摘要");
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
                .map(e -> new LockEntryResponse(e.name(), e.version()))
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
