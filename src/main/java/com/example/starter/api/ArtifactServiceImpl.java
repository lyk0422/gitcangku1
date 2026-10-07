package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CoordinationGroupRequest;
import com.example.starter.api.dto.CoordinationGroupResponse;
import com.example.starter.api.dto.CoordinationHitResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.CoordinationGroup;
import com.example.starter.domain.CoordinationTuples;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.CoordinationGroupRow;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockCoordinationHitRow;
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
import java.util.Comparator;
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
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_COORDINATION_GROUPS = 3;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_REGISTER_GROUP = "REGISTER_COORDINATION_GROUP";

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
            result.add(toLockResponse(row, repositoryDao.listLockEntries(row.id()),
                    repositoryDao.listLockCoordinationHits(row.id())));
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
                repositoryDao.listLockCoordinationHits(id));
    }

    @Override
    public CoordinationGroupResponse registerCoordinationGroup(String requestId,
                                                               CoordinationGroupRequest request) {
        requireRequestId(requestId);
        validateCoordinationGroupRequest(request);
        String hash = coordinationGroupHash(request);
        return executeIdempotent(requestId, OP_REGISTER_GROUP, hash, 201,
                () -> doRegisterCoordinationGroup(request), CoordinationGroupResponse.class);
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
        Map<String, Integer> solution = LockResolver.resolve(snapshot, rootName, rootVersion);
        if (solution == null) {
            throw ApiException.unprocessable(
                    "不存在满足全部依赖区间的未撤回版本组合，无法锁定");
        }

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        List<CoordinationHitResponse> hits =
                freezeCoordinationHits(lockFileId, snapshot.coordinationGroups(), solution);
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                entries, hits);
    }

    /**
     * 冻结本次锁定命中的协调组依据：锁图中含某组至少 2 名成员时，
     * 记录该组当前修订号、允许元组全集与命中元组；解析器已保证必有命中元组。
     */
    private List<CoordinationHitResponse> freezeCoordinationHits(
            long lockFileId, List<CoordinationGroup> groups, Map<String, Integer> solution) {
        List<CoordinationHitResponse> hits = new ArrayList<>();
        for (CoordinationGroup group : groups) {
            Map<String, Integer> chosenInGroup = new TreeMap<>();
            for (String member : group.members()) {
                Integer version = solution.get(member);
                if (version != null) {
                    chosenInGroup.put(member, version);
                }
            }
            if (chosenInGroup.size() < 2) {
                continue;
            }
            Map<String, Integer> matched = LockResolver.matchedTuple(group, chosenInGroup);
            if (matched == null) {
                // 解析结果必然满足投影约束，此处为防御性检查。
                throw new IllegalStateException("锁定结果未命中协调组 " + group.name() + " 的任何允许元组");
            }
            repositoryDao.insertLockCoordinationHit(lockFileId, group.name(), group.revision(),
                    CoordinationTuples.canonicalTuple(matched),
                    CoordinationTuples.canonicalTuples(group.allowedTuples()));
            hits.add(new CoordinationHitResponse(group.name(), group.revision(),
                    new TreeMap<>(matched), group.allowedTuples()));
        }
        return List.copyOf(hits);
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

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<LockCoordinationHitRow> hitRows) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<CoordinationHitResponse> hits = hitRows.stream()
                .map(h -> new CoordinationHitResponse(h.groupName(), h.groupRevision(),
                        CoordinationTuples.parseTuple(h.matchedTuple()),
                        CoordinationTuples.parseTuples(h.allowedTuples())))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, hits);
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

    // ------------------------------------------------------------------
    // 协调组登记/修订
    // ------------------------------------------------------------------

    /**
     * 登记或修订协调组（运行在已加行锁的写事务内）：
     * 同一仓库版本视图下校验期望版本、元组引用存在性，再整体替换组规则并推进仓库版本。
     */
    private CoordinationGroupResponse doRegisterCoordinationGroup(CoordinationGroupRequest request) {
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

        String groupName = request.groupName().trim();
        List<String> members = sortedMembers(request);
        List<Map<String, Integer>> tuples = canonicalGroupTuples(request);

        // 允许元组引用的制品版本必须已登记（撤回版本可保留在历史元组中）。
        for (Map<String, Integer> tuple : tuples) {
            for (Map.Entry<String, Integer> entry : tuple.entrySet()) {
                if (!repositoryDao.artifactExists(entry.getKey(), entry.getValue())) {
                    throw ApiException.notFound("允许元组引用的制品版本不存在: "
                            + entry.getKey() + ":" + entry.getValue());
                }
            }
        }

        Instant now = Instant.now(clock);
        CoordinationGroupRow existing = repositoryDao.findCoordinationGroup(groupName);
        long groupId;
        int revision;
        if (existing == null) {
            if (repositoryDao.countCoordinationGroups() >= MAX_COORDINATION_GROUPS) {
                throw ApiException.unprocessable(
                        "协调组数量已达上限 " + MAX_COORDINATION_GROUPS);
            }
            groupId = repositoryDao.insertCoordinationGroup(groupName, now);
            revision = 1;
        } else {
            groupId = existing.id();
            revision = existing.revision() + 1;
            repositoryDao.reviseCoordinationGroup(groupId, revision, now);
            repositoryDao.deleteGroupMembersAndTuples(groupId);
        }
        for (String member : members) {
            repositoryDao.insertGroupMember(groupId, member);
        }
        int tupleNo = 1;
        for (Map<String, Integer> tuple : tuples) {
            for (Map.Entry<String, Integer> entry : tuple.entrySet()) {
                repositoryDao.insertGroupTuple(groupId, tupleNo, entry.getKey(), entry.getValue());
            }
            tupleNo++;
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new CoordinationGroupResponse(groupName, revision, repositoryVersion,
                members, tuples, now);
    }

    /**
     * 协调组请求校验：成员 2 至 4 个不同制品名，允许元组 1 至 5 条、
     * 每条完整覆盖全部成员且不重复。
     */
    private void validateCoordinationGroupRequest(CoordinationGroupRequest request) {
        if (request.groupName() == null || request.groupName().isBlank()) {
            throw ApiException.badRequest("groupName 不能为空");
        }
        if (request.operator() == null || request.operator().isBlank()) {
            throw ApiException.badRequest("operator 不能为空");
        }
        List<String> rawMembers = request.members();
        if (rawMembers == null || rawMembers.size() < 2 || rawMembers.size() > 4) {
            throw ApiException.badRequest("协调组成员数量须为 2 至 4");
        }
        Set<String> memberSet = new HashSet<>();
        for (String raw : rawMembers) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("成员名称不能为空");
            }
            String name = raw.trim();
            if (name.chars().anyMatch(c -> c == ',' || c == ';' || c == '=')) {
                throw ApiException.badRequest("成员名称不能包含 , ; = 字符: " + name);
            }
            if (!memberSet.add(name)) {
                throw ApiException.badRequest("成员名称重复: " + name);
            }
        }
        List<Map<String, Integer>> rawTuples = request.allowedTuples();
        if (rawTuples == null || rawTuples.isEmpty() || rawTuples.size() > 5) {
            throw ApiException.badRequest("允许元组数量须为 1 至 5");
        }
        Set<String> seenTuples = new HashSet<>();
        for (Map<String, Integer> tuple : rawTuples) {
            if (tuple == null) {
                throw ApiException.badRequest("允许元组不能为 null");
            }
            Map<String, Integer> normalized = new TreeMap<>();
            for (Map.Entry<String, Integer> entry : tuple.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw ApiException.badRequest("允许元组的成员名称不能为空");
                }
                if (entry.getValue() == null || entry.getValue() < 1) {
                    throw ApiException.badRequest("允许元组的版本必须为正整数");
                }
                normalized.put(entry.getKey().trim(), entry.getValue());
            }
            if (!normalized.keySet().equals(memberSet)) {
                throw ApiException.badRequest("每条允许元组必须完整列出全部成员版本");
            }
            if (!seenTuples.add(CoordinationTuples.canonicalTuple(normalized))) {
                throw ApiException.badRequest("允许元组重复: "
                        + CoordinationTuples.canonicalTuple(normalized));
            }
        }
    }

    /** 成员名称去空白并升序，换序书写视为同参。 */
    private static List<String> sortedMembers(CoordinationGroupRequest request) {
        return request.members().stream().map(String::trim).sorted().toList();
    }

    /** 允许元组规范化：成员键去空白、组内按键升序、元组间按规范化文本升序，换序书写视为同参。 */
    private static List<Map<String, Integer>> canonicalGroupTuples(CoordinationGroupRequest request) {
        return request.allowedTuples().stream()
                .map(tuple -> {
                    Map<String, Integer> normalized = new TreeMap<>();
                    tuple.forEach((name, version) -> normalized.put(name.trim(), version));
                    return normalized;
                })
                .sorted(Comparator.comparing(CoordinationTuples::canonicalTuple))
                .toList();
    }

    /**
     * 协调组幂等身份：操作、非空操作者、组名、规范化成员与元组、期望仓库版本，
     * 长度前缀结构化拼接防碰撞；版本按十进制值、文本原样。
     */
    private static String coordinationGroupHash(CoordinationGroupRequest request) {
        StringBuilder sb = new StringBuilder();
        appendHashPart(sb, OP_REGISTER_GROUP);
        appendHashPart(sb, request.operator().trim());
        appendHashPart(sb, request.groupName().trim());
        for (String member : sortedMembers(request)) {
            appendHashPart(sb, member);
        }
        for (Map<String, Integer> tuple : canonicalGroupTuples(request)) {
            appendHashPart(sb, CoordinationTuples.canonicalTuple(tuple));
        }
        appendHashPart(sb, String.valueOf(request.expectedRepositoryVersion()));
        return sha256(sb.toString());
    }

    private static void appendHashPart(StringBuilder sb, String part) {
        sb.append(part.length()).append(':').append(part).append('\n');
    }
}
