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
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.SignatureGate;
import com.example.starter.domain.SignaturePolicy;
import com.example.starter.domain.TrustedKey;
import com.example.starter.repo.RepositoryDao;
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
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 * 策略发布、钥匙撤销与补签同样串行在该行锁上并推进仓库版本号，
 * 因此锁文件解析期间这些状态不可能变化，锁图只引用一个一致状态。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_-]{1,128}");

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
        String contentDigest = resolveDigest(request);
        String hash = sha256(OP_REGISTER + "|" + request.name().trim() + "|" + request.version()
                + "|" + contentDigest + "|" + canonicalDependencies(request));
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request, contentDigest), ArtifactResponse.class);
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
        validatePolicyRequest(request);
        List<String> sortedKeys = new ArrayList<>(new TreeSet<>(request.keyIds()));
        String hash = sha256(OP_PUBLISH_POLICY + "|" + request.policyVersion() + "|"
                + request.thresholdM() + "|" + request.effectiveAt() + "|"
                + String.join(",", sortedKeys));
        return executeIdempotent(requestId, OP_PUBLISH_POLICY, hash, 201,
                () -> doPublishPolicy(request, sortedKeys), PolicyResponse.class);
    }

    @Override
    public KeyResponse revokeKey(String requestId, String keyId) {
        requireRequestId(requestId);
        if (keyId == null || keyId.isBlank()) {
            throw ApiException.badRequest("keyId 不能为空");
        }
        String normalized = keyId.trim();
        if (!KEY_ID_PATTERN.matcher(normalized).matches()) {
            throw ApiException.badRequest("keyId 字符集不合法: " + normalized);
        }
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
        if (version <= 0) {
            throw ApiException.badRequest("version 必须为正整数");
        }
        if (request == null || request.keyId() == null || request.keyId().isBlank()) {
            throw ApiException.badRequest("keyId 不能为空");
        }
        if (request.digest() == null || !DIGEST_PATTERN.matcher(request.digest()).matches()) {
            throw ApiException.badRequest("digest 必须为 64 位十六进制 SHA-256 摘要");
        }
        String normalizedName = name.trim();
        String normalizedKey = request.keyId().trim();
        if (!KEY_ID_PATTERN.matcher(normalizedKey).matches()) {
            throw ApiException.badRequest("keyId 字符集不合法: " + normalizedKey);
        }
        String digest = request.digest().toLowerCase();
        String hash = sha256(OP_ADD_SIGNATURE + "|" + normalizedName + "|" + version
                + "|" + normalizedKey + "|" + digest);
        return executeIdempotent(requestId, OP_ADD_SIGNATURE, hash, 201,
                () -> doAddSignature(normalizedName, version, normalizedKey, digest),
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
        return repositoryDao.listPolicies().stream().map(this::toPolicyResponse).toList();
    }

    @Override
    public PolicyResponse getPolicy(long policyVersion) {
        SignaturePolicy policy = repositoryDao.loadPolicy(policyVersion);
        if (policy == null) {
            throw ApiException.notFound("策略版本不存在: " + policyVersion);
        }
        return toPolicyResponse(policy);
    }

    @Override
    public List<KeyResponse> listKeys() {
        return repositoryDao.listTrustedKeys().stream()
                .map(k -> new KeyResponse(k.keyId(), k.revoked(), k.createdAt(), k.revokedAt()))
                .toList();
    }

    @Override
    public KeyResponse getKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw ApiException.badRequest("keyId 不能为空");
        }
        TrustedKey key = repositoryDao.loadTrustedKey(keyId.trim());
        if (key == null) {
            throw ApiException.notFound("可信钥匙不存在: " + keyId);
        }
        return new KeyResponse(key.keyId(), key.revoked(), key.createdAt(), key.revokedAt());
    }

    @Override
    public List<SignatureResponse> listSignatures(String name, int version) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (version <= 0) {
            throw ApiException.badRequest("version 必须为正整数");
        }
        return repositoryDao.listSignaturesByName(name.trim(), version).stream()
                .map(s -> new SignatureResponse(name.trim(), version,
                        s.keyId(), s.digest(), s.createdAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 业务操作（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    private ArtifactResponse doRegister(RegisterArtifactRequest request, String contentDigest) {
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

    private PolicyResponse doPublishPolicy(PublishPolicyRequest request, List<String> sortedKeys) {
        long policyVersion = request.policyVersion();
        if (repositoryDao.policyExists(policyVersion)) {
            throw ApiException.conflict("策略版本已存在: " + policyVersion);
        }
        if (policyVersion <= repositoryDao.maxPolicyVersion()) {
            throw ApiException.unprocessable(
                    "policyVersion 必须严格递增，当前最高版本: " + repositoryDao.maxPolicyVersion());
        }

        Instant now = Instant.now(clock);
        repositoryDao.insertPolicy(policyVersion, request.thresholdM(),
                request.effectiveAt(), now);
        for (int i = 0; i < sortedKeys.size(); i++) {
            String keyId = sortedKeys.get(i);
            if (!repositoryDao.trustedKeyExists(keyId)) {
                repositoryDao.insertTrustedKey(keyId, now);
            }
            repositoryDao.insertPolicyKey(policyVersion, keyId, i);
        }
        repositoryDao.incrementRepositoryVersion();
        return new PolicyResponse(policyVersion, List.copyOf(sortedKeys),
                request.thresholdM(), request.effectiveAt(), now);
    }

    private KeyResponse doRevokeKey(String keyId) {
        TrustedKey key = repositoryDao.loadTrustedKey(keyId);
        if (key == null) {
            throw ApiException.notFound("可信钥匙不存在: " + keyId);
        }
        if (key.revoked()) {
            throw ApiException.conflict("钥匙已撤销: " + keyId);
        }
        Instant now = Instant.now(clock);
        int affected = repositoryDao.revokeTrustedKey(keyId, now);
        if (affected == 0) {
            throw ApiException.conflict("钥匙已撤销: " + keyId);
        }
        repositoryDao.incrementRepositoryVersion();
        return new KeyResponse(keyId, true, key.createdAt(), now);
    }

    private SignatureResponse doAddSignature(String name, int version,
                                             String keyId, String digest) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        // 补签只强制“同一钥匙仅一份、digest 等于内容摘要”；
        // 钥匙是否被当前策略信任、是否已撤销，统一在锁定验证时判定，
        // 撤销后历史签名保留不改写。
        if (!artifact.contentDigest().equals(digest)) {
            throw ApiException.unprocessable(
                    "签名 digest 与制品内容摘要不符: " + name + ":" + version);
        }

        Instant now = Instant.now(clock);
        try {
            repositoryDao.insertSignature(artifact.id(), keyId, digest, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "该制品版本已存在钥匙 " + keyId + " 的签名: " + name + ":" + version);
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

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        // 闭包内统一选择解析时刻的当前生效策略；全部写者串行在同一行锁上，
        // 策略激活、钥匙撤销、补签、撤回均不可能在本次解析期间提交。
        Instant policyTime = Instant.now(clock);
        SignaturePolicy policy = repositoryDao.loadEffectivePolicy(policyTime);
        Set<String> revokedKeys = loadRevokedKeys(policyTime);

        // 相同依赖节点在 solution 中只出现一次，因此只验证一次。
        TreeMap<String, NodeEvidence> evidenceByName = new TreeMap<>();
        for (Map.Entry<String, Integer> node : solution.entrySet()) {
            ArtifactVersion nodeArtifact = repositoryDao.loadArtifact(
                    node.getKey(), node.getValue());
            if (nodeArtifact == null || nodeArtifact.withdrawn()) {
                throw ApiException.conflict("依赖节点不存在或已撤回: "
                        + node.getKey() + ":" + node.getValue());
            }
            List<ArtifactSignature> signatures =
                    repositoryDao.listSignatures(nodeArtifact.id());
            Long policyVersion = null;
            List<String> countedKeyIds = List.of();
            if (policy != null) {
                SignatureGate.Result gateResult =
                        SignatureGate.evaluate(policy, revokedKeys, nodeArtifact, signatures);
                if (gateResult instanceof SignatureGate.Failed failed) {
                    throw ApiException.unprocessable(failureMessage(failed));
                }
                policyVersion = policy.policyVersion();
                countedKeyIds = ((SignatureGate.Passed) gateResult).countedKeyIds();
            }
            evidenceByName.put(node.getKey(), new NodeEvidence(
                    nodeArtifact.contentDigest(), policyVersion, countedKeyIds));
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        evidenceByName.forEach((n, evidence) -> repositoryDao.insertLockEntry(
                lockFileId, n, solution.get(n), evidence.contentDigest(),
                evidence.policyVersion(), String.join(",", evidence.signatureKeyIds())));

        List<LockEntryResponse> entries = evidenceByName.entrySet().stream()
                .map(e -> new LockEntryResponse(e.getKey(), solution.get(e.getKey()),
                        e.getValue().contentDigest(), e.getValue().policyVersion(),
                        e.getValue().signatureKeyIds()))
                .toList();
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    private record NodeEvidence(String contentDigest, Long policyVersion,
                                List<String> signatureKeyIds) {
    }

    private Set<String> loadRevokedKeys(Instant now) {
        Set<String> revoked = new HashSet<>();
        for (TrustedKey key : repositoryDao.listTrustedKeys()) {
            if (key.revokedAt() != null && !key.revokedAt().isAfter(now)) {
                revoked.add(key.keyId());
            }
        }
        return revoked;
    }

    private String failureMessage(SignatureGate.Failed failed) {
        String node = failed.nodeName() + ":" + failed.nodeVersion();
        return switch (failed.reason()) {
            case DIGEST_MISMATCH -> "节点 " + node + " 存在摘要与制品内容不符的可信签名";
            case INSUFFICIENT_SIGNATURES -> "节点 " + node + " 的合格签名数 "
                    + failed.qualifiedCount() + " 不足阈值 " + failed.threshold();
        };
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
        if (request.contentDigest() != null
                && !DIGEST_PATTERN.matcher(request.contentDigest()).matches()) {
            throw ApiException.badRequest("contentDigest 必须为 64 位十六进制 SHA-256 摘要");
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

    private void validatePolicyRequest(PublishPolicyRequest request) {
        if (request.policyVersion() <= 0) {
            throw ApiException.badRequest("policyVersion 必须为正整数");
        }
        List<String> keyIds = request.keyIds();
        if (keyIds.isEmpty() || keyIds.size() > 10) {
            throw ApiException.badRequest("可信 keyId 数量必须在 1～10 之间");
        }
        Set<String> unique = new HashSet<>();
        for (String keyId : keyIds) {
            if (keyId == null || keyId.isBlank()) {
                throw ApiException.badRequest("keyId 不能为空");
            }
            String normalized = keyId.trim();
            if (!KEY_ID_PATTERN.matcher(normalized).matches()) {
                throw ApiException.badRequest("keyId 字符集不合法: " + normalized);
            }
            if (!unique.add(normalized)) {
                throw ApiException.badRequest("可信 keyId 重复: " + normalized);
            }
        }
        if (request.thresholdM() < 1 || request.thresholdM() > unique.size()) {
            throw ApiException.badRequest(
                    "阈值 m 必须满足 1≤m≤key 数，当前 key 数=" + unique.size()
                            + "，m=" + request.thresholdM());
        }
        if (request.effectiveAt() == null) {
            throw ApiException.badRequest("effectiveAt 不能为空");
        }
    }

    /** 缺省摘要按规范化制品清单推导，显式摘要统一转小写。 */
    private String resolveDigest(RegisterArtifactRequest request) {
        if (request.contentDigest() == null || request.contentDigest().isBlank()) {
            List<DependencyRange> ranges = request.dependencies().stream()
                    .map(d -> new DependencyRange(d.name().trim(),
                            d.minimumVersion(), d.maximumVersion()))
                    .toList();
            return ArtifactVersion.canonicalDigest(
                    request.name().trim(), request.version(), ranges);
        }
        return request.contentDigest().trim().toLowerCase();
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

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(), e.contentDigest(),
                        e.policyVersion(), parseKeyIds(e.signatureKeyIds())))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private List<String> parseKeyIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(","));
    }

    private PolicyResponse toPolicyResponse(SignaturePolicy policy) {
        return new PolicyResponse(policy.policyVersion(), policy.keyIds(),
                policy.thresholdM(), policy.effectiveAt(), policy.createdAt());
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
