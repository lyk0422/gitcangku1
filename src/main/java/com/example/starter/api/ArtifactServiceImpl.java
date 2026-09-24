package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReresolveDiffResponse;
import com.example.starter.api.dto.ReresolveReportResponse;
import com.example.starter.api.dto.ReresolveRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.ReresolveReportRow;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_RERESOLVE = "RERESOLVE_LOCK";

    /** 重解析结论：新解析集合与原锁文件逐名称逐版本一致。 */
    static final String CONCLUSION_REPRODUCIBLE = "REPRODUCIBLE";
    /** 重解析结论：可行但存在差异。 */
    static final String CONCLUSION_DRIFTED = "DRIFTED";
    /** 重解析结论：没有任何可行组合。 */
    static final String CONCLUSION_INFEASIBLE = "INFEASIBLE";

    /** 漂移原因：原候选被撤回。 */
    static final String DRIFT_WITHDRAWN = "WITHDRAWN";
    /** 漂移原因：被更高版本取代。 */
    static final String DRIFT_SUPERSEDED = "SUPERSEDED";
    /** 漂移原因：依赖区间不再满足（含新增/移除：原解析的依赖闭包已不匹配当前约束）。 */
    static final String DRIFT_RANGE_NOT_SATISFIED = "RANGE_NOT_SATISFIED";

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
    public LockFileResponse getLock(long id) {
        LockFileRow row = repositoryDao.getLockFile(id);
        if (row == null) {
            throw ApiException.notFound("锁文件不存在: " + id);
        }
        return toLockResponse(row, repositoryDao.listLockEntries(id));
    }

    @Override
    public ReresolveReportResponse reresolveLock(String reresolveKey, ReresolveRequest request) {
        if (reresolveKey == null || reresolveKey.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Reresolve-Key");
        }
        if (request.lockId() == null || request.expectedRepositoryVersion() == null) {
            throw ApiException.badRequest("lockId 与 expectedRepositoryVersion 不能为空");
        }
        String hash = sha256(OP_RERESOLVE + "|" + request.lockId()
                + "|" + request.expectedRepositoryVersion());
        return executeIdempotent(reresolveKey, OP_RERESOLVE, hash, 201,
                () -> doReresolve(request), ReresolveReportResponse.class);
    }

    @Override
    public ReresolveReportResponse getReresolveReport(long id) {
        ReresolveReportRow row = repositoryDao.getReresolveReport(id);
        if (row == null) {
            throw ApiException.notFound("重解析报告不存在: " + id);
        }
        return toReportResponse(row);
    }

    @Override
    public List<ReresolveReportResponse> listReresolveReports(long lockId) {
        List<ReresolveReportResponse> result = new ArrayList<>();
        for (ReresolveReportRow row : repositoryDao.listReresolveReportsByLock(lockId)) {
            result.add(toReportResponse(row));
        }
        return result;
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

        Instant now = now();
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
                now(), toDependencyViews(artifact.dependencies()));
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

        Instant now = now();
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
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
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews);
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    /**
     * 当前时间，截断到微秒：与 H2/MySQL TIMESTAMP(6) 的存储精度对齐，
     * 保证内存返回值与数据库回读值一致。
     */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
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
    // 锁文件重解析（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    /**
     * 在同一事务内基于当前仓库状态重解析锁文件根版本，并固化不可变报告。
     * 原锁文件不被改写；重解析不推进仓库版本号，也不创建新锁文件。
     */
    private ReresolveReportResponse doReresolve(ReresolveRequest request) {
        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

        LockFileRow lock = repositoryDao.getLockFile(request.lockId());
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + request.lockId());
        }
        ArtifactVersion root = repositoryDao.loadArtifact(lock.rootName(), lock.rootVersion());
        if (root == null) {
            throw ApiException.notFound(
                    "根制品版本不存在: " + lock.rootName() + ":" + lock.rootVersion());
        }
        if (root.withdrawn()) {
            throw ApiException.unprocessable(
                    "根制品版本已撤回: " + lock.rootName() + ":" + lock.rootVersion());
        }

        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        LockResolver.Resolution resolution =
                LockResolver.resolveDetailed(snapshot, lock.rootName(), lock.rootVersion());

        Map<String, Integer> original = new TreeMap<>();
        for (LockEntryRow entry : repositoryDao.listLockEntries(lock.id())) {
            original.put(entry.name(), entry.version());
        }

        String conclusion;
        List<LockEntryResponse> entries;
        List<ReresolveDiffResponse> diffs;
        if (resolution.feasible()) {
            Map<String, Integer> solution = resolution.solution();
            diffs = computeDriftDiffs(original, solution, snapshot);
            conclusion = diffs.isEmpty() ? CONCLUSION_REPRODUCIBLE : CONCLUSION_DRIFTED;
            entries = new ArrayList<>();
            solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        } else {
            conclusion = CONCLUSION_INFEASIBLE;
            entries = List.of();
            diffs = resolution.blockers().stream()
                    .map(b -> new ReresolveDiffResponse(b.name(), "BLOCKER", b.reason(),
                            null, null, b.minimumVersion(), b.maximumVersion(), b.versions()))
                    .toList();
        }

        Instant now = now();
        long reportId = repositoryDao.insertReresolveReport(
                lock.id(), currentVersion, conclusion, currentRequestId.get(), now);
        for (LockEntryResponse entry : entries) {
            repositoryDao.insertReresolveReportEntry(reportId, entry.name(), entry.version());
        }
        for (ReresolveDiffResponse diff : diffs) {
            repositoryDao.insertReresolveReportDiff(reportId, diff.name(), diff.changeType(),
                    diff.reason(), diff.oldVersion(), diff.newVersion(),
                    diff.minimumVersion(), diff.maximumVersion(), joinVersions(diff.versions()));
        }
        return new ReresolveReportResponse(reportId, lock.id(), currentVersion, conclusion,
                now, entries, diffs);
    }

    /**
     * 逐名称比较原锁文件与新解析集合，产出按名称升序的差异明细。
     *
     * <p>原因判定：原版本在当前快照中已撤回 → WITHDRAWN；新版本号更高 → SUPERSEDED；
     * 其余（含新增、移除与降级）→ RANGE_NOT_SATISFIED。
     */
    private List<ReresolveDiffResponse> computeDriftDiffs(Map<String, Integer> original,
                                                          Map<String, Integer> solution,
                                                          RepositorySnapshot snapshot) {
        Set<String> names = new HashSet<>();
        names.addAll(original.keySet());
        names.addAll(solution.keySet());
        List<ReresolveDiffResponse> diffs = new ArrayList<>();
        for (String name : new TreeSet<>(names)) {
            Integer oldVersion = original.get(name);
            Integer newVersion = solution.get(name);
            if (oldVersion == null) {
                diffs.add(new ReresolveDiffResponse(name, "ADDED", DRIFT_RANGE_NOT_SATISFIED,
                        null, newVersion, null, null, null));
            } else if (newVersion == null) {
                diffs.add(new ReresolveDiffResponse(name, "REMOVED", DRIFT_RANGE_NOT_SATISFIED,
                        oldVersion, null, null, null, null));
            } else if (!oldVersion.equals(newVersion)) {
                String reason;
                if (isWithdrawn(snapshot, name, oldVersion)) {
                    reason = DRIFT_WITHDRAWN;
                } else if (newVersion > oldVersion) {
                    reason = DRIFT_SUPERSEDED;
                } else {
                    reason = DRIFT_RANGE_NOT_SATISFIED;
                }
                diffs.add(new ReresolveDiffResponse(name, "CHANGED", reason,
                        oldVersion, newVersion, null, null, null));
            }
        }
        return List.copyOf(diffs);
    }

    /** 指定名称版本在当前快照中是否已撤回；版本不存在时视为未撤回。 */
    private static boolean isWithdrawn(RepositorySnapshot snapshot, String name, int version) {
        return snapshot.artifacts().getOrDefault(name, List.of()).stream()
                .anyMatch(a -> a.version() == version && a.withdrawn());
    }

    private ReresolveReportResponse toReportResponse(ReresolveReportRow row) {
        List<LockEntryResponse> entries = repositoryDao.listReresolveEntries(row.id()).stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<ReresolveDiffResponse> diffs = repositoryDao.listReresolveDiffs(row.id()).stream()
                .map(d -> new ReresolveDiffResponse(d.name(), d.changeType(), d.reason(),
                        d.oldVersion(), d.newVersion(), d.minimumVersion(), d.maximumVersion(),
                        d.versions()))
                .toList();
        return new ReresolveReportResponse(row.id(), row.lockFileId(), row.repositoryVersion(),
                row.conclusion(), row.createdAt(), entries, diffs);
    }

    /** 版本清单序列化为逗号分隔字符串；null 清单保持 null。 */
    private static String joinVersions(List<Integer> versions) {
        if (versions == null) {
            return null;
        }
        return versions.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
