package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CapabilityExclusionRequest;
import com.example.starter.api.dto.CapabilityExclusionResponse;
import com.example.starter.api.dto.CapabilityPairView;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.CapabilityPair;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
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
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 *
 * <p>幂等身份为结构化 JSON（操作、操作者、资源与全部参数、期望版本）的 SHA-256，
 * 不拼接分隔符，避免参数内容造成的身份碰撞。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_CAPABILITIES_PER_ARTIFACT = 3;
    private static final int MAX_EXCLUSIONS = 10;
    private static final int MAX_CAPABILITY_LENGTH = 128;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_ADD_EXCLUSION = "ADD_EXCLUSION";
    private static final String OP_REMOVE_EXCLUSION = "REMOVE_EXCLUSION";

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
        Map<String, Object> identity = baseIdentity(OP_REGISTER, operator)
                .put("name", request.name().trim())
                .put("version", request.version())
                .put("dependencies", canonicalDependencies(request))
                .put("capabilities", canonicalCapabilities(request.capabilities()))
                .build();
        return executeIdempotent(requestId, OP_REGISTER, structuredHash(identity), 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String operator,
                                             String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        Map<String, Object> identity = baseIdentity(OP_WITHDRAW, operator)
                .put("name", name.trim())
                .put("version", version)
                .build();
        return executeIdempotent(requestId, OP_WITHDRAW, structuredHash(identity), 200,
                () -> doWithdraw(name.trim(), version), ArtifactResponse.class);
    }

    @Override
    public CapabilityExclusionResponse addExclusion(String requestId, String operator,
                                                    CapabilityExclusionRequest request) {
        requireRequestId(requestId);
        CapabilityPair pair = validateExclusionRequest(request);
        Map<String, Object> identity = baseIdentity(OP_ADD_EXCLUSION, operator)
                .put("capabilityA", pair.first())
                .put("capabilityB", pair.second())
                .build();
        return executeIdempotent(requestId, OP_ADD_EXCLUSION, structuredHash(identity), 201,
                () -> doAddExclusion(pair), CapabilityExclusionResponse.class);
    }

    @Override
    public CapabilityExclusionResponse removeExclusion(String requestId, String operator,
                                                       CapabilityExclusionRequest request) {
        requireRequestId(requestId);
        CapabilityPair pair = validateExclusionRequest(request);
        Map<String, Object> identity = baseIdentity(OP_REMOVE_EXCLUSION, operator)
                .put("capabilityA", pair.first())
                .put("capabilityB", pair.second())
                .build();
        return executeIdempotent(requestId, OP_REMOVE_EXCLUSION, structuredHash(identity), 200,
                () -> doRemoveExclusion(pair), CapabilityExclusionResponse.class);
    }

    @Override
    public List<CapabilityPairView> listExclusions() {
        return repositoryDao.listExclusions().stream()
                .map(p -> new CapabilityPairView(p.first(), p.second()))
                .toList();
    }

    @Override
    public LockFileResponse createLock(String requestId, String operator, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        Map<String, Object> identity = baseIdentity(OP_LOCK, operator)
                .put("rootName", request.rootName().trim())
                .put("rootVersion", request.rootVersion())
                .put("expectedRepositoryVersion", request.expectedRepositoryVersion())
                .build();
        return executeIdempotent(requestId, OP_LOCK, structuredHash(identity), 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row));
        }
        return result;
    }

    @Override
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row);
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
        List<String> capabilities = canonicalCapabilities(request.capabilities());
        for (String capability : capabilities) {
            repositoryDao.insertCapability(artifactId, capability);
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, repositoryVersion, now,
                toDependencyViews(request.dependencies()), capabilities);
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
                Instant.now(clock), toDependencyViews(artifact.dependencies()),
                List.copyOf(artifact.capabilities()));
    }

    private CapabilityExclusionResponse doAddExclusion(CapabilityPair pair) {
        if (repositoryDao.exclusionExists(pair.first(), pair.second())) {
            throw ApiException.conflict(
                    "互斥规则已存在: " + pair.first() + " <-> " + pair.second());
        }
        if (repositoryDao.countExclusions() >= MAX_EXCLUSIONS) {
            throw ApiException.unprocessable("互斥规则数量已达上限 " + MAX_EXCLUSIONS);
        }
        repositoryDao.insertExclusion(pair.first(), pair.second(), Instant.now(clock));
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new CapabilityExclusionResponse(pair.first(), pair.second(), true, repositoryVersion);
    }

    private CapabilityExclusionResponse doRemoveExclusion(CapabilityPair pair) {
        if (!repositoryDao.exclusionExists(pair.first(), pair.second())) {
            throw ApiException.notFound(
                    "互斥规则不存在: " + pair.first() + " <-> " + pair.second());
        }
        repositoryDao.deleteExclusion(pair.first(), pair.second());
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new CapabilityExclusionResponse(pair.first(), pair.second(), false, repositoryVersion);
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
                    "不存在满足全部依赖区间与能力互斥约束的未撤回版本组合，无法锁定");
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);

        // 冻结每个入选制品当时声明的能力。
        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((name, version) -> {
            ArtifactVersion selected = findInSnapshot(snapshot, name, version);
            List<String> capabilities = selected == null
                    ? List.of() : List.copyOf(selected.capabilities());
            long entryId = repositoryDao.insertLockEntry(lockFileId, name, version);
            for (String capability : capabilities) {
                repositoryDao.insertLockEntryCapability(entryId, capability);
            }
            entries.add(new LockEntryResponse(name, version, capabilities));
        });

        // 冻结当时的全部互斥规则；之后的规则新增/撤销不影响本锁文件。
        List<CapabilityPairView> frozenExclusions = new ArrayList<>();
        for (CapabilityPair pair : snapshot.exclusions()) {
            repositoryDao.insertLockFileExclusion(lockFileId, pair.first(), pair.second());
            frozenExclusions.add(new CapabilityPairView(pair.first(), pair.second()));
        }
        frozenExclusions.sort((a, b) -> a.capabilityA().equals(b.capabilityA())
                ? a.capabilityB().compareTo(b.capabilityB())
                : a.capabilityA().compareTo(b.capabilityA()));

        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                entries, List.copyOf(frozenExclusions));
    }

    private static ArtifactVersion findInSnapshot(RepositorySnapshot snapshot,
                                                  String name, int version) {
        List<ArtifactVersion> versions = snapshot.artifacts().get(name);
        if (versions == null) {
            return null;
        }
        return versions.stream()
                .filter(a -> a.version() == version)
                .findFirst()
                .orElse(null);
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
        List<String> capabilities = request.capabilities();
        if (capabilities.size() > MAX_CAPABILITIES_PER_ARTIFACT) {
            throw ApiException.badRequest(
                    "每个制品版本最多声明 " + MAX_CAPABILITIES_PER_ARTIFACT + " 个能力");
        }
        Set<String> seen = new HashSet<>();
        for (String capability : capabilities) {
            validateCapabilityName(capability);
            if (!seen.add(capability.trim())) {
                throw ApiException.badRequest("能力名重复: " + capability.trim());
            }
        }
    }

    /** 校验互斥规则请求并返回规范化（字典序）能力对；能力相同不互斥。 */
    private CapabilityPair validateExclusionRequest(CapabilityExclusionRequest request) {
        if (request.capabilityA() == null || request.capabilityA().isBlank()
                || request.capabilityB() == null || request.capabilityB().isBlank()) {
            throw ApiException.badRequest("能力名不能为空");
        }
        String a = request.capabilityA().trim();
        String b = request.capabilityB().trim();
        validateCapabilityName(a);
        validateCapabilityName(b);
        if (a.equals(b)) {
            throw ApiException.badRequest("能力相同不互斥: " + a);
        }
        return CapabilityPair.of(a, b);
    }

    private static void validateCapabilityName(String capability) {
        if (capability == null || capability.isBlank()) {
            throw ApiException.badRequest("能力名不能为空");
        }
        if (capability.trim().length() > MAX_CAPABILITY_LENGTH) {
            throw ApiException.badRequest("能力名长度不能超过 " + MAX_CAPABILITY_LENGTH);
        }
    }

    /** 能力名去空白、去重并按字典序排列。 */
    private static List<String> canonicalCapabilities(List<String> capabilities) {
        return capabilities.stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }
    // ------------------------------------------------------------------
    // 幂等身份：结构化 JSON 摘要，避免分隔符碰撞
    // ------------------------------------------------------------------

    /** 幂等身份构建器：键固定排序，值保持原始类型。 */
    private IdentityBuilder baseIdentity(String operation, String operatorName) {
        return new IdentityBuilder()
                .put("operation", operation)
                .put("operator", normalizeOperator(operatorName));
    }

    /** 操作者标识：缺省为 anonymous，去首尾空白。 */
    private static String normalizeOperator(String operatorName) {
        if (operatorName == null || operatorName.isBlank()) {
            return "anonymous";
        }
        return operatorName.trim();
    }

    /** 将结构化身份序列化为规范 JSON 后计算 SHA-256。 */
    private String structuredHash(Map<String, Object> identity) {
        return sha256(writeJson(identity));
    }

    private static final class IdentityBuilder {
        private final Map<String, Object> values = new TreeMap<>();

        IdentityBuilder put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        Map<String, Object> build() {
            return values;
        }
    }

    private List<Map<String, Object>> canonicalDependencies(RegisterArtifactRequest request) {
        return request.dependencies().stream()
                .map(d -> {
                    Map<String, Object> dep = new TreeMap<>();
                    dep.put("name", d.name().trim());
                    dep.put("minimumVersion", d.minimumVersion());
                    dep.put("maximumVersion", d.maximumVersion());
                    return dep;
                })
                .sorted((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")))
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

    private LockFileResponse toLockResponse(LockFileRow row) {
        List<LockEntryRow> entries = repositoryDao.listLockEntries(row.id());
        Map<Long, List<String>> capabilities = repositoryDao.listLockEntryCapabilities(row.id());
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        List.copyOf(capabilities.getOrDefault(e.id(), List.of()))))
                .toList();
        List<CapabilityPairView> exclusions = repositoryDao.listLockFileExclusions(row.id())
                .stream()
                .map(p -> new CapabilityPairView(p.first(), p.second()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, exclusions);
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