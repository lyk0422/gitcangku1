package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.LiveAffectedRow;
import com.example.starter.playout.PlayoutRepository.LiveGrantSnapshotRow;
import com.example.starter.playout.PlayoutRepository.LiveOverrideRow;
import com.example.starter.playout.PlayoutRepository.LiveRecoveryRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateLiveOverrideItem;
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.LiveAffectedItem;
import com.example.starter.playout.api.Dtos.LiveDiagnosticsResponse;
import com.example.starter.playout.api.Dtos.LiveGrantSnapshot;
import com.example.starter.playout.api.Dtos.LiveOverrideBatchResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideStatus;
import com.example.starter.playout.api.Dtos.LiveOverrideSummary;
import com.example.starter.playout.api.Dtos.LiveRecoveryItem;
import com.example.starter.playout.api.Dtos.OverrideStatus;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.RevokeLiveOverrideRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 播出编排核心服务：素材、频道、授权、草稿、发布、播出决定、紧急插播与直播覆盖。
 *
 * <p>约定：数据库内时间统一为 UTC 纪元毫秒；API 边界统一为 Asia/Shanghai、毫秒精度 ISO 8601；
 * 业务日为 Asia/Shanghai 日历日。所有区间左闭右开。所有写操作与幂等去重记录在同一事务提交，
 * 失败整体回滚、不占用 requestId。</p>
 *
 * <p>直播覆盖令：同一频道时段仅一个有效覆盖；覆盖创建、撤销、日草稿修改、发布与紧急插播均在
 * 频道行锁上按事务提交顺序裁决。覆盖结束（ACTIVE 到点）后对受影响素材逐条裁决：覆盖结束时刻
 * 尚未开始的素材 RESUMED 恢复播出，其余 MISSED_BY_LIVE 不补播；裁决一经写入不可变。</p>
 */
@Service
public class PlayoutService {

    /** 业务时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_REPLACE_DRAFT = "REPLACE_DRAFT";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_REVOKE_GRANT = "REVOKE_GRANT";
    private static final String OP_CREATE_OVERRIDE = "CREATE_OVERRIDE";
    private static final String OP_CANCEL_OVERRIDE = "CANCEL_OVERRIDE";
    private static final String OP_CREATE_LIVE_OVERRIDE = "CREATE_LIVE_OVERRIDE";
    private static final String OP_REVOKE_LIVE_OVERRIDE = "REVOKE_LIVE_OVERRIDE";

    /** 紧急插播时长上限（含）：30 分钟，单位毫秒。 */
    private static final long OVERRIDE_MAX_DURATION_MS = 30L * 60L * 1000L;

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;
    private final PlayoutClock clock;

    public PlayoutService(PlayoutRepository repo, ObjectMapper objectMapper, PlayoutClock clock) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 素材 ----------

    /** 创建素材；id 为空时生成稳定 ID。 */
    @Transactional
    public AssetResponse createAsset(CreateAssetRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        try {
            repo.insertAsset(id, request.durationMs(), nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_ID", "素材 ID 已存在: " + id);
        }
        return new AssetResponse(id, request.durationMs());
    }

    // ---------- 频道 ----------

    /** 创建频道，保底素材必须已存在且不会被撤销。 */
    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        if (repo.findAsset(request.fallbackAssetId()).isEmpty()) {
            throw ApiException.notFound("保底素材不存在: " + request.fallbackAssetId());
        }
        try {
            repo.insertChannel(id, request.fallbackAssetId(), nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_ID", "频道 ID 已存在: " + id);
        }
        return new ChannelResponse(id, request.fallbackAssetId());
    }

    // ---------- 授权 ----------

    /** 创建授权，关联频道与普通素材，有效区间左闭右开。 */
    @Transactional
    public GrantResponse createGrant(CreateGrantRequest request) {
        var channel = repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        long fromMs = toMs(request.validFrom());
        long toMs = toMs(request.validTo());
        if (toMs <= fromMs) {
            throw ApiException.badRequest("授权有效区间终点必须大于起点");
        }
        if (channel.fallbackAssetId().equals(request.assetId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材无需授权，不能对其创建授权");
        }
        long id = repo.insertGrant(request.channelId(), request.assetId(), fromMs, toMs, nowMs());
        return new GrantResponse(id, request.channelId(), request.assetId(),
                request.validFrom(), request.validTo(), false);
    }

    /** 撤销授权；幂等：同 requestId 同参数返回原结果，改参数返回 409。 */
    @Transactional
    public GrantResponse revokeGrant(long grantId, String requestId) {
        String hash = fingerprint(OP_REVOKE_GRANT, Map.of("grantId", grantId));
        return idempotent(requestId, OP_REVOKE_GRANT, hash, GrantResponse.class, () -> {
            GrantRow grant = repo.findGrant(grantId)
                    .orElseThrow(() -> ApiException.notFound("授权不存在: " + grantId));
            int updated = repo.revokeGrant(grantId, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("GRANT_ALREADY_REVOKED", "授权已撤销: " + grantId);
            }
            return new GrantResponse(grant.id(), grant.channelId(), grant.assetId(),
                    atMs(grant.validFromMs()), atMs(grant.validToMs()), true);
        });
    }

    // ---------- 草稿 ----------

    /** 整份替换草稿；携带 expectedDraftVersion（0 表示首次创建），版本不符返回 409 且不改写原草稿。 */
    @Transactional
    public DraftResponse replaceDraft(String channelId, LocalDate businessDay,
                                      ReplaceDraftRequest request) {
        String hash = fingerprint(OP_REPLACE_DRAFT, orderedMap(
                "channelId", channelId,
                "businessDay", businessDay.toString(),
                "expectedDraftVersion", request.expectedDraftVersion(),
                "segments", canonicalSegments(request.segments())));
        return idempotent(request.requestId(), OP_REPLACE_DRAFT, hash, DraftResponse.class, () -> {
            // 频道锁：与同频道覆盖创建/结束裁决、发布、紧急插播按提交顺序串行。
            repo.lockChannelForUpdate(channelId);
            repo.findChannel(channelId)
                    .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
            advanceLiveOverrides(channelId, nowMs());

            List<ValidatedSegment> segments = validateSegments(channelId, businessDay, request.segments());
            long newVersion;
            if (request.expectedDraftVersion() == 0) {
                try {
                    repo.insertDraft(channelId, businessDay, nowMs());
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                            "草稿已存在，expectedDraftVersion 不能为 0");
                }
                newVersion = 1;
            } else {
                int updated = repo.bumpDraftVersion(channelId, businessDay,
                        request.expectedDraftVersion(), nowMs());
                if (updated == 0) {
                    throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                            "草稿版本不符，期望 " + request.expectedDraftVersion());
                }
                newVersion = request.expectedDraftVersion() + 1;
            }
            repo.deleteDraftSegments(channelId, businessDay);
            for (ValidatedSegment segment : segments) {
                repo.insertDraftSegment(segment.id(), channelId, businessDay,
                        segment.assetId(), segment.startMs(), segment.endMs());
            }
            return new DraftResponse(channelId, businessDay.toString(), newVersion,
                    segments.stream().map(ValidatedSegment::toResponse).toList());
        });
    }

    // ---------- 发布 ----------

    /**
     * 发布草稿：校验草稿版本与发布版本，逐片段加锁校验授权仍有效后原子生成只读快照。
     * 与授权撤销并发时按数据库提交顺序生效：撤销先提交则本方法因授权失效而 422 拒绝。
     */
    @Transactional
    public PublishResponse publish(String channelId, LocalDate businessDay, PublishRequest request) {
        String hash = fingerprint(OP_PUBLISH, orderedMap(
                "channelId", channelId,
                "businessDay", businessDay.toString(),
                "draftVersion", request.draftVersion(),
                "expectedPublishedVersion", request.expectedPublishedVersion()));
        return idempotent(request.requestId(), OP_PUBLISH, hash, PublishResponse.class, () -> {
            // 频道锁：新快照发布与覆盖令选取受影响素材必须按提交顺序互斥。
            repo.lockChannelForUpdate(channelId);
            repo.findChannel(channelId)
                    .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
            advanceLiveOverrides(channelId, nowMs());

            DraftRow draft = repo.findDraft(channelId, businessDay)
                    .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
            List<SegmentRow> segments = repo.findDraftSegments(channelId, businessDay);
            if (draft.version() != request.draftVersion()) {
                throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                        "草稿版本不符，当前 " + draft.version());
            }
            long currentPublished = repo.currentPublishedVersion(channelId, businessDay);
            if (currentPublished != request.expectedPublishedVersion()) {
                throw ApiException.conflict("PUBLISHED_VERSION_CONFLICT",
                        "发布版本冲突，当前 " + currentPublished);
            }
            List<Long> grantIds = new ArrayList<>();
            for (SegmentRow segment : segments) {
                GrantRow grant = selectCoveringGrantForUpdate(channelId, segment);
                grantIds.add(grant.id());
            }
            long newVersion = currentPublished + 1;
            long publicationId;
            try {
                publicationId = repo.insertPublication(channelId, businessDay,
                        newVersion, draft.version(), nowMs());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("PUBLISHED_VERSION_CONFLICT",
                        "发布版本冲突，版本 " + newVersion + " 已存在");
            }
            for (int i = 0; i < segments.size(); i++) {
                SegmentRow segment = segments.get(i);
                repo.insertPublicationSegment(publicationId, segment.id(), segment.assetId(),
                        grantIds.get(i), segment.startMs(), segment.endMs());
            }
            return new PublishResponse(publicationId, channelId, businessDay.toString(),
                    newVersion, draft.version());
        });
    }

    // ---------- 播出决定 ----------

    /**
     * 按频道与时刻查询播出决定。命中直播覆盖窗口（授权实时仍未撤销）时来源 LIVE；
     * 否则在命中该时刻的 ACTIVE 紧急插播中选取授权当前未撤销的最高优先级插播；
     * 再否则沿用原节目与保底逻辑。查询为只读，不推进覆盖状态、不写任何证据。
     */
    @Transactional(readOnly = true)
    public PlayoutDecisionResponse playoutDecision(String channelId, OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);

        Optional<LiveOverrideRow> live = repo.findLiveWindowAt(channelId, atMs);
        if (live.isPresent() && isGrantCurrentlyValid(live.get().grantId())) {
            return new PlayoutDecisionResponse(channelId, at, live.get().sourceId(),
                    DecisionSource.LIVE, null, null, null, null, live.get().overrideKey());
        }

        Optional<OverrideRow> override = selectActiveOverrideAt(channelId, atMs);
        if (override.isPresent()) {
            OverrideRow hit = override.get();
            return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                    DecisionSource.EMERGENCY, null, null, null, hit.overrideKey(), null);
        }

        LocalDate businessDay = at.atZoneSameInstant(ZONE).toLocalDate();

        Optional<PublicationRow> publication = repo.findLatestPublication(channelId, businessDay);
        if (publication.isEmpty()) {
            return fallback(channelId, at, channel.fallbackAssetId(),
                    FallbackReason.NO_PUBLISHED_SCHEDULE);
        }
        Optional<PublicationSegmentRow> segment =
                repo.findPublicationSegmentAt(publication.get().id(), atMs);
        if (segment.isEmpty()) {
            return fallback(channelId, at, channel.fallbackAssetId(),
                    FallbackReason.GAP);
        }
        PublicationSegmentRow hit = segment.get();

        // 覆盖窗口仍在但直播授权已失效：原素材保留占位、不得播出（窗口结束后也不会补播）。
        if (live.isPresent()) {
            return new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK, FallbackReason.LIVE_PREEMPTED,
                    publication.get().id(), hit.segmentId(), null, live.get().overrideKey());
        }
        // 覆盖已结束：被裁决为 MISSED_BY_LIVE 的素材永久不播；RESUMED 的素材正常恢复。
        Optional<String> liveResult = repo.findLiveRecoveryResultForSegment(
                channelId, publication.get().id(), hit.segmentId());
        if (liveResult.isPresent() && "MISSED_BY_LIVE".equals(liveResult.get())) {
            return new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK, FallbackReason.LIVE_PREEMPTED,
                    publication.get().id(), hit.segmentId(), null, null);
        }

        GrantRow grant = repo.findGrant(hit.grantId())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + hit.grantId()));
        if (grant.revoked()) {
            return new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK,
                    FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(), null, null);
        }
        return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(), null, null);
    }

    /**
     * 选取某时刻生效的最高优先级紧急插播：候选为区间命中且 ACTIVE 的插播，按优先级降序，
     * 跳过其指定授权已撤销的插播。授权状态为查询时的实时状态。
     */
    private Optional<OverrideRow> selectActiveOverrideAt(String channelId, long atMs) {
        for (OverrideRow candidate : repo.findActiveOverridesAt(channelId, atMs)) {
            if (isGrantCurrentlyValid(candidate.grantId())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean isGrantCurrentlyValid(long grantId) {
        GrantRow grant = repo.findGrant(grantId).orElse(null);
        return grant != null && !grant.revoked();
    }

    // ---------- 紧急插播 ----------

    /**
     * 创建限时紧急插播：创建即 ACTIVE，只可取消不可改写。校验频道/素材/授权存在、授权属于该
     * 频道与素材且未撤销并完整覆盖区间、区间同日且时长在 (0, 30 分钟]；同频道同优先级 ACTIVE
     * 区间不得重叠（相邻合法），且不得与有效直播覆盖窗口相交。携带 requestId 幂等。
     */
    @Transactional
    public EmergencyOverrideResponse createEmergencyOverride(CreateEmergencyOverrideRequest request) {
        String hash = fingerprint(OP_CREATE_OVERRIDE, orderedMap(
                "overrideKey", request.overrideKey(),
                "channelId", request.channelId(),
                "assetId", request.assetId(),
                "grantId", request.grantId(),
                "priority", request.priority(),
                "start", toMs(request.start()),
                "end", toMs(request.end())));
        return idempotent(request.requestId(), OP_CREATE_OVERRIDE, hash,
                EmergencyOverrideResponse.class, () -> doCreateOverride(request));
    }

    private EmergencyOverrideResponse doCreateOverride(CreateEmergencyOverrideRequest request) {
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        long durationMs = endMs - startMs;
        if (durationMs <= 0) {
            throw ApiException.badRequest("紧急插播结束时间必须大于开始时间");
        }
        if (durationMs > OVERRIDE_MAX_DURATION_MS) {
            throw ApiException.badRequest("紧急插播时长不得超过 30 分钟");
        }
        LocalDate startDay = request.start().atZoneSameInstant(ZONE).toLocalDate();
        LocalDate endDay = request.end().atZoneSameInstant(ZONE).toLocalDate();
        if (!startDay.equals(endDay)) {
            throw ApiException.badRequest("紧急插播起止时间必须处于同一 Asia/Shanghai 业务日");
        }

        repo.lockChannelForUpdate(request.channelId());
        advanceLiveOverrides(request.channelId(), nowMs());
        var channel = repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        if (channel.fallbackAssetId().equals(request.assetId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材不得用于紧急插播");
        }

        // 同键语义：已存在（含已取消）即冲突，重放创建不能复活已取消插播。
        if (repo.findOverrideForUpdate(request.overrideKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_OVERRIDE_KEY",
                    "紧急插播 overrideKey 已存在: " + request.overrideKey());
        }

        // 锁定授权行：与撤销事务按提交顺序串行。撤销先提交时 revoked=1，此处 422 拒绝。
        GrantRow grant = repo.findGrantForUpdate(request.grantId())
                .orElseThrow(() -> ApiException.notFound("授权不存在: " + request.grantId()));
        if (!grant.channelId().equals(request.channelId()) || !grant.assetId().equals(request.assetId())) {
            throw ApiException.unprocessable("GRANT_NOT_MATCHED",
                    "指定授权不属于该频道或素材: " + request.grantId());
        }
        if (grant.revoked()) {
            throw ApiException.unprocessable("GRANT_INVALID",
                    "指定授权已撤销: " + request.grantId());
        }
        if (grant.validFromMs() > startMs || grant.validToMs() < endMs) {
            throw ApiException.unprocessable("GRANT_NOT_COVERING",
                    "指定授权未完整覆盖插播区间: 实际[" + grant.validFromMs() + "," + grant.validToMs()
                            + ") 要求覆盖[" + startMs + "," + endMs + ")，差额 "
                            + Math.max(0L, startMs - grant.validFromMs()) + "/"
                            + Math.max(0L, grant.validToMs() - endMs) + " 毫秒");
        }

        List<OverrideRow> overlapping = repo.findActiveOverlappingForUpdate(
                request.channelId(), request.priority(), startMs, endMs);
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("OVERRIDE_INTERVAL_CONFLICT",
                    "同频道同优先级 ACTIVE 插播区间重叠: " + overlapping.get(0).overrideKey());
        }
        // 与直播覆盖互斥：同频道时段仅一个有效覆盖。
        List<LiveOverrideRow> liveConflicts = repo.findLiveOverlappingForUpdate(
                request.channelId(), startMs, endMs);
        if (!liveConflicts.isEmpty()) {
            throw ApiException.conflict("LIVE_OVERRIDE_INTERVAL_CONFLICT",
                    "区间与有效直播覆盖令重叠: " + liveConflicts.get(0).overrideKey());
        }

        long createdAtMs = nowMs();
        try {
            repo.insertOverride(request.overrideKey(), request.channelId(), request.assetId(),
                    request.grantId(), request.priority(), startMs, endMs, startDay, createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_OVERRIDE_KEY",
                    "紧急插播 overrideKey 已存在: " + request.overrideKey());
        }
        return new EmergencyOverrideResponse(request.overrideKey(), request.channelId(),
                request.assetId(), request.grantId(), request.priority(),
                request.start(), request.end(), OverrideStatus.ACTIVE, null, null,
                atMs(createdAtMs));
    }

    /**
     * 取消紧急插播：仅 ACTIVE 可取消，已取消再取消为 409 状态冲突；取消提交后释放同优先级
     * 冲突区间。携带 requestId 幂等：同键同参返回首次结果（含 CANCELLED 明细），改参 409。
     */
    @Transactional
    public EmergencyOverrideResponse cancelEmergencyOverride(String overrideKey, String requestId) {
        String hash = fingerprint(OP_CANCEL_OVERRIDE, Map.of("overrideKey", overrideKey));
        return idempotent(requestId, OP_CANCEL_OVERRIDE, hash, EmergencyOverrideResponse.class, () -> {
            // 先在频道锁上与同频道创建/取消串行，取消提交后并发创建即可复用该区间。
            OverrideRow existing = repo.findOverride(overrideKey)
                    .orElseThrow(() -> ApiException.notFound("紧急插播不存在: " + overrideKey));
            repo.lockChannelForUpdate(existing.channelId());
            advanceLiveOverrides(existing.channelId(), nowMs());
            int updated = repo.cancelOverride(overrideKey, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("OVERRIDE_NOT_ACTIVE",
                        "紧急插播已取消，不能重复取消: " + overrideKey);
            }
            return toOverrideResponse(repo.findOverrideForUpdate(overrideKey).orElseThrow());
        });
    }

    /** 查询紧急插播明细（不做状态校验，ACTIVE/CANCELLED 均返回，含取消情况与原授权关联）。 */
    @Transactional(readOnly = true)
    public EmergencyOverrideResponse getEmergencyOverride(String overrideKey) {
        OverrideRow row = repo.findOverride(overrideKey)
                .orElseThrow(() -> ApiException.notFound("紧急插播不存在: " + overrideKey));
        return toOverrideResponse(row);
    }

    private static EmergencyOverrideResponse toOverrideResponse(OverrideRow row) {
        return new EmergencyOverrideResponse(row.overrideKey(), row.channelId(), row.assetId(),
                row.grantId(), row.priority(), atMs(row.startMs()), atMs(row.endMs()),
                row.active() ? OverrideStatus.ACTIVE : OverrideStatus.CANCELLED,
                row.cancelRequestId(),
                row.cancelledAtMs() == null ? null : atMs(row.cancelledAtMs()),
                atMs(row.createdAtMs()));
    }

    // ---------- 直播覆盖令 ----------

    /**
     * 批量创建直播覆盖令（可跨频道）：全部条目先完整校验通过，再在单事务内提交；
     * 任一条目失败整体回滚，不留部分覆盖、不占用 requestId。同 requestId 同参重放返回
     * 首次完整响应快照，异参 409。幂等指纹含操作者、操作类型、按 overrideKey 规范化后的
     * 完整条目集合（JSON 序列化，不做未转义分隔符拼接）。
     */
    @Transactional
    public LiveOverrideBatchResponse createLiveOverrides(List<CreateLiveOverrideItem> items,
                                                         String operator, String requestId) {
        List<CreateLiveOverrideItem> canonical = items == null ? List.of() : items.stream()
                .sorted(Comparator.comparing(CreateLiveOverrideItem::overrideKey))
                .toList();
        List<Map<String, Object>> itemHashes = new ArrayList<>();
        for (CreateLiveOverrideItem item : canonical) {
            itemHashes.add(orderedMap(
                    "overrideKey", item.overrideKey(),
                    "channelId", item.channelId(),
                    "sourceId", item.sourceId(),
                    "grantId", item.grantId(),
                    "start", toMs(item.start()),
                    "end", toMs(item.end())));
        }
        String hash = fingerprint(OP_CREATE_LIVE_OVERRIDE, orderedMap(
                "operator", operator, "items", itemHashes));
        return idempotent(requestId, OP_CREATE_LIVE_OVERRIDE, hash,
                LiveOverrideBatchResponse.class,
                () -> doCreateLiveOverrides(canonical, operator, requestId));
    }

    private LiveOverrideBatchResponse doCreateLiveOverrides(List<CreateLiveOverrideItem> items,
                                                            String operator, String requestId) {
        if (items.isEmpty()) {
            throw ApiException.badRequest("直播覆盖令条目不能为空");
        }
        long now = nowMs();

        // 基础参数校验（区间左闭右开）。
        for (CreateLiveOverrideItem item : items) {
            if (toMs(item.end()) <= toMs(item.start())) {
                throw ApiException.badRequest(
                        "直播覆盖结束时间必须大于开始时间: " + item.overrideKey());
            }
        }

        // 多频道按频道 ID 排序加锁，避免跨频道死锁；锁内先推进到点覆盖状态。
        List<String> channelIds = items.stream().map(CreateLiveOverrideItem::channelId)
                .distinct().sorted().toList();
        for (String channelId : channelIds) {
            repo.lockChannelForUpdate(channelId);
        }
        for (String channelId : channelIds) {
            if (repo.findChannel(channelId).isEmpty()) {
                throw ApiException.notFound("频道不存在: " + channelId);
            }
            advanceLiveOverrides(channelId, now);
        }

        // 批次内键、同频道区间不得自相冲突。
        Set<String> keysInBatch = new LinkedHashSet<>();
        for (CreateLiveOverrideItem item : items) {
            if (!keysInBatch.add(item.overrideKey())) {
                throw ApiException.conflict("DUPLICATE_LIVE_OVERRIDE_KEY",
                        "批次内直播覆盖令键重复: " + item.overrideKey());
            }
        }
        for (int i = 0; i < items.size(); i++) {
            CreateLiveOverrideItem a = items.get(i);
            for (int j = i + 1; j < items.size(); j++) {
                CreateLiveOverrideItem b = items.get(j);
                if (a.channelId().equals(b.channelId())
                        && toMs(a.start()) < toMs(b.end()) && toMs(b.start()) < toMs(a.end())) {
                    throw ApiException.conflict("LIVE_OVERRIDE_INTERVAL_CONFLICT",
                            "批次内同频道覆盖区间重叠: " + a.overrideKey() + " 与 " + b.overrideKey());
                }
            }
        }

        // 占用键行锁（按键排序），已存在即冲突。
        List<LiveOverrideRow> existingRows =
                repo.findLiveOverridesForUpdate(new ArrayList<>(keysInBatch));
        if (!existingRows.isEmpty()) {
            throw ApiException.conflict("DUPLICATE_LIVE_OVERRIDE_KEY",
                    "直播覆盖令键已存在: " + existingRows.get(0).overrideKey());
        }

        // 预锁全部授权行（按 ID 排序），与授权撤销按提交顺序串行。
        List<Long> grantIds = items.stream().map(CreateLiveOverrideItem::grantId)
                .distinct().sorted().toList();
        Map<Long, GrantRow> grants = new LinkedHashMap<>();
        for (Long grantId : grantIds) {
            GrantRow grant = repo.findGrantForUpdate(grantId)
                    .orElseThrow(() -> ApiException.notFound("授权不存在: " + grantId));
            grants.put(grantId, grant);
        }

        // 逐条完整校验，收集待写入的最终状态；此阶段不做任何业务插入。
        List<PlannedLiveOverride> planned = new ArrayList<>();
        for (CreateLiveOverrideItem item : items) {
            planned.add(planLiveOverride(item, grants.get(item.grantId()), now));
        }

        // 校验完整最终状态后统一提交写入。
        for (PlannedLiveOverride plan : planned) {
            long id = repo.insertLiveOverride(plan.key(), plan.channelId(), plan.sourceId(),
                    plan.grantId(), plan.startMs(), plan.endMs(), "SCHEDULED", requestId,
                    operator, now);
            repo.insertLiveGrantSnapshot(id, plan.grantId(), plan.channelId(), plan.sourceId(),
                    plan.grantValidFromMs(), plan.grantValidToMs(), false, now);
            for (PlannedAffected affected : plan.affected()) {
                repo.insertLiveAffected(id, plan.channelId(), affected.businessDay(),
                        affected.publicationId(), affected.segmentId(), affected.assetId(),
                        affected.startMs(), affected.endMs());
            }
        }
        // 新覆盖令统一以 SCHEDULED 落库，再按当前时钟激活已到点者，started_at_ms 由条件更新写入。
        for (String channelId : channelIds) {
            advanceLiveOverrides(channelId, now);
        }

        List<LiveOverrideResponse> responses = new ArrayList<>();
        long totalDurationMs = 0L;
        int totalAffected = 0;
        long totalSuppressedMs = 0L;
        for (PlannedLiveOverride plan : planned) {
            LiveOverrideRow row = repo.findLiveOverrideByKey(plan.key()).orElseThrow();
            responses.add(assembleLiveResponse(plan.key()));
            totalDurationMs += plan.endMs() - plan.startMs();
            totalAffected += plan.affected().size();
            totalSuppressedMs += plan.affected().stream().mapToLong(PlannedAffected::suppressedMs).sum();
        }
        // responses 顺序与规范化后的 canonical 一致（按 overrideKey 排序），保持稳定。
        return new LiveOverrideBatchResponse(requestId, responses.size(), channelIds,
                totalDurationMs, totalAffected, totalSuppressedMs, 0, 0, responses);
    }

    /** 校验单条覆盖令并计算受影响素材集合（不写库）。 */
    private PlannedLiveOverride planLiveOverride(CreateLiveOverrideItem item, GrantRow grant, long now) {
        long startMs = toMs(item.start());
        long endMs = toMs(item.end());

        if (repo.findAsset(item.sourceId()).isEmpty()) {
            throw ApiException.notFound("直播源素材不存在: " + item.sourceId());
        }
        var channel = repo.findChannel(item.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + item.channelId()));
        if (channel.fallbackAssetId().equals(item.sourceId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材不得作为直播源: " + item.sourceId());
        }
        if (!grant.channelId().equals(item.channelId()) || !grant.assetId().equals(item.sourceId())) {
            throw ApiException.unprocessable("GRANT_NOT_MATCHED",
                    "指定授权不属于该频道或直播源: " + item.grantId());
        }
        if (grant.revoked()) {
            throw ApiException.unprocessable("GRANT_INVALID",
                    "直播源授权已撤销: " + item.grantId());
        }
        if (grant.validFromMs() > startMs || grant.validToMs() < endMs) {
            long shortfallFrom = Math.max(0L, startMs - grant.validFromMs());
            long shortfallTo = Math.max(0L, grant.validToMs() - endMs);
            throw ApiException.unprocessable("GRANT_NOT_COVERING",
                    "直播源授权未完整覆盖时段: 实际授权[" + grant.validFromMs() + "," + grant.validToMs()
                            + ")，要求覆盖[" + startMs + "," + endMs + ")，起点差额 " + shortfallFrom
                            + " 毫秒、终点差额 " + shortfallTo + " 毫秒");
        }
        if (now >= endMs) {
            throw ApiException.unprocessable("LIVE_INTERVAL_IN_PAST",
                    "直播覆盖区间已完全过去，当前 " + now + "，区间结束 " + endMs);
        }

        // 与同频道已有有效覆盖令区间相交即冲突（相邻合法）。
        List<LiveOverrideRow> overlapping =
                repo.findLiveOverlappingForUpdate(item.channelId(), startMs, endMs);
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("LIVE_OVERRIDE_INTERVAL_CONFLICT",
                    "同频道时段仅允许一个有效直播覆盖: 与 " + overlapping.get(0).overrideKey() + " 重叠");
        }
        // 与同频道 ACTIVE 紧急插播冲突，按提交顺序后者拒绝。
        List<OverrideRow> emergencyConflicts =
                repo.findAnyActiveEmergencyOverlappingForUpdate(item.channelId(), startMs, endMs);
        if (!emergencyConflicts.isEmpty()) {
            throw ApiException.conflict("EMERGENCY_OVERRIDE_CONFLICT",
                    "覆盖时段与进行中的紧急插播冲突: " + emergencyConflicts.get(0).overrideKey());
        }

        // 与已开始节目冲突：创建时刻正在播出、且开播早于本令的节目不能被截断。
        if (startMs <= now) {
            LocalDate today = atMs(now).atZoneSameInstant(ZONE).toLocalDate();
            Optional<PublicationRow> currentPublication =
                    repo.findLatestPublication(item.channelId(), today);
            if (currentPublication.isPresent()) {
                Optional<PublicationSegmentRow> onAir = repo.findPublicationSegments(
                        currentPublication.get().id()).stream()
                        .filter(seg -> seg.startMs() < now && seg.endMs() > now)
                        .findFirst();
                if (onAir.isPresent()) {
                    throw ApiException.conflict("LIVE_PROGRAM_ALREADY_STARTED",
                            "覆盖开始时刻节目已开始播出，实际在播片段 " + onAir.get().segmentId()
                                    + " [" + onAir.get().startMs() + "," + onAir.get().endMs()
                                    + ")，覆盖起点 " + startMs);
                }
            }
        }

        // 受影响素材：覆盖窗口跨越的每个业务日取最新发布快照，与区间相交的片段全部保留占位。
        List<PlannedAffected> affected = collectAffected(item.channelId(), startMs, endMs);
        return new PlannedLiveOverride(item.overrideKey(), item.channelId(), item.sourceId(),
                item.grantId(), startMs, endMs, grant.validFromMs(), grant.validToMs(), affected);
    }

    /**
     * 计算受覆盖决策影响的已发布快照片段：严格相交片段在覆盖窗口内保留占位（抑制交集时长），
     * 与覆盖结束端点相接（恰在结束时开播，左闭右开下不属于覆盖内）的后续片段也纳入，
     * 供覆盖结束时裁决为 RESUMED；其抑制交集时长为 0。逐业务日取最新快照。
     */
    private List<PlannedAffected> collectAffected(String channelId, long startMs, long endMs) {
        List<PlannedAffected> affected = new ArrayList<>();
        LocalDate day = atMs(startMs).atZoneSameInstant(ZONE).toLocalDate();
        LocalDate endDay = atMs(endMs - 1).atZoneSameInstant(ZONE).toLocalDate();
        while (!day.isAfter(endDay)) {
            long dayStart = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
            long dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            long windowStart = Math.max(startMs, dayStart);
            long windowEnd = Math.min(endMs, dayEnd);
            Optional<PublicationRow> publication = repo.findLatestPublication(channelId, day);
            if (publication.isPresent()) {
                for (PublicationSegmentRow seg : repo.findPublicationSegments(publication.get().id())) {
                    if (seg.startMs() < windowEnd && seg.endMs() > windowStart) {
                        long suppressedMs = Math.min(seg.endMs(), endMs) - Math.max(seg.startMs(), startMs);
                        affected.add(new PlannedAffected(day, publication.get().id(), seg.segmentId(),
                                seg.assetId(), seg.startMs(), seg.endMs(), Math.max(0L, suppressedMs)));
                    }
                }
            }
            day = day.plusDays(1);
        }
        // 仅在覆盖真正结束端点处纳入恰在结束时开播的后续片段（跨日边界不重复纳入）。
        LocalDate finishDay = atMs(endMs).atZoneSameInstant(ZONE).toLocalDate();
        repo.findLatestPublication(channelId, finishDay).ifPresent(publication -> {
            for (PublicationSegmentRow seg : repo.findPublicationSegments(publication.id())) {
                if (seg.startMs() == endMs) {
                    affected.add(new PlannedAffected(finishDay, publication.id(), seg.segmentId(),
                            seg.assetId(), seg.startMs(), seg.endMs(), 0L));
                }
            }
        });
        affected.sort(Comparator.comparingLong(PlannedAffected::startMs)
                .thenComparing(PlannedAffected::publicationId)
                .thenComparing(PlannedAffected::segmentId));
        return affected;
    }

    /**
     * 撤销直播覆盖令：仅 SCHEDULED（尚未开始覆盖）可撤销；ACTIVE/ENDED/REVOKED 一律 409。
     * 已产生的受影响素材与授权快照为不可变证据保留，撤销不删除、不改写。
     */
    @Transactional
    public LiveOverrideResponse revokeLiveOverride(String overrideKey,
                                                   RevokeLiveOverrideRequest request) {
        String hash = fingerprint(OP_REVOKE_LIVE_OVERRIDE, orderedMap(
                "overrideKey", overrideKey, "operator", request.operator()));
        return idempotent(request.requestId(), OP_REVOKE_LIVE_OVERRIDE, hash,
                LiveOverrideResponse.class, () -> {
                    LiveOverrideRow existing = repo.findLiveOverrideByKey(overrideKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "直播覆盖令不存在: " + overrideKey));
                    repo.lockChannelForUpdate(existing.channelId());
                    advanceLiveOverrides(existing.channelId(), nowMs());
                    LiveOverrideRow locked = repo.findLiveOverrideForUpdate(overrideKey).orElseThrow();
                    if (locked.status().equals("REVOKED")) {
                        throw ApiException.conflict("LIVE_OVERRIDE_ALREADY_REVOKED",
                                "直播覆盖令已撤销: " + overrideKey);
                    }
                    int updated = repo.revokeLiveOverride(overrideKey, request.requestId(),
                            request.operator(), nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("LIVE_OVERRIDE_NOT_REVOKABLE",
                                "仅尚未开始的直播覆盖令可撤销，当前状态 " + locked.status()
                                        + ": " + overrideKey);
                    }
                    return assembleLiveResponse(overrideKey);
                });
    }

    /** 查询直播覆盖令明细：受影响素材、恢复裁决与授权版本快照均随明细返回，读取不改状态。 */
    @Transactional(readOnly = true)
    public LiveOverrideResponse getLiveOverride(String overrideKey) {
        repo.findLiveOverrideByKey(overrideKey)
                .orElseThrow(() -> ApiException.notFound("直播覆盖令不存在: " + overrideKey));
        return assembleLiveResponse(overrideKey);
    }

    /** 直播覆盖令历史，排序固定为开始时间升序、ID 升序；频道与 UTC 时间窗口可选，读取不改状态。 */
    @Transactional(readOnly = true)
    public List<LiveOverrideSummary> listLiveOverrides(String channelId,
                                                       OffsetDateTime from, OffsetDateTime to) {
        Long fromMs = from == null ? null : toMs(from);
        Long toMs = to == null ? null : toMs(to);
        return repo.listLiveOverrides(channelId, fromMs, toMs).stream()
                .map(this::toSummary).toList();
    }

    /**
     * 频道某时刻直播诊断：实际在播覆盖、抑制/错过/恢复片段数量与直播毫秒数均为实际值，
     * 统计窗口为 at 所属 Asia/Shanghai 业务日 [dayStart, at]。纯读操作，不推进任何状态。
     */
    @Transactional(readOnly = true)
    public LiveDiagnosticsResponse liveDiagnostics(String channelId, OffsetDateTime at) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);
        LocalDate day = at.atZoneSameInstant(ZONE).toLocalDate();
        long dayStart = day.atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<LiveOverrideRow> dayOverrides =
                repo.findLiveOverridesInWindow(channelId, dayStart, atMs + 1);
        List<LiveOverrideSummary> summaries = dayOverrides.stream().map(this::toSummary).toList();

        LiveOverrideRow active = repo.findLiveWindowAt(channelId, atMs).orElse(null);
        boolean liveOnAir = false;
        long liveOnAirMs = 0L;
        long suppressedNow = 0L;
        if (active != null && isGrantCurrentlyValid(active.grantId())) {
            liveOnAir = true;
            for (LiveAffectedRow affected : repo.findLiveAffected(active.id())) {
                if (affected.scheduledStartMs() <= atMs && affected.scheduledEndMs() > atMs) {
                    suppressedNow++;
                }
            }
        }
        // 业务日内截至 at 的实际已直播毫秒：每个非撤销覆盖窗口与 [dayStart, at] 的交集（at 恰在起点为 0）。
        for (LiveOverrideRow row : dayOverrides) {
            if (row.status().equals("REVOKED")) {
                continue;
            }
            long from = Math.max(row.startMs(), dayStart);
            long to = Math.min(row.endMs(), atMs);
            if (to > from) {
                liveOnAirMs += to - from;
            }
        }
        long missed = 0L;
        long resumed = 0L;
        for (LiveOverrideRow row : dayOverrides) {
            missed += repo.countLiveRecovery(row.id(), "MISSED_BY_LIVE");
            resumed += repo.countLiveRecovery(row.id(), "RESUMED");
        }
        return new LiveDiagnosticsResponse(channelId, at, liveOnAir,
                active == null ? null : active.overrideKey(),
                active == null ? null : active.sourceId(),
                dayOverrides.stream().filter(r -> !r.status().equals("REVOKED")).count(),
                suppressedNow, missed, resumed, liveOnAirMs, summaries);
    }

    /**
     * 主动推进频道内到点直播覆盖状态并完成结束裁决（写路径在频道锁内也会自动调用）。
     * 供测试与运维触发使用；已完成的状态推进与裁决为条件更新，重复执行不产生重复证据。
     */
    @Transactional
    public void settleChannel(String channelId) {
        repo.lockChannelForUpdate(channelId);
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        advanceLiveOverrides(channelId, nowMs());
    }

    /**
     * 持频道锁后调用：SCHEDULED 到开始转 ACTIVE；ACTIVE 到结束转 ENDED 并逐条写入不可变
     * 恢复裁决。裁决基准为覆盖结束时刻：原计划开始不早于结束时刻（左闭右开，恰在结束时
     * 开播）的素材 RESUMED 恢复，其余 MISSED_BY_LIVE，不自动补播。
     */
    private void advanceLiveOverrides(String channelId, long now) {
        for (LiveOverrideRow row : repo.findDueLiveOverridesForUpdate(channelId, now)) {
            if ("SCHEDULED".equals(row.status()) && row.startMs() <= now) {
                if (repo.activateLiveOverride(row.id(), now) == 0) {
                    continue;
                }
                if (row.endMs() <= now) {
                    finishLiveOverride(row, now);
                }
            } else if ("ACTIVE".equals(row.status()) && row.endMs() <= now) {
                finishLiveOverride(row, now);
            }
        }
    }

    private void finishLiveOverride(LiveOverrideRow row, long now) {
        if (repo.finishLiveOverride(row.id(), now) == 0) {
            return;
        }
        List<LiveAffectedRow> affectedRows = repo.findLiveAffected(row.id());
        if (!repo.findLiveRecovery(row.id()).isEmpty()) {
            return;
        }
        for (LiveAffectedRow affected : affectedRows) {
            String result = affected.scheduledStartMs() >= row.endMs()
                    ? "RESUMED" : "MISSED_BY_LIVE";
            try {
                repo.insertLiveRecovery(row.id(), row.channelId(), affected.businessDay(),
                        affected.publicationId(), affected.segmentId(), result,
                        affected.scheduledStartMs(), affected.scheduledEndMs(), now);
            } catch (DuplicateKeyException ignored) {
                // 并发重复裁决以已有证据为准。
            }
        }
    }

    private LiveOverrideResponse assembleLiveResponse(String overrideKey) {
        LiveOverrideRow row = repo.findLiveOverrideByKey(overrideKey).orElseThrow();
        List<LiveAffectedRow> affectedRows = repo.findLiveAffected(row.id());
        List<LiveRecoveryRow> recoveryRows = repo.findLiveRecovery(row.id());
        Optional<LiveGrantSnapshotRow> snapshot = repo.findLiveGrantSnapshot(row.id());
        Long startedAt = row.status().equals("ACTIVE") || row.status().equals("ENDED")
                ? row.startedAtMs() : null;
        return toLiveResponse(row,
                affectedRows.stream().map(a -> toPlanned(row, a)).toList(),
                recoveryRows, snapshot.orElse(null), startedAt);
    }

    private PlannedAffected toPlanned(LiveOverrideRow row, LiveAffectedRow a) {
        long suppressedMs = Math.min(row.endMs(), a.scheduledEndMs())
                - Math.max(row.startMs(), a.scheduledStartMs());
        return new PlannedAffected(a.businessDay(), a.publicationId(), a.segmentId(),
                a.assetId(), a.scheduledStartMs(), a.scheduledEndMs(), Math.max(0L, suppressedMs));
    }

    private LiveOverrideResponse toLiveResponse(LiveOverrideRow row,
                                                List<PlannedAffected> affected,
                                                List<LiveRecoveryRow> recoveries,
                                                LiveGrantSnapshotRow snapshot,
                                                Long startedAtMs) {
        Map<String, LiveRecoveryRow> recoveryBySegment = new LinkedHashMap<>();
        for (LiveRecoveryRow recovery : recoveries) {
            recoveryBySegment.put(recovery.publicationId() + ":" + recovery.segmentId(), recovery);
        }
        long suppressedDurationMs = affected.stream().mapToLong(PlannedAffected::suppressedMs).sum();
        List<LiveAffectedItem> items = affected.stream()
                .map(a -> {
                    LiveRecoveryRow recovery =
                            recoveryBySegment.get(a.publicationId() + ":" + a.segmentId());
                    return new LiveAffectedItem(a.publicationId(), a.businessDay().toString(),
                            a.segmentId(), a.assetId(), atMs(a.startMs()), atMs(a.endMs()),
                            recovery == null ? "SUPPRESSED" : recovery.result(),
                            recovery == null ? null : atMs(recovery.recoveredAtMs()));
                })
                .toList();
        List<LiveRecoveryItem> recoveryItems = recoveries.stream()
                .sorted(Comparator.comparingLong(LiveRecoveryRow::scheduledStartMs)
                        .thenComparing(LiveRecoveryRow::publicationId)
                        .thenComparing(LiveRecoveryRow::segmentId))
                .map(r -> new LiveRecoveryItem(r.publicationId(), r.businessDay().toString(),
                        r.segmentId(), r.result(), atMs(r.scheduledStartMs()),
                        atMs(r.scheduledEndMs()), atMs(r.recoveredAtMs())))
                .toList();
        int resumed = (int) recoveries.stream().filter(r -> "RESUMED".equals(r.result())).count();
        int missed = (int) recoveries.stream().filter(r -> "MISSED_BY_LIVE".equals(r.result())).count();
        LiveGrantSnapshot snapshotDto = snapshot == null ? null : new LiveGrantSnapshot(
                snapshot.grantId(), snapshot.channelId(), snapshot.assetId(),
                atMs(snapshot.validFromMs()), atMs(snapshot.validToMs()), snapshot.revoked(),
                atMs(snapshot.capturedAtMs()));
        return new LiveOverrideResponse(row.id(), row.overrideKey(), row.channelId(), row.sourceId(),
                row.grantId(), atMs(row.startMs()), atMs(row.endMs()),
                LiveOverrideStatus.valueOf(row.status()), row.operator(),
                startedAtMs == null ? null : atMs(startedAtMs),
                row.endedAtMs() == null ? null : atMs(row.endedAtMs()),
                atMs(row.createdAtMs()), row.revokeRequestId(), row.revokeOperator(),
                row.revokedAtMs() == null ? null : atMs(row.revokedAtMs()),
                row.endMs() - row.startMs(), affected.size(), suppressedDurationMs,
                resumed, missed, items, recoveryItems, snapshotDto);
    }

    private LiveOverrideSummary toSummary(LiveOverrideRow row) {
        long id = row.id();
        int resumed = (int) repo.findLiveRecovery(id).stream()
                .filter(r -> "RESUMED".equals(r.result())).count();
        int missed = (int) repo.findLiveRecovery(id).stream()
                .filter(r -> "MISSED_BY_LIVE".equals(r.result())).count();
        int affectedCount = repo.findLiveAffected(id).size();
        return new LiveOverrideSummary(row.id(), row.overrideKey(), row.channelId(), row.sourceId(),
                row.grantId(), atMs(row.startMs()), atMs(row.endMs()),
                LiveOverrideStatus.valueOf(row.status()), row.operator(),
                row.startedAtMs() == null ? null : atMs(row.startedAtMs()),
                row.endedAtMs() == null ? null : atMs(row.endedAtMs()),
                atMs(row.createdAtMs()), row.revokeRequestId(),
                row.revokedAtMs() == null ? null : atMs(row.revokedAtMs()),
                affectedCount, resumed, missed);
    }

    // ---------- 内部方法 ----------

    /** 校验并规范化草稿片段：非空、区间内、不跨日、不重叠、素材存在且被单条未撤销授权完整覆盖。 */
    private List<ValidatedSegment> validateSegments(String channelId, LocalDate businessDay,
                                                    List<SegmentInput> inputs) {
        long dayStartMs = businessDay.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEndMs = businessDay.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<ValidatedSegment> segments = new ArrayList<>();
        for (SegmentInput input : inputs) {
            if (input.start() == null || input.end() == null) {
                throw ApiException.badRequest("片段开始与结束时间不能为空");
            }
            if (input.assetId() == null || input.assetId().isBlank()) {
                throw ApiException.badRequest("片段素材 ID 不能为空");
            }
            long startMs = toMs(input.start());
            long endMs = toMs(input.end());
            if (endMs <= startMs) {
                throw ApiException.badRequest("片段结束时间必须大于开始时间");
            }
            if (startMs < dayStartMs || endMs > dayEndMs) {
                throw ApiException.unprocessable("SEGMENT_CROSSES_DAY",
                        "片段不得跨业务日: " + input.start() + " ~ " + input.end());
            }
            String id = input.id() == null || input.id().isBlank()
                    ? UUID.randomUUID().toString() : input.id();
            segments.add(new ValidatedSegment(id, input.assetId(), startMs, endMs));
        }

        List<ValidatedSegment> sorted = segments.stream()
                .sorted(Comparator.comparingLong(ValidatedSegment::startMs)
                        .thenComparing(ValidatedSegment::id))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMs() < sorted.get(i - 1).endMs()) {
                throw ApiException.unprocessable("SEGMENT_OVERLAP",
                        "片段时间重叠: " + sorted.get(i - 1).id() + " 与 " + sorted.get(i).id());
            }
        }

        for (ValidatedSegment segment : segments) {
            if (repo.findAsset(segment.assetId()).isEmpty()) {
                throw ApiException.notFound("素材不存在: " + segment.assetId());
            }
            List<GrantRow> covering = repo.findCoveringGrants(channelId, segment.assetId(),
                    segment.startMs(), segment.endMs());
            if (covering.isEmpty()) {
                throw ApiException.unprocessable("NO_COVERING_GRANT",
                        "片段未被单条有效授权完整覆盖: " + segment.id());
            }
        }
        return segments;
    }

    /** 发布时加行锁选取覆盖授权；不存在或未撤销的覆盖授权时 422 拒绝整次发布。 */
    private GrantRow selectCoveringGrantForUpdate(String channelId, SegmentRow segment) {
        List<GrantRow> candidates = repo.findCoveringGrantsForUpdate(channelId, segment.assetId(),
                segment.startMs(), segment.endMs());
        return candidates.stream()
                .filter(grant -> !grant.revoked())
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("GRANT_INVALID",
                        "片段授权已失效，发布拒绝: " + segment.id()));
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

    /**
     * 幂等指纹：将规范化对象（Map/List/标量）序列化为 JSON 后取 SHA-256。
     * JSON 转义天然消除分隔符歧义，禁止改用未转义分隔符拼接。
     */
    private String fingerprint(String operation, Object canonical) {
        try {
            Map<String, Object> envelope = new TreeMap<>();
            envelope.put("operation", operation);
            envelope.put("params", canonical);
            return sha256(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    /** 保持插入顺序的字段映射，配合 {@link #fingerprint} 做稳定 JSON 序列化。 */
    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private List<Map<String, Object>> canonicalSegments(List<SegmentInput> segments) {
        return segments.stream()
                .sorted(Comparator.comparingLong((SegmentInput s) ->
                                s.start() == null ? Long.MIN_VALUE : toMs(s.start()))
                        .thenComparing(s -> s.id() == null ? "" : s.id()))
                .map(s -> orderedMap(
                        "id", s.id() == null ? "" : s.id(),
                        "assetId", s.assetId(),
                        "start", s.start() == null ? "" : toMs(s.start()),
                        "end", s.end() == null ? "" : toMs(s.end())))
                .toList();
    }

    private PlayoutDecisionResponse fallback(String channelId, OffsetDateTime at, String assetId,
                                             FallbackReason reason) {
        return new PlayoutDecisionResponse(channelId, at, assetId,
                DecisionSource.FALLBACK, reason, null, null, null, null);
    }

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }

    private long nowMs() {
        return clock.nowMs();
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

    /** 校验后的片段。 */
    private record ValidatedSegment(String id, String assetId, long startMs, long endMs) {
        SegmentResponse toResponse() {
            return new SegmentResponse(id, assetId, atMs(startMs), atMs(endMs));
        }
    }

    /** 通过校验、待写入的单条直播覆盖令最终状态。 */
    private record PlannedLiveOverride(String key, String channelId, String sourceId, long grantId,
                                       long startMs, long endMs, long grantValidFromMs,
                                       long grantValidToMs, List<PlannedAffected> affected) {
    }

    /** 待写入的受影响素材及该片段实际被抑制毫秒数。 */
    private record PlannedAffected(LocalDate businessDay, long publicationId, String segmentId,
                                   String assetId, long startMs, long endMs, long suppressedMs) {
    }
}
