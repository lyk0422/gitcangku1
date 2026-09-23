package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.LeaseAckRow;
import com.example.starter.playout.PlayoutRepository.LeaseOverrideRow;
import com.example.starter.playout.PlayoutRepository.LeaseRow;
import com.example.starter.playout.PlayoutRepository.LeaseSegmentRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AckSegmentRequest;
import com.example.starter.playout.api.Dtos.AckSegmentResponse;
import com.example.starter.playout.api.Dtos.AckView;
import com.example.starter.playout.api.Dtos.LeaseOverrideView;
import com.example.starter.playout.api.Dtos.LeaseResponse;
import com.example.starter.playout.api.Dtos.LeaseSegmentView;
import com.example.starter.playout.api.Dtos.LeaseStatus;
import com.example.starter.playout.api.Dtos.PublicationReferencesResponse;
import com.example.starter.playout.api.Dtos.PullLeaseRequest;
import com.example.starter.playout.api.Dtos.RenewLeaseRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 播出端版本租约服务：拉取绑定、续租、分段确认与只读查询。
 *
 * <p>约定：拉取在单个事务内锁定频道行与相关授权行，读取最新发布版本、完整分段、授权撤销状态
 * 与 ACTIVE 插播，生成一致快照并落库，之后不回写；每客户端+频道+业务日最多一个 ACTIVE 租约
 * （由 active_unique 唯一索引保证）；过期为惰性判定（now &gt;= expiresAtMs），拉取/续租/确认
 * 遇到过期 ACTIVE 租约时先转为 EXPIRED 再处理。确认只能按快照顺序推进，全部确认后租约
 * COMPLETED。requestId 幂等：同参重放返回首次结果，异参 409，失败回滚不占键。
 */
@Service
public class LeaseService {

    /** 默认租约时长：10 分钟，单位毫秒。 */
    static final long DEFAULT_LEASE_TTL_MS = 10L * 60L * 1000L;

    /** 租约时长上限（含）：1 小时，单位毫秒。 */
    static final long MAX_LEASE_TTL_MS = 60L * 60L * 1000L;

    private static final String OP_PULL_LEASE = "PULL_LEASE";
    private static final String OP_RENEW_LEASE = "RENEW_LEASE";

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager txManager;

    public LeaseService(PlayoutRepository repo, ObjectMapper objectMapper,
                        PlatformTransactionManager txManager) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.txManager = txManager;
    }

    // ---------- 拉取租约 ----------

    /**
     * 拉取版本租约：存在未过期 ACTIVE 租约时返回同一租约（新发布不替换）；否则原子绑定当时
     * 最新发布版本及其完整分段、授权判定与插播快照，生成递增 leaseEpoch 与 expiresAt。
     * 无已发布版本时 422，失败不占 requestId。
     */
    @Transactional
    public LeaseResponse pullLease(PullLeaseRequest request) {
        LocalDate businessDay = parseBusinessDay(request.businessDay());
        long ttlMs = resolveTtl(request.leaseTtlMs());
        String hash = sha256(OP_PULL_LEASE + "|" + request.clientKey() + "|" + request.channelId()
                + "|" + businessDay + "|" + ttlMs);
        return idempotent(request.requestId(), OP_PULL_LEASE, hash, LeaseResponse.class,
                () -> doPullLease(request.clientKey(), request.channelId(), businessDay, ttlMs));
    }

    private LeaseResponse doPullLease(String clientKey, String channelId, LocalDate businessDay,
                                      long ttlMs) {
        // 锁频道行：与插播创建/取消串行，保证插播快照一致。
        repo.lockChannelForUpdate(channelId);
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        long now = nowMs();
        Optional<LeaseRow> active = repo.findActiveLeaseForUpdate(clientKey, channelId, businessDay);
        if (active.isPresent()) {
            LeaseRow lease = active.get();
            if (now < lease.expiresAtMs()) {
                // 租约未过期：重复拉取返回同一版本，新发布不得偷偷替换。
                return toLeaseResponse(lease);
            }
            repo.markLeaseExpired(lease.id(), now);
        }

        PublicationRow publication = repo.findLatestPublication(channelId, businessDay)
                .orElseThrow(() -> ApiException.unprocessable("NO_PUBLISHED_VERSION",
                        "频道该业务日无已发布版本: " + channelId + " " + businessDay));

        // 逐段锁定授权行读取撤销状态：与授权撤销按提交顺序串行，形成一致快照。
        List<PublicationSegmentRow> segments = repo.findPublicationSegments(publication.id());
        boolean[] segmentRevoked = new boolean[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            segmentRevoked[i] = grantRevokedForUpdate(segments.get(i).grantId());
        }
        List<OverrideRow> overrides = repo.findActiveOverridesForDay(channelId, businessDay);
        boolean[] overrideRevoked = new boolean[overrides.size()];
        for (int i = 0; i < overrides.size(); i++) {
            overrideRevoked[i] = grantRevokedForUpdate(overrides.get(i).grantId());
        }

        long epoch = repo.maxLeaseEpoch(clientKey, channelId, businessDay) + 1;
        long leaseId;
        try {
            leaseId = repo.insertLease(clientKey, channelId, businessDay, publication.id(),
                    publication.publishedVersion(), epoch, ttlMs, now + ttlMs, now);
        } catch (DuplicateKeyException e) {
            // 并发拉取：等待对方事务提交后返回其租约（同一版本）。
            LeaseRow winner = repo.findActiveLeaseForUpdate(clientKey, channelId, businessDay)
                    .orElseThrow(() -> ApiException.conflict("LEASE_CONFLICT",
                            "租约并发冲突: " + clientKey));
            return toLeaseResponse(winner);
        }
        for (int i = 0; i < segments.size(); i++) {
            PublicationSegmentRow segment = segments.get(i);
            repo.insertLeaseSegment(leaseId, i, segment.segmentId(), segment.assetId(),
                    segment.grantId(), segmentRevoked[i], segment.startMs(), segment.endMs());
        }
        for (int i = 0; i < overrides.size(); i++) {
            OverrideRow override = overrides.get(i);
            repo.insertLeaseOverride(leaseId, override.overrideKey(), override.assetId(),
                    override.grantId(), overrideRevoked[i], override.priority(),
                    override.startMs(), override.endMs());
        }
        return toLeaseResponse(repo.findLease(leaseId).orElseThrow());
    }

    // ---------- 续租 ----------

    /** 续租：仅 ACTIVE 且未过期可续，推进 leaseEpoch、延长 expiresAt，保持发布版本不变。 */
    @Transactional
    public LeaseResponse renewLease(long leaseId, RenewLeaseRequest request) {
        String hash = sha256(OP_RENEW_LEASE + "|" + leaseId + "|" + request.leaseEpoch());
        return idempotent(request.requestId(), OP_RENEW_LEASE, hash, LeaseResponse.class, () -> {
            // 过期标记在独立事务提交，拒绝续租的回滚不会带走状态迁移。
            markExpiredIfNeeded(leaseId);
            LeaseRow lease = repo.findLeaseForUpdate(leaseId)
                    .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
            long now = nowMs();
            if ("EXPIRED".equals(lease.status()) || now >= lease.expiresAtMs()) {
                throw ApiException.conflict("LEASE_EXPIRED", "租约已过期，不能续租: " + leaseId);
            }
            if (!lease.active()) {
                throw ApiException.conflict("LEASE_NOT_ACTIVE",
                        "租约非 ACTIVE，不能续租: " + lease.status());
            }
            if (lease.leaseEpoch() != request.leaseEpoch()) {
                throw ApiException.conflict("LEASE_EPOCH_MISMATCH",
                        "租约纪元不符，当前 " + lease.leaseEpoch());
            }
            repo.renewLease(leaseId, lease.leaseEpoch() + 1, now + lease.ttlMs(), now);
            return toLeaseResponse(repo.findLease(leaseId).orElseThrow());
        });
    }

    // ---------- 分段确认 ----------

    /**
     * 分段确认：只能确认下一个未确认分段，playedAt 须落在该分段时窗 [start, end) 内；
     * leaseEpoch 须等于租约当前纪元；过期租约、旧纪元、跳段返回 409，改时间返回 422。
     * ackKey 幂等：同键同参返回首次结果，同键异参 409，失败不占键。全部确认后租约 COMPLETED。
     */
    @Transactional
    public AckSegmentResponse ackSegment(long leaseId, AckSegmentRequest request) {
        // 过期标记在独立事务提交，拒绝确认的回滚不会带走状态迁移。
        markExpiredIfNeeded(leaseId);
        LeaseRow lease = repo.findLeaseForUpdate(leaseId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
        long playedAtMs = toMs(request.playedAt());

        // ackKey 幂等判定优先于状态校验：重放历史确认（含使租约完成的确认）返回首次结果。
        Optional<LeaseAckRow> existing = repo.findLeaseAck(leaseId, request.ackKey());
        if (existing.isPresent()) {
            return replayAck(lease, existing.get(), request, playedAtMs);
        }

        long now = nowMs();
        if ("EXPIRED".equals(lease.status()) || now >= lease.expiresAtMs()) {
            throw ApiException.conflict("LEASE_EXPIRED", "租约已过期，不能确认: " + leaseId);
        }
        if (!lease.active()) {
            throw ApiException.conflict("LEASE_NOT_ACTIVE",
                    "租约非 ACTIVE，不能确认: " + lease.status());
        }
        if (lease.leaseEpoch() != request.leaseEpoch()) {
            throw ApiException.conflict("LEASE_EPOCH_MISMATCH",
                    "租约纪元不符，当前 " + lease.leaseEpoch());
        }

        LeaseSegmentRow segment = repo.findLeaseSegment(leaseId, request.segmentId())
                .orElseThrow(() -> ApiException.unprocessable("SEGMENT_NOT_IN_LEASE",
                        "分段不属于该租约快照: " + request.segmentId()));
        LeaseSegmentRow next = repo.findNextUnackedSegment(leaseId)
                .orElseThrow(() -> ApiException.conflict("ACK_OUT_OF_ORDER",
                        "租约没有待确认分段: " + leaseId));
        if (!next.segmentId().equals(request.segmentId())) {
            throw ApiException.conflict("ACK_OUT_OF_ORDER",
                    "只能确认下一个未确认分段: " + next.segmentId());
        }
        if (playedAtMs < segment.startMs() || playedAtMs >= segment.endMs()) {
            throw ApiException.unprocessable("PLAYED_AT_OUT_OF_WINDOW",
                    "playedAt 不在分段时窗内: " + request.segmentId());
        }

        repo.markSegmentAcked(leaseId, request.segmentId(), request.ackKey(), playedAtMs);
        List<LeaseSegmentRow> segments = repo.findLeaseSegments(leaseId);
        int total = segments.size();
        int ackedCount = (int) segments.stream().filter(LeaseSegmentRow::acked).count();
        String status = ackedCount == total ? "COMPLETED" : "ACTIVE";
        repo.insertLeaseAck(leaseId, request.ackKey(), request.segmentId(), request.leaseEpoch(),
                playedAtMs, ackedCount, status, now);
        if (ackedCount == total) {
            repo.completeLease(leaseId, now);
        }
        return new AckSegmentResponse(leaseId, request.ackKey(), request.segmentId(),
                request.leaseEpoch(), atMs(playedAtMs), ackedCount, total,
                LeaseStatus.valueOf(status));
    }

    private AckSegmentResponse replayAck(LeaseRow lease, LeaseAckRow ack,
                                         AckSegmentRequest request, long playedAtMs) {
        if (!ack.segmentId().equals(request.segmentId())
                || ack.playedAtMs() != playedAtMs
                || ack.leaseEpoch() != request.leaseEpoch()) {
            throw ApiException.conflict("ACK_KEY_CONFLICT",
                    "ackKey 已使用且参数不一致: " + request.ackKey());
        }
        int total = repo.findLeaseSegments(lease.id()).size();
        return new AckSegmentResponse(lease.id(), ack.ackKey(), ack.segmentId(), ack.leaseEpoch(),
                atMs(ack.playedAtMs()), ack.ackedCount(), total,
                LeaseStatus.valueOf(ack.leaseStatus()));
    }

    // ---------- 只读查询 ----------

    /** 租约明细（含分段与插播快照、确认进度），只读。 */
    @Transactional(readOnly = true)
    public LeaseResponse getLease(long leaseId) {
        LeaseRow lease = repo.findLease(leaseId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
        return toLeaseResponse(lease);
    }

    /** 租约确认记录列表，按受理顺序，只读。 */
    @Transactional(readOnly = true)
    public List<AckView> getLeaseAcks(long leaseId) {
        repo.findLease(leaseId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
        return repo.findLeaseAcks(leaseId).stream()
                .map(ack -> new AckView(ack.ackKey(), ack.segmentId(), ack.leaseEpoch(),
                        atMs(ack.playedAtMs()), atMs(ack.createdAtMs())))
                .toList();
    }

    /**
     * 发布版本引用查询：activeLeaseCount 为引用该版本的未过期 ACTIVE 租约数；
     * 仅当为 0 时 cleanable 为 true，只读，不回写任何状态。
     */
    @Transactional(readOnly = true)
    public PublicationReferencesResponse getPublicationReferences(long publicationId) {
        PublicationRow publication = repo.findPublication(publicationId)
                .orElseThrow(() -> ApiException.notFound("发布快照不存在: " + publicationId));
        long activeLeases = repo.countUnexpiredActiveLeasesForPublication(publicationId, nowMs());
        return new PublicationReferencesResponse(publication.id(), publication.channelId(),
                publication.businessDay().toString(), publication.publishedVersion(),
                activeLeases, activeLeases == 0);
    }

    // ---------- 内部方法 ----------

    /**
     * 惰性过期标记：租约为 ACTIVE 且已到期时，在独立事务（REQUIRES_NEW）中转为 EXPIRED 并提交，
     * 使随后拒绝请求的异常回滚不会带走状态迁移。须在未持有租约行锁时调用。
     */
    private void markExpiredIfNeeded(long leaseId) {
        Optional<LeaseRow> lease = repo.findLease(leaseId);
        if (lease.isPresent() && lease.get().active() && nowMs() >= lease.get().expiresAtMs()) {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> repo.markLeaseExpired(leaseId, nowMs()));
        }
    }

    /** 读取授权撤销状态并加行锁，与撤销事务按提交顺序串行，保证快照一致。 */
    private boolean grantRevokedForUpdate(long grantId) {
        GrantRow grant = repo.findGrantForUpdate(grantId)
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + grantId));
        return grant.revoked();
    }

    private LeaseResponse toLeaseResponse(LeaseRow lease) {
        List<LeaseSegmentView> segments = repo.findLeaseSegments(lease.id()).stream()
                .map(s -> new LeaseSegmentView(s.segmentId(), s.assetId(), s.grantId(),
                        s.grantRevoked(), atMs(s.startMs()), atMs(s.endMs()), s.acked(),
                        s.ackKey(), s.playedAtMs() == null ? null : atMs(s.playedAtMs())))
                .toList();
        List<LeaseOverrideView> overrides = repo.findLeaseOverrides(lease.id()).stream()
                .map(o -> new LeaseOverrideView(o.overrideKey(), o.assetId(), o.grantId(),
                        o.grantRevoked(), o.priority(), atMs(o.startMs()), atMs(o.endMs())))
                .toList();
        return new LeaseResponse(lease.id(), lease.clientKey(), lease.channelId(),
                lease.businessDay().toString(), lease.publicationId(), lease.publishedVersion(),
                lease.leaseEpoch(), LeaseStatus.valueOf(lease.status()), atMs(lease.expiresAtMs()),
                atMs(lease.createdAtMs()), segments, overrides);
    }

    private static long resolveTtl(Long leaseTtlMs) {
        if (leaseTtlMs == null) {
            return DEFAULT_LEASE_TTL_MS;
        }
        if (leaseTtlMs > MAX_LEASE_TTL_MS) {
            throw ApiException.badRequest("租约时长不得超过 1 小时: " + leaseTtlMs);
        }
        return leaseTtlMs;
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }

    /**
     * 幂等执行：去重记录与业务结果同事务提交；同 requestId 同参数返回原结果，
     * 同 requestId 不同参数返回 409；业务失败抛异常回滚，不占用 requestId。
     */
    private <T> T idempotent(String requestId, String operation, String paramsHash,
                             Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        Optional<RequestRow> existing = repo.findRequestForUpdate(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, paramsHash, type);
        }
        try {
            repo.insertRequest(requestId, operation, paramsHash, nowMs());
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：等待对方事务结束后读取已提交记录
            RequestRow committed = repo.findRequestForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                            "requestId 并发冲突: " + requestId));
            return replay(committed, operation, paramsHash, type);
        }
        T result = action.get();
        try {
            repo.completeRequest(requestId, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
        return result;
    }

    private <T> T replay(RequestRow row, String operation, String paramsHash, Class<T> type) {
        if (!row.operation().equals(operation) || !row.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已使用且参数不一致: " + row.requestId());
        }
        if (row.responseBody() == null) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 请求尚未完成: " + row.requestId());
        }
        try {
            return objectMapper.readValue(row.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), PlayoutService.ZONE);
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
