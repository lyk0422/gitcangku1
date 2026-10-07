package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CoordinationGroupRequest;
import com.example.starter.api.dto.CoordinationGroupResponse;
import com.example.starter.api.dto.CoordinationHitView;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.TupleEntrySpec;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.LockSolution;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.CoordinationGroupRecord;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.LockHitRow;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 制品仓库业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行仓库版本表互斥并发写，
 * 再做业务变更并写入幂等成功记录，原子提交；业务失败整体回滚，不占用 requestId。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_COORDINATION_GROUPS = 3;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_UPSERT_GROUP = "UPSERT_COORDINATION_GROUP";

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
    public CoordinationGroupResponse upsertCoordinationGroup(String requestId,
                                                             CoordinationGroupRequest request) {
        requireRequestId(requestId);
        validateGroupRequest(request);
        // 结构化身份：操作 + 资源 + 非空操作者 + 全参数（成员/元组规范化排序）+ 期望版本。
        String hash = sha256(writeJson(canonicalGroupIdentity(request)));
        return executeIdempotent(requestId, OP_UPSERT_GROUP, hash, 201,
                () -> doUpsertGroup(request), CoordinationGroupResponse.class);
    }

    @Override
    public List<CoordinationGroupResponse> listCoordinationGroups() {
        return repositoryDao.listCoordinationGroups().stream()
                .map(this::toGroupResponse)
                .toList();
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
        LockSolution solution = LockResolver.resolveSolution(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间与协调组约束的未撤回版本组合，无法锁定");
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.versions().forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        // 冻结命中依据：组与允许元组此后修订/删除都不影响本锁。
        for (LockSolution.CoordinationHit hit : solution.hits()) {
            repositoryDao.insertLockCoordinationHit(lockFileId, hit.groupId(),
                    writeJson(toTupleEntries(hit.tuple())));
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.versions().forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                entries, toHitViews(solution.hits()));
    }

    private CoordinationGroupResponse doUpsertGroup(CoordinationGroupRequest request) {
        String groupId = request.groupId().trim();
        String operator = request.operator().trim();

        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

        // 允许元组引用的版本必须已登记（撤回版本保留在历史元组中，仍视为存在）。
        for (List<TupleEntrySpec> tuple : request.tuples()) {
            for (TupleEntrySpec entry : tuple) {
                if (!repositoryDao.artifactExists(entry.name(), entry.version())) {
                    throw ApiException.notFound("允许元组引用的制品版本不存在: "
                            + entry.name() + ":" + entry.version());
                }
            }
        }

        CoordinationGroupRecord existing = repositoryDao.findCoordinationGroup(groupId);
        if (existing == null && repositoryDao.countCoordinationGroups() >= MAX_COORDINATION_GROUPS) {
            throw ApiException.unprocessable(
                    "协调组数量已达上限 " + MAX_COORDINATION_GROUPS);
        }

        Instant now = Instant.now(clock);
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        long groupRef;
        if (existing == null) {
            groupRef = repositoryDao.insertCoordinationGroup(groupId, operator,
                    repositoryVersion, now);
        } else {
            groupRef = existing.id();
            repositoryDao.replaceCoordinationGroupContent(groupRef, operator,
                    repositoryVersion, now);
        }

        List<String> members = normalizedMembers(request);
        for (String member : members) {
            repositoryDao.insertCoordinationMember(groupRef, member);
        }
        List<List<TupleEntrySpec>> tuples = normalizedTuples(request);
        for (List<TupleEntrySpec> tuple : tuples) {
            long tupleRef = repositoryDao.insertCoordinationTuple(groupRef, tupleKey(tuple));
            for (TupleEntrySpec entry : tuple) {
                repositoryDao.insertCoordinationTupleEntry(tupleRef, entry.name(), entry.version());
            }
        }

        return new CoordinationGroupResponse(groupId, operator, members,
                toTupleViews(tuples), repositoryVersion, now);
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
        List<CoordinationHitView> hitViews = repositoryDao.listLockCoordinationHits(row.id())
                .stream()
                .map(this::toHitView)
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, hitViews);
    }

    // ------------------------------------------------------------------
    // 协调组：校验、规范化与视图转换
    // ------------------------------------------------------------------

    /** 请求形状校验（不依赖数据库状态）；成员与元组数量边界在此兜底。 */
    private void validateGroupRequest(CoordinationGroupRequest request) {
        if (request.groupId() == null || request.groupId().isBlank()) {
            throw ApiException.badRequest("groupId 不能为空");
        }
        if (request.operator() == null || request.operator().isBlank()) {
            throw ApiException.badRequest("operator 不能为空");
        }
        List<String> members = request.members();
        if (members == null || members.size() < 2 || members.size() > 4) {
            throw ApiException.badRequest("协调组成员数量必须为 2 至 4");
        }
        Set<String> distinct = new HashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                throw ApiException.badRequest("协调组成员名称不能为空");
            }
            if (!distinct.add(member)) {
                throw ApiException.badRequest("协调组成员重复: " + member);
            }
        }
        List<List<TupleEntrySpec>> tuples = request.tuples();
        if (tuples == null || tuples.isEmpty() || tuples.size() > 5) {
            throw ApiException.badRequest("允许元组数量必须为 1 至 5");
        }
        Set<String> tupleKeys = new HashSet<>();
        for (List<TupleEntrySpec> tuple : tuples) {
            if (tuple == null || tuple.size() != members.size()) {
                throw ApiException.badRequest("每条允许元组必须完整列出组内全部成员");
            }
            Set<String> covered = new HashSet<>();
            for (TupleEntrySpec entry : tuple) {
                if (entry == null || entry.name() == null || entry.name().isBlank()) {
                    throw ApiException.badRequest("允许元组条目名称不能为空");
                }
                if (!distinct.contains(entry.name())) {
                    throw ApiException.badRequest("允许元组包含组外成员: " + entry.name());
                }
                if (!covered.add(entry.name())) {
                    throw ApiException.badRequest("允许元组内成员重复: " + entry.name());
                }
            }
            if (!covered.equals(distinct)) {
                throw ApiException.badRequest("每条允许元组必须完整列出组内全部成员");
            }
            if (!tupleKeys.add(tupleKey(sortedTuple(tuple)))) {
                throw ApiException.badRequest("允许元组重复: " + tupleKey(sortedTuple(tuple)));
            }
        }
    }

    /** 成员按名称升序规范化（换序同参）。 */
    private List<String> normalizedMembers(CoordinationGroupRequest request) {
        return request.members().stream().sorted().toList();
    }

    /** 元组规范化：每条元组内按名称升序，元组之间按规范化键升序（换序同参）。 */
    private List<List<TupleEntrySpec>> normalizedTuples(CoordinationGroupRequest request) {
        return request.tuples().stream()
                .map(this::sortedTuple)
                .sorted((a, b) -> tupleKey(a).compareTo(tupleKey(b)))
                .toList();
    }

    /** 单条元组按成员名称升序排序。 */
    private List<TupleEntrySpec> sortedTuple(List<TupleEntrySpec> tuple) {
        return tuple.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /** 规范化元组键：name=version 按名称升序拼接，用于唯一约束与重复判定。 */
    private String tupleKey(List<TupleEntrySpec> sortedTuple) {
        StringBuilder sb = new StringBuilder();
        for (TupleEntrySpec entry : sortedTuple) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.name()).append('=').append(entry.version());
        }
        return sb.toString();
    }

    /** 幂等身份：操作、资源、操作者、规范化全参数与期望版本的结构化表示。 */
    private Map<String, Object> canonicalGroupIdentity(CoordinationGroupRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_UPSERT_GROUP);
        identity.put("groupId", request.groupId().trim());
        identity.put("operator", request.operator().trim());
        identity.put("members", normalizedMembers(request));
        identity.put("tuples", normalizedTuples(request).stream()
                .map(this::toTupleEntries)
                .toList());
        identity.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return identity;
    }

    private List<LockEntryResponse> toTupleEntries(Map<String, Integer> tuple) {
        return tuple.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new LockEntryResponse(e.getKey(), e.getValue()))
                .toList();
    }

    private List<LockEntryResponse> toTupleEntries(List<TupleEntrySpec> sortedTuple) {
        return sortedTuple.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
    }

    private List<List<LockEntryResponse>> toTupleViews(List<List<TupleEntrySpec>> tuples) {
        return tuples.stream().map(this::toTupleEntries).toList();
    }

    private List<CoordinationHitView> toHitViews(List<LockSolution.CoordinationHit> hits) {
        return hits.stream()
                .map(h -> new CoordinationHitView(h.groupId(), toTupleEntries(h.tuple())))
                .toList();
    }

    private CoordinationHitView toHitView(LockHitRow row) {
        return new CoordinationHitView(row.groupId(), readTupleEntries(row.tupleJson()));
    }

    private CoordinationGroupResponse toGroupResponse(CoordinationGroupRecord record) {
        List<List<LockEntryResponse>> tupleViews = record.tuples().stream()
                .map(this::toTupleEntries)
                .toList();
        return new CoordinationGroupResponse(record.groupId(), record.operator(),
                record.members(), tupleViews, record.repositoryVersion(),
                record.createdAt());
    }

    @SuppressWarnings("unchecked")
    private List<LockEntryResponse> readTupleEntries(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, List.class);
            List<LockEntryResponse> entries = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                entries.add(new LockEntryResponse(
                        (String) item.get("name"), ((Number) item.get("version")).intValue()));
            }
            return List.copyOf(entries);
        } catch (Exception e) {
            throw new IllegalStateException("命中元组反序列化失败", e);
        }
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
