package com.example.starter.api;

import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.DefinePolicyRequest;
import com.example.starter.api.dto.MigratePoliciesRequest;
import com.example.starter.api.dto.MigrationResponse;
import com.example.starter.api.dto.PolicyCoordinateView;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceDiagnosticResponse;
import com.example.starter.api.dto.ProvenancePathView;
import com.example.starter.api.dto.PublishLockRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.api.dto.ReleasedEntryView;
import com.example.starter.api.dto.SubmitAttestationRequest;
import com.example.starter.api.dto.ViolationView;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.PolicyCoordinate;
import com.example.starter.domain.ProvenanceAttestation;
import com.example.starter.domain.ProvenancePolicy;
import com.example.starter.domain.ProvenancePolicyEvaluator;
import com.example.starter.domain.ProvenanceViolation;
import com.example.starter.repo.ProvenanceDao;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.support.ApiException;
import com.example.starter.support.Hashes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 制品来源策略业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 * 策略、证明、撤销、迁移与发布因此按提交顺序裁决。
 */
@Service
public class ProvenanceServiceImpl implements ProvenanceService {

    private static final String OP_DEFINE_POLICY = "DEFINE_POLICY";
    private static final String OP_SUBMIT_ATTESTATION = "SUBMIT_ATTESTATION";
    private static final String OP_REVOKE_ATTESTATION = "REVOKE_ATTESTATION";
    private static final String OP_MIGRATE_POLICIES = "MIGRATE_POLICIES";
    private static final String OP_PUBLISH_LOCK = "PUBLISH_LOCK";

    private final RepositoryDao repositoryDao;
    private final ProvenanceDao provenanceDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProvenanceServiceImpl(RepositoryDao repositoryDao,
                                 ProvenanceDao provenanceDao,
                                 TransactionTemplate transactionTemplate,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.repositoryDao = repositoryDao;
        this.provenanceDao = provenanceDao;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 策略版本
    // ------------------------------------------------------------------

    @Override
    public PolicyResponse definePolicy(String requestId, String operatorHeader,
                                       DefinePolicyRequest request) {
        requireRequestId(requestId);
        String lockName = requireLockName(request.lockfileName());
        if (request.baseVersion() == null || request.baseVersion() < 0) {
            throw ApiException.badRequest("baseVersion 不能为空且必须 >= 0");
        }
        List<PolicyCoordinate> coordinates = normalizeCoordinates(request.coordinates().stream()
                .map(c -> new PolicyCoordinateSpecInput(c.name(), c.requiredLevel(), c.requiredDigest()))
                .toList());
        String operator = resolveOperator(null, operatorHeader);

        String canonical = coordinates.stream()
                .map(c -> c.name() + ":" + c.requiredLevel() + ":" + c.requiredDigest())
                .reduce((a, b) -> a + "," + b).orElse("");
        String hash = Hashes.sha256(OP_DEFINE_POLICY + "|" + lockName + "|"
                + request.baseVersion() + "|" + canonical + "|" + operator);

        return executeIdempotent(requestId, OP_DEFINE_POLICY, hash, 201,
                () -> doDefinePolicy(lockName, request.baseVersion(), operator, coordinates),
                PolicyResponse.class);
    }

    private PolicyResponse doDefinePolicy(String lockName, int baseVersion, String operator,
                                          List<PolicyCoordinate> coordinates) {
        int current = provenanceDao.currentPolicyVersion(lockName);
        if (current != baseVersion) {
            throw ApiException.conflict("策略基线版本不匹配：base=" + baseVersion
                    + ", current=" + current);
        }
        int newVersion = current + 1;
        Instant now = Instant.now(clock);
        long policyId = provenanceDao.insertPolicy(lockName, newVersion, operator, now);
        for (PolicyCoordinate coordinate : coordinates) {
            provenanceDao.insertPolicyCoordinate(policyId, coordinate);
        }
        repositoryDao.incrementRepositoryVersion();
        return toPolicyResponse(lockName, newVersion, now, coordinates);
    }

    @Override
    public PolicyResponse getPolicy(String lockName, Integer version) {
        String normalized = requireLockName(lockName);
        int targetVersion;
        if (version == null) {
            targetVersion = provenanceDao.currentPolicyVersion(normalized);
            if (targetVersion == 0) {
                throw ApiException.notFound("锁定图尚未定义来源策略: " + normalized);
            }
        } else {
            if (version <= 0) {
                throw ApiException.badRequest("version 必须为正整数");
            }
            targetVersion = version;
        }
        ProvenancePolicy policy = provenanceDao.loadPolicy(normalized, targetVersion);
        if (policy == null) {
            throw ApiException.notFound("策略版本不存在: " + normalized + "@" + targetVersion);
        }
        return toPolicyResponse(policy.lockfileName(), policy.version(), policy.createdAt(),
                policy.coordinates());
    }

    // ------------------------------------------------------------------
    // 证明提交与撤销
    // ------------------------------------------------------------------

    @Override
    public AttestationResponse submitAttestation(String requestId, String operatorHeader,
                                                 SubmitAttestationRequest request) {
        requireRequestId(requestId);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String name = request.name().trim();
        String sourceRepository = requireText(request.sourceRepository(), "sourceRepository");
        String buildDigest = requireText(request.buildDigest(), "buildDigest");
        String operator = resolveOperator(request.operator(), operatorHeader);

        String hash = Hashes.sha256(OP_SUBMIT_ATTESTATION + "|" + name + "|" + request.version()
                + "|" + sourceRepository + "|" + buildDigest + "|"
                + request.attestationLevel() + "|" + operator);

        return executeIdempotent(requestId, OP_SUBMIT_ATTESTATION, hash, 201,
                () -> doSubmitAttestation(name, request.version(), sourceRepository,
                        buildDigest, request.attestationLevel(), operator),
                AttestationResponse.class);
    }

    private AttestationResponse doSubmitAttestation(String name, int version, String sourceRepository,
                                                    String buildDigest, int level, String operator) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在，无法提交证明: " + name + ":" + version);
        }
        Instant now = Instant.now(clock);
        long id = provenanceDao.insertAttestation(name, version, sourceRepository, buildDigest,
                level, operator, now);
        repositoryDao.incrementRepositoryVersion();
        return new AttestationResponse(id, name, version, sourceRepository, buildDigest,
                level, operator, false, now);
    }

    @Override
    public AttestationResponse revokeAttestation(String requestId, long attestationId) {
        requireRequestId(requestId);
        String hash = Hashes.sha256(OP_REVOKE_ATTESTATION + "|" + attestationId);
        return executeIdempotent(requestId, OP_REVOKE_ATTESTATION, hash, 200,
                () -> doRevokeAttestation(attestationId), AttestationResponse.class);
    }

    private AttestationResponse doRevokeAttestation(long attestationId) {
        ProvenanceAttestation attestation = provenanceDao.loadAttestation(attestationId);
        if (attestation == null) {
            throw ApiException.notFound("来源证明不存在: " + attestationId);
        }
        if (attestation.revoked()) {
            throw ApiException.conflict("来源证明已撤销: " + attestationId);
        }
        int affected = provenanceDao.markRevoked(attestationId);
        if (affected == 0) {
            // 并发撤销抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict("来源证明已撤销: " + attestationId);
        }
        repositoryDao.incrementRepositoryVersion();
        return new AttestationResponse(attestation.id(), attestation.name(), attestation.version(),
                attestation.sourceRepository(), attestation.buildDigest(),
                attestation.attestationLevel(), attestation.operator(), true,
                Instant.now(clock));
    }

    // ------------------------------------------------------------------
    // 批量策略迁移
    // ------------------------------------------------------------------

    @Override
    public MigrationResponse migratePolicies(String requestId, String operatorHeader,
                                             MigratePoliciesRequest request) {
        requireRequestId(requestId);
        String operator = resolveOperator(request.operator(), operatorHeader);
        if (request.targets() == null || request.targets().isEmpty()) {
            throw ApiException.badRequest("targets 不能为空");
        }
        Set<Long> uniqueLockIds = new LinkedHashSet<>();
        for (MigratePoliciesRequest.MigrationTarget target : request.targets()) {
            if (target.lockFileId() == null || target.lockFileId() <= 0) {
                throw ApiException.badRequest("lockFileId 必须为正整数");
            }
            if (target.targetPolicyVersion() == null || target.targetPolicyVersion() <= 0) {
                throw ApiException.badRequest("targetPolicyVersion 必须为正整数");
            }
            if (!uniqueLockIds.add(target.lockFileId())) {
                throw ApiException.badRequest("迁移目标中 lockFileId 重复: " + target.lockFileId());
            }
        }
        String canonical = request.targets().stream()
                .map(t -> t.lockFileId() + ":" + t.targetPolicyVersion())
                .sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
        String hash = Hashes.sha256(OP_MIGRATE_POLICIES + "|" + canonical + "|" + operator);

        return executeIdempotent(requestId, OP_MIGRATE_POLICIES, hash, 201,
                () -> doMigrate(request.targets(), operator), MigrationResponse.class);
    }

    private MigrationResponse doMigrate(List<MigratePoliciesRequest.MigrationTarget> targets,
                                        String operator) {
        // 预校验阶段：收集所有锁定图按锁定坐标集合命中目标策略版本的违规，全部合规才写入。
        List<MigrationResponse.MigrationItem> items = new ArrayList<>();
        List<ViolationView> allViolations = new ArrayList<>();
        for (MigratePoliciesRequest.MigrationTarget target : targets) {
            LockFileRow lock = repositoryDao.getLockFile(target.lockFileId());
            if (lock == null) {
                throw ApiException.notFound("锁文件不存在: " + target.lockFileId());
            }
            if (lock.lockName() == null) {
                throw ApiException.unprocessable(
                        "旧锁定图未纳入来源策略管理，不能迁移: " + target.lockFileId());
            }
            ProvenancePolicy policy = provenanceDao.loadPolicy(
                    lock.lockName(), target.targetPolicyVersion());
            if (policy == null) {
                throw ApiException.unprocessable("目标策略版本不存在: "
                        + lock.lockName() + "@" + target.targetPolicyVersion());
            }
            TreeMap<String, ArtifactVersion> chosen = loadChosenArtifacts(target.lockFileId());
            List<ProvenanceViolation> violations = ProvenancePolicyEvaluator.evaluate(
                    chosen, lock.rootName(), policy, this::lookupAttestation);
            if (!violations.isEmpty()) {
                for (ProvenanceViolation v : violations) {
                    allViolations.add(new ViolationView(v.reason(), v.path(),
                            "锁文件 " + target.lockFileId() + "：" + v.detail()));
                }
            }
            items.add(new MigrationResponse.MigrationItem(
                    target.lockFileId(), lock.lockName(), target.targetPolicyVersion()));
        }
        if (!allViolations.isEmpty()) {
            throw ApiException.provenanceViolation(
                    "批量策略迁移预校验失败，未写入任何绑定", allViolations);
        }

        // 全部命中：原子绑定。
        for (MigrationResponse.MigrationItem item : items) {
            provenanceDao.bindPolicyVersion(item.lockFileId(), item.policyVersion());
        }
        repositoryDao.incrementRepositoryVersion();
        return new MigrationResponse(List.copyOf(items));
    }

    // ------------------------------------------------------------------
    // 发布
    // ------------------------------------------------------------------

    @Override
    public ReleaseSnapshotResponse publishLock(String requestId, String operatorHeader,
                                               PublishLockRequest request) {
        requireRequestId(requestId);
        if (request.lockFileId() == null || request.lockFileId() <= 0) {
            throw ApiException.badRequest("lockFileId 必须为正整数");
        }
        String operator = resolveOperator(null, operatorHeader);
        long lockFileId = request.lockFileId();
        // 同 requestId 重放返回固化原快照（证明集合即使随后演变，已发布结果不倒改）；
        // provenanceKey 自身携带锁定图版本、策略版本、规范化证明摘要与操作者并落库唯一约束。
        String hash = Hashes.sha256(OP_PUBLISH_LOCK + "|" + lockFileId + "|" + operator);

        return executeIdempotent(requestId, OP_PUBLISH_LOCK, hash, 201,
                () -> doPublish(lockFileId, operator), ReleaseSnapshotResponse.class);
    }

    private ReleaseSnapshotResponse doPublish(long lockFileId, String operator) {
        // 在持有行锁的事务内构建发布计划，证明读取与全部写操作按提交顺序串行裁决。
        ReleasePlan plan = buildReleasePlan(lockFileId, operator);
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);

        ProvenanceDao.ReleaseRow existing = provenanceDao.getReleaseByLockFile(lockFileId);
        if (existing != null) {
            if (existing.provenanceKey().equals(plan.provenanceKey())) {
                // 同键重放：锁定图版本、策略版本、规范化证明摘要与操作者均一致，返回已固化快照。
                return toReleaseResponse(existing);
            }
            throw ApiException.conflict(
                    "锁定图已发布，当前证明集合或操作者产生不同 provenanceKey，不能重复发布: "
                            + lockFileId);
        }

        Instant now = Instant.now(clock);
        long snapshotId;
        try {
            snapshotId = provenanceDao.insertReleaseSnapshot(lockFileId, lock.rootName(),
                    lock.rootVersion(), plan.policyVersion(), plan.provenanceKey(), operator, now);
        } catch (DuplicateKeyException e) {
            // 并发发布：唯一约束（lock_file_id / provenance_key）兜底，无半成品。
            ProvenanceDao.ReleaseRow raced = provenanceDao.getReleaseByLockFile(lockFileId);
            if (raced != null && raced.provenanceKey().equals(plan.provenanceKey())) {
                return toReleaseResponse(raced);
            }
            throw ApiException.conflict("锁定图已发布: " + lockFileId);
        }
        for (ReleasePlanEntry entry : plan.entries()) {
            provenanceDao.insertReleaseEntry(snapshotId, new ProvenanceDao.ReleaseEntryRow(
                    snapshotId, entry.name(), entry.version(), entry.attestation().id(),
                    entry.attestation().sourceRepository(), entry.attestation().buildDigest(),
                    entry.attestation().attestationLevel()));
        }
        return toReleaseResponse(new ProvenanceDao.ReleaseRow(snapshotId, lockFileId,
                lock.rootName(), lock.rootVersion(), plan.policyVersion(),
                plan.provenanceKey(), operator, now));
    }

    @Override
    public ReleaseSnapshotResponse getRelease(long lockFileId) {
        ProvenanceDao.ReleaseRow row = provenanceDao.getReleaseByLockFile(lockFileId);
        if (row == null) {
            throw ApiException.notFound("锁定图尚未发布: " + lockFileId);
        }
        return toReleaseResponse(row);
    }

    // ------------------------------------------------------------------
    // 来源路径与发布阻断诊断
    // ------------------------------------------------------------------

    @Override
    public ProvenanceDiagnosticResponse diagnose(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        TreeMap<String, ArtifactVersion> chosen = loadChosenArtifacts(lockFileId);

        if (lock.policyVersion() == 0) {
            // 旧锁定图不做来源门禁：compliant 恒为 true 且不产生违规；路径仍如实展示证明命中状态。
            ProvenancePolicy empty = new ProvenancePolicy(lock.lockName() == null
                    ? "" : lock.lockName(), 0, Instant.now(clock), List.of());
            List<ProvenancePolicyEvaluator.CoordinateProvenance> inspected =
                    ProvenancePolicyEvaluator.inspect(chosen, lock.rootName(), empty,
                            this::lookupAttestation);
            return new ProvenanceDiagnosticResponse(lockFileId, lock.rootName(),
                    lock.rootVersion(), 0, 0, true, toPathViews(inspected), List.of());
        }

        int currentPolicyVersion = provenanceDao.currentPolicyVersion(lock.lockName());
        ProvenancePolicy policy = provenanceDao.loadPolicy(lock.lockName(), lock.policyVersion());
        if (policy == null) {
            throw new IllegalStateException("锁定图绑定的策略版本缺失: "
                    + lock.lockName() + "@" + lock.policyVersion());
        }
        List<ProvenancePolicyEvaluator.CoordinateProvenance> inspected =
                ProvenancePolicyEvaluator.inspect(chosen, lock.rootName(), policy,
                        this::lookupAttestation);
        List<ProvenanceViolation> violations = new ArrayList<>(
                ProvenancePolicyEvaluator.violations(inspected, policy));
        if (currentPolicyVersion != lock.policyVersion()) {
            violations.add(0, new ProvenanceViolation(ProvenanceViolation.POLICY_VERSION_OUTDATED,
                    List.of(lock.rootName() + ":" + lock.rootVersion()),
                    "绑定版本 v" + lock.policyVersion() + "，当前版本 v" + currentPolicyVersion));
        }
        List<ViolationView> violationViews = violations.stream()
                .map(v -> new ViolationView(v.reason(), v.path(), v.detail()))
                .toList();
        return new ProvenanceDiagnosticResponse(lockFileId, lock.rootName(), lock.rootVersion(),
                lock.policyVersion(), currentPolicyVersion, violations.isEmpty(),
                toPathViews(inspected), violationViews);
    }

    // ------------------------------------------------------------------
    // 共享装配
    // ------------------------------------------------------------------

    /** 发布计划：绑定策略版本、逐坐标命中的证明与 provenanceKey。 */
    private record ReleasePlan(int policyVersion, String provenanceKey,
                               List<ReleasePlanEntry> entries) {
    }

    private record ReleasePlanEntry(String name, int version, ProvenanceAttestation attestation) {
    }

    /**
     * 在不加写锁的只读快照上预计算发布计划；重新执行当前证明与策略门禁，
     * 任一坐标缺证明、撤销、摘要不匹配或等级不足即 422 并列完整路径。
     */
    private ReleasePlan buildReleasePlan(long lockFileId, String operator) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        if (lock.policyVersion() == 0 || lock.lockName() == null) {
            throw ApiException.unprocessable(
                    "锁定图未绑定来源策略，不能通过来源门禁发布: " + lockFileId);
        }
        int currentPolicyVersion = provenanceDao.currentPolicyVersion(lock.lockName());
        if (currentPolicyVersion != lock.policyVersion()) {
            throw ApiException.provenanceViolation(
                    "锁定图绑定策略版本落后于当前版本，须先完成批量策略迁移",
                    List.of(new ViolationView(ProvenanceViolation.POLICY_VERSION_OUTDATED,
                            List.of(lock.rootName() + ":" + lock.rootVersion()),
                            "绑定版本 v" + lock.policyVersion() + "，当前版本 v"
                                    + currentPolicyVersion)));
        }
        ProvenancePolicy policy = provenanceDao.loadPolicy(lock.lockName(), lock.policyVersion());
        if (policy == null) {
            throw new IllegalStateException("锁定图绑定的策略版本缺失: "
                    + lock.lockName() + "@" + lock.policyVersion());
        }
        TreeMap<String, ArtifactVersion> chosen = loadChosenArtifacts(lockFileId);
        List<ProvenanceViolation> violations = ProvenancePolicyEvaluator.evaluate(
                chosen, lock.rootName(), policy, this::lookupAttestation);
        if (!violations.isEmpty()) {
            List<ViolationView> views = violations.stream()
                    .map(v -> new ViolationView(v.reason(), v.path(), v.detail()))
                    .toList();
            throw ApiException.provenanceViolation("发布被来源策略阻断", views);
        }

        List<ReleasePlanEntry> entries = new ArrayList<>();
        for (ArtifactVersion artifact : chosen.values()) {
            ProvenancePolicyEvaluator.LookupResult found =
                    lookupAttestation(artifact.name(), artifact.version());
            if (found.state() != ProvenancePolicyEvaluator.LookupResult.State.VALID) {
                // evaluate 已拦截，防御性处理。
                throw ApiException.unprocessable("制品缺少有效来源证明: "
                        + artifact.name() + ":" + artifact.version());
            }
            entries.add(new ReleasePlanEntry(artifact.name(), artifact.version(),
                    found.attestation()));
        }
        String canonical = entries.stream()
                .map(e -> e.name() + ":" + e.version() + ":" + e.attestation().id() + ":"
                        + e.attestation().sourceRepository() + ":"
                        + e.attestation().buildDigest() + ":"
                        + e.attestation().attestationLevel())
                .reduce((a, b) -> a + "," + b).orElse("");
        String provenanceKey = Hashes.sha256("LOCK=" + lockFileId + "|POLICY="
                + lock.policyVersion() + "|ATTESTATIONS=" + canonical + "|OPERATOR=" + operator);
        return new ReleasePlan(lock.policyVersion(), provenanceKey, List.copyOf(entries));
    }

    /** 读取锁定图固化的坐标集合并还原制品版本（含依赖声明，用于完整路径计算）。 */
    private TreeMap<String, ArtifactVersion> loadChosenArtifacts(long lockFileId) {
        TreeMap<String, ArtifactVersion> chosen = new TreeMap<>();
        for (LockEntryRow entry : repositoryDao.listLockEntries(lockFileId)) {
            ArtifactVersion artifact = repositoryDao.loadArtifact(entry.name(), entry.version());
            if (artifact == null) {
                throw new IllegalStateException("锁定条目对应制品版本缺失: "
                        + entry.name() + ":" + entry.version());
            }
            chosen.put(entry.name(), artifact);
        }
        return chosen;
    }

    private ProvenancePolicyEvaluator.LookupResult lookupAttestation(String name, int version) {
        ProvenanceAttestation latest = provenanceDao.loadLatestAttestation(name, version);
        if (latest == null) {
            return new ProvenancePolicyEvaluator.LookupResult(
                    ProvenancePolicyEvaluator.LookupResult.State.ABSENT, null);
        }
        if (latest.revoked()) {
            return new ProvenancePolicyEvaluator.LookupResult(
                    ProvenancePolicyEvaluator.LookupResult.State.REVOKED, latest);
        }
        return new ProvenancePolicyEvaluator.LookupResult(
                ProvenancePolicyEvaluator.LookupResult.State.VALID, latest);
    }

    /** 将逐坐标来源检查结果转换为路径视图。 */
    private List<ProvenancePathView> toPathViews(
            List<ProvenancePolicyEvaluator.CoordinateProvenance> inspected) {
        return inspected.stream()
                .map(cp -> new ProvenancePathView(cp.name(), cp.version(), cp.path(),
                        cp.matched(), cp.reason(),
                        cp.attestation() == null ? null : cp.attestation().id(),
                        cp.attestation() == null ? null : cp.attestation().sourceRepository(),
                        cp.attestation() == null ? null : cp.attestation().buildDigest(),
                        cp.attestation() == null ? null : cp.attestation().attestationLevel()))
                .toList();
    }

    private PolicyResponse toPolicyResponse(String lockName, int version, Instant createdAt,
                                            List<PolicyCoordinate> coordinates) {
        List<PolicyCoordinateView> views = coordinates.stream()
                .map(c -> new PolicyCoordinateView(c.name(), c.requiredLevel(), c.requiredDigest()))
                .toList();
        return new PolicyResponse(lockName, version, createdAt, views);
    }

    private ReleaseSnapshotResponse toReleaseResponse(ProvenanceDao.ReleaseRow row) {
        List<ReleasedEntryView> entries = provenanceDao.listReleaseEntries(row.id()).stream()
                .map(e -> new ReleasedEntryView(e.name(), e.version(), e.attestationId(),
                        e.sourceRepository(), e.buildDigest(), e.attestationLevel()))
                .toList();
        return new ReleaseSnapshotResponse(row.id(), row.lockFileId(), row.rootName(),
                row.rootVersion(), row.policyVersion(), row.provenanceKey(), row.operator(),
                row.createdAt(), entries);
    }

    // ------------------------------------------------------------------
    // 校验与幂等控制
    // ------------------------------------------------------------------

    private record PolicyCoordinateSpecInput(String name, int requiredLevel, String requiredDigest) {
    }

    private List<PolicyCoordinate> normalizeCoordinates(List<PolicyCoordinateSpecInput> raw) {
        if (raw.isEmpty()) {
            throw ApiException.badRequest("coordinates 至少包含一条坐标要求");
        }
        TreeMap<String, PolicyCoordinate> sorted = new TreeMap<>();
        for (PolicyCoordinateSpecInput spec : raw) {
            if (spec.name() == null || spec.name().isBlank()) {
                throw ApiException.badRequest("坐标名称不能为空");
            }
            String name = spec.name().trim();
            String digest = spec.requiredDigest() == null ? "" : spec.requiredDigest().trim();
            if (sorted.put(name, new PolicyCoordinate(name, spec.requiredLevel(), digest)) != null) {
                throw ApiException.badRequest("策略坐标名称重复: " + name);
            }
        }
        return List.copyOf(sorted.values());
    }

    private String resolveOperator(String bodyOperator, String headerOperator) {
        String operator = bodyOperator != null && !bodyOperator.isBlank()
                ? bodyOperator.trim()
                : (headerOperator == null ? "" : headerOperator.trim());
        if (operator.isEmpty()) {
            throw ApiException.badRequest("缺少操作者（请求体 operator 或请求头 X-Operator）");
        }
        return operator;
    }

    private String requireLockName(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            throw ApiException.badRequest("lockfileName 不能为空");
        }
        return lockName.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private <T> T executeIdempotent(String requestId, String operation, String requestHash,
                                    int httpStatus, Supplier<T> action, Class<T> responseType) {
        T replay = replayIfPresent(requestId, operation, requestHash, responseType);
        if (replay != null) {
            return replay;
        }

        try {
            return transactionTemplate.execute(status -> {
                repositoryDao.lockRepositoryState();
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
            T replayAfterRace = replayIfPresent(requestId, operation, requestHash, responseType);
            if (replayAfterRace != null) {
                return replayAfterRace;
            }
            throw ApiException.conflict("相同 requestId 的请求正在处理中: " + requestId);
        }
    }

    private <T> T replayIfPresent(String requestId, String operation, String requestHash,
                                  Class<T> responseType) {
        IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
        if (record == null) {
            return null;
        }
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已用于不同参数的请求: " + requestId);
        }
        return readJson(record.responseJson(), responseType);
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
