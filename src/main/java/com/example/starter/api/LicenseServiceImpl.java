package com.example.starter.api;

import com.example.starter.api.dto.BindNoticeRequest;
import com.example.starter.api.dto.LicenseCheckView;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeBindingResponse;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.RegisterNoticeTextRequest;
import com.example.starter.api.dto.RegisterPolicyRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.api.dto.ReleaseSnapshotResponse.ReleasedEntry;
import com.example.starter.api.dto.ReleaseSnapshotResponse.ReleasedItem;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LicenseGate;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.repo.LicenseDao;
import com.example.starter.repo.LicenseDao.NoticeTextRow;
import com.example.starter.repo.LicenseDao.ReleaseEntryRow;
import com.example.starter.repo.LicenseDao.ReleaseItemRow;
import com.example.starter.repo.LicenseDao.ReleaseRow;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.time.Clock;
import java.time.Instant;

/**
 * 许可证告知与发布门禁业务服务实现。
 *
 * <p>所有写操作在单个事务内完成：先锁单行 repository_state 串行化全部写
 * （含制品登记/锁定与许可证更新，按提交顺序裁决），再执行业务并写入幂等成功记录；
 * 业务失败整体回滚、不占用 requestId。发布批量校验任一图不合规即整体回滚。
 */
@Service
public class LicenseServiceImpl implements LicenseService {

    private static final String OP_POLICY = "REGISTER_POLICY";
    private static final String OP_NOTICE = "REGISTER_NOTICE";
    private static final String OP_APPROVE = "APPROVE_NOTICE";
    private static final String OP_WITHDRAW = "WITHDRAW_NOTICE";
    private static final String OP_NARROW = "NARROW_NOTICE_REGIONS";
    private static final String OP_BIND = "BIND_NOTICE";
    private static final String OP_RELEASE = "RELEASE_LOCKS";

    private static final String SCOPE_LOCK = "LOCK";
    private static final String SCOPE_COORDINATE = "COORDINATE";

    private final RepositoryDao repositoryDao;
    private final LicenseDao licenseDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LicenseServiceImpl(RepositoryDao repositoryDao,
                              LicenseDao licenseDao,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.repositoryDao = repositoryDao;
        this.licenseDao = licenseDao;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 策略
    // ------------------------------------------------------------------

    @Override
    public LicensePolicyResponse registerPolicy(String requestId, RegisterPolicyRequest request) {
        requireRequestId(requestId);
        String scopeType = normalizeScope(request.scopeType());
        String artifactName = normalizeName(request.artifactName());
        String licenseId = requireText(request.licenseId(), "licenseId 不能为空");
        String action = normalizeAction(request.action());
        if (SCOPE_LOCK.equals(scopeType)) {
            if (request.lockFileId() == null) {
                throw ApiException.badRequest("LOCK 作用域必须提供 lockFileId");
            }
            if (request.artifactVersion() != null) {
                throw ApiException.badRequest("LOCK 作用域不能指定 artifactVersion");
            }
        } else {
            if (request.lockFileId() != null) {
                throw ApiException.badRequest("COORDINATE 作用域不能提供 lockFileId");
            }
        }
        String hash = sha256(OP_POLICY + "|" + scopeType + "|" + request.lockFileId() + "|"
                + artifactName + "|" + request.artifactVersion() + "|" + licenseId + "|" + action);
        return executeIdempotent(requestId, OP_POLICY, hash, 201,
                () -> doRegisterPolicy(scopeType, request.lockFileId(), artifactName,
                        request.artifactVersion(), licenseId, action, requestId),
                LicensePolicyResponse.class);
    }

    private LicensePolicyResponse doRegisterPolicy(String scopeType, Long lockFileId,
                                                   String artifactName, Integer artifactVersion,
                                                   String licenseId, String action,
                                                   String requestId) {
        if (SCOPE_LOCK.equals(scopeType)) {
            List<LockEntryRow> entries = requireLockEntries(lockFileId);
            boolean inClosure = entries.stream().anyMatch(e -> e.name().equals(artifactName));
            if (!inClosure) {
                throw ApiException.unprocessable("制品不在锁定图闭包内: " + artifactName);
            }
        }
        Instant now = Instant.now(clock);
        long id = licenseDao.insertPolicy(scopeType, lockFileId, artifactName, artifactVersion,
                licenseId, action, requestId, now);
        return new LicensePolicyResponse(id, scopeType, lockFileId, artifactName, artifactVersion,
                licenseId, action, now);
    }

    // ------------------------------------------------------------------
    // 告知文本
    // ------------------------------------------------------------------

    @Override
    public NoticeTextResponse registerNoticeText(String requestId, RegisterNoticeTextRequest request) {
        requireRequestId(requestId);
        String noticeKey = requireText(request.noticeKey(), "noticeKey 不能为空");
        String licenseId = requireText(request.licenseId(), "licenseId 不能为空");
        String body = requireText(request.body(), "body 不能为空");
        TreeSet<String> regions = normalizeRegions(request.regions());
        String hash = sha256(OP_NOTICE + "|" + noticeKey + "|" + request.version() + "|"
                + licenseId + "|" + body + "|" + String.join(",", regions));
        return executeIdempotent(requestId, OP_NOTICE, hash, 201,
                () -> doRegisterNoticeText(noticeKey, request.version(), licenseId, body,
                        regions, requestId),
                NoticeTextResponse.class);
    }

    private NoticeTextResponse doRegisterNoticeText(String noticeKey, int version, String licenseId,
                                                    String body, TreeSet<String> regions,
                                                    String requestId) {
        if (licenseDao.findNoticeText(noticeKey, version) != null) {
            throw ApiException.conflict("告知文本版本已存在: " + noticeKey + ":" + version);
        }
        Instant now = Instant.now(clock);
        long id = licenseDao.insertNoticeText(noticeKey, version, licenseId, body,
                String.join(",", regions), requestId, now);
        return new NoticeTextResponse(id, noticeKey, version, licenseId, body,
                List.copyOf(regions), LicenseGate.STATUS_DRAFT, now, now);
    }

    @Override
    public NoticeTextResponse approveNoticeText(String requestId, String noticeKey, int version) {
        requireRequestId(requestId);
        requireText(noticeKey, "noticeKey 不能为空");
        String hash = sha256(OP_APPROVE + "|" + noticeKey + "|" + version);
        return executeIdempotent(requestId, OP_APPROVE, hash, 200,
                () -> transitionNotice(noticeKey, version, LicenseGate.STATUS_DRAFT,
                        LicenseGate.STATUS_APPROVED, "批准"),
                NoticeTextResponse.class);
    }

    @Override
    public NoticeTextResponse withdrawNoticeText(String requestId, String noticeKey, int version) {
        requireRequestId(requestId);
        requireText(noticeKey, "noticeKey 不能为空");
        String hash = sha256(OP_WITHDRAW + "|" + noticeKey + "|" + version);
        // 撤销要求当前为 APPROVED；已撤销再撤回报 409，失败不占键。
        return executeIdempotent(requestId, OP_WITHDRAW, hash, 200,
                () -> transitionNotice(noticeKey, version, LicenseGate.STATUS_APPROVED,
                        LicenseGate.STATUS_WITHDRAWN, "撤销"),
                NoticeTextResponse.class);
    }

    @Override
    public NoticeTextResponse narrowNoticeRegions(String requestId, String noticeKey, int version,
                                                  NarrowRegionsRequest request) {
        requireRequestId(requestId);
        requireText(noticeKey, "noticeKey 不能为空");
        TreeSet<String> regions = normalizeRegions(request.regions());
        String hash = sha256(OP_NARROW + "|" + noticeKey + "|" + version + "|"
                + String.join(",", regions));
        return executeIdempotent(requestId, OP_NARROW, hash, 200,
                () -> doNarrowRegions(noticeKey, version, regions), NoticeTextResponse.class);
    }

    private NoticeTextResponse doNarrowRegions(String noticeKey, int version,
                                               TreeSet<String> regions) {
        NoticeTextRow row = requireNoticeText(noticeKey, version);
        if (!LicenseGate.STATUS_APPROVED.equals(row.status())) {
            throw ApiException.conflict("仅已批准文本可缩窄地区，当前状态: " + row.status());
        }
        TreeSet<String> current = parseRegions(row.regions());
        if (!current.containsAll(regions)) {
            throw ApiException.unprocessable("新地区集合必须是当前地区集合的子集: current="
                    + current + ", requested=" + regions);
        }
        Instant now = Instant.now(clock);
        licenseDao.updateNoticeRegions(row.id(), String.join(",", regions), now);
        return toNoticeResponse(licenseDao.getNoticeTextById(row.id()));
    }

    /**
     * DRAFT→APPROVED 条件迁移；若已经是目标状态则幂等返回当前文本，
     * 其他状态返回 409。
     */
    private NoticeTextResponse transitionNotice(String noticeKey, int version,
                                                String expectedStatus, String targetStatus,
                                                String actionName) {
        NoticeTextRow row = requireNoticeText(noticeKey, version);
        if (targetStatus.equals(row.status())) {
            return toNoticeResponse(row);
        }
        int affected = licenseDao.updateNoticeStatus(row.id(), expectedStatus, targetStatus,
                Instant.now(clock));
        if (affected == 0) {
            throw ApiException.conflict("告知文本当前状态不允许" + actionName + ": "
                    + noticeKey + ":" + version + " (" + row.status() + ")");
        }
        return toNoticeResponse(licenseDao.getNoticeTextById(row.id()));
    }

    // ------------------------------------------------------------------
    // 告知绑定
    // ------------------------------------------------------------------

    @Override
    public NoticeBindingResponse bindNotice(String requestId, BindNoticeRequest request) {
        requireRequestId(requestId);
        String scopeType = normalizeScope(request.scopeType());
        String artifactName = normalizeName(request.artifactName());
        String licenseId = requireText(request.licenseId(), "licenseId 不能为空");
        String noticeKey = requireText(request.noticeKey(), "noticeKey 不能为空");
        if (SCOPE_LOCK.equals(scopeType)) {
            if (request.lockFileId() == null) {
                throw ApiException.badRequest("LOCK 作用域必须提供 lockFileId");
            }
            if (request.artifactVersion() != null) {
                throw ApiException.badRequest("LOCK 作用域不能指定 artifactVersion");
            }
        } else if (request.lockFileId() != null) {
            throw ApiException.badRequest("COORDINATE 作用域不能提供 lockFileId");
        }
        String hash = sha256(OP_BIND + "|" + scopeType + "|" + request.lockFileId() + "|"
                + artifactName + "|" + request.artifactVersion() + "|" + licenseId + "|"
                + noticeKey + "|" + request.noticeVersion());
        return executeIdempotent(requestId, OP_BIND, hash, 201,
                () -> doBindNotice(scopeType, request.lockFileId(), artifactName,
                        request.artifactVersion(), licenseId, noticeKey,
                        request.noticeVersion(), requestId),
                NoticeBindingResponse.class);
    }

    private NoticeBindingResponse doBindNotice(String scopeType, Long lockFileId,
                                               String artifactName, Integer artifactVersion,
                                               String licenseId, String noticeKey,
                                               int noticeVersion, String requestId) {
        // 绑定仅登记指向关系；文本是否批准、许可证是否一致、地区是否覆盖均在发布门禁判定。
        requireNoticeText(noticeKey, noticeVersion);
        if (SCOPE_LOCK.equals(scopeType)) {
            List<LockEntryRow> entries = requireLockEntries(lockFileId);
            boolean inClosure = entries.stream().anyMatch(e -> e.name().equals(artifactName));
            if (!inClosure) {
                throw ApiException.unprocessable("制品不在锁定图闭包内: " + artifactName);
            }
        }
        Instant now = Instant.now(clock);
        long id = licenseDao.insertBinding(scopeType, lockFileId, artifactName, artifactVersion,
                licenseId, noticeKey, noticeVersion, requestId, now);
        return new NoticeBindingResponse(id, scopeType, lockFileId, artifactName, artifactVersion,
                licenseId, noticeKey, noticeVersion, now);
    }

    // ------------------------------------------------------------------
    // 发布门禁
    // ------------------------------------------------------------------

    @Override
    public ReleaseSnapshotResponse release(String requestId, ReleaseRequest request) {
        requireRequestId(requestId);
        List<Long> lockIds = new ArrayList<>(new TreeSet<>(request.lockFileIds()));
        if (lockIds.size() != request.lockFileIds().size()) {
            throw ApiException.badRequest("批量发布的锁定图 ID 不能重复");
        }
        TreeSet<String> regions = normalizeRegions(request.regions());
        return releaseIdempotent(requestId, lockIds, regions);
    }

    /**
     * 发布专用幂等事务：指纹须在持锁评估后计算，包含锁定图版本、地区、
     * 规范化制品集合与命中告知文本版本；任意图不合规则 422 回滚，不占用 requestId。
     */
    private ReleaseSnapshotResponse releaseIdempotent(String requestId, List<Long> lockIds,
                                                      Set<String> regions) {
        try {
            return transactionTemplate.execute(txStatus -> {
                repositoryDao.lockRepositoryState();
                Instant now = Instant.now(clock);

                // 全部图先评估，任一不存在抛 404，不合规在占键之前判定。
                List<EvaluatedLock> evaluated = new ArrayList<>();
                for (Long lockId : lockIds) {
                    evaluated.add(evaluateLock(lockId, regions));
                }
                String fingerprint = releaseFingerprint(regions, evaluated);

                IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
                if (record != null) {
                    if (!record.operation().equals(OP_RELEASE)
                            || !record.requestHash().equals(fingerprint)) {
                        throw ApiException.conflict(
                                "requestId 已用于不同参数的请求: " + requestId);
                    }
                    return readJson(record.responseJson(), ReleaseSnapshotResponse.class);
                }
                repositoryDao.insertPendingIdempotentRequest(
                        requestId, OP_RELEASE, fingerprint, now);

                List<LicenseCheckView> failedViews = new ArrayList<>();
                for (EvaluatedLock evaluatedLock : evaluated) {
                    if (!evaluatedLock.result().compliant()) {
                        failedViews.add(evaluatedLock.view());
                    }
                }
                if (!failedViews.isEmpty()) {
                    throw ApiException.unprocessable(
                            "许可证告知校验未通过，" + failedViews.size()
                                    + " 张锁定图不合规，整批不予发布",
                            List.copyOf(failedViews));
                }

                long releaseId = persistRelease(requestId, regions, evaluated, now);
                ReleaseSnapshotResponse response =
                        toReleaseResponse(licenseDao.getRelease(releaseId));
                repositoryDao.completeIdempotentRequest(requestId, 201, writeJson(response));
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 行锁已串行化写事务；若持锁等待期间赢家已提交，按其最终状态重放或冲突。
            throw ApiException.conflict("相同 requestId 的请求正在处理中: " + requestId);
        }
    }

    /**
     * 计算发布请求指纹：每张图含锁定图版本（根坐标与锁 ID）、规范化制品集合、
     * 命中策略许可证与动作及绑定的告知文本版本；整批附规范化目标地区。
     */
    private String releaseFingerprint(Set<String> regions, List<EvaluatedLock> evaluated) {
        StringBuilder sb = new StringBuilder(OP_RELEASE).append("|R=").append(String.join(",", regions));
        for (EvaluatedLock evaluatedLock : evaluated) {
            LockFileRow lock = evaluatedLock.lock();
            sb.append("|L=").append(lock.id()).append(":").append(lock.rootName())
                    .append(":").append(lock.rootVersion());
            String artifactSet = evaluatedLock.artifacts().stream()
                    .map(a -> a.name() + ":" + a.version())
                    .sorted()
                    .reduce((a, b) -> a + "," + b).orElse("");
            sb.append("|A=").append(artifactSet);
            String hitSet = evaluatedLock.result().hits().stream()
                    .map(h -> h.name() + ":" + h.version() + "|" + h.licenseId() + "|"
                            + h.action() + "|"
                            + (h.noticeKey() == null ? "" : h.noticeKey() + ":" + h.noticeVersion()))
                    .sorted()
                    .reduce((a, b) -> a + ";" + b).orElse("");
            sb.append("|H=").append(hitSet);
        }
        return sha256(sb.toString());
    }

    private long persistRelease(String requestId, Set<String> regions,
                                List<EvaluatedLock> evaluated, Instant now) {
        long releaseId = licenseDao.insertRelease(requestId, now);
        for (EvaluatedLock evaluatedLock : evaluated) {
            LockFileRow lock = evaluatedLock.lock();
            long itemId = licenseDao.insertReleaseItem(releaseId, lock.id(),
                    lock.rootName(), lock.rootVersion(), String.join(",", regions), now);
            Map<String, LicenseGate.Hit> hitByName = new HashMap<>();
            evaluatedLock.result().hits().forEach(h -> hitByName.put(h.name(), h));
            for (LicenseGate.GateArtifact artifact : evaluatedLock.artifacts()) {
                LicenseGate.Hit hit = hitByName.get(artifact.name());
                String licenseId = null;
                String noticeKey = null;
                Integer noticeVersion = null;
                String noticeRegions = null;
                if (hit != null) {
                    licenseId = hit.licenseId();
                    if (hit.noticeKey() != null) {
                        noticeKey = hit.noticeKey();
                        noticeVersion = hit.noticeVersion();
                        noticeRegions = String.join(",", hit.noticeRegions());
                    }
                }
                licenseDao.insertReleaseEntry(itemId, artifact.name(), artifact.version(),
                        artifact.direct(), licenseId, noticeKey, noticeVersion, noticeRegions);
            }
        }
        return releaseId;
    }

    @Override
    public LicenseCheckView checkLock(long lockFileId, List<String> regions) {
        TreeSet<String> normalized = regions == null || regions.isEmpty()
                ? new TreeSet<>() : normalizeRegions(regions);
        return transactionTemplate.execute(status -> evaluateLock(lockFileId, normalized).view());
    }

    /**
     * 加载锁定图闭包并执行门禁评估；读操作在事务内完成以保证快照一致。
     */
    private EvaluatedLock evaluateLock(long lockFileId, Set<String> regions) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁定图不存在: " + lockFileId);
        }
        List<LockEntryRow> entries = repositoryDao.listLockEntries(lockFileId);
        RepositorySnapshot snapshot = repositoryDao.loadSnapshot();
        List<LicenseGate.GateArtifact> artifacts = buildGateArtifacts(lock, entries, snapshot);

        List<LicenseGate.GatePolicy> gatePolicies = licenseDao.listPolicies().stream()
                .map(p -> new LicenseGate.GatePolicy(p.id(), p.scopeType(), p.lockFileId(),
                        p.artifactName(), p.artifactVersion(), p.licenseId(), p.action()))
                .toList();
        List<LicenseGate.GateBinding> gateBindings = licenseDao.listBindings().stream()
                .map(b -> new LicenseGate.GateBinding(b.id(), b.scopeType(), b.lockFileId(),
                        b.artifactName(), b.artifactVersion(), b.licenseId(),
                        b.noticeKey(), b.noticeVersion()))
                .toList();
        Map<String, LicenseGate.GateNotice> notices = new HashMap<>();
        for (NoticeTextRow row : licenseDao.listAllNoticeTexts()) {
            notices.put(row.noticeKey() + "#" + row.version(),
                    new LicenseGate.GateNotice(row.noticeKey(), row.version(), row.licenseId(),
                            row.status(), parseRegions(row.regions())));
        }

        LicenseGate.GateResult result = LicenseGate.evaluate(lockFileId, artifacts,
                gatePolicies, gateBindings, notices, regions);
        LicenseCheckView view = toCheckView(lockFileId, lock.rootName(), result);
        return new EvaluatedLock(lock, artifacts, result, view);
    }

    /**
     * 基于锁定条目与仓库依赖声明构建闭包制品视图：direct 标记与 BFS 最短命中路径。
     */
    private List<LicenseGate.GateArtifact> buildGateArtifacts(
            LockFileRow lock, List<LockEntryRow> entries, RepositorySnapshot snapshot) {
        Map<String, Integer> versions = new TreeMap<>();
        entries.forEach(e -> versions.put(e.name(), e.version()));

        // 解析出每个闭包制品的 ArtifactVersion（含依赖声明）。
        Map<String, ArtifactVersion> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : versions.entrySet()) {
            List<ArtifactVersion> candidates = snapshot.artifacts().get(e.getKey());
            if (candidates == null) {
                throw ApiException.unprocessable("锁定图引用的制品版本在仓库中缺失: "
                        + e.getKey() + ":" + e.getValue());
            }
            ArtifactVersion found = candidates.stream()
                    .filter(a -> a.version() == e.getValue())
                    .findFirst()
                    .orElseThrow(() -> ApiException.unprocessable(
                            "锁定图引用的制品版本在仓库中缺失: " + e.getKey() + ":" + e.getValue()));
            selected.put(e.getKey(), found);
        }

        ArtifactVersion root = selected.get(lock.rootName());
        if (root == null || root.version() != lock.rootVersion()) {
            throw ApiException.unprocessable("锁定图根制品与条目不一致: "
                    + lock.rootName() + ":" + lock.rootVersion());
        }
        Set<String> directNames = new HashSet<>();
        directNames.add(lock.rootName());
        for (DependencyRange dep : root.dependencies()) {
            if (versions.containsKey(dep.name())) {
                directNames.add(dep.name());
            }
        }

        // BFS 最短路径；边为已选制品声明且落在闭包内的依赖。
        Map<String, List<String>> paths = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        paths.put(lock.rootName(), List.of(lock.rootName() + ":" + lock.rootVersion()));
        queue.add(lock.rootName());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ArtifactVersion currentVersion = selected.get(current);
            if (currentVersion == null) {
                continue;
            }
            for (DependencyRange dep : currentVersion.dependencies()) {
                if (!versions.containsKey(dep.name()) || paths.containsKey(dep.name())) {
                    continue;
                }
                List<String> nextPath = new ArrayList<>(paths.get(current));
                nextPath.add(dep.name() + ":" + versions.get(dep.name()));
                paths.put(dep.name(), List.copyOf(nextPath));
                queue.add(dep.name());
            }
        }

        List<LicenseGate.GateArtifact> artifacts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : versions.entrySet()) {
            List<String> path = paths.getOrDefault(e.getKey(),
                    List.of(lock.rootName() + ":" + lock.rootVersion()));
            artifacts.add(new LicenseGate.GateArtifact(e.getKey(), e.getValue(),
                    directNames.contains(e.getKey()), path));
        }
        return List.copyOf(artifacts);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public NoticeTextResponse getNoticeText(String noticeKey, int version) {
        return toNoticeResponse(requireNoticeText(noticeKey, version));
    }

    @Override
    public List<ReleaseSnapshotResponse> listReleases() {
        List<ReleaseSnapshotResponse> result = new ArrayList<>();
        for (ReleaseRow row : licenseDao.listReleases()) {
            result.add(toReleaseResponse(row));
        }
        return result;
    }

    @Override
    public ReleaseSnapshotResponse getRelease(long releaseId) {
        ReleaseRow row = licenseDao.getRelease(releaseId);
        if (row == null) {
            throw ApiException.notFound("发布快照不存在: " + releaseId);
        }
        return toReleaseResponse(row);
    }

    // ------------------------------------------------------------------
    // 幂等控制（与制品写操作同一串行化机制）
    // ------------------------------------------------------------------

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
                T result = action.get();
                repositoryDao.completeIdempotentRequest(
                        requestId, httpStatus, writeJson(result));
                return result;
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

    // ------------------------------------------------------------------
    // 转换与校验辅助
    // ------------------------------------------------------------------

    private record EvaluatedLock(LockFileRow lock, List<LicenseGate.GateArtifact> artifacts,
                                 LicenseGate.GateResult result, LicenseCheckView view) {
    }

    private LicenseCheckView toCheckView(long lockFileId, String rootName,
                                         LicenseGate.GateResult result) {
        List<LicenseCheckView.Hit> hits = result.hits().stream()
                .map(h -> new LicenseCheckView.Hit(h.name(), h.version(), h.direct(), h.path(),
                        h.scopeType(), h.licenseId(), h.action(), h.noticeKey(),
                        h.noticeVersion(), h.noticeRegions()))
                .toList();
        List<LicenseCheckView.MissingNotice> missing = result.missing().stream()
                .map(m -> new LicenseCheckView.MissingNotice(m.reason(), m.name(), m.version(),
                        m.direct(), m.path(), m.licenseId(), m.detail()))
                .toList();
        return new LicenseCheckView(lockFileId, rootName, hits, missing);
    }

    private NoticeTextResponse toNoticeResponse(NoticeTextRow row) {
        if (row == null) {
            throw new IllegalStateException("告知文本行不存在");
        }
        return new NoticeTextResponse(row.id(), row.noticeKey(), row.version(), row.licenseId(),
                row.body(), List.copyOf(parseRegions(row.regions())), row.status(),
                row.createdAt(), row.updatedAt());
    }

    private ReleaseSnapshotResponse toReleaseResponse(ReleaseRow row) {
        List<ReleasedItem> items = new ArrayList<>();
        for (ReleaseItemRow itemRow : licenseDao.listReleaseItems(row.id())) {
            List<ReleasedEntry> entries = new ArrayList<>();
            for (ReleaseEntryRow entryRow : licenseDao.listReleaseEntries(itemRow.id())) {
                entries.add(new ReleasedEntry(entryRow.name(), entryRow.version(),
                        entryRow.direct(), entryRow.licenseId(), entryRow.noticeKey(),
                        entryRow.noticeVersion(),
                        entryRow.noticeRegions() == null || entryRow.noticeRegions().isBlank()
                                ? List.of() : parseRegions(entryRow.noticeRegions()).stream().toList()));
            }
            items.add(new ReleasedItem(itemRow.lockFileId(), itemRow.rootName(),
                    itemRow.rootVersion(), List.copyOf(parseRegions(itemRow.regions())),
                    List.copyOf(entries)));
        }
        return new ReleaseSnapshotResponse(row.id(), row.createdAt(), List.copyOf(items));
    }

    private NoticeTextRow requireNoticeText(String noticeKey, int version) {
        NoticeTextRow row = licenseDao.findNoticeText(noticeKey, version);
        if (row == null) {
            throw ApiException.notFound("告知文本版本不存在: " + noticeKey + ":" + version);
        }
        return row;
    }

    private List<LockEntryRow> requireLockEntries(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁定图不存在: " + lockFileId);
        }
        return repositoryDao.listLockEntries(lockFileId);
    }

    private String normalizeScope(String scopeType) {
        String normalized = requireText(scopeType, "scopeType 不能为空");
        if (!SCOPE_LOCK.equals(normalized) && !SCOPE_COORDINATE.equals(normalized)) {
            throw ApiException.badRequest("scopeType 必须为 LOCK 或 COORDINATE");
        }
        return normalized;
    }

    private String normalizeAction(String action) {
        String normalized = requireText(action, "action 不能为空");
        if (!LicenseGate.ACTION_NOTICE_REQUIRED.equals(normalized)
                && !LicenseGate.ACTION_ALLOWED.equals(normalized)) {
            throw ApiException.badRequest("action 必须为 NOTICE_REQUIRED 或 ALLOWED");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("artifactName 不能为空");
        }
        return name.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }

    private TreeSet<String> normalizeRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            throw ApiException.badRequest("regions 不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String region : regions) {
            if (region == null || region.isBlank()) {
                throw ApiException.badRequest("地区代码不能为空");
            }
            normalized.add(region.trim().toUpperCase());
        }
        return normalized;
    }

    private TreeSet<String> parseRegions(String regions) {
        TreeSet<String> result = new TreeSet<>();
        if (regions == null || regions.isBlank()) {
            return result;
        }
        for (String region : regions.split(",")) {
            if (!region.isBlank()) {
                result.add(region.trim());
            }
        }
        return result;
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
