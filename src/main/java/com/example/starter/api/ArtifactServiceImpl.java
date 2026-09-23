package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.KeyResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SignatureResponse;
import com.example.starter.domain.ArtifactSignature;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockNodeEvidence;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.SignatureVerifier;
import com.example.starter.domain.SigningPolicy;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.KeyRow;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.PolicyRow;
import com.example.starter.repo.RepositoryDao.SignatureRow;
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
import java.util.stream.Collectors;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 * 锁解析在持有行锁的同一事务快照内完成策略选择与逐节点签名阈值验证，
 * 因此策略激活、钥匙撤销、补签与解析并发时，锁文件只能引用一个一致状态。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_POLICY_KEYS = 10;
    private static final int MAX_KEY_ID_LENGTH = 128;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_PUBLISH_POLICY = "PUBLISH_POLICY";
    private static final String OP_REVOKE_KEY = "REVOKE_KEY";
    private static final String OP_ADD_SIGNATURE = "ADD_SIGNATURE";

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
                + request.contentDigest() + "|" + canonicalDependencies(request));
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
    public PolicyResponse publishPolicy(String requestId, PublishPolicyRequest request) {
        requireRequestId(requestId);
        List<String> keyIds = normalizePolicyKeys(request.keyIds());
        validatePolicyRequest(request, keyIds);
        String hash = sha256(OP_PUBLISH_POLICY + "|" + request.policyVersion() + "|"
                + request.threshold() + "|" + request.effectiveAt() + "|"
                + String.join(",", keyIds));
        return executeIdempotent(requestId, OP_PUBLISH_POLICY, hash, 201,
                () -> doPublishPolicy(request, keyIds), PolicyResponse.class);
    }

    @Override
    public KeyResponse revokeKey(String requestId, String keyId) {
        requireRequestId(requestId);
        if (keyId == null || keyId.isBlank()) {
            throw ApiException.badRequest("keyId 不能为空");
        }
        String normalized = keyId.trim();
        String hash = sha256(OP_REVOKE_KEY + "|" + normalized);
        return executeIdempotent(requestId, OP_REVOKE_KEY, hash, 200,
                () -> doRevokeKey(normalized), KeyResponse.class);
    }

    @Override
    public SignatureResponse addSignature(String requestId, String name, int version,
                                          AddSignatureRequest request) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (request.keyId() == null || request.keyId().isBlank()) {
            throw ApiException.badRequest("keyId 不能为空");
        }
        String hash = sha256(OP_ADD_SIGNATURE + "|" + name.trim() + "|" + version + "|"
                + request.keyId().trim() + "|" + request.digest());
        return executeIdempotent(requestId, OP_ADD_SIGNATURE, hash, 201,
                () -> doAddSignature(name.trim(), version, request.keyId().trim(), request.digest()),
                SignatureResponse.class);
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

    // ------------------------------------------------------------------
    // 只读查询
    // ------------------------------------------------------------------

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
    public List<PolicyResponse> listPolicies() {
        List<PolicyResponse> result = new ArrayList<>();
        for (PolicyRow row : repositoryDao.listPolicies()) {
            result.add(toPolicyResponse(row));
        }
        return result;
    }

    @Override
    public PolicyResponse getPolicy(long policyVersion) {
        PolicyRow row = repositoryDao.getPolicy(policyVersion);
        if (row == null) {
            throw ApiException.notFound("策略不存在: " + policyVersion);
        }
        return toPolicyResponse(row);
    }

    @Override
    public PolicyResponse getEffectivePolicy() {
        SigningPolicy policy = repositoryDao.findEffectivePolicy(Instant.now(clock));
        return policy == null ? null : new PolicyResponse(policy.policyVersion(),
                policy.keyIds(), policy.threshold(), policy.effectiveAt(),
                repositoryDao.getPolicy(policy.policyVersion()).createdAt());
    }

    @Override
    public KeyResponse getKey(String keyId) {
        KeyRow row = repositoryDao.getSigningKey(keyId);
        if (row == null) {
            throw ApiException.notFound("钥匙不存在: " + keyId);
        }
        return new KeyResponse(row.keyId(), row.revoked(), row.revokedAt());
    }

    @Override
    public List<SignatureResponse> listSignatures(String name, int version) {
        if (repositoryDao.loadArtifact(name, version) == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        return repositoryDao.listSignaturesByCoordinates(name, version).stream()
                .map(r -> new SignatureResponse(r.name(), r.version(), r.keyId(),
                        r.digest(), r.createdAt()))
                .toList();
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

        String contentDigest = request.contentDigest() != null
                ? request.contentDigest()
                : deriveContentDigest(name, version, request.dependencies());
        Instant now = Instant.now(clock);
        long artifactId = repositoryDao.insertArtifact(name, version, contentDigest, now);
        for (var dep : request.dependencies()) {
            repositoryDao.insertDependency(artifactId, dep.name().trim(),
                    dep.minimumVersion(), dep.maximumVersion());
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, contentDigest, repositoryVersion, now,
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
        return new ArtifactResponse(name, version, true, artifact.contentDigest(),
                repositoryVersion, Instant.now(clock), toDependencyViews(artifact.dependencies()));
    }

    private PolicyResponse doPublishPolicy(PublishPolicyRequest request, List<String> keyIds) {
        long policyVersion = request.policyVersion();
        long currentMax = repositoryDao.maxPolicyVersion();
        if (repositoryDao.getPolicy(policyVersion) != null) {
            throw ApiException.conflict("策略版本已存在: " + policyVersion);
        }
        if (policyVersion <= currentMax) {
            throw ApiException.unprocessable("策略版本号必须严格递增：新版本=" + policyVersion
                    + ", 当前最大=" + currentMax);
        }

        Instant now = Instant.now(clock);
        long policyId = repositoryDao.insertPolicy(policyVersion, request.threshold(),
                request.effectiveAt(), currentRequestId.get(), now);
        for (String keyId : keyIds) {
            // 随策略首次出现的钥匙自动登记；已存在（含已撤销）钥匙状态保持不变。
            repositoryDao.insertSigningKeyIfAbsent(keyId, now);
            repositoryDao.insertPolicyKey(policyId, policyVersion, keyId);
        }
        repositoryDao.incrementRepositoryVersion();

        return new PolicyResponse(policyVersion, keyIds, request.threshold(),
                request.effectiveAt(), now);
    }

    private KeyResponse doRevokeKey(String keyId) {
        KeyRow key = repositoryDao.getSigningKey(keyId);
        if (key == null) {
            throw ApiException.notFound("钥匙不存在: " + keyId);
        }
        if (key.revoked()) {
            throw ApiException.conflict("钥匙已撤销: " + keyId);
        }
        Instant now = Instant.now(clock);
        int affected = repositoryDao.markKeyRevoked(keyId, now);
        if (affected == 0) {
            throw ApiException.conflict("钥匙已撤销: " + keyId);
        }
        repositoryDao.incrementRepositoryVersion();
        return new KeyResponse(keyId, true, now);
    }

    private SignatureResponse doAddSignature(String name, int version, String keyId, String digest) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        KeyRow key = repositoryDao.getSigningKey(keyId);
        if (key == null) {
            throw ApiException.notFound("钥匙不存在: " + keyId);
        }
        if (key.revoked()) {
            throw ApiException.conflict("钥匙已撤销，不得追加签名: " + keyId);
        }
        if (!artifact.contentDigest().equals(digest)) {
            throw ApiException.unprocessable("签名 digest 与制品内容摘要不符："
                    + name + ":" + version);
        }

        boolean alreadySigned = repositoryDao.listSignaturesForArtifact(artifact.id()).stream()
                .anyMatch(s -> s.keyId().equals(keyId));
        if (alreadySigned) {
            throw ApiException.conflict("该钥匙已对此制品版本签名: " + keyId);
        }

        Instant now = Instant.now(clock);
        String signatureKey = name + "/" + version + "/" + keyId;
        try {
            repositoryDao.insertSignature(artifact.id(), signatureKey, keyId, digest,
                    currentRequestId.get(), now);
        } catch (DuplicateKeyException e) {
            // 并发补签抢先提交，唯一约束兜底。
            throw ApiException.conflict("该钥匙已对此制品版本签名: " + keyId);
        }
        repositoryDao.incrementRepositoryVersion();
        return new SignatureResponse(name, version, keyId, digest, now);
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

        // 同一事务快照内解析依赖闭包；所有写操作均被行锁串行化，快照稳定。
        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        Instant now = Instant.now(clock);
        SigningPolicy policy = repositoryDao.findEffectivePolicy(now);
        // 闭包内每个节点（名称唯一）只取一次快照对象、只验证一次。
        TreeMap<String, ArtifactVersion> nodes = resolveNodes(snapshot, solution);
        TreeMap<String, LockNodeEvidence> evidence = verifySignatures(policy, now, nodes);

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        List<LockEntryResponse> entries = new ArrayList<>();
        for (Map.Entry<String, ArtifactVersion> node : nodes.entrySet()) {
            String nodeName = node.getKey();
            ArtifactVersion artifact = node.getValue();
            LockNodeEvidence nodeEvidence = evidence.get(nodeName);
            String frozenDigest = nodeEvidence == null ? null : nodeEvidence.digest();
            Long frozenPolicyVersion = nodeEvidence == null ? null : nodeEvidence.policyVersion();
            String frozenKeyIds = nodeEvidence == null ? null
                    : String.join(",", nodeEvidence.signerKeyIds());
            repositoryDao.insertLockEntry(lockFileId, nodeName, artifact.version(),
                    frozenDigest, frozenPolicyVersion, frozenKeyIds);
            entries.add(new LockEntryResponse(nodeName, artifact.version(), frozenDigest,
                    frozenPolicyVersion,
                    nodeEvidence == null ? null : List.copyOf(nodeEvidence.signerKeyIds())));
        }

        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /**
     * 将解析出的名称->版本映射绑定到快照内的制品对象（含 id 与内容摘要），按名称升序。
     */
    private TreeMap<String, ArtifactVersion> resolveNodes(RepositorySnapshot snapshot,
                                                          Map<String, Integer> solution) {
        TreeMap<String, ArtifactVersion> nodes = new TreeMap<>();
        for (Map.Entry<String, Integer> chosen : solution.entrySet()) {
            List<ArtifactVersion> versions = snapshot.artifacts().get(chosen.getKey());
            ArtifactVersion matched = versions == null ? null : versions.stream()
                    .filter(a -> a.version() == chosen.getValue())
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                // 理论不可达：解析器输出必然来自快照。防御性失败，整体回滚。
                throw ApiException.unprocessable("锁定解析结果与快照不一致: "
                        + chosen.getKey() + ":" + chosen.getValue());
            }
            nodes.put(chosen.getKey(), matched);
        }
        return nodes;
    }

    /**
     * 在调用方选定的单一策略快照上逐节点验证签名阈值。
     *
     * <p>无生效策略时返回空映射（不冻结签名证据，沿用无签名旧语义）；
     * 任一节点摘要不符或有效可信签名不足 m 即整体 422，不生成锁。
     */
    private TreeMap<String, LockNodeEvidence> verifySignatures(
            SigningPolicy policy, Instant now, TreeMap<String, ArtifactVersion> nodes) {
        TreeMap<String, LockNodeEvidence> evidence = new TreeMap<>();
        if (policy == null) {
            return evidence;
        }
        Set<String> revokedKeyIds = repositoryDao.listRevokedKeyIds();
        Map<Long, List<ArtifactSignature>> signaturesByArtifact =
                repositoryDao.listSignaturesForArtifacts(nodes.values().stream()
                        .map(ArtifactVersion::id).toList());

        for (Map.Entry<String, ArtifactVersion> node : nodes.entrySet()) {
            ArtifactVersion artifact = node.getValue();
            if (artifact.withdrawn()) {
                // 解析器已过滤撤回候选，此处为防御性快照一致性校验。
                throw ApiException.conflict("依赖闭包内存在已撤回版本: "
                        + node.getKey() + ":" + artifact.version());
            }
            List<ArtifactSignature> signatures = signaturesByArtifact.getOrDefault(
                    artifact.id(), List.of());
            SignatureVerifier.Result result = SignatureVerifier.verify(
                    policy, artifact.contentDigest(), signatures, revokedKeyIds);
            if (!result.success()) {
                String reason = result.reason() == SignatureVerifier.FailureReason.DIGEST_MISMATCH
                        ? "存在摘要不符的可信签名"
                        : "未撤销可信签名数量不足阈值 " + policy.threshold();
                throw ApiException.unprocessable("制品 " + node.getKey() + ":"
                        + artifact.version() + " 签名验证失败：" + reason);
            }
            evidence.put(node.getKey(), new LockNodeEvidence(
                    artifact.contentDigest(), policy.policyVersion(), result.signerKeyIds()));
        }
        // now 仅用于表达“策略选择时刻”这一单一一致点；全部节点共用同一 policy。
        return evidence;
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
        // 快速路径：已提交的成功记录直接重放（同参重放不重新选签名、不重新解析）。
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

    private List<String> normalizePolicyKeys(List<String> rawKeyIds) {
        if (rawKeyIds == null || rawKeyIds.isEmpty()) {
            throw ApiException.badRequest("策略至少包含 1 个可信 keyId");
        }
        if (rawKeyIds.size() > MAX_POLICY_KEYS) {
            throw ApiException.badRequest("策略最多包含 " + MAX_POLICY_KEYS + " 个可信 keyId");
        }
        Set<String> normalized = new java.util.TreeSet<>();
        for (String raw : rawKeyIds) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("keyId 不能为空");
            }
            String keyId = raw.trim();
            if (keyId.length() > MAX_KEY_ID_LENGTH) {
                throw ApiException.badRequest("keyId 长度不能超过 " + MAX_KEY_ID_LENGTH);
            }
            if (!normalized.add(keyId)) {
                throw ApiException.badRequest("策略可信 keyId 重复: " + keyId);
            }
        }
        return List.copyOf(normalized);
    }

    private void validatePolicyRequest(PublishPolicyRequest request, List<String> keyIds) {
        if (request.threshold() < 1 || request.threshold() > keyIds.size()) {
            throw ApiException.badRequest("阈值 m 必须满足 1 <= m <= keyId 数量（"
                    + keyIds.size() + "）");
        }
    }

    private String canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> d.name().trim() + ":" + d.minimumVersion() + ":" + d.maximumVersion())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** 未显式提供内容摘要时，按规范化坐标与依赖区间派生稳定的 SHA-256 摘要。 */
    private String deriveContentDigest(String name, int version, List<DependencySpec> deps) {
        return sha256("artifact|" + name + "|" + version + "|"
                + deps.stream()
                .map(d -> d.name().trim() + ":" + d.minimumVersion() + ":" + d.maximumVersion())
                .sorted()
                .collect(Collectors.joining(",")));
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

    private PolicyResponse toPolicyResponse(PolicyRow row) {
        return new PolicyResponse(row.policyVersion(),
                repositoryDao.listPolicyKeys(row.policyVersion()), row.threshold(),
                row.effectiveAt(), row.createdAt());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(), e.digest(),
                        e.policyVersion(), splitKeyIds(e.signerKeyIds())))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private List<String> splitKeyIds(String csv) {
        if (csv == null || csv.isEmpty()) {
            return null;
        }
        return List.of(csv.split(","));
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
