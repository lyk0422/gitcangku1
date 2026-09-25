package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.AssetRow;
import com.example.starter.playout.PlayoutRepository.BlackoutRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRegionRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.PlayoutRepository.SpliceConfigRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.BlackoutResponse;
import com.example.starter.playout.api.Dtos.BlackoutStatus;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateBlackoutRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateReceiptRequest;
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.OverrideStatus;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublicationRegionView;
import com.example.starter.playout.api.Dtos.PublicationSegmentView;
import com.example.starter.playout.api.Dtos.PublicationSnapshotResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReceiptResponse;
import com.example.starter.playout.api.Dtos.RegionalDecisionSource;
import com.example.starter.playout.api.Dtos.RegionalPlayoutResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.ReplaceSplicesRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.example.starter.playout.api.Dtos.SpliceConfigResponse;
import com.example.starter.playout.api.Dtos.SpliceDiagnosticsResponse;
import com.example.starter.playout.api.Dtos.SpliceRegionInput;
import com.example.starter.playout.api.Dtos.SpliceRegionResponse;
import com.example.starter.playout.api.Dtos.SpliceViolation;
import com.example.starter.playout.api.Dtos.SpliceWindowInput;
import com.example.starter.playout.api.Dtos.SpliceWindowResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 播出编排核心服务：素材、频道、授权、草稿、发布与播出决定。
 *
 * <p>约定：数据库内时间统一为 UTC 纪元毫秒；API 边界统一为 Asia/Shanghai、毫秒精度 ISO 8601；
 * 业务日为 Asia/Shanghai 日历日。授权区间为左闭右开。所有写操作与幂等去重记录在同一事务提交，
 * 失败整体回滚、不占用 requestId。</p>
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
    private static final String OP_REPLACE_SPLICES = "REPLACE_SPLICES";
    private static final String OP_CREATE_BLACKOUT = "CREATE_BLACKOUT";
    private static final String OP_CANCEL_BLACKOUT = "CANCEL_BLACKOUT";
    private static final String OP_WITHDRAW_ASSET = "WITHDRAW_ASSET";
    private static final String OP_PULL_ASSET = "PULL_ASSET";
    private static final String OP_CREATE_RECEIPT = "CREATE_RECEIPT";

    /** 紧急插播时长上限（含）：30 分钟，单位毫秒。 */
    private static final long OVERRIDE_MAX_DURATION_MS = 30L * 60L * 1000L;

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;

    public PlayoutService(PlayoutRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
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
        return new AssetResponse(id, request.durationMs(), 1, false);
    }

    /** 撤回素材：终态，撤回后不得用于新发布的插播；幂等：同 requestId 返回原结果。 */
    @Transactional
    public AssetResponse withdrawAsset(String assetId, String requestId) {
        String hash = sha256(OP_WITHDRAW_ASSET + "|" + assetId);
        return idempotent(requestId, OP_WITHDRAW_ASSET, hash, AssetResponse.class, () -> {
            int updated = repo.withdrawAsset(assetId);
            if (updated == 0) {
                if (repo.findAsset(assetId).isEmpty()) {
                    throw ApiException.notFound("素材不存在: " + assetId);
                }
                throw ApiException.conflict("ASSET_ALREADY_WITHDRAWN", "素材已撤回: " + assetId);
            }
            return toAssetResponse(assetId);
        });
    }

    /** 拉取素材新版本：版本递增，不改写任何历史发布快照；幂等：同 requestId 返回原结果。 */
    @Transactional
    public AssetResponse pullAssetVersion(String assetId, String requestId) {
        String hash = sha256(OP_PULL_ASSET + "|" + assetId);
        return idempotent(requestId, OP_PULL_ASSET, hash, AssetResponse.class, () -> {
            int updated = repo.pullAssetVersion(assetId);
            if (updated == 0) {
                throw ApiException.notFound("素材不存在: " + assetId);
            }
            return toAssetResponse(assetId);
        });
    }

    private AssetResponse toAssetResponse(String assetId) {
        AssetRow asset = repo.findAsset(assetId)
                .orElseThrow(() -> ApiException.notFound("素材不存在: " + assetId));
        return new AssetResponse(asset.id(), asset.durationMs(), asset.version(), asset.withdrawn());
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

    /** 创建授权，关联频道与普通素材，有效区间左闭右开；regionCode 为空表示全部区域。 */
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
        String regionCode = normalizeRegion(request.regionCode());
        long id = repo.insertGrant(request.channelId(), request.assetId(), regionCode,
                fromMs, toMs, nowMs());
        return new GrantResponse(id, request.channelId(), request.assetId(), regionCode,
                request.validFrom(), request.validTo(), 1, false);
    }

    /** 撤销授权；幂等：同 requestId 同参数返回原结果，改参数返回 409。撤销同时递增授权版本。 */
    @Transactional
    public GrantResponse revokeGrant(long grantId, String requestId) {
        String hash = sha256(OP_REVOKE_GRANT + "|" + grantId);
        return idempotent(requestId, OP_REVOKE_GRANT, hash, GrantResponse.class, () -> {
            GrantRow grant = repo.findGrant(grantId)
                    .orElseThrow(() -> ApiException.notFound("授权不存在: " + grantId));
            int updated = repo.revokeGrant(grantId, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("GRANT_ALREADY_REVOKED", "授权已撤销: " + grantId);
            }
            return new GrantResponse(grant.id(), grant.channelId(), grant.assetId(),
                    grant.regionCode(), atMs(grant.validFromMs()), atMs(grant.validToMs()),
                    grant.version() + 1, true);
        });
    }

    // ---------- 草稿 ----------

    /** 整份替换草稿；携带 expectedDraftVersion（0 表示首次创建），版本不符返回 409 且不改写原草稿。 */
    @Transactional
    public DraftResponse replaceDraft(String channelId, LocalDate businessDay,
                                      ReplaceDraftRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String hash = sha256(OP_REPLACE_DRAFT + "|" + channelId + "|" + businessDay
                + "|" + request.expectedDraftVersion() + "|" + canonicalSegments(request.segments()));
        return idempotent(request.requestId(), OP_REPLACE_DRAFT, hash, DraftResponse.class, () -> {
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
            // 插播配置挂在节目单条目上，随草稿整份替换一并清除
            repo.deleteSpliceConfigsForDay(channelId, businessDay);
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
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String hash = sha256(OP_PUBLISH + "|" + channelId + "|" + businessDay
                + "|" + request.draftVersion() + "|" + request.expectedPublishedVersion());
        return idempotent(request.requestId(), OP_PUBLISH, hash, PublishResponse.class, () -> {
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
            List<GrantRow> segmentGrants = new ArrayList<>();
            for (SegmentRow segment : segments) {
                segmentGrants.add(selectCoveringGrantForUpdate(channelId, segment));
            }
            // 区域插播逐区域解析：任一区域授权失效、素材被撤回或窗口与黑屏相交，
            // 整次发布 422 并稳定列出区域与原因，不发布部分区域。
            List<SpliceConfigRow> spliceConfigs = repo.findSpliceConfigsForDay(channelId, businessDay);
            Map<Long, GrantRow> spliceGrants = new HashMap<>();
            List<SpliceViolation> violations = new ArrayList<>();
            for (SpliceConfigRow config : spliceConfigs) {
                validateSpliceForPublish(channelId, config, spliceGrants, violations);
            }
            if (!violations.isEmpty()) {
                violations.sort(Comparator.comparing(SpliceViolation::regionCode)
                        .thenComparing(SpliceViolation::segmentId)
                        .thenComparing(SpliceViolation::reason));
                throw ApiException.unprocessable("SPLICE_BLOCKED",
                        "区域插播校验失败，整次发布拒绝，共 " + violations.size() + " 处阻断", violations);
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
                        segmentGrants.get(i).id(), segment.startMs(), segment.endMs());
            }
            freezeRegionalSnapshot(publicationId, segments, segmentGrants, spliceConfigs, spliceGrants);
            return new PublishResponse(publicationId, channelId, businessDay.toString(),
                    newVersion, draft.version());
        });
    }

    // ---------- 播出决定 ----------

    /**
     * 按频道与时刻查询播出决定。先在命中该时刻的 ACTIVE 紧急插播中选取指定授权当前未撤销的
     * 最高优先级插播（EMERGENCY 来源，随附 overrideKey）；高优先级插播授权失效时自动落到
     * 仍有效的低优先级插播。无有效插播候选时沿用原节目与保底逻辑。查询不改变插播状态，
     * 插播不随授权撤销自动换绑授权。到插播结束时刻（左闭右开）不再命中。
     */
    @Transactional(readOnly = true)
    public PlayoutDecisionResponse playoutDecision(String channelId, OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);

        Optional<OverrideRow> override = selectActiveOverrideAt(channelId, atMs);
        if (override.isPresent()) {
            OverrideRow hit = override.get();
            return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                    DecisionSource.EMERGENCY, null, null, null, hit.overrideKey());
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
        GrantRow grant = repo.findGrant(hit.grantId())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + hit.grantId()));
        if (grant.revoked()) {
            return new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK,
                    FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(), null);
        }
        return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(), null);
    }

    /**
     * 选取某时刻生效的最高优先级紧急插播：候选为区间命中且 ACTIVE 的插播，按优先级降序，
     * 跳过其指定授权已撤销的插播。授权状态为查询时的实时状态。
     */
    private Optional<OverrideRow> selectActiveOverrideAt(String channelId, long atMs) {
        for (OverrideRow candidate : repo.findActiveOverridesAt(channelId, atMs)) {
            GrantRow grant = repo.findGrant(candidate.grantId()).orElse(null);
            if (grant != null && !grant.revoked()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ---------- 紧急插播 ----------

    /**
     * 创建限时紧急插播：创建即 ACTIVE，只可取消不可改写。校验频道/素材/授权存在、授权属于该
     * 频道与素材且未撤销并完整覆盖区间、区间同日且时长在 (0, 30 分钟]；同频道同优先级 ACTIVE
     * 区间不得重叠（相邻合法）。携带 requestId 幂等：同键同参返回首次结果，改参 409，失败不占键；
     * 已取消插播不能用相同 overrideKey 重放复活。
     */
    @Transactional
    public EmergencyOverrideResponse createEmergencyOverride(CreateEmergencyOverrideRequest request) {
        String hash = sha256(OP_CREATE_OVERRIDE + "|" + request.overrideKey() + "|"
                + request.channelId() + "|" + request.assetId() + "|" + request.grantId() + "|"
                + request.priority() + "|" + toMs(request.start()) + "|" + toMs(request.end()));
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
                    "指定授权未完整覆盖插播区间: " + request.grantId());
        }

        List<OverrideRow> overlapping = repo.findActiveOverlappingForUpdate(
                request.channelId(), request.priority(), startMs, endMs);
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("OVERRIDE_INTERVAL_CONFLICT",
                    "同频道同优先级 ACTIVE 插播区间重叠: " + overlapping.get(0).overrideKey());
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
     * 冲突区间。携带 requestId 幂等：同键同参返回首次结果（含 CANCELLED 明细），改参 409，
     * 失败不占键。
     */
    @Transactional
    public EmergencyOverrideResponse cancelEmergencyOverride(String overrideKey, String requestId) {
        String hash = sha256(OP_CANCEL_OVERRIDE + "|" + overrideKey);
        return idempotent(requestId, OP_CANCEL_OVERRIDE, hash, EmergencyOverrideResponse.class, () -> {
            // 先在频道锁上与同频道创建/取消串行，取消提交后并发创建即可复用该区间。
            OverrideRow existing = repo.findOverride(overrideKey)
                    .orElseThrow(() -> ApiException.notFound("紧急插播不存在: " + overrideKey));
            repo.lockChannelForUpdate(existing.channelId());
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

    // ---------- 区域插播配置 ----------

    /**
     * 整份替换某节目单条目的区域插播配置。spliceKey 为幂等键，指纹含节目单版本、区域、窗口、
     * 素材及授权版本：同键同参重放首次完整结果，改参 409，失败不占键。配置挂在当前草稿版本上
     * （expectedDraftVersion 乐观锁），草稿整份替换后配置随之清除。同条目同区域窗口不得重叠，
     * 端点相接合法；窗口须落在条目窗口内；插播素材必须具有覆盖该区域与完整窗口的有效授权版本。
     */
    @Transactional
    public SpliceConfigResponse replaceSplices(String channelId, LocalDate businessDay,
                                               String segmentId, ReplaceSplicesRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        // 指纹含授权版本：先解析各窗口授权（不存在则 404，失败不占键）
        Map<Long, GrantRow> grantsByRequest = new LinkedHashMap<>();
        for (SpliceRegionInput region : request.regions()) {
            for (SpliceWindowInput window : region.windows()) {
                GrantRow grant = repo.findGrant(window.grantId())
                        .orElseThrow(() -> ApiException.notFound("授权不存在: " + window.grantId()));
                grantsByRequest.put(window.grantId(), grant);
            }
        }
        String hash = sha256(OP_REPLACE_SPLICES + "|" + channelId + "|" + businessDay + "|" + segmentId
                + "|" + request.expectedDraftVersion() + "|"
                + canonicalSplices(request.regions(), grantsByRequest));
        return idempotent(request.spliceKey(), OP_REPLACE_SPLICES, hash, SpliceConfigResponse.class,
                () -> doReplaceSplices(channelId, businessDay, segmentId, request));
    }

    private SpliceConfigResponse doReplaceSplices(String channelId, LocalDate businessDay,
                                                  String segmentId, ReplaceSplicesRequest request) {
        // 锁草稿行：与草稿整份替换、发布按提交顺序串行
        DraftRow draft = repo.findDraftForUpdate(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
        if (draft.version() != request.expectedDraftVersion()) {
            throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                    "草稿版本不符，当前 " + draft.version());
        }
        SegmentRow segment = repo.findDraftSegments(channelId, businessDay).stream()
                .filter(s -> s.id().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("节目单条目不存在: " + segmentId));
        var channel = repo.findChannel(channelId).orElseThrow();

        Set<String> seenRegions = new HashSet<>();
        List<SpliceConfigRow> validated = new ArrayList<>();
        for (SpliceRegionInput region : request.regions()) {
            String regionCode = normalizeRegion(region.regionCode());
            if (regionCode == null) {
                throw ApiException.badRequest("区域码不能为空");
            }
            if (!seenRegions.add(regionCode)) {
                throw ApiException.badRequest("区域码重复: " + regionCode);
            }
            List<SpliceConfigRow> windows = new ArrayList<>();
            for (SpliceWindowInput window : region.windows()) {
                windows.add(validateSpliceWindow(channelId, channel.fallbackAssetId(), segment,
                        regionCode, window));
            }
            // 同条目同区域窗口不得重叠（端点相接合法）
            List<SpliceConfigRow> sorted = windows.stream()
                    .sorted(Comparator.comparingLong(SpliceConfigRow::startMs)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startMs() < sorted.get(i - 1).endMs()) {
                    throw ApiException.unprocessable("SPLICE_WINDOW_OVERLAP",
                            "同条目同区域插播窗口重叠: 区域 " + regionCode);
                }
            }
            validated.addAll(sorted);
        }

        repo.deleteSpliceConfigs(channelId, businessDay, segmentId);
        for (SpliceConfigRow row : validated) {
            repo.insertSpliceConfig(channelId, businessDay, segmentId, row.regionCode(),
                    row.assetId(), row.grantId(), row.grantVersion(), row.startMs(), row.endMs(),
                    nowMs());
        }
        return toSpliceConfigResponse(channelId, businessDay, segmentId, draft.version(),
                repo.findSpliceConfigs(channelId, businessDay, segmentId));
    }

    /** 校验单个插播窗口并返回规范化配置行（授权加行锁，与撤销按提交顺序串行）。 */
    private SpliceConfigRow validateSpliceWindow(String channelId, String fallbackAssetId,
                                                 SegmentRow segment, String regionCode,
                                                 SpliceWindowInput window) {
        long startMs = toMs(window.start());
        long endMs = toMs(window.end());
        if (endMs <= startMs) {
            throw ApiException.badRequest("插播窗口终点必须大于起点");
        }
        if (startMs < segment.startMs() || endMs > segment.endMs()) {
            throw ApiException.unprocessable("SPLICE_WINDOW_OUT_OF_SEGMENT",
                    "插播窗口须落在条目窗口内: " + segment.id());
        }
        AssetRow asset = repo.findAsset(window.assetId())
                .orElseThrow(() -> ApiException.notFound("素材不存在: " + window.assetId()));
        if (fallbackAssetId.equals(window.assetId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材不得用于区域插播");
        }
        if (asset.withdrawn()) {
            throw ApiException.unprocessable("ASSET_WITHDRAWN",
                    "插播素材已撤回: " + window.assetId());
        }
        GrantRow grant = repo.findGrantForUpdate(window.grantId())
                .orElseThrow(() -> ApiException.notFound("授权不存在: " + window.grantId()));
        if (!grant.channelId().equals(channelId) || !grant.assetId().equals(window.assetId())) {
            throw ApiException.unprocessable("GRANT_NOT_MATCHED",
                    "指定授权不属于该频道或素材: " + window.grantId());
        }
        if (grant.revoked()) {
            throw ApiException.unprocessable("GRANT_INVALID",
                    "指定授权已撤销: " + window.grantId());
        }
        if (grant.validFromMs() > startMs || grant.validToMs() < endMs) {
            throw ApiException.unprocessable("GRANT_NOT_COVERING",
                    "指定授权未完整覆盖插播窗口: " + window.grantId());
        }
        if (grant.regionCode() != null && !grant.regionCode().equals(regionCode)) {
            throw ApiException.unprocessable("GRANT_REGION_MISMATCH",
                    "指定授权不覆盖区域 " + regionCode + ": " + window.grantId());
        }
        return new SpliceConfigRow(0, channelId, null, segment.id(),
                regionCode, window.assetId(), grant.id(), grant.version(), startMs, endMs);
    }

    /** 查询条目当前插播配置。 */
    @Transactional(readOnly = true)
    public SpliceConfigResponse getSpliceConfig(String channelId, LocalDate businessDay,
                                                String segmentId) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        DraftRow draft = repo.findDraft(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
        return toSpliceConfigResponse(channelId, businessDay, segmentId, draft.version(),
                repo.findSpliceConfigs(channelId, businessDay, segmentId));
    }

    private static SpliceConfigResponse toSpliceConfigResponse(String channelId,
                                                               LocalDate businessDay,
                                                               String segmentId, long draftVersion,
                                                               List<SpliceConfigRow> rows) {
        Map<String, List<SpliceWindowResponse>> byRegion = new LinkedHashMap<>();
        for (SpliceConfigRow row : rows) {
            byRegion.computeIfAbsent(row.regionCode(), k -> new ArrayList<>())
                    .add(new SpliceWindowResponse(row.assetId(), row.grantId(), row.grantVersion(),
                            atMs(row.startMs()), atMs(row.endMs())));
        }
        List<SpliceRegionResponse> regions = byRegion.entrySet().stream()
                .map(e -> new SpliceRegionResponse(e.getKey(), e.getValue()))
                .toList();
        return new SpliceConfigResponse(channelId, businessDay.toString(), segmentId,
                draftVersion, regions);
    }

    // ---------- 黑屏窗口 ----------

    /** 创建区域黑屏窗口：创建即 ACTIVE，UTC 左闭右开；携带 requestId 幂等，失败不占键。 */
    @Transactional
    public BlackoutResponse createBlackout(String channelId, CreateBlackoutRequest request) {
        String regionCode = normalizeRegion(request.regionCode());
        if (regionCode == null) {
            throw ApiException.badRequest("区域码不能为空");
        }
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        if (endMs <= startMs) {
            throw ApiException.badRequest("黑屏窗口终点必须大于起点");
        }
        String hash = sha256(OP_CREATE_BLACKOUT + "|" + channelId + "|" + regionCode
                + "|" + startMs + "|" + endMs);
        return idempotent(request.requestId(), OP_CREATE_BLACKOUT, hash, BlackoutResponse.class, () -> {
            repo.findChannel(channelId)
                    .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
            long id = repo.insertBlackout(channelId, regionCode, startMs, endMs, nowMs());
            return toBlackoutResponse(repo.findBlackout(id).orElseThrow());
        });
    }

    /** 取消黑屏窗口：仅 ACTIVE 可取消；携带 requestId 幂等，失败不占键。 */
    @Transactional
    public BlackoutResponse cancelBlackout(long blackoutId, String requestId) {
        String hash = sha256(OP_CANCEL_BLACKOUT + "|" + blackoutId);
        return idempotent(requestId, OP_CANCEL_BLACKOUT, hash, BlackoutResponse.class, () -> {
            repo.findBlackoutForUpdate(blackoutId)
                    .orElseThrow(() -> ApiException.notFound("黑屏窗口不存在: " + blackoutId));
            int updated = repo.cancelBlackout(blackoutId, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("BLACKOUT_NOT_ACTIVE",
                        "黑屏窗口已取消，不能重复取消: " + blackoutId);
            }
            return toBlackoutResponse(repo.findBlackoutForUpdate(blackoutId).orElseThrow());
        });
    }

    private static BlackoutResponse toBlackoutResponse(BlackoutRow row) {
        return new BlackoutResponse(row.id(), row.channelId(), row.regionCode(),
                atMs(row.startMs()), atMs(row.endMs()),
                row.active() ? BlackoutStatus.ACTIVE : BlackoutStatus.CANCELLED,
                row.cancelRequestId(),
                row.cancelledAtMs() == null ? null : atMs(row.cancelledAtMs()),
                atMs(row.createdAtMs()));
    }

    // ---------- 区域播放决策与回执 ----------

    /**
     * 按频道、区域与时刻查询区域播放决策。紧急插播优先；之后基于最新发布快照解析：
     * 命中该区域固化插播窗口且授权当前未撤销时播出插播素材（SPLICE），插播授权已撤销时回退
     * 主素材；无插播窗口覆盖时使用主素材（PROGRAM）。解析只读发布快照，不按当前插播配置重新解析。
     */
    @Transactional(readOnly = true)
    public RegionalPlayoutResponse regionalPlayout(String channelId, String regionCode,
                                                   OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);

        Optional<OverrideRow> override = selectActiveOverrideAt(channelId, atMs);
        if (override.isPresent()) {
            return new RegionalPlayoutResponse(channelId, regionCode, at, override.get().assetId(),
                    RegionalDecisionSource.EMERGENCY, null, null, null, null, null, null, null);
        }

        LocalDate businessDay = at.atZoneSameInstant(ZONE).toLocalDate();
        Optional<PublicationRow> publication = repo.findLatestPublication(channelId, businessDay);
        if (publication.isEmpty()) {
            return regionalFallback(channelId, regionCode, at, channel.fallbackAssetId(),
                    FallbackReason.NO_PUBLISHED_SCHEDULE);
        }
        Optional<PublicationSegmentRow> segment =
                repo.findPublicationSegmentAt(publication.get().id(), atMs);
        if (segment.isEmpty()) {
            return regionalFallback(channelId, regionCode, at, channel.fallbackAssetId(),
                    FallbackReason.GAP);
        }
        PublicationSegmentRow hit = segment.get();
        GrantRow mainGrant = repo.findGrant(hit.grantId())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + hit.grantId()));
        if (mainGrant.revoked()) {
            return new RegionalPlayoutResponse(channelId, regionCode, at, channel.fallbackAssetId(),
                    RegionalDecisionSource.FALLBACK, FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(), null, null, null, null);
        }

        List<PublicationRegionRow> rows =
                repo.findPublicationRegions(publication.get().id(), hit.segmentId(), regionCode);
        PublicationRegionRow mainRow = rows.stream()
                .filter(r -> "MAIN".equals(r.source())).findFirst().orElse(null);
        Optional<PublicationRegionRow> spliceRow = rows.stream()
                .filter(r -> "SPLICE".equals(r.source())
                        && r.spliceStartMs() <= atMs && r.spliceEndMs() > atMs)
                .findFirst();
        if (spliceRow.isPresent()) {
            PublicationRegionRow splice = spliceRow.get();
            GrantRow spliceGrant = repo.findGrant(splice.grantId()).orElse(null);
            if (spliceGrant != null && !spliceGrant.revoked()) {
                return new RegionalPlayoutResponse(channelId, regionCode, at, splice.assetId(),
                        RegionalDecisionSource.SPLICE, null,
                        publication.get().id(), hit.segmentId(),
                        atMs(splice.spliceStartMs()), atMs(splice.spliceEndMs()),
                        splice.grantId(), splice.grantVersion());
            }
            // 插播授权已撤销：回退主素材，不回退到保底
            return new RegionalPlayoutResponse(channelId, regionCode, at, hit.assetId(),
                    RegionalDecisionSource.PROGRAM, FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(), null, null,
                    mainRow == null ? null : mainRow.grantId(),
                    mainRow == null ? null : mainRow.grantVersion());
        }
        return new RegionalPlayoutResponse(channelId, regionCode, at, hit.assetId(),
                RegionalDecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(), null, null,
                mainRow == null ? null : mainRow.grantId(),
                mainRow == null ? null : mainRow.grantVersion());
    }

    /**
     * 创建区域播出回执：窗口内回执使用发布快照素材，不按当前配置重新解析。
     * 携带 requestId 幂等：同键同参返回首次回执，改参 409，失败不占键。
     */
    @Transactional
    public ReceiptResponse createReceipt(String channelId, String regionCode,
                                         CreateReceiptRequest request) {
        String normalized = normalizeRegion(regionCode);
        if (normalized == null) {
            throw ApiException.badRequest("区域码不能为空");
        }
        String hash = sha256(OP_CREATE_RECEIPT + "|" + channelId + "|" + normalized
                + "|" + toMs(request.at()));
        return idempotent(request.requestId(), OP_CREATE_RECEIPT, hash, ReceiptResponse.class, () -> {
            RegionalPlayoutResponse decision = regionalPlayout(channelId, normalized, request.at());
            long createdAtMs = nowMs();
            long id = repo.insertReceipt(channelId, normalized, toMs(request.at()),
                    decision.assetId(), decision.source().name(), decision.publicationId(),
                    decision.segmentId(), decision.grantId(), decision.grantVersion(), createdAtMs);
            return new ReceiptResponse(id, channelId, normalized, request.at(), decision.assetId(),
                    decision.source(), decision.publicationId(), decision.segmentId(),
                    decision.grantId(), decision.grantVersion(), atMs(createdAtMs));
        });
    }

    // ---------- 发布快照与诊断查询 ----------

    /** 查询发布快照：区域、条目、实际素材、授权版本、插播窗口与回退原因均为发布时固化，只读。 */
    @Transactional(readOnly = true)
    public PublicationSnapshotResponse getPublicationSnapshot(long publicationId) {
        PublicationRow publication = repo.findPublicationById(publicationId)
                .orElseThrow(() -> ApiException.notFound("发布快照不存在: " + publicationId));
        List<PublicationSegmentView> segments = repo.findPublicationSegments(publicationId).stream()
                .map(s -> new PublicationSegmentView(s.segmentId(), s.assetId(), s.grantId(),
                        atMs(s.startMs()), atMs(s.endMs())))
                .toList();
        List<PublicationRegionView> regions = repo.findPublicationRegions(publicationId).stream()
                .map(r -> new PublicationRegionView(r.segmentId(), r.regionCode(), r.assetId(),
                        r.grantId(), r.grantVersion(),
                        r.spliceStartMs() == null ? null : atMs(r.spliceStartMs()),
                        r.spliceEndMs() == null ? null : atMs(r.spliceEndMs()),
                        r.source(), r.fallbackReason()))
                .toList();
        return new PublicationSnapshotResponse(publication.id(), publication.channelId(),
                publication.businessDay().toString(), publication.publishedVersion(),
                publication.draftVersion(), segments, regions);
    }

    /**
     * 授权阻断诊断：对当前草稿的全部插播配置按发布口径检查（授权失效、素材撤回、黑屏相交），
     * 返回稳定排序的区域级阻断明细；不改变任何状态。
     */
    @Transactional(readOnly = true)
    public SpliceDiagnosticsResponse spliceDiagnostics(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        repo.findDraft(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
        List<SpliceViolation> violations = new ArrayList<>();
        for (SpliceConfigRow config : repo.findSpliceConfigsForDay(channelId, businessDay)) {
            GrantRow grant = repo.findGrant(config.grantId()).orElse(null);
            checkSpliceConfig(channelId, config, grant, violations);
        }
        violations.sort(Comparator.comparing(SpliceViolation::regionCode)
                .thenComparing(SpliceViolation::segmentId)
                .thenComparing(SpliceViolation::reason));
        return new SpliceDiagnosticsResponse(channelId, businessDay.toString(), violations);
    }

    /** 发布时校验单条插播配置（授权加行锁，与撤销按提交顺序串行）；通过时记录发布口径授权。 */
    private void validateSpliceForPublish(String channelId, SpliceConfigRow config,
                                          Map<Long, GrantRow> spliceGrants,
                                          List<SpliceViolation> violations) {
        GrantRow grant = repo.findGrantForUpdate(config.grantId()).orElse(null);
        int before = violations.size();
        checkSpliceConfig(channelId, config, grant, violations);
        if (violations.size() == before && grant != null) {
            spliceGrants.put(config.id(), grant);
        }
    }

    /** 发布与诊断共用的插播检查：授权匹配/未撤销/覆盖窗口与区域、素材未撤回、不与黑屏相交。 */
    private void checkSpliceConfig(String channelId, SpliceConfigRow config, GrantRow grant,
                                   List<SpliceViolation> violations) {
        String where = "条目 " + config.segmentId() + " 区域 " + config.regionCode()
                + " 窗口 [" + atMs(config.startMs()) + ", " + atMs(config.endMs()) + ")";
        if (grant == null) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "GRANT_INVALID", "插播授权不存在: " + config.grantId() + "，" + where));
            return;
        }
        if (!grant.channelId().equals(channelId) || !grant.assetId().equals(config.assetId())) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "GRANT_NOT_MATCHED", "插播授权不属于该频道或素材: " + config.grantId() + "，" + where));
            return;
        }
        if (grant.revoked()) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "GRANT_INVALID", "插播授权已撤销: " + config.grantId() + "，" + where));
            return;
        }
        if (grant.validFromMs() > config.startMs() || grant.validToMs() < config.endMs()) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "GRANT_NOT_COVERING", "插播授权未完整覆盖窗口: " + config.grantId() + "，" + where));
            return;
        }
        if (grant.regionCode() != null && !grant.regionCode().equals(config.regionCode())) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "GRANT_REGION_MISMATCH",
                    "插播授权不覆盖区域 " + config.regionCode() + ": " + config.grantId() + "，" + where));
            return;
        }
        AssetRow asset = repo.findAsset(config.assetId()).orElse(null);
        if (asset == null || asset.withdrawn()) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "ASSET_WITHDRAWN", "插播素材已撤回: " + config.assetId() + "，" + where));
            return;
        }
        if (!repo.findActiveBlackoutsOverlapping(channelId, config.regionCode(),
                config.startMs(), config.endMs()).isEmpty()) {
            violations.add(new SpliceViolation(config.regionCode(), config.segmentId(),
                    "BLACKOUT_OVERLAP", "插播窗口与黑屏窗口相交，" + where));
        }
    }

    /** 发布成功后固化区域解析快照：每个条目每个区域一行 MAIN（回退原因 NO_SPLICE）加逐窗口 SPLICE。 */
    private void freezeRegionalSnapshot(long publicationId, List<SegmentRow> segments,
                                        List<GrantRow> segmentGrants,
                                        List<SpliceConfigRow> spliceConfigs,
                                        Map<Long, GrantRow> spliceGrants) {
        if (spliceConfigs.isEmpty()) {
            return;
        }
        Map<String, SegmentRow> segmentById = new HashMap<>();
        Map<String, GrantRow> grantBySegmentId = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            segmentById.put(segments.get(i).id(), segments.get(i));
            grantBySegmentId.put(segments.get(i).id(), segmentGrants.get(i));
        }
        // configs 已按 segment_id, region_code, start_ms 排序，按（条目, 区域）分组
        Map<String, List<SpliceConfigRow>> groups = new LinkedHashMap<>();
        for (SpliceConfigRow config : spliceConfigs) {
            groups.computeIfAbsent(config.segmentId() + "|" + config.regionCode(),
                    k -> new ArrayList<>()).add(config);
        }
        long nowMs = nowMs();
        for (Map.Entry<String, List<SpliceConfigRow>> group : groups.entrySet()) {
            List<SpliceConfigRow> rows = group.getValue();
            SpliceConfigRow first = rows.get(0);
            SegmentRow segment = segmentById.get(first.segmentId());
            GrantRow mainGrant = grantBySegmentId.get(first.segmentId());
            repo.insertPublicationRegion(publicationId, first.segmentId(), first.regionCode(),
                    segment.assetId(), mainGrant.id(), mainGrant.version(), null, null,
                    "MAIN", "NO_SPLICE", nowMs);
            for (SpliceConfigRow config : rows) {
                GrantRow grant = spliceGrants.get(config.id());
                repo.insertPublicationRegion(publicationId, config.segmentId(), config.regionCode(),
                        config.assetId(), grant.id(), grant.version(),
                        config.startMs(), config.endMs(), "SPLICE", null, nowMs);
            }
        }
    }

    private RegionalPlayoutResponse regionalFallback(String channelId, String regionCode,
                                                     OffsetDateTime at, String assetId,
                                                     FallbackReason reason) {
        return new RegionalPlayoutResponse(channelId, regionCode, at, assetId,
                RegionalDecisionSource.FALLBACK, reason, null, null, null, null, null, null);
    }

    private static String canonicalSplices(List<SpliceRegionInput> regions,
                                           Map<Long, GrantRow> grantsByRequest) {
        StringBuilder sb = new StringBuilder();
        for (SpliceRegionInput region : regions) {
            sb.append(region.regionCode()).append(':');
            for (SpliceWindowInput window : region.windows()) {
                GrantRow grant = grantsByRequest.get(window.grantId());
                sb.append(window.assetId()).append(',')
                        .append(window.grantId()).append(',')
                        .append(grant == null ? 0 : grant.version()).append(',')
                        .append(toMs(window.start())).append(',')
                        .append(toMs(window.end())).append(';');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    private static String normalizeRegion(String regionCode) {
        return regionCode == null || regionCode.isBlank() ? null : regionCode.trim();
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

    private static String canonicalSegments(List<SegmentInput> segments) {
        StringBuilder sb = new StringBuilder();
        for (SegmentInput s : segments) {
            sb.append(s.id() == null ? "" : s.id()).append(',')
                    .append(s.assetId()).append(',')
                    .append(s.start() == null ? "" : toMs(s.start())).append(',')
                    .append(s.end() == null ? "" : toMs(s.end())).append(';');
        }
        return sb.toString();
    }

    private PlayoutDecisionResponse fallback(String channelId, OffsetDateTime at, String assetId,
                                             FallbackReason reason) {
        return new PlayoutDecisionResponse(channelId, at, assetId,
                DecisionSource.FALLBACK, reason, null, null, null);
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

    /** 校验后的片段。 */
    private record ValidatedSegment(String id, String assetId, long startMs, long endMs) {
        SegmentResponse toResponse() {
            return new SegmentResponse(id, assetId, atMs(startMs), atMs(endMs));
        }
    }
}
