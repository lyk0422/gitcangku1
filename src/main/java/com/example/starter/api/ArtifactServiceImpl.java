package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockPeerMatchResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PeerSpec;
import com.example.starter.api.dto.PeerView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.WithdrawRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.LockPeerMatchRow;
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
 * 登记/撤回/锁定均要求非空操作者；撤回与锁定核对同一 expectedRepositoryVersion。
 * 幂等指纹为结构化 JSON（操作、资源、全参数、期望版本、操作者）的 SHA-256，
 * 字段名分层且互不碰撞；peer 声明按名称排序后参与指纹，换序同参。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int MAX_PEERS = 3;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
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
        requireOperator(request.operator());
        validateRegisterRequest(request);
        String hash = sha256(canonicalRegister(request));
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request), ArtifactResponse.class);
    }

    @Override
    public ArtifactResponse withdrawArtifact(String requestId, String name, int version,
                                             WithdrawRequest request) {
        requireRequestId(requestId);
        requireOperator(request.operator());
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        if (request.expectedRepositoryVersion() == null) {
            throw ApiException.badRequest("expectedRepositoryVersion 不能为空");
        }
        String hash = sha256(canonicalWithdraw(name.trim(), version, request));
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> doWithdraw(name.trim(), version, request), ArtifactResponse.class);
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        requireOperator(request.operator());
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String hash = sha256(canonicalLock(request));
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
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
        long artifactId = repositoryDao.insertArtifact(name, version, request.operator(), now);
        for (DependencySpec dep : request.dependencies()) {
            repositoryDao.insertDependency(artifactId, dep.name().trim(),
                    dep.minimumVersion(), dep.maximumVersion());
        }
        for (PeerSpec peer : request.peers()) {
            repositoryDao.insertPeer(artifactId, peer.name().trim(),
                    peer.minimumVersion(), peer.maximumVersion());
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, request.operator(), repositoryVersion, now,
                toDependencyViews(request.dependencies()), toPeerViews(request.peers()));
    }

    private ArtifactResponse doWithdraw(String name, int version, WithdrawRequest request) {
        // 当前事务已在入口持有 repository_state 行锁；此处读取版本号并做乐观校验。
        long currentVersion = repositoryDao.lockRepositoryState();
        if (currentVersion != request.expectedRepositoryVersion()) {
            throw ApiException.conflict("仓库版本不匹配：expected="
                    + request.expectedRepositoryVersion() + ", actual=" + currentVersion);
        }

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
        return new ArtifactResponse(name, version, true, request.operator(), repositoryVersion,
                Instant.now(clock), toRangeViews(artifact.dependencies()),
                toRangePeerViews(artifact.peers()));
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
                    "不存在满足全部普通依赖与 peer 约束的未撤回版本组合，无法锁定");
        }

        // 依据求解结果冻结全部 peer 声明及实际宿主匹配版本：解析器已保证宿主可达且版本满足。
        List<LockPeerMatchResponse> peerMatches = collectPeerMatches(snapshot, solution);

        Instant now = Instant.now(clock);
        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, request.operator(),
                currentVersion, currentRequestId.get(), now);
        solution.forEach((n, v) -> repositoryDao.insertLockEntry(lockFileId, n, v));
        for (LockPeerMatchResponse match : peerMatches) {
            repositoryDao.insertLockPeerMatch(lockFileId, match.declarerName(),
                    match.declarerVersion(), match.hostName(), match.minimumVersion(),
                    match.maximumVersion(), match.matchedVersion());
        }

        List<LockEntryResponse> entries = new ArrayList<>();
        solution.forEach((n, v) -> entries.add(new LockEntryResponse(n, v)));
        return new LockFileResponse(lockFileId, rootName, rootVersion, request.operator(),
                currentVersion, now, entries, peerMatches);
    }

    /**
     * 按名称升序遍历候选解中的制品，收集其全部 peer 声明与实际宿主匹配版本。
     */
    private List<LockPeerMatchResponse> collectPeerMatches(
            RepositorySnapshot snapshot, Map<String, Integer> solution) {
        Map<String, List<ArtifactVersion>> all = snapshot.artifacts();
        List<LockPeerMatchResponse> matches = new ArrayList<>();
        for (String declarerName : new TreeMap<>(solution).keySet()) {
            int declarerVersion = solution.get(declarerName);
            ArtifactVersion artifact = all.get(declarerName).stream()
                    .filter(a -> a.version() == declarerVersion)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "求解结果中的制品版本缺失: " + declarerName + ":" + declarerVersion));
            TreeMap<String, DependencyRange> peersByName = new TreeMap<>();
            artifact.peers().forEach(p -> peersByName.put(p.name(), p));
            for (DependencyRange peer : peersByName.values()) {
                Integer matchedVersion = solution.get(peer.name());
                if (matchedVersion == null || matchedVersion < peer.minimumVersion()
                        || matchedVersion > peer.maximumVersion()) {
                    throw new IllegalStateException(
                            "peer 约束未被求解结果满足: " + declarerName + ":" + declarerVersion
                                    + " -> " + peer.name());
                }
                matches.add(new LockPeerMatchResponse(declarerName, declarerVersion,
                        peer.name(), peer.minimumVersion(), peer.maximumVersion(),
                        matchedVersion));
            }
        }
        return List.copyOf(matches);
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
        // 快速路径：已提交的成功记录直接重放首次完整响应。
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
            // 同键并发：赢家已提交则重放其结果，否则报告冲突；不向调用方泄漏唯一键错误。
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
    // 结构化幂等指纹（JSON 字段分层命名，无碰撞；数字按十进制值，文本原样）
    // ------------------------------------------------------------------

    private String canonicalRegister(RegisterArtifactRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", OP_REGISTER);
        root.put("name", request.name().trim());
        root.put("version", request.version());
        root.put("operator", request.operator());
        root.put("dependencies", canonicalRanges(request.dependencies().stream()
                .map(d -> List.<Object>of(d.name().trim(), d.minimumVersion(), d.maximumVersion())).toList()));
        root.put("peers", canonicalRanges(request.peers().stream()
                .map(p -> List.<Object>of(p.name().trim(), p.minimumVersion(), p.maximumVersion())).toList()));
        return writeJson(root);
    }

    private String canonicalWithdraw(String name, int version, WithdrawRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", OP_WITHDRAW);
        root.put("name", name);
        root.put("version", version);
        root.put("operator", request.operator());
        root.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return writeJson(root);
    }

    private String canonicalLock(LockRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", OP_LOCK);
        root.put("rootName", request.rootName().trim());
        root.put("rootVersion", request.rootVersion());
        root.put("operator", request.operator());
        root.put("expectedRepositoryVersion", request.expectedRepositoryVersion());
        return writeJson(root);
    }

    /** 区间声明按名称、上下界排序后序列化，使换序请求同参。 */
    private List<List<Object>> canonicalRanges(List<List<Object>> ranges) {
        return ranges.stream()
                .map(r -> {
                    @SuppressWarnings("unchecked")
                    List<Object> typed = (List<Object>) r;
                    return List.copyOf(typed);
                })
                .sorted((a, b) -> {
                    int byName = ((String) a.get(0)).compareTo((String) b.get(0));
                    if (byName != 0) {
                        return byName;
                    }
                    int byMin = Integer.compare((Integer) a.get(1), (Integer) b.get(1));
                    if (byMin != 0) {
                        return byMin;
                    }
                    return Integer.compare((Integer) a.get(2), (Integer) b.get(2));
                })
                .toList();
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
        validateRanges(deps, "依赖");

        List<PeerSpec> peers = request.peers();
        if (peers.size() > MAX_PEERS) {
            throw ApiException.badRequest("每个制品版本最多声明 " + MAX_PEERS + " 条 peer 依赖");
        }
        Set<String> peerNames = new HashSet<>();
        for (PeerSpec peer : peers) {
            if (peer.name() == null || peer.name().isBlank()) {
                throw ApiException.badRequest("peer 名称不能为空");
            }
            String peerName = peer.name().trim();
            if (peer.minimumVersion() > peer.maximumVersion()) {
                throw ApiException.badRequest(
                        "peer " + peerName + " 的最低版本不能高于最高版本");
            }
            if (!peerNames.add(peerName)) {
                throw ApiException.badRequest("peer 名称重复: " + peerName);
            }
            if (peerName.equals(request.name().trim())) {
                throw ApiException.badRequest("peer 名称不能与制品自身同名: " + peerName);
            }
        }
    }

    private void validateRanges(List<DependencySpec> deps, String label) {
        Set<String> names = new HashSet<>();
        for (DependencySpec dep : deps) {
            if (dep.name() == null || dep.name().isBlank()) {
                throw ApiException.badRequest(label + "名称不能为空");
            }
            String depName = dep.name().trim();
            if (dep.minimumVersion() > dep.maximumVersion()) {
                throw ApiException.badRequest(
                        label + " " + depName + " 的最低版本不能高于最高版本");
            }
            if (!names.add(depName)) {
                throw ApiException.badRequest(label + "名称重复: " + depName);
            }
        }
    }

    private List<DependencyView> toDependencyViews(List<DependencySpec> raw) {
        TreeMap<String, DependencyView> sorted = new TreeMap<>();
        for (DependencySpec spec : raw) {
            sorted.put(spec.name().trim(), new DependencyView(
                    spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
        }
        return List.copyOf(sorted.values());
    }

    private List<PeerView> toPeerViews(List<PeerSpec> raw) {
        TreeMap<String, PeerView> sorted = new TreeMap<>();
        for (PeerSpec spec : raw) {
            sorted.put(spec.name().trim(), new PeerView(
                    spec.name().trim(), spec.minimumVersion(), spec.maximumVersion()));
        }
        return List.copyOf(sorted.values());
    }

    private List<DependencyView> toRangeViews(List<DependencyRange> raw) {
        return raw.stream()
                .map(r -> new DependencyView(r.name(), r.minimumVersion(), r.maximumVersion()))
                .toList();
    }

    private List<PeerView> toRangePeerViews(List<DependencyRange> raw) {
        return raw.stream()
                .map(r -> new PeerView(r.name(), r.minimumVersion(), r.maximumVersion()))
                .toList();
    }

    private LockFileResponse toLockResponse(LockFileRow row) {
        List<LockEntryResponse> entryViews = repositoryDao.listLockEntries(row.id()).stream()
                .map(e -> new LockEntryResponse(e.name(), e.version()))
                .toList();
        List<LockPeerMatchResponse> peerViews = repositoryDao.listLockPeerMatches(row.id()).stream()
                .map(this::toPeerMatchResponse)
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(), row.operator(),
                row.repositoryVersion(), row.createdAt(), entryViews, peerViews);
    }

    private LockPeerMatchResponse toPeerMatchResponse(LockPeerMatchRow row) {
        return new LockPeerMatchResponse(row.declarerName(), row.declarerVersion(),
                row.hostName(), row.minimumVersion(), row.maximumVersion(), row.matchedVersion());
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("operator 不能为空");
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
