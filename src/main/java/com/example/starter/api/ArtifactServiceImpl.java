package com.example.starter.api;

import com.example.starter.api.dto.ApproveRiskExceptionRequest;
import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CreateRiskExceptionRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DependencyView;
import com.example.starter.api.dto.ExceptionApprovalView;
import com.example.starter.api.dto.ExceptionRef;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReviseRiskExceptionRequest;
import com.example.starter.api.dto.RiskExceptionView;
import com.example.starter.api.dto.RiskResponse;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.LockResolver;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.RiskExceptionStatus;
import com.example.starter.domain.RiskLevel;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.ApprovalRow;
import com.example.starter.repo.RepositoryDao.IdempotentRecord;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.RepositoryDao.RiskExceptionRow;
import com.example.starter.repo.RepositoryDao.RiskRow;
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
 * <p>高风险门禁：锁定解析出的每个精确版本若风险为 HIGH 或更高，请求必须携带一个
 * 精确匹配名称与版本、两名不同审批人已确认同一申请版本、未撤销且严格晚于当前 UTC
 * 时刻到期的豁免；否则返回 422 且不写入任何锁数据。豁免依据（ID、申请版本、审批人）
 * 与当时风险级别在锁定时固化，豁免撤销/到期只影响后续新锁定。
 */
@Service
public class ArtifactServiceImpl implements ArtifactService {

    private static final int MAX_NAMES = 20;
    private static final int MAX_VERSIONS_PER_NAME = 5;

    private static final String OP_REGISTER = "REGISTER_ARTIFACT";
    private static final String OP_WITHDRAW = "WITHDRAW_ARTIFACT";
    private static final String OP_LOCK = "CREATE_LOCK";
    private static final String OP_MARK_RISK = "MARK_RISK";
    private static final String OP_CREATE_EXCEPTION = "CREATE_RISK_EXCEPTION";
    private static final String OP_APPROVE_EXCEPTION = "APPROVE_RISK_EXCEPTION";
    private static final String OP_REVISE_EXCEPTION = "REVISE_RISK_EXCEPTION";
    private static final String OP_REVOKE_EXCEPTION = "REVOKE_RISK_EXCEPTION";

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
    public RiskResponse markRisk(String requestId, MarkRiskRequest request) {
        requireRequestId(requestId);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name 不能为空");
        }
        RiskLevel level = parseRiskLevel(request.riskLevel());
        String name = request.name().trim();
        String reason = normalizeReason(request.reason());
        String hash = sha256(OP_MARK_RISK + "|" + name + "|" + request.version() + "|"
                + level.name() + "|" + (reason == null ? "" : reason));
        return executeIdempotent(requestId, OP_MARK_RISK, hash, 200,
                () -> doMarkRisk(name, request.version(), level, reason), RiskResponse.class);
    }

    @Override
    public List<RiskResponse> listRisks() {
        return repositoryDao.listRisks().stream()
                .map(r -> new RiskResponse(r.name(), r.version(), r.riskLevel(), r.reason(),
                        r.createdAt(), r.updatedAt()))
                .toList();
    }

    @Override
    public RiskExceptionView createRiskException(String requestId, CreateRiskExceptionRequest request) {
        requireRequestId(requestId);
        if (request.dependencyName() == null || request.dependencyName().isBlank()) {
            throw ApiException.badRequest("dependencyName 不能为空");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (request.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        String name = request.dependencyName().trim();
        String reason = request.reason().trim();
        Instant expiresAt = request.expiresAt();
        String hash = sha256(OP_CREATE_EXCEPTION + "|" + name + "|" + request.dependencyVersion()
                + "|" + reason + "|" + expiresAt);
        return executeIdempotent(requestId, OP_CREATE_EXCEPTION, hash, 201,
                () -> doCreateException(name, request.dependencyVersion(), reason, expiresAt),
                RiskExceptionView.class);
    }

    @Override
    public RiskExceptionView approveRiskException(String requestId, long exceptionId,
                                                  ApproveRiskExceptionRequest request) {
        requireRequestId(requestId);
        if (request.approver() == null || request.approver().isBlank()) {
            throw ApiException.badRequest("approver 不能为空");
        }
        if (request.exceptionVersion() == null) {
            throw ApiException.badRequest("exceptionVersion 不能为空");
        }
        String approver = request.approver().trim();
        int version = request.exceptionVersion();
        String hash = sha256(OP_APPROVE_EXCEPTION + "|" + exceptionId + "|" + approver + "|" + version);
        return executeIdempotent(requestId, OP_APPROVE_EXCEPTION, hash, 200,
                () -> doApprove(exceptionId, approver, version), RiskExceptionView.class);
    }

    @Override
    public RiskExceptionView reviseRiskException(String requestId, long exceptionId,
                                                 ReviseRiskExceptionRequest request) {
        requireRequestId(requestId);
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (request.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        String reason = request.reason().trim();
        Instant expiresAt = request.expiresAt();
        String hash = sha256(OP_REVISE_EXCEPTION + "|" + exceptionId + "|" + reason + "|" + expiresAt);
        return executeIdempotent(requestId, OP_REVISE_EXCEPTION, hash, 200,
                () -> doRevise(exceptionId, reason, expiresAt), RiskExceptionView.class);
    }

    @Override
    public RiskExceptionView revokeRiskException(String requestId, long exceptionId) {
        requireRequestId(requestId);
        String hash = sha256(OP_REVOKE_EXCEPTION + "|" + exceptionId);
        return executeIdempotent(requestId, OP_REVOKE_EXCEPTION, hash, 200,
                () -> doRevoke(exceptionId), RiskExceptionView.class);
    }

    @Override
    public List<RiskExceptionView> listRiskExceptions() {
        List<RiskExceptionView> result = new ArrayList<>();
        for (RiskExceptionRow row : repositoryDao.listRiskExceptions()) {
            result.add(toExceptionView(row, repositoryDao.listApprovals(row.id())));
        }
        return result;
    }

    @Override
    public RiskExceptionView getRiskException(long exceptionId) {
        RiskExceptionRow row = repositoryDao.findRiskException(exceptionId);
        if (row == null) {
            throw ApiException.notFound("豁免不存在: " + exceptionId);
        }
        return toExceptionView(row, repositoryDao.listApprovals(exceptionId));
    }

    @Override
    public LockFileResponse createLock(String requestId, LockRequest request) {
        requireRequestId(requestId);
        if (request.rootName() == null || request.rootName().isBlank()) {
            throw ApiException.badRequest("rootName 不能为空");
        }
        String hash = sha256(OP_LOCK + "|" + request.rootName().trim() + "|" + request.rootVersion()
                + "|" + request.expectedRepositoryVersion() + "|" + canonicalExceptions(request));
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

    private RiskResponse doMarkRisk(String name, int version, RiskLevel level, String reason) {
        Instant now = Instant.now(clock);
        RiskRow existing = repositoryDao.findRisk(name, version);
        if (existing == null) {
            repositoryDao.insertRisk(name, version, level.name(), reason, now);
        } else {
            repositoryDao.updateRisk(name, version, level.name(), reason, now);
        }
        // 风险标记不改变制品依赖图，仓库版本号仅随登记/撤回推进；与锁定的并发由行锁串行化。
        RiskRow saved = repositoryDao.findRisk(name, version);
        return new RiskResponse(saved.name(), saved.version(), saved.riskLevel(),
                saved.reason(), saved.createdAt(), saved.updatedAt());
    }

    private RiskExceptionView doCreateException(String name, int version, String reason,
                                                Instant expiresAt) {
        Instant now = Instant.now(clock);
        if (!expiresAt.isAfter(now)) {
            throw ApiException.unprocessable("豁免到期时刻必须晚于当前 UTC 时刻");
        }
        long id = repositoryDao.insertRiskException(name, version, reason, expiresAt, now);
        return toExceptionView(repositoryDao.lockRiskException(id), List.of());
    }

    private RiskExceptionView doApprove(long exceptionId, String approver, int exceptionVersion) {
        RiskExceptionRow row = repositoryDao.lockRiskException(exceptionId);
        if (row == null) {
            throw ApiException.notFound("豁免不存在: " + exceptionId);
        }
        RiskExceptionStatus status = RiskExceptionStatus.valueOf(row.status());
        if (status == RiskExceptionStatus.REVOKED) {
            throw ApiException.unprocessable("豁免已撤销，不能审批: " + exceptionId);
        }
        if (status == RiskExceptionStatus.APPROVED) {
            throw ApiException.conflict("豁免已生效，不能重复审批: " + exceptionId);
        }
        if (row.exceptionVersion() != exceptionVersion) {
            throw ApiException.conflict("豁免申请版本不匹配：expected=" + exceptionVersion
                    + ", actual=" + row.exceptionVersion());
        }
        boolean alreadyApproved = repositoryDao.listApprovals(exceptionId).stream()
                .anyMatch(a -> a.approver().equals(approver));
        if (alreadyApproved) {
            throw ApiException.conflict("审批人已确认过该豁免: " + approver);
        }
        Instant now = Instant.now(clock);
        repositoryDao.insertApproval(exceptionId, approver, exceptionVersion, now);
        if (repositoryDao.countApprovalsForVersion(exceptionId, exceptionVersion) >= 2) {
            repositoryDao.markExceptionApproved(exceptionId, now);
        }
        return toExceptionView(repositoryDao.lockRiskException(exceptionId),
                repositoryDao.listApprovals(exceptionId));
    }

    private RiskExceptionView doRevise(long exceptionId, String reason, Instant expiresAt) {
        RiskExceptionRow row = repositoryDao.lockRiskException(exceptionId);
        if (row == null) {
            throw ApiException.notFound("豁免不存在: " + exceptionId);
        }
        RiskExceptionStatus status = RiskExceptionStatus.valueOf(row.status());
        if (status == RiskExceptionStatus.APPROVED) {
            throw ApiException.conflict("豁免已生效，不能修订: " + exceptionId);
        }
        if (status == RiskExceptionStatus.REVOKED) {
            throw ApiException.unprocessable("豁免已撤销，不能修订: " + exceptionId);
        }
        Instant now = Instant.now(clock);
        if (!expiresAt.isAfter(now)) {
            throw ApiException.unprocessable("豁免到期时刻必须晚于当前 UTC 时刻");
        }
        repositoryDao.reviseRiskException(exceptionId, reason, expiresAt);
        RiskExceptionRow revised = repositoryDao.lockRiskException(exceptionId);
        // 申请版本加一后，旧版本审批全部作废，审批人可重新确认新版本。
        repositoryDao.deleteApprovalsExceptVersion(exceptionId, revised.exceptionVersion());
        return toExceptionView(revised, List.of());
    }

    private RiskExceptionView doRevoke(long exceptionId) {
        RiskExceptionRow row = repositoryDao.lockRiskException(exceptionId);
        if (row == null) {
            throw ApiException.notFound("豁免不存在: " + exceptionId);
        }
        RiskExceptionStatus status = RiskExceptionStatus.valueOf(row.status());
        if (status == RiskExceptionStatus.PENDING) {
            throw ApiException.unprocessable("豁免尚未生效，不能撤销: " + exceptionId);
        }
        if (status == RiskExceptionStatus.REVOKED) {
            throw ApiException.conflict("豁免已撤销: " + exceptionId);
        }
        repositoryDao.revokeRiskException(exceptionId, Instant.now(clock));
        return toExceptionView(repositoryDao.lockRiskException(exceptionId),
                repositoryDao.listApprovals(exceptionId));
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

        // 高风险门禁：为每个解析出的精确版本读取风险，HIGH 及以上必须有精确匹配的有效豁免。
        Instant now = Instant.now(clock);
        Map<String, RiskLevel> riskByEntry = resolveRiskLevels(solution);
        Map<String, ValidatedException> exceptionByEntry =
                validateExceptions(request, solution, riskByEntry, now);

        long lockFileId = repositoryDao.insertLockFile(rootName, rootVersion, currentVersion,
                currentRequestId.get(), now);
        List<LockEntryResponse> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> chosen : solution.entrySet()) {
            String name = chosen.getKey();
            int resolvedVersion = chosen.getValue();
            RiskLevel risk = riskByEntry.getOrDefault(name, RiskLevel.NONE);
            ValidatedException basis = exceptionByEntry.get(name);
            if (basis == null) {
                repositoryDao.insertLockEntry(lockFileId, name, resolvedVersion,
                        risk.name(), null, null, null);
            } else {
                repositoryDao.insertLockEntry(lockFileId, name, resolvedVersion, risk.name(),
                        basis.row().id(), basis.row().exceptionVersion(),
                        RiskExceptionStatus.APPROVED.name());
                int seq = 1;
                for (ApprovalRow approval : basis.approvers()) {
                    repositoryDao.insertLockEntryApprover(lockFileId, name, approval.approver(), seq++);
                }
            }
            entries.add(new LockEntryResponse(name, resolvedVersion, risk.name(),
                    basis == null ? null : new LockEntryResponse.ExceptionBasis(
                            basis.row().id(), basis.row().exceptionVersion(),
                            RiskExceptionStatus.APPROVED.name(),
                            basis.approvers().stream().map(ApprovalRow::approver).toList())));
        }
        return new LockFileResponse(lockFileId, rootName, rootVersion, currentVersion, now, entries);
    }

    /**
     * 门禁校验通过的豁免，绑定到其精确覆盖的锁条目。
     */
    private record ValidatedException(RiskExceptionRow row, List<ApprovalRow> approvers) {
    }

    /**
     * 逐个校验请求携带的豁免，并确认每个 HIGH 及以上风险条目恰有一个有效豁免覆盖。
     * 任何缺豁免、已撤销、已到期（恰好到期也算）、名称/版本不符、重复引用均返回 422。
     */
    private Map<String, ValidatedException> validateExceptions(
            LockRequest request, Map<String, Integer> solution,
            Map<String, RiskLevel> riskByEntry, Instant now) {
        Map<String, ValidatedException> matched = new LinkedHashMap<>();
        Set<Long> seenExceptionIds = new HashSet<>();
        for (ExceptionRef ref : request.exceptions()) {
            if (ref.exceptionId() == null || !seenExceptionIds.add(ref.exceptionId())) {
                throw ApiException.unprocessable("豁免引用重复: " + ref.exceptionId());
            }
            RiskExceptionRow row = repositoryDao.findRiskException(ref.exceptionId());
            if (row == null) {
                throw ApiException.unprocessable("豁免不存在: " + ref.exceptionId());
            }
            Integer resolvedVersion = solution.get(row.dependencyName());
            if (resolvedVersion == null || resolvedVersion != row.dependencyVersion()) {
                throw ApiException.unprocessable("豁免 " + ref.exceptionId()
                        + " 的适用依赖及版本与锁定解析结果不符: "
                        + row.dependencyName() + "@" + row.dependencyVersion());
            }
            RiskLevel risk = riskByEntry.getOrDefault(row.dependencyName(), RiskLevel.NONE);
            if (!risk.blocksLock()) {
                throw ApiException.unprocessable("依赖 " + row.dependencyName() + "@"
                        + row.dependencyVersion() + " 风险为 " + risk.name()
                        + "，无需引用豁免 " + ref.exceptionId());
            }
            RiskExceptionStatus status = RiskExceptionStatus.valueOf(row.status());
            if (status != RiskExceptionStatus.APPROVED) {
                throw ApiException.unprocessable("豁免 " + ref.exceptionId()
                        + (status == RiskExceptionStatus.REVOKED ? " 已撤销" : " 尚未经两名审批人确认生效"));
            }
            // 恰好到期不再有效：到期时刻必须严格晚于当前 UTC 时刻。
            if (!row.expiresAt().isAfter(now)) {
                throw ApiException.unprocessable("豁免 " + ref.exceptionId() + " 已到期（到期时刻 "
                        + row.expiresAt() + "，当前 " + now + "）");
            }
            List<ApprovalRow> approvals = repositoryDao.listApprovals(row.id()).stream()
                    .filter(a -> a.exceptionVersion() == row.exceptionVersion())
                    .toList();
            long distinctApprovers = approvals.stream().map(ApprovalRow::approver).distinct().count();
            if (approvals.size() < 2 || distinctApprovers < 2) {
                // 状态与审批计数不一致的防御性分支，正常流程不会到达。
                throw ApiException.unprocessable("豁免 " + ref.exceptionId()
                        + " 缺少两名不同审批人对同一申请版本的确认");
            }
            if (matched.put(row.dependencyName(), new ValidatedException(row, approvals)) != null) {
                throw ApiException.unprocessable("依赖 " + row.dependencyName()
                        + " 被多个豁免重复覆盖");
            }
        }

        for (Map.Entry<String, RiskLevel> entry : riskByEntry.entrySet()) {
            if (entry.getValue().blocksLock() && !matched.containsKey(entry.getKey())) {
                throw ApiException.unprocessable("依赖 " + entry.getKey() + "@"
                        + solution.get(entry.getKey()) + " 风险为 " + entry.getValue().name()
                        + "，缺少有效的高风险豁免，禁止新锁定");
            }
        }
        return matched;
    }

    /** 批量读取解析结果的风险标记，未标记视为 NONE。 */
    private Map<String, RiskLevel> resolveRiskLevels(Map<String, Integer> solution) {
        List<Map.Entry<String, Integer>> targets = new ArrayList<>(solution.entrySet());
        Map<String, RiskRow> rows = repositoryDao.findRisks(targets);
        Map<String, RiskLevel> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> target : targets) {
            RiskRow row = rows.get(RepositoryDao.riskKey(target.getKey(), target.getValue()));
            if (row != null) {
                result.put(target.getKey(), RiskLevel.valueOf(row.riskLevel()));
            }
        }
        return result;
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

    private RiskLevel parseRiskLevel(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("riskLevel 不能为空");
        }
        try {
            return RiskLevel.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知风险等级: " + code);
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }

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

    private String canonicalExceptions(LockRequest request) {
        return request.exceptions().stream()
                .map(e -> String.valueOf(e.exceptionId()))
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

    private RiskExceptionView toExceptionView(RiskExceptionRow row, List<ApprovalRow> approvals) {
        RiskExceptionStatus status = RiskExceptionStatus.valueOf(row.status());
        List<ExceptionApprovalView> views = approvals.stream()
                .map(a -> new ExceptionApprovalView(a.approver(), a.exceptionVersion(), a.approvedAt()))
                .toList();
        boolean valid = status == RiskExceptionStatus.APPROVED
                && row.expiresAt().isAfter(Instant.now(clock));
        return new RiskExceptionView(row.id(), row.dependencyName(), row.dependencyVersion(),
                row.exceptionVersion(), status.name(), row.reason(), row.expiresAt(), valid,
                row.createdAt(), row.decidedAt(), views);
    }

    private LockFileResponse toLockResponse(LockFileRow row, List<LockEntryRow> entries) {
        List<LockEntryResponse> entryViews = new ArrayList<>();
        for (LockEntryRow entry : entries) {
            LockEntryResponse.ExceptionBasis basis = null;
            if (entry.exceptionId() != null && entry.exceptionVersion() != null) {
                List<String> approvers = repositoryDao.listLockEntryApprovers(row.id(), entry.name());
                basis = new LockEntryResponse.ExceptionBasis(entry.exceptionId(),
                        entry.exceptionVersion(), entry.statusAtLock(), List.copyOf(approvers));
            }
            entryViews.add(new LockEntryResponse(entry.name(), entry.version(),
                    entry.riskLevel(), basis));
        }
        return new LockFileResponse(row.id(), row.rootName(), row.rootVersion(),
                row.repositoryVersion(), row.createdAt(), List.copyOf(entryViews));
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
