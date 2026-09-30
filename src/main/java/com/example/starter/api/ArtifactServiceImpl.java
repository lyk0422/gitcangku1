package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.ExceptionApproveRequest;
import com.example.starter.api.dto.ExceptionCreateRequest;
import com.example.starter.api.dto.ExceptionEvidence;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.ExceptionRevokeRequest;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.RiskLevel;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.ExceptionRow;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockExceptionRow;
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
 *
 * <p>风险门禁：锁定解析结果中的 HIGH 及以上风险依赖版本，必须逐个命中请求声明的
 * 有效豁免（APPROVED、未到期——恰好到期即失效、依赖名与精确版本完全一致），
 * 否则 422 且不生成任何锁文件数据；豁免依据在锁定时固化，事后撤销/到期不改写历史锁。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;
    private static final int REQUIRED_APPROVERS = 2;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_MARK_RISK = "MARK_RISK";
    private static final String OP_CREATE_EXCEPTION = "CREATE_EXCEPTION";
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
        RiskLevel riskLevel = parseRiskLevel(request.riskLevel());
        String hash = sha256(OP_REGISTER + "|" + request.name().trim() + "|" + request.version() + "|"
                + riskLevel.name() + "|" + canonicalDependencies(request));
        return executeIdempotent(requestId, OP_REGISTER, hash, 201,
                () -> doRegister(request, riskLevel), ArtifactResponse.class);
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
    public ArtifactResponse markRisk(String requestId, String name, int version, MarkRiskRequest request) {
        requireRequestId(requestId);
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        RiskLevel riskLevel = parseRiskLevel(request.riskLevel());
        String hash = sha256(OP_MARK_RISK + "|" + name.trim() + "|" + version + "|" + riskLevel.name());
        return executeIdempotent(requestId, OP_MARK_RISK, hash, 200,
                () -> doMarkRisk(name.trim(), version, riskLevel), ArtifactResponse.class);
    }

    @Override
    public ExceptionResponse createException(String requestId, ExceptionCreateRequest request) {
        requireRequestId(requestId);
        validateExceptionRequest(request);
        String hash = sha256(OP_CREATE_EXCEPTION + "|" + request.dependencyName().trim() + "|"
                + request.dependencyVersion() + "|" + request.expiresAt() + "|" + request.reason().trim());
        return executeIdempotent(requestId, OP_CREATE_EXCEPTION, hash, 201,
                () -> doCreateException(request), ExceptionResponse.class);
    }

    @Override
    public ExceptionResponse approveException(long exceptionId, ExceptionApproveRequest request) {
        if (request == null || request.approver() == null || request.approver().isBlank()) {
            throw ApiException.badRequest("approver 不能为空");
        }
        String approver = request.approver().trim();
        return inWriteTransaction(() -> {
            ExceptionRow row = repositoryDao.lockException(exceptionId);
            if (row == null) {
                throw ApiException.notFound("豁免申请不存在: " + exceptionId);
            }
            if (!"PENDING".equals(row.status())) {
                throw ApiException.unprocessable("豁免申请当前状态不可审批: " + row.status());
            }
            if (request.contentVersion() != null
                    && request.contentVersion() != row.contentVersion()) {
                throw ApiException.unprocessable("审批确认的申请版本不一致：expected="
                        + row.contentVersion() + ", actual=" + request.contentVersion());
            }
            List<String> approvers = repositoryDao.listApprovers(exceptionId);
            if (approvers.contains(approver)) {
                throw ApiException.unprocessable("审批人已确认过该豁免申请: " + approver);
            }
            try {
                repositoryDao.insertApproval(exceptionId, approver, row.contentVersion(),
                        Instant.now(clock));
            } catch (DuplicateKeyException e) {
                // 同一审批人并发确认，唯一约束拦截。
                throw ApiException.unprocessable("审批人已确认过该豁免申请: " + approver);
            }
            List<String> allApprovers = repositoryDao.listApprovers(exceptionId);
            Instant approvedAt = null;
            if (allApprovers.size() >= REQUIRED_APPROVERS) {
                approvedAt = Instant.now(clock);
                int affected = repositoryDao.markExceptionApproved(
                        exceptionId, row.contentVersion(), approvedAt);
                if (affected == 0) {
                    throw ApiException.unprocessable("豁免申请状态已变化，审批未生效");
                }
            }
            return toExceptionResponse(
                    repositoryDao.lockException(exceptionId), allApprovers);
        });
    }

    @Override
    public ExceptionResponse revokeException(long exceptionId, ExceptionRevokeRequest request) {
        if (request == null || request.revoker() == null || request.revoker().isBlank()) {
            throw ApiException.badRequest("revoker 不能为空");
        }
        return inWriteTransaction(() -> {
            ExceptionRow row = repositoryDao.lockException(exceptionId);
            if (row == null) {
                throw ApiException.notFound("豁免申请不存在: " + exceptionId);
            }
            if (!"APPROVED".equals(row.status())) {
                throw ApiException.unprocessable("仅已生效豁免可撤销，当前状态: " + row.status());
            }
            Instant now = Instant.now(clock);
            int affected = repositoryDao.markExceptionRevoked(exceptionId, now);
            if (affected == 0) {
                throw ApiException.unprocessable("豁免申请状态已变化，撤销未生效");
            }
            return toExceptionResponse(repositoryDao.lockException(exceptionId),
                    repositoryDao.listApprovers(exceptionId));
        });
    }

    @Override
    public ExceptionResponse getException(long exceptionId) {
        ExceptionRow row = repositoryDao.getException(exceptionId);
        if (row == null) {
            throw ApiException.notFound("豁免申请不存在: " + exceptionId);
        }
        return toExceptionResponse(row, repositoryDao.listApprovers(exceptionId));
    }

    @Override
    public List<ExceptionResponse> listExceptions() {
        List<ExceptionResponse> result = new ArrayList<>();
        for (ExceptionRow row : repositoryDao.listExceptions()) {
            result.add(toExceptionResponse(row, repositoryDao.listApprovers(row.id())));
        }
        return result;
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        validateLockRequest(request);
        String hash = sha256(OP_LOCK + "|" + request.rootName().trim() + "|" + request.rootVersion()
                + "|" + request.expectedRepositoryVersion() + "|"
                + request.exceptions().stream().distinct().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse(""));
        return executeIdempotent(requestId, OP_LOCK, hash, 201,
                () -> doLock(request), LockFileResponse.class);
    }

    @Override
    public List<LockFileResponse> listLocks() {
        List<LockFileResponse> result = new ArrayList<>();
        for (LockFileRow row : repositoryDao.listLockFiles()) {
            result.add(toLockResponse(row,
                    repositoryDao.listLockEntries(row.id()),
                    repositoryDao.listLockExceptions(row.id())));
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
                repositoryDao.listLockExceptions(id));
    }

    // ------------------------------------------------------------------
    // 业务操作（运行在已加行锁的写事务内）
    // ------------------------------------------------------------------

    private ArtifactResponse doRegister(RegisterArtifactRequest request, RiskLevel riskLevel) {
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
        long artifactId = repositoryDao.insertArtifact(name, version, riskLevel, now);
        for (var dep : request.dependencies()) {
            repositoryDao.insertDependency(artifactId, dep.name().trim(),
                    dep.minimumVersion(), dep.maximumVersion());
        }
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();

        return new ArtifactResponse(name, version, false, riskLevel.name(), repositoryVersion, now,
                toDependencyViews(request.dependencies()));
    }

    private ArtifactResponse doMarkRisk(String name, int version, RiskLevel riskLevel) {
        ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
        if (artifact == null) {
            throw ApiException.notFound("制品版本不存在: " + name + ":" + version);
        }
        repositoryDao.updateRiskLevel(name, version, riskLevel);
        // 风险级别影响锁定门禁，属于仓库状态变更，推进版本号以便锁定方感知。
        long repositoryVersion = repositoryDao.incrementRepositoryVersion();
        return new ArtifactResponse(name, version, artifact.withdrawn(), riskLevel.name(),
                repositoryVersion, Instant.now(clock),
                toDependencyViews(artifact.dependencies()));
    }

    private ExceptionResponse doCreateException(ExceptionCreateRequest request) {
        Instant now = Instant.now(clock);
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.unprocessable("豁免到期时刻必须晚于当前时间");
        }
        long id = repositoryDao.insertException(request.dependencyName().trim(),
                request.dependencyVersion(), request.expiresAt(), request.reason().trim(), now);
        return toExceptionResponse(repositoryDao.lockException(id), List.of());
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
        return new ArtifactResponse(name, version, true, artifact.riskLevel().name(),
                repositoryVersion, Instant.now(clock), toDependencyViews(artifact.dependencies()));
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

        // 解析结果与风险级别、豁免门禁均在同一事务（持有全局写锁）内判定，
        // 豁免撤销/审批并发时按已提交状态为准，不存在绕过窗口。
        Instant now = Instant.now(clock);
        TreeMap<String, ResolvedEntry> resolved = new TreeMap<>();
        for (Map.Entry<String, Integer> e : solution.entrySet()) {
            ArtifactVersion artifact = repositoryDao.loadArtifact(e.getKey(), e.getValue());
            if (artifact == null) {
                throw ApiException.unprocessable(
                        "锁定解析的制品版本不存在: " + e.getKey() + ":" + e.getValue());
            }
            resolved.put(e.getKey(), new ResolvedEntry(artifact, null));
        }

        Map<Long, ExceptionRow> declared = repositoryDao.getExceptions(request.exceptions());
        // exceptionId -> 它所覆盖的高风险条目
        Map<Long, ResolvedEntry> coveredByException = new LinkedHashMap<>();
        for (Long exceptionId : request.exceptions()) {
            ExceptionRow exception = declared.get(exceptionId);
            if (exception == null) {
                throw ApiException.unprocessable("豁免不存在: " + exceptionId);
            }
            ResolvedEntry target = validateExceptionForLock(exception, resolved, rootName, now);
            if (coveredByException.put(exceptionId, target) != null) {
                throw ApiException.unprocessable("豁免重复声明: " + exceptionId);
            }
        }
        // 每个 HIGH 及以上风险的“依赖”条目必须恰好被一个有效且精确匹配的豁免覆盖；
        // 根制品是锁定主体而非依赖，不纳入该门禁。
        for (Map.Entry<String, ResolvedEntry> e : resolved.entrySet()) {
            ResolvedEntry entry = e.getValue();
            if (e.getKey().equals(rootName)) {
                continue;
            }
            if (entry.artifact().riskLevel().atLeastHigh() && entry.exceptionId() == null) {
                throw ApiException.unprocessable("依赖 " + entry.artifact().name() + ":"
                        + entry.artifact().version() + " 风险级别为 "
                        + entry.artifact().riskLevel().name() + "，缺少有效豁免，禁止锁定");
            }
        }

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        List<LockEntryResponse> entryViews = new ArrayList<>();
        resolved.forEach((n, entry) -> {
            repositoryDao.insertLockEntry(lockFileId, n, entry.artifact().version(),
                    entry.artifact().riskLevel(), entry.exceptionId());
            entryViews.add(new LockEntryResponse(n, entry.artifact().version(),
                    entry.artifact().riskLevel().name(), entry.exceptionId()));
        });

        List<ExceptionEvidence> evidences = new ArrayList<>();
        for (Map.Entry<Long, ResolvedEntry> used : coveredByException.entrySet()) {
            ExceptionRow exception = declared.get(used.getKey());
            List<String> approvers = repositoryDao.listApprovers(used.getKey());
            repositoryDao.insertLockException(lockFileId, used.getKey(),
                    exception.dependencyName(), exception.dependencyVersion(),
                    exception.expiresAt(), exception.reason(), exception.status(),
                    String.join(",", approvers), now);
            evidences.add(new ExceptionEvidence(used.getKey(),
                    exception.dependencyName(), exception.dependencyVersion(),
                    exception.expiresAt(), exception.reason(), exception.status(),
                    List.copyOf(approvers), now));
        }
        evidences.sort(java.util.Comparator.comparing(ExceptionEvidence::dependencyName));

        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now,
                List.copyOf(entryViews), List.copyOf(evidences));
    }

    /**
     * 校验单个声明豁免对当前锁定解析结果有效：APPROVED、未到期（恰好到期即失效）、
     * 依赖名与精确版本完全一致且该条目确为 HIGH 及以上风险；返回被覆盖的条目。
     */
    private ResolvedEntry validateExceptionForLock(ExceptionRow exception,
                                                   TreeMap<String, ResolvedEntry> resolved,
                                                   String rootName,
                                                   Instant now) {
        ResolvedEntry target = resolved.get(exception.dependencyName());
        if (target == null || target.artifact().version() != exception.dependencyVersion()) {
            throw ApiException.unprocessable("豁免 " + exception.id() + " 版本不符：仅适用 "
                    + exception.dependencyName() + ":" + exception.dependencyVersion());
        }
        if (exception.dependencyName().equals(rootName)) {
            throw ApiException.unprocessable("豁免仅适用于依赖，不能用于根制品 "
                    + exception.dependencyName() + ":" + exception.dependencyVersion());
        }
        if (!target.artifact().riskLevel().atLeastHigh()) {
            throw ApiException.unprocessable("依赖 " + exception.dependencyName() + ":"
                    + exception.dependencyVersion() + " 风险级别为 "
                    + target.artifact().riskLevel().name() + "，无需豁免");
        }
        if ("PENDING".equals(exception.status())) {
            throw ApiException.unprocessable("豁免 " + exception.id() + " 尚未完成双人审批");
        }
        if ("REVOKED".equals(exception.status())) {
            throw ApiException.unprocessable("豁免 " + exception.id() + " 已撤销");
        }
        if (!"APPROVED".equals(exception.status())) {
            throw ApiException.unprocessable("豁免 " + exception.id() + " 状态不合法: "
                    + exception.status());
        }
        if (!now.isBefore(exception.expiresAt())) {
            // 恰好到期与已到期同等处理：不再有效。
            throw ApiException.unprocessable("豁免 " + exception.id() + " 已到期（到期时刻 "
                    + exception.expiresAt() + "）");
        }
        if (target.exceptionId() != null) {
            throw ApiException.unprocessable("依赖 " + exception.dependencyName() + ":"
                    + exception.dependencyVersion() + " 只能使用一个豁免，存在重复覆盖");
        }
        target = target.withExceptionId(exception.id());
        resolved.put(exception.dependencyName(), target);
        return target;
    }

    /** 解析后的锁定条目及其匹配到的豁免 ID（未匹配为 null）。 */
    private record ResolvedEntry(ArtifactVersion artifact, Long exceptionId) {
        ResolvedEntry withExceptionId(Long id) {
            return new ResolvedEntry(artifact, id);
        }
    }

    // ------------------------------------------------------------------
    // 事务与幂等控制
    // ------------------------------------------------------------------

    /**
     * 当前写事务使用的 requestId，供同事务内的记录写入引用。
     */
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private <T> T inWriteTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> {
            // 审批/撤销与锁定共用单行仓库版本锁互斥，按提交顺序判定状态。
            repositoryDao.lockRepositoryState();
            return action.get();
        });
    }

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
            // 同键并发：赢家可能尚未提交。有界等待其提交后重放首次结果，
            // 超时仍无成功记录则报告冲突（赢家业务失败回滚时不占键，调用方可安全重试）。
            T replayAfterRace = awaitCommittedReplay(requestId, operation, requestHash, responseType);
            if (replayAfterRace != null) {
                return replayAfterRace;
            }
            throw ApiException.conflict("相同 requestId 的请求正在处理中: " + requestId);
        }
    }

    /**
     * 同键并发输家等待赢家事务提交：轮询已完成的幂等记录，最多等待约 10 秒。
     * 异参已占用立即 409；赢家回滚则等待结束后返回 null。
     */
    private <T> T awaitCommittedReplay(String requestId, String operation, String requestHash,
                                       Class<T> responseType) {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        do {
            IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
            if (record != null) {
                if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
                    throw ApiException.conflict(
                            "requestId 已用于不同参数的请求: " + requestId);
                }
                return readJson(record.responseJson(), responseType);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.nanoTime() < deadlineNanos);
        return null;
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

    private void validateExceptionRequest(ExceptionCreateRequest request) {
        if (request.dependencyName() == null || request.dependencyName().isBlank()) {
            throw ApiException.badRequest("dependencyName 不能为空");
        }
        if (request.dependencyVersion() <= 0) {
            throw ApiException.badRequest("dependencyVersion 必须为正整数");
        }
        if (request.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
    }

    private void validateLockRequest(LockRequest request) {
        Set<Long> unique = new HashSet<>();
        for (Long exceptionId : request.exceptions()) {
            if (exceptionId == null || exceptionId <= 0) {
                throw ApiException.badRequest("豁免 ID 必须为正整数");
            }
            if (!unique.add(exceptionId)) {
                throw ApiException.unprocessable(
                        "豁免 ID 重复声明: " + exceptionId);
            }
        }
    }

    private RiskLevel parseRiskLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return RiskLevel.NONE;
        }
        RiskLevel level = RiskLevel.parse(raw);
        if (level == null) {
            throw ApiException.badRequest("未知风险级别: " + raw);
        }
        return level;
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

    private ExceptionResponse toExceptionResponse(ExceptionRow row, List<String> approvers) {
        return new ExceptionResponse(row.id(), row.dependencyName(), row.dependencyVersion(),
                row.expiresAt(), row.reason(), row.status(), row.contentVersion(),
                List.copyOf(approvers), row.createdAt(), row.approvedAt(), row.revokedAt());
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries,
                                            List<LockExceptionRow> exceptions) {
        List<LockEntryResponse> entryViews = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version(),
                        e.riskLevel().name(), e.exceptionId()))
                .toList();
        List<ExceptionEvidence> evidenceViews = exceptions.stream()
                .map(x -> new ExceptionEvidence(x.exceptionId(), x.dependencyName(),
                        x.dependencyVersion(), x.expiresAt(), x.reason(), x.status(),
                        List.copyOf(x.approvers()), x.lockedAt()))
                .toList();
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), entryViews, evidenceViews);
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
