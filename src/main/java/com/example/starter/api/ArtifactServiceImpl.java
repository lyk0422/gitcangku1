package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SubstitutionStepResponse;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.PolicyValidator;
import com.example.starter.domain.RejectedCandidate;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.ResolutionResult;
import com.example.starter.domain.SubstitutionPolicy;
import com.example.starter.domain.SubstitutionResolver;
import com.example.starter.domain.SubstitutionRule;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.StepRow;
import com.example.starter.support.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * 制品登记、撤回、恢复、策略激活与锁定因此严格按提交顺序串行，
 * 锁图在同一事务快照内读取制品、撤回状态与唯一 policyVersion，不会混用两个策略版本。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_RESTORE = "RESTORE_ARTIFACT";
    private static final String OP_POLICY = "PUBLISH_POLICY";
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
        String hash = sha256(OP_REGISTER + "|" + request.name().trim() + "|" + request.version() + "|"
                + canonicalDependencies(request) + "|" + canonicalPlatforms(request.platforms()));
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
                () -> doWithdraw(name.trim(), version, false), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse restoreArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String hash = sha256(OP_RESTORE + "|" + name.trim() + "|" + version);
        return executeIdempotent(requestId, OP_RESTORE, hash, 200,
                () -> doWithdraw(name.trim(), version, true), ArtifactResponse.class);
    }

    @Override
    public PolicyResponse publishPolicy(String requestId, PublishPolicyRequest request) {
        requireRequestId(requestId);
        validatePolicyRequest(request);
        String hash = sha256(OP_POLICY + "|" + request.policyKey().trim() + "|"
                + canonicalPolicyRules(request));
        return executeIdempotent(requestId, OP_POLICY, hash, 201,
                () -> doPublishPolicy(request), PolicyResponse.class);
    }

    @Override
    public List<PolicyResponse> listPolicies() {
        List<PolicyResponse> result = new ArrayList<>();
        for (SubstitutionPolicy header : repositoryDao.listPolicyHeaders()) {
            SubstitutionPolicy full = repositoryDao.loadPolicy(header.policyVersion());
            result.add(toPolicyResponse(full));
        }
        return result;
    }

    @Override
    public PolicyResponse getPolicy(long policyVersion) {
        SubstitutionPolicy policy = repositoryDao.loadPolicy(policyVersion);
        if (policy == null) {
            throw ApiException.notFound("策略版本不存在: " + policyVersion);
        }
        return toPolicyResponse(policy);
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String platform = request.platform() == null || request.platform().isBlank()
                ? null : request.platform().trim();
        String hash = sha256(OP_LOCK + "|" + request.rootName().trim() + "|" + request.rootVersion()
                + "|" + request.expectedRepositoryVersion() + "|" + (platform == null ? "" : platform));
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
                () -> doLock(request, platform), LockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row,
                    repositoryDao.listLockEntries(row.id()),
                    repositoryDao.listSubstitutionSteps(row.id())));
        }
        return result;
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row, repositoryDao.listLockEntries(id),
                repositoryDao.listSubstitutionSteps(id));
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

        List<String> platforms = normalizePlatforms(request.platforms());
        Instant now = Instant.now(clock);
        long artifactId = repositoryDao.insertArtifact(name, version, now);
        for (var dep : request.dependencies()) {
            repositoryDao.insertDependency(artifactId, dep.name().trim(),
                    dep.minimumVersion(), dep.maximumVersion());
        }
        for (String platform : platforms) {
            repositoryDao.insertPlatform(artifactId, platform);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, repositoryVersion, now,
                toDependencyViews(request.dependencies()), List.copyOf(platforms));
    }

    private ArtifactResponse doWithdraw(String name, int version, boolean restore) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        int affected;
        if (restore) {
            if (!artifact.withdrawn()) {
                throw ApiException.conflict("制品版本未撤回，无需恢复: " + name + ":" + version);
            }
            affected = repositoryDao.restoreWithdrawn(artifact.id());
        } else {
            if (artifact.withdrawn()) {
                throw ApiException.conflict("制品版本已撤回: " + name + ":" + version);
            }
            affected = repositoryDao.markWithdrawn(artifact.id());
        }
        if (affected == 0) {
            // 并发抢先提交（持有行锁时理论上不会发生，防御性处理）。
            throw ApiException.conflict(
                    restore ? "制品版本已恢复: " + name + ":" + version
                            : "制品版本已撤回: " + name + ":" + version);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ArtifactResponse(name, version, !restore, repositoryVersion,
                Instant.now(clock), toDependencyViews(artifact.dependencies()),
                List.copyOf(artifact.platforms()));
    }

    private PolicyResponse doPublishPolicy(PublishPolicyRequest request) {
        String policyKey = request.policyKey().trim();
        if (repositoryDao.policyKeyExists(policyKey)) {
            throw ApiException.conflict("policyKey 已存在: " + policyKey);
        }
        try {
            PolicyValidator.validate(request.rules());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage());
        }

        Instant now = Instant.now(clock);
        long policyVersion = repositoryDao.insertPolicy(policyKey, now);
        for (int i = 0; i < request.rules().size(); i++) {
            PublishPolicyRequest.RuleSpec rule = request.rules().get(i);
            long ruleId = repositoryDao.insertRule(policyVersion, i,
                    rule.sourcePattern().trim(), rule.targetPlatform().trim(),
                    rule.effectiveAt());
            for (int p = 0; p < rule.alternatives().size(); p++) {
                PublishPolicyRequest.AlternativeSpec alt = rule.alternatives().get(p);
                repositoryDao.insertAlternative(ruleId, p + 1, alt.name().trim(), alt.version());
            }
        }
        // 策略激活推进仓库版本：后续锁定的期望版本必须观察到新策略。
        repositoryDao.incrementRepositoryVersion();
        return toPolicyResponse(repositoryDao.loadPolicy(policyVersion));
    }

    private LockFileResponse doLock(LockRequest request, String platform) {
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

        // 同一事务快照内读取制品与唯一当前策略版本。
        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        Instant now = Instant.now(clock);

        final Map<String, Integer> solution;
        final List<com.example.starter.domain.SubstitutionStep> steps;
        final Long policyVersion;
        if (platform == null) {
            Map<String, Integer> resolved = LockResolver.resolve(snapshot, rootName, rootVersion);
            if (resolved == null) {
                throw ApiException.unprocessable(
                        "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
            }
            solution = resolved;
            steps = List.of();
            policyVersion = null;
        } else {
            SubstitutionPolicy policy = repositoryDao.loadLatestPolicy();
            ResolutionResult result;
            try {
                result = SubstitutionResolver.resolve(
                        snapshot, rootName, rootVersion, policy, platform, now);
            } catch (SubstitutionResolver.SubstitutionConflictException e) {
                throw ApiException.unprocessable(e.getMessage());
            }
            if (result == null || result.solution() == null) {
                throw ApiException.unprocessable(
                        "不存在满足全部依赖区间与替代策略的可用版本组合，无法锁定");
            }
            solution = result.solution();
            steps = result.steps();
            policyVersion = result.policyVersion();
        }

        Instant createdAt = now;
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), platform, policyVersion, createdAt);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            repositoryDao.insertSubstitutionStep(lockFileId, i,
                    step.original().name(), step.original().version(),
                    step.ruleId(), step.ruleIndex(), step.sourcePattern(),
                    step.finalCoordinate().name(), step.finalCoordinate().version(),
                    writeJson(step.rejected()), policyVersion == null ? -1L : policyVersion);
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, createdAt,
                entries, platform, policyVersion, toStepResponses(steps));
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
        normalizePlatforms(request.platforms());
    }

    private void validatePolicyRequest(PublishPolicyRequest request) {
        if (request.policyKey() == null || request.policyKey().isBlank()) {
            throw ApiException.badRequest("policyKey 不能为空");
        }
        Set<String> seenAlternatives = new HashSet<>();
        for (PublishPolicyRequest.RuleSpec rule : request.rules()) {
            if (rule.targetPlatform() == null || rule.targetPlatform().isBlank()) {
                throw ApiException.badRequest("targetPlatform 不能为空");
            }
            seenAlternatives.clear();
            for (PublishPolicyRequest.AlternativeSpec alternative : rule.alternatives()) {
                if (alternative.name() == null || alternative.name().isBlank()) {
                    throw ApiException.badRequest("替代坐标名称不能为空");
                }
                if (!seenAlternatives.add(
                        alternative.name().trim() + ":" + alternative.version())) {
                    throw ApiException.badRequest("规则内替代坐标重复: "
                            + alternative.name().trim() + ":" + alternative.version());
                }
            }
        }
    }

    private List<String> normalizePlatforms(List<String> raw) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String platform : raw) {
            if (platform == null || platform.isBlank()) {
                throw ApiException.badRequest("平台标识不能为空");
            }
            String trimmed = platform.trim();
            if (sorted.putIfAbsent(trimmed, trimmed) != null) {
                throw ApiException.badRequest("平台清单重复: " + trimmed);
            }
        }
        return new ArrayList<>(sorted.values());
    }

    private String canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> d.name().trim() + ":" + d.minimumVersion() + ":" + d.maximumVersion())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private String canonicalPlatforms(List<String> platforms) {
        return platforms.stream().map(String::trim).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private String canonicalPolicyRules(PublishPolicyRequest request) {
        return request.rules().stream()
                .map(r -> r.sourcePattern().trim() + ">" + r.targetPlatform().trim() + "@"
                        + r.effectiveAt() + "=" + r.alternatives().stream()
                        .map(a -> a.name().trim() + ":" + a.version())
                        .reduce((a, b) -> a + "," + b).orElse(""))
                .reduce((a, b) -> a + "|" + b)
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

    private PolicyResponse toPolicyResponse(SubstitutionPolicy policy) {
        List<PolicyResponse.RuleView> rules = policy.rules().stream()
                .map(this::toRuleView)
                .toList();
        return new PolicyResponse(policy.policyVersion(), policy.policyKey(),
                policy.createdAt(), rules);
    }

    private PolicyResponse.RuleView toRuleView(SubstitutionRule rule) {
        List<PolicyResponse.CoordinateView> alternatives = rule.alternatives().stream()
                .map(c -> new PolicyResponse.CoordinateView(c.name(), c.version()))
                .toList();
        return new PolicyResponse.RuleView(rule.ruleIndex(), rule.sourcePattern(),
                rule.targetPlatform(), rule.effectiveAt(), alternatives);
    }

    private List<SubstitutionStepResponse> toStepResponses(
            List<com.example.starter.domain.SubstitutionStep> steps) {
        List<SubstitutionStepResponse> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            List<SubstitutionStepResponse.RejectedCandidateView> rejected = step.rejected().stream()
                    .map(r -> new SubstitutionStepResponse.RejectedCandidateView(
                            r.name(), r.version(), r.reason()))
                    .toList();
            result.add(new SubstitutionStepResponse(i,
                    step.original().name(), step.original().version(),
                    step.ruleIndex(), step.sourcePattern(),
                    step.finalCoordinate().name(), step.finalCoordinate().version(),
                    step.policyVersion(), rejected));
        }
        return List.copyOf(result);
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<StepRow> steps) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<SubstitutionStepResponse> stepViews = new ArrayList<>();
        for (StepRow step : steps) {
            List<RejectedCandidate> rejected = readRejected(step.rejectedCandidatesJson());
            List<SubstitutionStepResponse.RejectedCandidateView> rejectedViews = rejected.stream()
                    .map(r -> new SubstitutionStepResponse.RejectedCandidateView(
                            r.name(), r.version(), r.reason()))
                    .toList();
            stepViews.add(new SubstitutionStepResponse(step.stepIndex(),
                    step.originalName(), step.originalVersion(),
                    step.ruleIndex(), step.sourcePattern(),
                    step.finalName(), step.finalVersion(),
                    step.policyVersion(), rejectedViews));
        }
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews,
                row.platform(), row.policyVersion(), List.copyOf(stepViews));
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

    private List<RejectedCandidate> readRejected(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RejectedCandidate>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("替代拒绝原因反序列化失败", e);
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
