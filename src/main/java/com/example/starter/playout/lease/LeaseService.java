package com.example.starter.playout.lease;

import com.example.starter.playout.PlayoutRepository;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.LeaseOverrideSnapshot;
import com.example.starter.playout.api.Dtos.LeaseResponse;
import com.example.starter.playout.api.Dtos.LeaseSegmentSnapshot;
import com.example.starter.playout.api.Dtos.LeaseStatus;
import com.example.starter.playout.api.Dtos.PublicationReferenceResponse;
import com.example.starter.playout.api.Dtos.PullLeaseRequest;
import com.example.starter.playout.api.Dtos.SegmentAckRequest;
import com.example.starter.playout.api.Dtos.SegmentAckResponse;
import com.example.starter.playout.lease.LeaseRepository.LeaseAckRow;
import com.example.starter.playout.lease.LeaseRepository.LeaseOverrideRow;
import com.example.starter.playout.lease.LeaseRepository.LeaseRow;
import com.example.starter.playout.lease.LeaseRepository.LeaseSegmentRow;
import com.example.starter.playout.lease.LeaseRepository.PublicationReferenceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 播出端版本租约服务：拉取绑定、分段确认、续租与只读查询。
 *
 * <p>约定与 {@code PlayoutService} 一致：数据库内时间为 UTC 纪元毫秒，API 边界为 Asia/Shanghai、
 * 毫秒精度 ISO 8601；写操作与幂等去重记录在同一事务提交，失败整体回滚、不占用 requestId/ackKey。
 * 拉取与发布、授权撤销、插播变化通过客户端锁、频道锁与授权行锁形成一致快照；快照写入后不回写。
 */
@Service
public class LeaseService {

    /** 业务时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_PULL_LEASE = "PULL_LEASE";
    private static final String OP_RENEW_LEASE = "RENEW_LEASE";

    private final LeaseRepository leaseRepo;
    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;
    private final long leaseTtlMs;

    public LeaseService(LeaseRepository leaseRepo, PlayoutRepository repo,
                        ObjectMapper objectMapper,
                        @Value("${playout.lease-ttl-ms:600000}") long leaseTtlMs) {
        this.leaseRepo = leaseRepo;
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.leaseTtlMs = leaseTtlMs;
    }

    // ---------- 拉取租约 ----------

    /**
     * 拉取版本租约：原子绑定当时最新发布版本及其完整分段、授权判定与插播快照，生成递增
     * leaseEpoch 与 expiresAt。同客户端+业务日存在未过期 ACTIVE 租约时返回同一租约（新发布
     * 不偷偷替换）；已过期则作废旧租约并绑定最新版本。requestId 幂等：同键同参返回首次结果，
     * 改参 409，失败不占键。
     */
    @Transactional
    public LeaseResponse pullLease(PullLeaseRequest request) {
        String hash = sha256(OP_PULL_LEASE + "|" + request.clientKey() + "|" + request.channelId()
                + "|" + request.businessDay());
        return idempotent(request.requestId(), OP_PULL_LEASE, hash, LeaseResponse.class,
                () -> doPullLease(request));
    }

    private LeaseResponse doPullLease(PullLeaseRequest request) {
        long nowMs = nowMs();
        // 客户端行锁：串行化同客户端的并发拉取，保证每客户端+业务日最多一个 ACTIVE 租约。
        registerAndLockClient(request.clientKey(), nowMs);
        repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        // 频道行锁：与插播创建/取消按同一锁串行，保证插播快照一致。
        repo.lockChannelForUpdate(request.channelId());

        Optional<LeaseRow> active =
                leaseRepo.findActiveLeaseForUpdate(request.clientKey(), request.businessDay());
        if (active.isPresent()) {
            LeaseRow lease = active.get();
            if (lease.expiresAtMs() > nowMs) {
                if (!lease.channelId().equals(request.channelId())) {
                    throw ApiException.conflict("LEASE_ALREADY_ACTIVE",
                            "该客户端在该业务日已持有其他频道的 ACTIVE 租约: " + lease.channelId());
                }
                // 未过期重复拉取：返回同一租约同一版本，不重新绑定。
                return toLeaseResponse(lease, LeaseStatus.ACTIVE);
            }
            // 已过期：作废后允许绑定最新版本。
            leaseRepo.markLeaseExpired(lease.id());
        }

        PublicationRow publication = repo
                .findLatestPublication(request.channelId(), request.businessDay())
                .orElseThrow(() -> ApiException.unprocessable("NO_PUBLISHED_VERSION",
                        "该频道在该业务日没有已发布版本: " + request.channelId()));
        List<PublicationSegmentRow> segments = repo.findPublicationSegments(publication.id());
        List<OverrideRow> overrides =
                repo.findActiveOverridesForDay(request.channelId(), request.businessDay());

        long leaseEpoch = leaseRepo.maxLeaseEpoch(request.clientKey(), request.businessDay()) + 1;
        long leaseId = leaseRepo.insertLease(request.clientKey(), request.channelId(),
                request.businessDay(), publication.id(), publication.publishedVersion(),
                leaseEpoch, nowMs + leaseTtlMs, nowMs);

        int seq = 1;
        for (PublicationSegmentRow segment : segments) {
            // 授权行锁：与撤销事务按提交顺序串行，快照中的授权判定与读取时刻一致。
            GrantRow grant = repo.findGrantForUpdate(segment.grantId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照引用的授权不存在: " + segment.grantId()));
            leaseRepo.insertLeaseSegment(leaseId, seq++, segment.segmentId(), segment.assetId(),
                    segment.grantId(), grant.revoked(), segment.startMs(), segment.endMs());
        }
        for (OverrideRow override : overrides) {
            leaseRepo.insertLeaseOverride(leaseId, override.overrideKey(), override.assetId(),
                    override.grantId(), override.priority(), override.startMs(), override.endMs());
        }
        return toLeaseResponse(leaseRepo.findLease(leaseId).orElseThrow(), LeaseStatus.ACTIVE);
    }

    // ---------- 续租 ----------

    /**
     * 续租：仅 ACTIVE 且未过期的租约可续租，推进 leaseEpoch 并延长 expiresAt，绑定发布版本不变。
     * requestId 幂等：同键同参返回首次结果，改参 409，失败不占键。
     */
    @Transactional
    public LeaseResponse renewLease(long leaseId, String requestId) {
        String hash = sha256(OP_RENEW_LEASE + "|" + leaseId);
        return idempotent(requestId, OP_RENEW_LEASE, hash, LeaseResponse.class, () -> {
            long nowMs = nowMs();
            LeaseRow lease = leaseRepo.findLeaseForUpdate(leaseId)
                    .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
            if (!lease.active()) {
                throw ApiException.conflict("LEASE_NOT_ACTIVE",
                        "租约非 ACTIVE，不能续租: " + leaseId);
            }
            if (lease.expiresAtMs() <= nowMs) {
                leaseRepo.markLeaseExpired(leaseId);
                throw ApiException.conflict("LEASE_EXPIRED", "租约已过期，不能续租: " + leaseId);
            }
            long newEpoch = lease.leaseEpoch() + 1;
            leaseRepo.renewLease(leaseId, newEpoch, nowMs + leaseTtlMs, nowMs);
            return toLeaseResponse(leaseRepo.findLease(leaseId).orElseThrow(), LeaseStatus.ACTIVE);
        });
    }

    // ---------- 分段确认 ----------

    /**
     * 分段确认：只能按顺序确认下一个未确认分段，playedAt 须落在该段时窗 [start, end) 内；
     * 租约须 ACTIVE、未过期且 leaseEpoch 匹配。ackKey 幂等：同键同参返回首次结果，改参 409，
     * 失败不占键。全部确认后租约转为 COMPLETED。
     */
    @Transactional
    public SegmentAckResponse ackSegment(SegmentAckRequest request) {
        Optional<LeaseAckRow> existing = leaseRepo.findAckForUpdate(request.ackKey());
        if (existing.isPresent()) {
            return replayAck(existing.get(), request);
        }

        LeaseRow lease = leaseRepo.findLeaseForUpdate(request.leaseId())
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + request.leaseId()));
        // 持有租约行锁后复查：并发同 ackKey 在对方提交后应重放首次结果而非误判跳段。
        Optional<LeaseAckRow> committed = leaseRepo.findAckForUpdate(request.ackKey());
        if (committed.isPresent()) {
            return replayAck(committed.get(), request);
        }
        long nowMs = nowMs();
        if (!lease.active()) {
            throw ApiException.conflict("LEASE_NOT_ACTIVE",
                    "租约非 ACTIVE，不能确认分段: " + request.leaseId());
        }
        if (lease.expiresAtMs() <= nowMs) {
            leaseRepo.markLeaseExpired(lease.id());
            throw ApiException.conflict("LEASE_EXPIRED", "租约已过期，不能确认分段: " + request.leaseId());
        }
        if (lease.leaseEpoch() != request.leaseEpoch()) {
            throw ApiException.conflict("LEASE_EPOCH_MISMATCH",
                    "租约纪元不符，当前 " + lease.leaseEpoch());
        }

        List<LeaseSegmentRow> segments = leaseRepo.findLeaseSegments(lease.id());
        int confirmed = leaseRepo.countAcks(lease.id());
        if (confirmed >= segments.size()) {
            throw ApiException.conflict("SEGMENT_OUT_OF_ORDER",
                    "所有分段均已确认: " + request.leaseId());
        }
        LeaseSegmentRow expected = segments.get(confirmed);
        if (!expected.segmentId().equals(request.segmentId())) {
            throw ApiException.conflict("SEGMENT_OUT_OF_ORDER",
                    "只能确认下一个未确认分段，期望 " + expected.segmentId());
        }
        long playedAtMs = toMs(request.playedAt());
        if (playedAtMs < expected.startMs() || playedAtMs >= expected.endMs()) {
            throw ApiException.unprocessable("PLAYED_AT_OUT_OF_WINDOW",
                    "playedAt 不在分段时窗内: " + request.playedAt());
        }

        int seq = confirmed + 1;
        try {
            leaseRepo.insertAck(request.ackKey(), lease.id(), lease.leaseEpoch(), seq,
                    request.segmentId(), playedAtMs, nowMs);
        } catch (DuplicateKeyException e) {
            // 并发同 ackKey：等待对方事务结束后读取已提交记录
            LeaseAckRow raced = leaseRepo.findAckForUpdate(request.ackKey())
                    .orElseThrow(() -> ApiException.conflict("ACK_KEY_CONFLICT",
                            "ackKey 并发冲突: " + request.ackKey()));
            return replayAck(raced, request);
        }

        LeaseStatus status = LeaseStatus.ACTIVE;
        if (seq == segments.size()) {
            leaseRepo.completeLease(lease.id(), nowMs);
            status = LeaseStatus.COMPLETED;
        }
        return new SegmentAckResponse(request.ackKey(), lease.id(), lease.leaseEpoch(),
                request.segmentId(), seq, atMs(playedAtMs), seq, segments.size(), status);
    }

    /** 重放已受理确认：参数一致返回首次结果，不一致 409。 */
    private SegmentAckResponse replayAck(LeaseAckRow ack, SegmentAckRequest request) {
        if (ack.leaseId() != request.leaseId()
                || ack.leaseEpoch() != request.leaseEpoch()
                || !ack.segmentId().equals(request.segmentId())
                || ack.playedAtMs() != toMs(request.playedAt())) {
            throw ApiException.conflict("ACK_KEY_CONFLICT",
                    "ackKey 已使用且参数不一致: " + request.ackKey());
        }
        int total = leaseRepo.findLeaseSegments(ack.leaseId()).size();
        LeaseStatus status = ack.seq() == total ? LeaseStatus.COMPLETED : LeaseStatus.ACTIVE;
        return new SegmentAckResponse(ack.ackKey(), ack.leaseId(), ack.leaseEpoch(),
                ack.segmentId(), ack.seq(), atMs(ack.playedAtMs()), ack.seq(), total, status);
    }

    // ---------- 只读查询 ----------

    /** 查询租约明细（只读；ACTIVE 但已过期的按 EXPIRED 返回，不回写）。 */
    @Transactional(readOnly = true)
    public LeaseResponse getLease(long leaseId) {
        LeaseRow lease = leaseRepo.findLease(leaseId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
        return toLeaseResponse(lease, effectiveStatus(lease, nowMs()));
    }

    /** 查询租约的分段确认列表（只读）。 */
    @Transactional(readOnly = true)
    public List<SegmentAckResponse> listAcks(long leaseId) {
        LeaseRow lease = leaseRepo.findLease(leaseId)
                .orElseThrow(() -> ApiException.notFound("租约不存在: " + leaseId));
        int total = leaseRepo.findLeaseSegments(leaseId).size();
        return leaseRepo.findAcks(leaseId).stream()
                .map(ack -> new SegmentAckResponse(ack.ackKey(), lease.id(), ack.leaseEpoch(),
                        ack.segmentId(), ack.seq(), atMs(ack.playedAtMs()), ack.seq(), total,
                        ack.seq() == total ? LeaseStatus.COMPLETED : LeaseStatus.ACTIVE))
                .toList();
    }

    /**
     * 查询频道某业务日各发布版本的租约引用（只读）。cleanable 为 true 表示不存在引用该版本的
     * 未过期 ACTIVE 租约，可标记清理。
     */
    @Transactional(readOnly = true)
    public List<PublicationReferenceResponse> publicationReferences(String channelId,
                                                                    LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        List<PublicationReferenceRow> rows =
                leaseRepo.findPublicationReferences(channelId, businessDay, nowMs());
        return rows.stream()
                .map(row -> new PublicationReferenceResponse(row.publicationId(),
                        row.publishedVersion(), row.activeLeaseCount(), row.activeLeaseCount() == 0))
                .toList();
    }

    // ---------- 内部方法 ----------

    /** 登记客户端（不存在则插入）并加行锁。 */
    private void registerAndLockClient(String clientKey, long nowMs) {
        try {
            leaseRepo.insertClient(clientKey, nowMs);
        } catch (DuplicateKeyException e) {
            // 已存在：直接加锁
        }
        leaseRepo.lockClientForUpdate(clientKey);
    }

    /** 换算租约对外状态：ACTIVE 但已过期按 EXPIRED 返回。 */
    private static LeaseStatus effectiveStatus(LeaseRow lease, long nowMs) {
        if (lease.active() && lease.expiresAtMs() <= nowMs) {
            return LeaseStatus.EXPIRED;
        }
        return LeaseStatus.valueOf(lease.status());
    }

    /** 组装租约明细响应，附带完整分段与插播快照。 */
    private LeaseResponse toLeaseResponse(LeaseRow lease, LeaseStatus status) {
        List<LeaseSegmentSnapshot> segments = leaseRepo.findLeaseSegments(lease.id()).stream()
                .map(s -> new LeaseSegmentSnapshot(s.seq(), s.segmentId(), s.assetId(), s.grantId(),
                        s.grantRevoked(), atMs(s.startMs()), atMs(s.endMs())))
                .toList();
        List<LeaseOverrideSnapshot> overrides = leaseRepo.findLeaseOverrides(lease.id()).stream()
                .map(o -> new LeaseOverrideSnapshot(o.overrideKey(), o.assetId(), o.grantId(),
                        o.priority(), atMs(o.startMs()), atMs(o.endMs())))
                .toList();
        return new LeaseResponse(lease.id(), lease.clientKey(), lease.channelId(),
                lease.businessDay().toString(), lease.leaseEpoch(), status, lease.publicationId(),
                lease.publishedVersion(), atMs(lease.expiresAtMs()), atMs(lease.createdAtMs()),
                segments, overrides);
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
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
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
