package com.example.starter.api;

import com.example.starter.api.dto.AdvisoryRequest;
import com.example.starter.api.dto.AdvisoryResponse;
import com.example.starter.api.dto.ExceptionRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.PublishSnapshotResponse;
import com.example.starter.api.dto.VulnerabilityHitResponse;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.repo.SecurityDao;
import com.example.starter.repo.SecurityDao.AdvisoryRow;
import com.example.starter.repo.SecurityDao.ExceptionRow;
import com.example.starter.repo.SecurityDao.HitRow;
import com.example.starter.repo.SecurityDao.PublishEntryRow;
import com.example.starter.repo.SecurityDao.PublishRow;
import com.example.starter.support.ApiException;
import com.example.starter.support.GateBlockedException;
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
import java.util.List;

/**
 * 制品漏洞豁免与发布门禁服务实现。
 *
 * <p>公告更新、豁免确认、撤销与发布全部在持有 {@code repository_state} 行锁的
 * 单个事务内完成，按事务提交顺序串行裁决；业务失败整体回滚，不占用 requestId
 * 与 exceptionKey。豁免双人确认成功后写入不可变双人快照；发布成功后复制
 * 不可变发布快照，撤销与到期均不改写历史。
 */
@Service
public class SecurityServiceImpl implements SecurityService {

    private static final String OP_CONFIRM = "CONFIRM_EXCEPTION";
    private static final String OP_REVOKE = "REVOKE_EXCEPTION";
    private static final String OP_PUBLISH = "PUBLISH_LOCK";
    private static final String SEVERITY_CRITICAL = "CRITICAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_REVOKED = "REVOKED";

    private final SecurityDao securityDao;
    private final RepositoryDao repositoryDao;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityServiceImpl(SecurityDao securityDao,
                               RepositoryDao repositoryDao,
                               TransactionTemplate transactionTemplate,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.securityDao = securityDao;
        this.repositoryDao = repositoryDao;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 公告
    // ------------------------------------------------------------------

    @Override
    public AdvisoryResponse upsertAdvisory(AdvisoryRequest request) {
        validateAdvisory(request);
        Instant now = Instant.now(clock);
        AdvisoryRow row = transactionTemplate.execute(status -> {
            repositoryDao.lockRepositoryState();
            return securityDao.upsertAdvisory(request.vulnerabilityId().trim(),
                    request.artifactName().trim(), request.artifactVersion(),
                    request.severity().trim().toUpperCase(), request.expiresAt(), now);
        });
        return toAdvisoryResponse(row);
    }

    @Override
    public List<AdvisoryResponse> listAdvisories() {
        return securityDao.listAdvisories().stream().map(this::toAdvisoryResponse).toList();
    }

    // ------------------------------------------------------------------
    // 豁免：创建（第一审核人）/ 确认（第二审核人）
    // ------------------------------------------------------------------

    @Override
    public ExceptionResponse confirmException(String requestId, ExceptionRequest request, String reviewer) {
        requireRequestId(requestId);
        if (reviewer == null || reviewer.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Reviewer");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.badRequest("豁免理由不能为空");
        }
        String actingReviewer = reviewer.trim();
        String vulnerabilityId = request.vulnerabilityId().trim();
        String reason = request.reason().trim();
        // 指纹含锁定图版本、漏洞、审核人、到期和理由；审核人取本次确认者，
        // 因而两名审核人的两次确认分别是两个独立可幂等重放的键。
        String key = exceptionKey(request.lockFileId(), vulnerabilityId, actingReviewer,
                request.expiresAt(), reason);

        return executeIdempotent(requestId, OP_CONFIRM, key,
                () -> doConfirm(request.lockFileId(), vulnerabilityId, request.expiresAt(),
                        reason, actingReviewer),
                ExceptionResponse.class);
    }

    private ExceptionResponse doConfirm(long lockFileId, String vulnerabilityId,
                                        Instant expiresAt, String reason, String actingReviewer) {
        Instant now = Instant.now(clock);
        LockFileRow lock = requireLock(lockFileId);

        if (!expiresAt.isAfter(now)) {
            throw ApiException.unprocessable("豁免到期时刻必须在未来: " + expiresAt);
        }
        // 第一人创建与第二人确认时都校验漏洞仍命中该锁定图且公告未过期。
        requireActiveHit(lockFileId, vulnerabilityId, now);

        // 同键（锁定图版本、漏洞、本审核人、到期、理由）成功重放首次结果。
        ExceptionRow sameKey = securityDao.findExceptionByKey(
                exceptionKey(lockFileId, vulnerabilityId, actingReviewer, expiresAt, reason));
        if (sameKey != null) {
            return toExceptionResponse(sameKey);
        }

        ExceptionRow active = findActiveException(lockFileId, vulnerabilityId);
        if (active != null) {
            if (!active.expiresAt().equals(expiresAt) || !active.reason().equals(reason)) {
                throw ApiException.conflict(
                        "该锁定图版本与漏洞已存在参数不同的豁免处理中: " + vulnerabilityId);
            }
            if (STATUS_CONFIRMED.equals(active.status())) {
                // 双人快照不可变：仅两名参与审核人可重放，第三审核人拒绝。
                if (actingReviewer.equals(active.reviewer1())
                        || actingReviewer.equals(active.reviewer2())) {
                    return toExceptionResponse(active);
                }
                throw ApiException.conflict(
                        "豁免已由两名不同审核人确认完成，不可再追加审核人: " + active.id());
            }
            // PENDING
            if (active.reviewer1().equals(actingReviewer)) {
                // 第一审核人重复提交（指纹一致时上方已重放，此处为防御性重放）。
                return toExceptionResponse(active);
            }
            // 第二审核人：必须与第一审核人不同，且再次校验到期时刻在未来、漏洞仍命中
            // （上方已统一校验）。条件更新保证只有一次确认成功。
            int affected = securityDao.completeException(active.id(), actingReviewer, now);
            if (affected == 0) {
                // 并发确认抢先提交：重放已完成的双人快照。
                ExceptionRow winner = securityDao.getException(active.id());
                if (winner != null && STATUS_CONFIRMED.equals(winner.status())) {
                    return toExceptionResponse(winner);
                }
                throw ApiException.conflict("豁免状态已变化，请重新查询: " + active.id());
            }
            return toExceptionResponse(securityDao.getException(active.id()));
        }

        // 无进行中豁免：本次审核人即第一审核人，创建 PENDING。
        long id = securityDao.insertPendingException(
                exceptionKey(lockFileId, vulnerabilityId, actingReviewer, expiresAt, reason),
                lockFileId, vulnerabilityId, expiresAt, reason, actingReviewer, now,
                currentRequestId());
        return toExceptionResponse(securityDao.getException(id));
    }

    // ------------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------------

    @Override
    public ExceptionResponse revokeException(String requestId, long exceptionId, String reviewer) {
        requireRequestId(requestId);
        if (reviewer == null || reviewer.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Reviewer");
        }
        String actingReviewer = reviewer.trim();
        String hash = sha256(OP_REVOKE + "|" + exceptionId + "|" + actingReviewer);
        return executeIdempotent(requestId, OP_REVOKE, hash, () -> {
            ExceptionRow row = securityDao.getException(exceptionId);
            if (row == null) {
                throw ApiException.notFound("豁免不存在: " + exceptionId);
            }
            if (STATUS_REVOKED.equals(row.status())) {
                throw ApiException.conflict("豁免已撤销: " + exceptionId);
            }
            Instant now = Instant.now(clock);
            int affected = securityDao.revokeException(exceptionId, actingReviewer, now);
            if (affected == 0) {
                throw ApiException.conflict("豁免状态已变化，请重新查询: " + exceptionId);
            }
            return toExceptionResponse(securityDao.getException(exceptionId));
        }, ExceptionResponse.class);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<ExceptionResponse> listExceptions(long lockFileId) {
        return securityDao.listExceptions(lockFileId).stream()
                .map(this::toExceptionResponse).toList();
    }

    @Override
    public List<VulnerabilityHitResponse> listVulnerabilityHits(long lockFileId) {
        requireLock(lockFileId);
        Instant now = Instant.now(clock);
        List<VulnerabilityHitResponse> result = new ArrayList<>();
        for (HitRow hit : securityDao.listActiveHits(lockFileId, now)) {
            ExceptionRow exception = latestException(lockFileId, hit.vulnerabilityId());
            result.add(toHitResponse(hit, exception, now));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 发布门禁
    // ------------------------------------------------------------------

    @Override
    public PublishSnapshotResponse publish(String requestId, long lockFileId) {
        requireRequestId(requestId);
        String hash = sha256(OP_PUBLISH + "|" + lockFileId);
        return executeIdempotent(requestId, OP_PUBLISH, hash,
                () -> doPublish(lockFileId), PublishSnapshotResponse.class);
    }

    private PublishSnapshotResponse doPublish(long lockFileId) {
        Instant now = Instant.now(clock);
        LockFileRow lock = requireLock(lockFileId);
        List<LockEntryRow> entries = repositoryDao.listLockEntries(lockFileId);

        // 收集所有未过期 CRITICAL 命中；任一缺少有效双人豁免即整次 422，列出全部阻断项。
        List<VulnerabilityHitResponse> blocked = new ArrayList<>();
        for (HitRow hit : securityDao.listActiveHits(lockFileId, now)) {
            if (!SEVERITY_CRITICAL.equalsIgnoreCase(hit.severity())) {
                continue;
            }
            ExceptionRow exception = latestException(lockFileId, hit.vulnerabilityId());
            if (!hasValidException(exception, now)) {
                blocked.add(toHitResponse(hit, exception, now));
            }
        }
        if (!blocked.isEmpty()) {
            // 抛出后事务回滚：不写发布快照、不推进版本、不占用 requestId。
            throw new GateBlockedException(blocked);
        }

        long publishId = securityDao.insertPublish(lockFileId, lock.rootName(), lock.rootVersion(),
                lock.repositoryVersion(), currentRequestId(), now);
        for (LockEntryRow entry : entries) {
            securityDao.insertPublishEntry(publishId, entry.name(), entry.version());
        }
        return toPublishResponse(securityDao.getPublish(publishId),
                securityDao.listPublishEntries(publishId));
    }

    @Override
    public List<PublishSnapshotResponse> listPublishes(long lockFileId) {
        requireLock(lockFileId);
        List<PublishSnapshotResponse> result = new ArrayList<>();
        for (PublishRow row : securityDao.listPublishes(lockFileId)) {
            result.add(toPublishResponse(row, securityDao.listPublishEntries(row.id())));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 门禁与作用域判定
    // ------------------------------------------------------------------

    /** 有效豁免：双人确认完成、未撤销、且到期时刻仍在未来。 */
    private boolean hasValidException(ExceptionRow row, Instant now) {
        return row != null
                && STATUS_CONFIRMED.equals(row.status())
                && row.expiresAt().isAfter(now);
    }

    /**
     * 命中对应的豁免作用域状态：NONE/PENDING/CONFIRMED/EXPIRED/REVOKED。
     * CONFIRMED 但已到期报告为 EXPIRED。
     */
    private String exceptionStatus(ExceptionRow row, Instant now) {
        if (row == null) {
            return "NONE";
        }
        return switch (row.status()) {
            case STATUS_PENDING -> STATUS_PENDING;
            case STATUS_REVOKED -> STATUS_REVOKED;
            case STATUS_CONFIRMED -> row.expiresAt().isAfter(now) ? STATUS_CONFIRMED : "EXPIRED";
            default -> "NONE";
        };
    }

    /** 查询某作用域（锁定图+漏洞）最近的一条豁免（含已撤销），无则 null。 */
    private ExceptionRow latestException(long lockFileId, String vulnerabilityId) {
        ExceptionRow result = null;
        for (ExceptionRow row : securityDao.listExceptions(lockFileId)) {
            if (row.vulnerabilityId().equals(vulnerabilityId)
                    && (result == null || row.id() > result.id())) {
                result = row;
            }
        }
        return result;
    }

    /** 查询某作用域当前进行中（PENDING/CONFIRMED）的豁免，无则 null。 */
    private ExceptionRow findActiveException(long lockFileId, String vulnerabilityId) {
        ExceptionRow result = null;
        for (ExceptionRow row : securityDao.listExceptions(lockFileId)) {
            if (row.vulnerabilityId().equals(vulnerabilityId)
                    && !STATUS_REVOKED.equals(row.status())
                    && (result == null || row.id() > result.id())) {
                result = row;
            }
        }
        return result;
    }

    private void requireActiveHit(long lockFileId, String vulnerabilityId, Instant now) {
        boolean hit = securityDao.listActiveHits(lockFileId, now).stream()
                .anyMatch(h -> h.vulnerabilityId().equals(vulnerabilityId));
        if (!hit) {
            throw ApiException.unprocessable(
                    "漏洞公告当前未命中该锁定图或已过期: " + vulnerabilityId);
        }
    }

    private LockFileRow requireLock(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁定图不存在: " + lockFileId);
        }
        return lock;
    }

    // ------------------------------------------------------------------
    // 幂等控制（与制品服务相同的 requestId 语义：成功留档，失败回滚不占键）
    // ------------------------------------------------------------------

    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    private <T> T executeIdempotent(String requestId, String operation, String requestHash,
                                    java.util.function.Supplier<T> action, Class<T> type) {
        T replay = replayIfPresent(requestId, operation, requestHash, type);
        if (replay != null) {
            return replay;
        }
        try {
            return transactionTemplate.execute(status -> {
                repositoryDao.lockRepositoryState();
                T existing = replayIfPresent(requestId, operation, requestHash, type);
                if (existing != null) {
                    return existing;
                }
                repositoryDao.insertPendingIdempotentRequest(
                        requestId, operation, requestHash, Instant.now(clock));
                currentRequestId.set(requestId);
                try {
                    T result = action.get();
                    repositoryDao.completeIdempotentRequest(requestId, 200, writeJson(result));
                    return result;
                } finally {
                    currentRequestId.remove();
                }
            });
        } catch (DuplicateKeyException e) {
            T replayAfterRace = replayIfPresent(requestId, operation, requestHash, type);
            if (replayAfterRace != null) {
                return replayAfterRace;
            }
            throw ApiException.conflict("相同 requestId 的请求正在处理中: " + requestId);
        }
    }

    private <T> T replayIfPresent(String requestId, String operation, String requestHash,
                                  Class<T> type) {
        RepositoryDao.IdempotentRecord record = repositoryDao.findIdempotentRequest(requestId);
        if (record == null) {
            return null;
        }
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已用于不同参数的请求: " + requestId);
        }
        return readJson(record.responseJson(), type);
    }

    private String currentRequestId() {
        String id = currentRequestId.get();
        if (id == null) {
            throw new IllegalStateException("当前无事务 requestId");
        }
        return id;
    }

    // ------------------------------------------------------------------
    // 校验与转换
    // ------------------------------------------------------------------

    private void validateAdvisory(AdvisoryRequest request) {
        if (request.vulnerabilityId() == null || request.vulnerabilityId().isBlank()) {
            throw ApiException.badRequest("vulnerabilityId 不能为空");
        }
        if (request.artifactName() == null || request.artifactName().isBlank()) {
            throw ApiException.badRequest("artifactName 不能为空");
        }
        if (request.severity() == null || request.severity().isBlank()) {
            throw ApiException.badRequest("severity 不能为空");
        }
        String severity = request.severity().trim().toUpperCase();
        if (!List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(severity)) {
            throw ApiException.badRequest("severity 取值非法: " + request.severity());
        }
        if (request.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
    }

    private AdvisoryResponse toAdvisoryResponse(AdvisoryRow row) {
        return new AdvisoryResponse(row.id(), row.vulnerabilityId(), row.artifactName(),
                row.artifactVersion(), row.severity(), row.expiresAt(), row.updatedAt());
    }

    private ExceptionResponse toExceptionResponse(ExceptionRow row) {
        if (row == null) {
            throw new IllegalStateException("豁免读取失败");
        }
        // 对外暴露的指纹：完成态含两名审核人；PENDING 仅含第一审核人（第二审核人留空）。
        String key = STATUS_CONFIRMED.equals(row.status()) && row.reviewer2() != null
                ? exceptionKey(row.lockFileId(), row.vulnerabilityId(), row.reviewer1(),
                        row.reviewer2(), row.expiresAt(), row.reason())
                : row.exceptionKey();
        return new ExceptionResponse(row.id(), key, row.lockFileId(), row.vulnerabilityId(),
                row.expiresAt(), row.reason(), row.status(), row.reviewer1(), row.reviewer2(),
                row.createdAt(), row.confirmedAt());
    }

    private VulnerabilityHitResponse toHitResponse(HitRow hit, ExceptionRow exception, Instant now) {
        String status = exceptionStatus(exception, now);
        Long exceptionId = exception == null ? null : exception.id();
        Instant exceptionExpires = exception == null ? null : exception.expiresAt();
        return new VulnerabilityHitResponse(hit.vulnerabilityId(), hit.artifactName(),
                hit.artifactVersion(), hit.severity(), hit.advisoryExpiresAt(), status,
                exceptionId, exceptionExpires);
    }

    private PublishSnapshotResponse toPublishResponse(PublishRow row, List<PublishEntryRow> entries) {
        if (row == null) {
            throw new IllegalStateException("发布快照读取失败");
        }
        List<LockEntryResponse> views = entries.stream()
                .map(e -> new LockEntryResponse(e.name(), e.version())).toList();
        return new PublishSnapshotResponse(row.id(), row.lockFileId(), row.rootName(),
                row.rootVersion(), row.repositoryVersion(), row.publishedAt(), views);
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

    /**
     * exceptionKey 指纹：锁定图版本、漏洞、审核人、到期、理由的 SHA-256。
     * 双人快照可传入第二名审核人；PENDING 阶段第二审核人传空串。
     */
    private static String exceptionKey(long lockFileId, String vulnerabilityId,
                                       String reviewer, Instant expiresAt, String reason) {
        return sha256(lockFileId + "|" + vulnerabilityId + "|" + reviewer + "|"
                + expiresAt + "|" + reason);
    }

    private static String exceptionKey(long lockFileId, String vulnerabilityId,
                                       String reviewer1, String reviewer2,
                                       Instant expiresAt, String reason) {
        return sha256(lockFileId + "|" + vulnerabilityId + "|" + reviewer1 + "|"
                + (reviewer2 == null ? "" : reviewer2) + "|" + expiresAt + "|" + reason);
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
