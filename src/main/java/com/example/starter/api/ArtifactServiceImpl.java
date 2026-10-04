package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CapabilityPairView;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.ExclusionRuleRequest;
import com.example.starter.api.dto.ExclusionRuleResponse;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单一事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 *
 * <p>幂等身份为结构化 JSON（操作、操作者、资源与全部参数、期望版本）的 SHA-256，
 * 避免分隔符拼接造成的碰撞。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_CAPABILITIES_PER_VERSION = 3;
    private static final int MAX_EXCLUSION_PAIRS = 10;
    private static final int MAX_CAPABILITY_LENGTH = 64;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_ADD_EXCLUSION = "ADD_CAPABILITY_EXCLUSION";
    private static final String OP_REMOVE_EXCLUSION = "REMOVE_CAPABILITY_EXCLUSION";

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
        return executeIdempotent(requestId, OP_REGISTER, registerHash(request), 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        String trimmed = name.trim();
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("operation", OP_WITHDRAW);
        identity.put("resource", trimmed);
        identity.put("version", version);
        return executeIdempotent(requestId, OP_WITHDRAW, sha256(identity.toString()), 200,
                () -> doWithdraw(trimmed, version), ArtifactResponse.class);
    }

    @Override
    public ExclusionRuleResponse addExclusion(String requestId, String operator,
                                              ExclusionRuleRequest request) {
        requireRequestId(requestId);
        CapabilityPair pair = normalizedPair(request);
        String hash = exclusionHash(OP_ADD_EXCLUSION, operator, pair,
                request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_ADD_EXCLUSION, hash, 201,
                () -> doAddExclusion(pair, request.expectedRepositoryVersion()),
                ExclusionRuleResponse.class);
    }

    @Override
    public ExclusionRuleResponse removeExclusion(String requestId, String operator,
                                                 ExclusionRuleRequest request) {
        requireRequestId(requestId);
        CapabilityPair pair = normalizedPair(request);
        String hash = exclusionHash(OP_REMOVE_EXCLUSION, operator, pair,
                request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_REMOVE_EXCLUSION, hash, 200,
                () -> doRemoveExclusion(pair, request.expectedRepositoryVersion()),
                ExclusionRuleResponse.class);
    }

    @Override
    public List<CapabilityPairView> listExclusions() {
        return repositoryDao.loadExclusions().stream()
                .map(pair -> new CapabilityPairView(pair.first(), pair.second()))
                .toList();
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("operation", OP_LOCK);
        identity.put("resource", request.rootName().trim());
        identity.put("rootVersion", request.rootVersion());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return executeIdempotent(requestId, OP_LOCK, sha256(identity.toString()), 201,
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
        List<String> capabilities = normalizedCapabilities(request);
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
                sortedCapabilities(artifact.capabilities()));
    }

    private ExclusionRuleResponse doAddExclusion(CapabilityPair pair, long expectedVersion) {
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedVersion + ", actual=" + currentVersion);
        }
        if (repositoryDao.exclusionExists(pair)) {
            throw ApiException.conflict("互斥规则已存在: " + pair.first() + " <-> " + pair.second());
        }
        if (repositoryDao.countExclusions() >= MAX_EXCLUSION_PAIRS) {
            throw ApiException.unprocessable(
                    "互斥能力对数量已达上限 " + MAX_EXCLUSION_PAIRS);
        }
        Instant now = Instant.now(clock);
        repositoryDao.insertExclusion(pair, now);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ExclusionRuleResponse(pair.first(), pair.second(), repositoryVersion, now);
    }

    private ExclusionRuleResponse doRemoveExclusion(CapabilityPair pair, long expectedVersion) {
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != expectedVersion) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + expectedVersion + ", actual=" + currentVersion);
        }
        if (!repositoryDao.exclusionExists(pair)) {
            throw ApiException.notFound("互斥规则不存在: " + pair.first() + " <-> " + pair.second());
        }
        repositoryDao.deleteExclusion(pair);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ExclusionRuleResponse(pair.first(), pair.second(), repositoryVersion,
                Instant.now(clock));
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
                    "不存在满足全部依赖区间与能力互斥规则的未撤回版本组合，无法锁定");
        }

        // 取回每个被选中制品版本的能力声明，随锁文件冻结。
        Map<String, List<String>> capabilitiesByName = new HashMap<>();
        solution.forEach((name, version) -> snapshot.artifacts().get(name).stream()
                .filter(a -> a.version() == version)
                .findFirst()
                .ifPresent(a -> capabilitiesByName.put(name, sortedCapabilities(a.capabilities()))));

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((name, version) -> {
            repositoryDao.insertLockEntry(lockFileId, name, version);
            for (String capability : capabilitiesByName.getOrDefault(name, List.of())) {
                repositoryDao.insertLockEntryCapability(lockFileId, name, capability);
            }
        });
        // 冻结锁定时刻生效的互斥规则，后续规则调整不回改历史锁文件。
        for (CapabilityPair pair : snapshot.exclusions()) {
            repositoryDao.insertLockFileExclusion(lockFileId, pair);
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((name, version) -> entries.add(new LockEntryResponse(name, version,
                capabilitiesByName.getOrDefault(name, List.of()))));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                entries, toPairViews(snapshot.exclusions()));
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
     * 存在成功记录时：同操作同参数返回原结果；异参数（含异操作）返回 409。不存在返回 null。
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
        if (capabilities.size() > MAX_CAPABILITIES_PER_VERSION) {
            throw ApiException.badRequest(
                    "每个制品版本最多声明 " + MAX_CAPABILITIES_PER_VERSION + " 个能力");
        }
        Set<String> capabilityNames = new HashSet<>();
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                throw ApiException.badRequest("能力名不能为空");
            }
            String trimmed = capability.trim();
            if (trimmed.length() > MAX_CAPABILITY_LENGTH) {
                throw ApiException.badRequest("能力名长度不能超过 " + MAX_CAPABILITY_LENGTH);
            }
            if (!capabilityNames.add(trimmed)) {
                throw ApiException.badRequest("能力名重复: " + trimmed);
            }
        }
    }

    /**
     * 校验并规范化互斥规则请求：能力名去空白、非空、长度受限；
     * 能力相同不构成互斥；反向登记与正向为同一对。
     */
    private CapabilityPair normalizedPair(ExclusionRuleRequest request) {
        String capabilityA = request.capabilityA() == null ? "" : request.capabilityA().trim();
        String capabilityB = request.capabilityB() == null ? "" : request.capabilityB().trim();
        if (capabilityA.isEmpty() || capabilityB.isEmpty()) {
            throw ApiException.badRequest("能力名不能为空");
        }
        if (capabilityA.length() > MAX_CAPABILITY_LENGTH
                || capabilityB.length() > MAX_CAPABILITY_LENGTH) {
            throw ApiException.badRequest("能力名长度不能超过 " + MAX_CAPABILITY_LENGTH);
        }
        if (capabilityA.equals(capabilityB)) {
            throw ApiException.badRequest("能力相同不构成互斥: " + capabilityA);
        }
        return CapabilityPair.of(capabilityA, capabilityB);
    }

    /** 登记请求中的能力名：去空白、升序、去重（重复已在校验阶段拒绝）。 */
    private List<String> normalizedCapabilities(RegisterArtifactRequest request) {
        return request.capabilities().stream()
                .map(String::trim)
                .sorted()
                .toList();
    }

    private static List<String> sortedCapabilities(List<String> capabilities) {
        return capabilities.stream().sorted().toList();
    }

    /** 结构化幂等身份：操作 + 资源 + 全部参数，JSON 序列化后取 SHA-256。 */
    private String registerHash(RegisterArtifactRequest request) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("operation", OP_REGISTER);
        identity.put("resource", request.name().trim());
        identity.put("version", request.version());
        ArrayNode dependencies = identity.putArray("dependencies");
        request.dependencies().stream()
                .sorted(Comparator.comparing(dep -> dep.name().trim()))
                .forEach(dep -> {
                    ObjectNode node = dependencies.addObject();
                    node.put("name", dep.name().trim());
                    node.put("minimumVersion", dep.minimumVersion());
                    node.put("maximumVersion", dep.maximumVersion());
                });
        ArrayNode capabilities = identity.putArray("capabilities");
        normalizedCapabilities(request).forEach(capabilities::add);
        return sha256(identity.toString());
    }

    /** 结构化幂等身份：操作 + 操作者 + 互斥对（无序）+ 期望仓库版本。 */
    private String exclusionHash(String operation, String operator, CapabilityPair pair,
                                 long expectedRepositoryVersion) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("operation", operation);
        identity.put("operator", operator == null ? "" : operator);
        identity.put("capabilityA", pair.first());
        identity.put("capabilityB", pair.second());
        identity.put("expectedRepositoryVersion", expectedRepositoryVersion);
        return sha256(identity.toString());
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

    private static List<CapabilityPairView> toPairViews(Iterable<CapabilityPair> pairs) {
        List<CapabilityPairView> views = new ArrayList<>();
        for (CapabilityPair pair : pairs) {
            views.add(new CapabilityPairView(pair.first(), pair.second()));
        }
        return List.copyOf(views);
    }

    private LockFileResponse toLockResponse(LockFileRow row) {
        List<LockEntryRow> entries = repositoryDao.listLockEntries(row.id());
        Map<String, List<String>> capabilities = repositoryDao.listLockEntryCapabilities(row.id());
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        capabilities.getOrDefault(e.name(), List.of())))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews,
                toPairViews(repositoryDao.listLockFileExclusions(row.id())));
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