package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.BlackoutWindowRow;
import com.example.starter.playout.PlayoutRepository.ChannelRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.DraftSpliceRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRegionRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.BlackoutWindowResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateBlackoutWindowRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.OverrideStatus;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublicationRegionRowResponse;
import com.example.starter.playout.api.Dtos.PublicationSnapshotResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.RegionPlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.example.starter.playout.api.Dtos.SnapshotSegmentResponse;
import com.example.starter.playout.api.Dtos.SpliceBlock;
import com.example.starter.playout.api.Dtos.SpliceDiagnosticsResponse;
import com.example.starter.playout.api.Dtos.SpliceFallbackReason;
import com.example.starter.playout.api.Dtos.SpliceInput;
import com.example.starter.playout.api.Dtos.SpliceResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String OP_WITHDRAW_ASSET = "WITHDRAW_ASSET";
    private static final String OP_CREATE_BLACKOUT = "CREATE_BLACKOUT";

    /** 授权与黑屏窗口的区域通配符：表示全部区域。 */
    private static final String REGION_ALL = "*";

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
        return new AssetResponse(id, request.durationMs(), false);
    }

    /** 撤回素材（终态）；幂等：同 requestId 同参数返回原结果，重复撤回返回 409。 */
    @Transactional
    public AssetResponse withdrawAsset(String assetId, String requestId) {
        String hash = sha256(OP_WITHDRAW_ASSET + "|" + assetId);
        return idempotent(requestId, OP_WITHDRAW_ASSET, hash, AssetResponse.class, () -> {
            var asset = repo.findAsset(assetId)
                    .orElseThrow(() -> ApiException.notFound("素材不存在: " + assetId));
            int updated = repo.withdrawAsset(assetId, requestId, nowMs());
            if (updated == 0) {
                throw ApiException.conflict("ASSET_ALREADY_WITHDRAWN", "素材已撤回: " + assetId);
            }
            return new AssetResponse(asset.id(), asset.durationMs(), true);
        });
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

    /** 创建授权，关联频道与普通素材，有效区间左闭右开；regionCode 为空表示 *（全部区域）。 */
    @Transactional
    public GrantResponse createGrant(CreateGrantRequest request) {
        var channel = repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        String regionCode = request.regionCode() == null || request.regionCode().isBlank()
                ? REGION_ALL : request.regionCode();
        long fromMs = toMs(request.validFrom());
        long toMs = toMs(request.validTo());
        if (toMs <= fromMs) {
            throw ApiException.badRequest("授权有效区间终点必须大于起点");
        }
        if (channel.fallbackAssetId().equals(request.assetId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材无需授权，不能对其创建授权");
        }
        long id = repo.insertGrant(request.channelId(), request.assetId(), regionCode,
                fromMs, toMs, nowMs());
        return new GrantResponse(id, request.channelId(), request.assetId(), regionCode,
                request.validFrom(), request.validTo(), false);
    }

    /** 撤销授权；幂等：同 requestId 同参数返回原结果，改参数返回 409。 */
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
                    grant.regionCode(), atMs(grant.validFromMs()), atMs(grant.validToMs()), true);
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
            repo.deleteDraftSplices(channelId, businessDay);
            for (ValidatedSegment segment : segments) {
                repo.insertDraftSegment(segment.id(), channelId, businessDay,
                        segment.assetId(), segment.startMs(), segment.endMs());
                for (ValidatedSplice splice : segment.splices()) {
                    repo.insertDraftSplice(splice.id(), segment.id(), channelId, businessDay,
                            splice.regionCode(), splice.assetId(), splice.startMs(), splice.endMs());
                }
            }
            return new DraftResponse(channelId, businessDay.toString(), newVersion,
                    segments.stream().map(ValidatedSegment::toResponse).toList());
        });
    }

    // ---------- 发布 ----------

    /**
     * 发布草稿：校验草稿版本与发布版本，逐片段加锁校验授权仍有效；对每个区域分别解析最具体的
     * 生效插播（无插播时使用主素材），任一区域的插播授权失效、素材被撤回或窗口与黑屏窗口相交，
     * 整次发布 422 并稳定列出区域和原因，不发布部分区域。快照原子固化区域、条目、实际素材、
     * 授权版本、插播窗口和回退原因，后续插播修改、授权撤销或素材版本拉取不改写历史快照。
     *
     * <p>携带 spliceKey 时以其为发布幂等键：指纹含节目单版本、区域、窗口、素材及授权版本，
     * 同键同内容重放首次完整快照，同键不同内容 409，失败不占键。与授权撤销、黑屏窗口创建并发
     * 时按数据库提交顺序裁决。
     */
    @Transactional
    public PublishResponse publish(String channelId, LocalDate businessDay, PublishRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String spliceKey = request.spliceKey() == null || request.spliceKey().isBlank()
                ? null : request.spliceKey();
        String hash = sha256(OP_PUBLISH + "|" + channelId + "|" + businessDay
                + "|" + request.draftVersion() + "|" + request.expectedPublishedVersion()
                + "|" + (spliceKey == null ? "" : spliceKey));
        return idempotent(request.requestId(), OP_PUBLISH, hash, PublishResponse.class, () -> {
            if (spliceKey != null) {
                String fingerprint = spliceFingerprint(channelId, businessDay, request.draftVersion());
                return idempotent("splice:" + spliceKey, OP_PUBLISH, fingerprint,
                        PublishResponse.class, () -> doPublish(channelId, businessDay, request));
            }
            return doPublish(channelId, businessDay, request);
        });
    }

    /**
     * 计算发布 spliceKey 指纹：节目单（草稿）版本 + 全部区域插播的区域、窗口、素材及授权版本。
     * 草稿不存在或版本不符时直接抛错，不占用 spliceKey。
     */
    private String spliceFingerprint(String channelId, LocalDate businessDay, long draftVersion) {
        DraftRow draft = repo.findDraft(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
        if (draft.version() != draftVersion) {
            throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                    "草稿版本不符，当前 " + draft.version());
        }
        StringBuilder sb = new StringBuilder()
                .append(channelId).append('|').append(businessDay).append('|').append(draftVersion);
        for (DraftSpliceRow splice : repo.findDraftSplices(channelId, businessDay)) {
            List<GrantRow> covering = repo.findCoveringGrantsForRegion(channelId, splice.assetId(),
                    splice.regionCode(), splice.startMs(), splice.endMs());
            String grantVersion = covering.isEmpty() ? "NONE" : String.valueOf(covering.get(0).id());
            sb.append('|').append(splice.regionCode()).append(',').append(splice.segmentId())
                    .append(',').append(splice.startMs()).append(',').append(splice.endMs())
                    .append(',').append(splice.assetId()).append(',').append(grantVersion);
        }
        return sha256(sb.toString());
    }

    private PublishResponse doPublish(String channelId, LocalDate businessDay,
                                      PublishRequest request) {
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
        // 频道锁：与黑屏窗口创建、紧急插播创建/取消按提交顺序串行。
        repo.lockChannelForUpdate(channelId);

        Map<String, Long> segmentGrants = new LinkedHashMap<>();
        for (SegmentRow segment : segments) {
            GrantRow grant = selectCoveringGrantForUpdate(channelId, segment);
            segmentGrants.put(segment.id(), grant.id());
        }

        // 逐区域解析插播：任一区域阻断即收集明细，整次发布 422，不发布部分区域。
        List<DraftSpliceRow> splices = repo.findDraftSplices(channelId, businessDay);
        Map<String, GrantRow> spliceGrants = new LinkedHashMap<>();
        List<SpliceBlock> blocks = new ArrayList<>();
        for (DraftSpliceRow splice : splices) {
            var asset = repo.findAsset(splice.assetId()).orElse(null);
            if (asset == null) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "SPLICE_ASSET_NOT_FOUND", "插播素材不存在: " + splice.assetId()));
                continue;
            }
            if (asset.withdrawn()) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "ASSET_WITHDRAWN", "插播素材已撤回: " + splice.assetId()));
                continue;
            }
            GrantRow grant = repo.findCoveringGrantsForRegionForUpdate(channelId, splice.assetId(),
                            splice.regionCode(), splice.startMs(), splice.endMs())
                    .stream().filter(g -> !g.revoked()).findFirst().orElse(null);
            if (grant == null) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "GRANT_INVALID", "区域 " + splice.regionCode() + " 插播授权已失效: "
                        + splice.assetId()));
                continue;
            }
            List<BlackoutWindowRow> blackouts = repo.findBlackoutsIntersecting(channelId,
                    splice.regionCode(), splice.startMs(), splice.endMs());
            if (!blackouts.isEmpty()) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "BLACKOUT_INTERSECT", "区域 " + splice.regionCode()
                        + " 插播窗口与黑屏窗口相交: " + blackouts.get(0).id()));
                continue;
            }
            spliceGrants.put(splice.id(), grant);
        }
        if (!blocks.isEmpty()) {
            blocks.sort(Comparator.comparing(SpliceBlock::regionCode)
                    .thenComparing(SpliceBlock::segmentId)
                    .thenComparing(SpliceBlock::reason));
            throw ApiException.spliceBlocked("区域插播阻断，整次发布拒绝", blocks);
        }

        long newVersion = currentPublished + 1;
        long publicationId;
        try {
            publicationId = repo.insertPublication(channelId, businessDay,
                    newVersion, draft.version(),
                    request.spliceKey() == null || request.spliceKey().isBlank()
                            ? null : request.spliceKey(),
                    nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PUBLISHED_VERSION_CONFLICT",
                    "发布版本冲突，版本 " + newVersion + " 已存在");
        }
        for (SegmentRow segment : segments) {
            repo.insertPublicationSegment(publicationId, segment.id(), segment.assetId(),
                    segmentGrants.get(segment.id()), segment.startMs(), segment.endMs());
        }
        List<PublicationRegionRowResponse> regionRows =
                snapshotRegionRows(publicationId, segments, splices, segmentGrants, spliceGrants);
        return new PublishResponse(publicationId, channelId, businessDay.toString(),
                newVersion, draft.version(),
                request.spliceKey() == null || request.spliceKey().isBlank()
                        ? null : request.spliceKey(),
                regionRows);
    }

    /**
     * 固化区域解析快照：对每个区域、每个条目，插播窗口行记录插播素材与授权版本（回退原因为空），
     * 主素材行记录主素材与条目授权（回退原因 NO_SPLICE_CONFIG / NO_SPLICE_WINDOW）。
     */
    private List<PublicationRegionRowResponse> snapshotRegionRows(
            long publicationId, List<SegmentRow> segments, List<DraftSpliceRow> splices,
            Map<String, Long> segmentGrants, Map<String, GrantRow> spliceGrants) {
        List<String> regions = splices.stream().map(DraftSpliceRow::regionCode)
                .distinct().sorted().toList();
        List<PublicationRegionRowResponse> rows = new ArrayList<>();
        for (String region : regions) {
            for (SegmentRow segment : segments) {
                List<DraftSpliceRow> segmentSplices = splices.stream()
                        .filter(s -> s.regionCode().equals(region) && s.segmentId().equals(segment.id()))
                        .sorted(Comparator.comparingLong(DraftSpliceRow::startMs))
                        .toList();
                for (DraftSpliceRow splice : segmentSplices) {
                    GrantRow grant = spliceGrants.get(splice.id());
                    repo.insertPublicationRegion(publicationId, segment.id(), region,
                            splice.assetId(), grant.id(), splice.startMs(), splice.endMs(), null);
                    rows.add(new PublicationRegionRowResponse(region, segment.id(),
                            splice.assetId(), grant.id(), atMs(splice.startMs()),
                            atMs(splice.endMs()), null));
                }
                String fallbackReason = segmentSplices.isEmpty()
                        ? SpliceFallbackReason.NO_SPLICE_CONFIG.name()
                        : SpliceFallbackReason.NO_SPLICE_WINDOW.name();
                repo.insertPublicationRegion(publicationId, segment.id(), region,
                        segment.assetId(), segmentGrants.get(segment.id()), null, null,
                        fallbackReason);
                rows.add(new PublicationRegionRowResponse(region, segment.id(),
                        segment.assetId(), segmentGrants.get(segment.id()), null, null,
                        fallbackReason));
            }
        }
        return rows;
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

    // ---------- 黑屏窗口 ----------

    /** 创建黑屏窗口（幂等）；regionCode 为空表示 *（全部区域），窗口为 UTC 左闭右开。 */
    @Transactional
    public BlackoutWindowResponse createBlackoutWindow(String channelId,
                                                       CreateBlackoutWindowRequest request) {
        String regionCode = request.regionCode() == null || request.regionCode().isBlank()
                ? REGION_ALL : request.regionCode();
        String hash = sha256(OP_CREATE_BLACKOUT + "|" + channelId + "|" + regionCode
                + "|" + toMs(request.start()) + "|" + toMs(request.end()));
        return idempotent(request.requestId(), OP_CREATE_BLACKOUT, hash,
                BlackoutWindowResponse.class, () -> {
                    long startMs = toMs(request.start());
                    long endMs = toMs(request.end());
                    if (endMs <= startMs) {
                        throw ApiException.badRequest("黑屏窗口结束时间必须大于开始时间");
                    }
                    // 频道锁：与发布按提交顺序串行，黑屏先提交则发布因相交而 422。
                    repo.lockChannelForUpdate(channelId);
                    repo.findChannel(channelId)
                            .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
                    long createdAtMs = nowMs();
                    long id = repo.insertBlackoutWindow(channelId, regionCode, startMs, endMs,
                            createdAtMs);
                    return new BlackoutWindowResponse(id, channelId, regionCode,
                            request.start(), request.end(), atMs(createdAtMs));
                });
    }

    // ---------- 区域播放决策 ----------

    /**
     * 按频道、区域与时刻查询播放决策。窗口内决策使用发布快照素材，不按当前插播配置重新解析；
     * 快照固化的插播授权在查询时已撤销时回退条目主素材（spliceFallbackReason=SPLICE_GRANT_REVOKED），
     * 主素材授权亦撤销时回退频道保底素材。
     */
    @Transactional(readOnly = true)
    public RegionPlayoutDecisionResponse regionPlayoutDecision(String channelId, String regionCode,
                                                               OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);

        Optional<OverrideRow> override = selectActiveOverrideAt(channelId, atMs);
        if (override.isPresent()) {
            OverrideRow hit = override.get();
            return new RegionPlayoutDecisionResponse(channelId, regionCode, at, hit.assetId(),
                    DecisionSource.EMERGENCY, null, null, null, null, hit.overrideKey(),
                    null, null, null);
        }

        LocalDate businessDay = at.atZoneSameInstant(ZONE).toLocalDate();
        Optional<PublicationRow> publication = repo.findLatestPublication(channelId, businessDay);
        if (publication.isEmpty()) {
            return new RegionPlayoutDecisionResponse(channelId, regionCode, at,
                    channel.fallbackAssetId(), DecisionSource.FALLBACK,
                    FallbackReason.NO_PUBLISHED_SCHEDULE, null, null, null, null, null, null, null);
        }
        Optional<PublicationSegmentRow> segment =
                repo.findPublicationSegmentAt(publication.get().id(), atMs);
        if (segment.isEmpty()) {
            return new RegionPlayoutDecisionResponse(channelId, regionCode, at,
                    channel.fallbackAssetId(), DecisionSource.FALLBACK,
                    FallbackReason.GAP, null, null, null, null, null, null, null);
        }
        PublicationSegmentRow hit = segment.get();

        Optional<PublicationRegionRow> spliceRow = repo.findSpliceRowAt(publication.get().id(),
                hit.segmentId(), regionCode, atMs);
        if (spliceRow.isPresent()) {
            PublicationRegionRow row = spliceRow.get();
            GrantRow spliceGrant = repo.findGrant(row.grantId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照引用的授权不存在: " + row.grantId()));
            if (!spliceGrant.revoked()) {
                return new RegionPlayoutDecisionResponse(channelId, regionCode, at, row.assetId(),
                        DecisionSource.SPLICE, null, null, publication.get().id(), hit.segmentId(),
                        null, row.grantId(), atMs(row.spliceStartMs()), atMs(row.spliceEndMs()));
            }
            return mainAssetDecision(channel, regionCode, at, publication.get(), hit,
                    SpliceFallbackReason.SPLICE_GRANT_REVOKED);
        }
        SpliceFallbackReason spliceReason = repo.findBaseRegionRow(publication.get().id(),
                        hit.segmentId(), regionCode)
                .map(row -> SpliceFallbackReason.valueOf(row.fallbackReason()))
                .orElse(SpliceFallbackReason.NO_SPLICE_CONFIG);
        return mainAssetDecision(channel, regionCode, at, publication.get(), hit, spliceReason);
    }

    /** 区域决策回落到条目主素材：主素材授权已撤销时回退频道保底素材。 */
    private RegionPlayoutDecisionResponse mainAssetDecision(
            ChannelRow channel, String regionCode,
            OffsetDateTime at, PublicationRow publication, PublicationSegmentRow segment,
            SpliceFallbackReason spliceReason) {
        GrantRow grant = repo.findGrant(segment.grantId())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + segment.grantId()));
        if (grant.revoked()) {
            return new RegionPlayoutDecisionResponse(channel.id(), regionCode, at,
                    channel.fallbackAssetId(), DecisionSource.FALLBACK,
                    FallbackReason.GRANT_REVOKED, spliceReason, publication.id(),
                    segment.segmentId(), null, null, null, null);
        }
        return new RegionPlayoutDecisionResponse(channel.id(), regionCode, at, segment.assetId(),
                DecisionSource.PROGRAM, null, spliceReason, publication.id(), segment.segmentId(),
                null, segment.grantId(), null, null);
    }

    // ---------- 发布快照与诊断查询 ----------

    /** 查询发布快照：快照只读，后续插播修改、授权撤销或素材版本拉取不改写。 */
    @Transactional(readOnly = true)
    public PublicationSnapshotResponse publicationSnapshot(long publicationId) {
        PublicationRow publication = repo.findPublication(publicationId)
                .orElseThrow(() -> ApiException.notFound("发布快照不存在: " + publicationId));
        List<SnapshotSegmentResponse> segments = repo.findPublicationSegments(publicationId)
                .stream()
                .map(s -> new SnapshotSegmentResponse(s.segmentId(), s.assetId(), s.grantId(),
                        atMs(s.startMs()), atMs(s.endMs())))
                .toList();
        List<PublicationRegionRowResponse> regions = repo.findPublicationRegions(publicationId)
                .stream()
                .map(PlayoutService::toRegionRowResponse)
                .toList();
        return new PublicationSnapshotResponse(publication.id(), publication.channelId(),
                publication.businessDay().toString(), publication.publishedVersion(),
                publication.draftVersion(), publication.spliceKey(), segments, regions);
    }

    /**
     * 授权阻断诊断：对当前草稿的全部区域插播逐项检查素材存在/撤回、区域授权覆盖与黑屏相交，
     * 返回按区域与条目稳定排序的阻断明细；blocks 为空表示区域插播可发布。诊断只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public SpliceDiagnosticsResponse spliceDiagnostics(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        DraftRow draft = repo.findDraft(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("草稿不存在: " + channelId + " " + businessDay));
        List<SpliceBlock> blocks = new ArrayList<>();
        for (DraftSpliceRow splice : repo.findDraftSplices(channelId, businessDay)) {
            var asset = repo.findAsset(splice.assetId()).orElse(null);
            if (asset == null) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "SPLICE_ASSET_NOT_FOUND", "插播素材不存在: " + splice.assetId()));
                continue;
            }
            if (asset.withdrawn()) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "ASSET_WITHDRAWN", "插播素材已撤回: " + splice.assetId()));
                continue;
            }
            boolean covered = repo.findCoveringGrantsForRegion(channelId, splice.assetId(),
                    splice.regionCode(), splice.startMs(), splice.endMs())
                    .stream().anyMatch(g -> !g.revoked());
            if (!covered) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "GRANT_INVALID", "区域 " + splice.regionCode() + " 插播授权已失效: "
                        + splice.assetId()));
                continue;
            }
            List<BlackoutWindowRow> blackouts = repo.findBlackoutsIntersecting(channelId,
                    splice.regionCode(), splice.startMs(), splice.endMs());
            if (!blackouts.isEmpty()) {
                blocks.add(new SpliceBlock(splice.regionCode(), splice.segmentId(), splice.assetId(),
                        "BLACKOUT_INTERSECT", "区域 " + splice.regionCode()
                        + " 插播窗口与黑屏窗口相交: " + blackouts.get(0).id()));
            }
        }
        blocks.sort(Comparator.comparing(SpliceBlock::regionCode)
                .thenComparing(SpliceBlock::segmentId)
                .thenComparing(SpliceBlock::reason));
        return new SpliceDiagnosticsResponse(channelId, businessDay.toString(),
                draft.version(), blocks);
    }

    private static PublicationRegionRowResponse toRegionRowResponse(PublicationRegionRow row) {
        return new PublicationRegionRowResponse(row.regionCode(), row.segmentId(), row.assetId(),
                row.grantId(),
                row.spliceStartMs() == null ? null : atMs(row.spliceStartMs()),
                row.spliceEndMs() == null ? null : atMs(row.spliceEndMs()),
                row.fallbackReason());
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
            segments.add(new ValidatedSegment(id, input.assetId(), startMs, endMs,
                    validateSplices(channelId, id, startMs, endMs, input.splices())));
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

    /**
     * 校验并规范化条目下的区域插播：窗口为 UTC 左闭右开且落在条目窗口内；同条目同区域窗口
     * 不得重叠（端点相接合法）；插播素材须存在、未撤回且具有覆盖该区域与完整窗口的有效授权，
     * 否则 422。
     */
    private List<ValidatedSplice> validateSplices(String channelId, String segmentId,
                                                  long segmentStartMs, long segmentEndMs,
                                                  List<SpliceInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<ValidatedSplice> splices = new ArrayList<>();
        for (SpliceInput input : inputs) {
            if (input.start() == null || input.end() == null) {
                throw ApiException.badRequest("插播窗口开始与结束时间不能为空");
            }
            long startMs = toMs(input.start());
            long endMs = toMs(input.end());
            if (endMs <= startMs) {
                throw ApiException.badRequest("插播窗口结束时间必须大于开始时间");
            }
            if (startMs < segmentStartMs || endMs > segmentEndMs) {
                throw ApiException.unprocessable("SPLICE_WINDOW_OUT_OF_SEGMENT",
                        "插播窗口须落在条目窗口内: " + segmentId + " " + input.regionCode());
            }
            String id = UUID.randomUUID().toString();
            splices.add(new ValidatedSplice(id, input.regionCode(), input.assetId(), startMs, endMs));
        }

        Map<String, List<ValidatedSplice>> byRegion = new LinkedHashMap<>();
        for (ValidatedSplice splice : splices) {
            byRegion.computeIfAbsent(splice.regionCode(), k -> new ArrayList<>()).add(splice);
        }
        for (Map.Entry<String, List<ValidatedSplice>> entry : byRegion.entrySet()) {
            List<ValidatedSplice> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(ValidatedSplice::startMs))
                    .toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startMs() < sorted.get(i - 1).endMs()) {
                    throw ApiException.unprocessable("SPLICE_WINDOW_OVERLAP",
                            "同条目同区域插播窗口重叠: " + segmentId + " " + entry.getKey());
                }
            }
        }

        for (ValidatedSplice splice : splices) {
            var asset = repo.findAsset(splice.assetId())
                    .orElseThrow(() -> ApiException.notFound("素材不存在: " + splice.assetId()));
            if (asset.withdrawn()) {
                throw ApiException.unprocessable("ASSET_WITHDRAWN",
                        "插播素材已撤回: " + splice.assetId());
            }
            List<GrantRow> covering = repo.findCoveringGrantsForRegion(channelId, splice.assetId(),
                    splice.regionCode(), splice.startMs(), splice.endMs());
            if (covering.isEmpty()) {
                throw ApiException.unprocessable("NO_COVERING_GRANT",
                        "插播素材缺少覆盖区域 " + splice.regionCode() + " 与完整窗口的有效授权: "
                                + splice.assetId());
            }
        }
        return splices;
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
                    .append(s.end() == null ? "" : toMs(s.end()));
            if (s.splices() != null) {
                for (SpliceInput splice : s.splices()) {
                    sb.append('|').append(splice.regionCode()).append(',')
                            .append(splice.assetId()).append(',')
                            .append(splice.start() == null ? "" : toMs(splice.start())).append(',')
                            .append(splice.end() == null ? "" : toMs(splice.end()));
                }
            }
            sb.append(';');
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
    private record ValidatedSegment(String id, String assetId, long startMs, long endMs,
                                    List<ValidatedSplice> splices) {
        SegmentResponse toResponse() {
            return new SegmentResponse(id, assetId, atMs(startMs), atMs(endMs),
                    splices.stream().map(ValidatedSplice::toResponse).toList());
        }
    }

    /** 校验后的区域插播。 */
    private record ValidatedSplice(String id, String regionCode, String assetId,
                                   long startMs, long endMs) {
        SpliceResponse toResponse() {
            return new SpliceResponse(id, regionCode, assetId, atMs(startMs), atMs(endMs));
        }
    }
}
