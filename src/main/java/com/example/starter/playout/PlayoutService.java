package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.AssetRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RatingWindowRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.ContentRating;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateInterruptionRequest;
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.InterruptionResponse;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.RatingCheckRecordResponse;
import com.example.starter.playout.api.Dtos.RatingViolation;
import com.example.starter.playout.api.Dtos.RatingWindowResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.example.starter.playout.api.Dtos.UpsertRatingWindowRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final String OP_CREATE_RATING_WINDOW = "CREATE_RATING_WINDOW";
    private static final String OP_UPDATE_RATING_WINDOW = "UPDATE_RATING_WINDOW";
    private static final String OP_CREATE_INTERRUPTION = "CREATE_INTERRUPTION";

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;

    public PlayoutService(PlayoutRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------- 素材 ----------

    /** 创建素材；id 为空时生成稳定 ID；rating 为空表示历史素材未声明分级。 */
    @Transactional
    public AssetResponse createAsset(CreateAssetRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        String rating = request.rating() == null ? null : request.rating().name();
        try {
            repo.insertAsset(id, request.durationMs(), rating, nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_ID", "素材 ID 已存在: " + id);
        }
        return new AssetResponse(id, request.durationMs(), request.rating());
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
        String hash = sha256(OP_REVOKE_GRANT + "|" + grantId);
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
     *
     * <p>分级校验：先锁定频道行，再读取提交时刻一致的管控时段配置，逐片段按计划播出开始
     * 时刻判定命中时段；素材有效分级（未声明按 MATURE）超过时段允许最高分级时累计全部
     * 越级明细并 422 拒绝，不发布草稿。校验通过后为每个片段写入一条历史校验记录。
     * 管控时段变更与发布经频道行锁串行化，按事务提交顺序裁决，不混合新旧配置。</p>
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
            List<Long> grantIds = new ArrayList<>();
            for (SegmentRow segment : segments) {
                GrantRow grant = selectCoveringGrantForUpdate(channelId, segment);
                grantIds.add(grant.id());
            }
            // 锁定频道行后读取一致时段配置，与时段变更事务按提交顺序串行
            repo.lockChannel(channelId);
            List<RatingWindowRow> windows = repo.findActiveRatingWindows(channelId);
            List<RatingCheckDecision> decisions = checkRatings(segments, windows);
            List<RatingViolation> violations = decisions.stream()
                    .flatMap(d -> d.violations().stream())
                    .toList();
            if (!violations.isEmpty()) {
                throw ApiException.ratingExceeded(
                        "存在 " + violations.size() + " 条素材分级超过命中管控时段允许的最高分级",
                        violations);
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
            for (RatingCheckDecision decision : decisions) {
                repo.insertPublicationRatingCheck(publicationId, channelId, businessDay,
                        newVersion, decision.segment().id(), decision.segment().assetId(),
                        decision.effectiveRating().name(),
                        decision.segment().startMs(), decision.segment().endMs(),
                        decision.window() == null ? null : decision.window().id(),
                        decision.window() == null ? null : decision.window().startMinute(),
                        decision.window() == null ? null : decision.window().endMinute(),
                        decision.window() == null ? null : decision.window().maxRating(),
                        nowMs());
            }
            return new PublishResponse(publicationId, channelId, businessDay.toString(),
                    newVersion, draft.version());
        });
    }

    // ---------- 播出决定 ----------

    /**
     * 按频道与时刻查询播出决定。命中有效片段返回节目素材；无已发布编排、空档或授权已撤销时
     * 返回保底素材及明确原因。撤销判定基于授权当前状态，不改写历史快照。
     * 返回素材时附带其有效分级（未声明按 MATURE）与该时刻命中的管控时段（如有），供下游过滤。
     */
    @Transactional(readOnly = true)
    public PlayoutDecisionResponse playoutDecision(String channelId, OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);
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
            return withRating(new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK, FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(),
                    null, null, null, null, null), channelId, atMs);
        }
        return withRating(new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(),
                null, null, null, null, null), channelId, atMs);
    }

    // ---------- 管控时段 ----------

    /**
     * 创建频道管控时段：起止为自运营日 00:00 起的分钟数，左闭右开，同运营日内；
     * 不得与同频道其他生效时段重叠，仅端点相接合法。重叠校验在频道行锁 + 时段行锁下完成，
     * 与发布、插播及其他时段变更按事务提交顺序串行裁决。
     */
    @Transactional
    public RatingWindowResponse createRatingWindow(String channelId,
                                                   UpsertRatingWindowRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        validateWindowRange(request.startMinute(), request.endMinute());

        String hash = sha256(OP_CREATE_RATING_WINDOW + "|" + channelId
                + "|" + request.startMinute() + "|" + request.endMinute()
                + "|" + request.maxRating());
        return idempotent(request.requestId(), OP_CREATE_RATING_WINDOW, hash,
                RatingWindowResponse.class, () -> {
                    repo.lockChannel(channelId);
                    List<RatingWindowRow> existing = repo.findActiveRatingWindowsForUpdate(channelId);
                    checkWindowOverlap(existing, null, request.startMinute(), request.endMinute());
                    long id = repo.insertRatingWindow(channelId, request.startMinute(),
                            request.endMinute(), request.maxRating().name(), nowMs());
                    return new RatingWindowResponse(id, channelId, request.startMinute(),
                            request.endMinute(), request.maxRating(), 1, false);
                });
    }

    /** 修改管控时段（乐观锁）；同样不得与其他生效时段重叠。 */
    @Transactional
    public RatingWindowResponse updateRatingWindow(String channelId, long windowId,
                                                   long expectedVersion,
                                                   UpsertRatingWindowRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        validateWindowRange(request.startMinute(), request.endMinute());

        String hash = sha256(OP_UPDATE_RATING_WINDOW + "|" + channelId + "|" + windowId
                + "|" + expectedVersion
                + "|" + request.startMinute() + "|" + request.endMinute()
                + "|" + request.maxRating());
        return idempotent(request.requestId(), OP_UPDATE_RATING_WINDOW, hash,
                RatingWindowResponse.class, () -> {
                    RatingWindowRow current = repo.findRatingWindow(windowId)
                            .orElseThrow(() -> ApiException.notFound("管控时段不存在: " + windowId));
                    if (!current.channelId().equals(channelId)) {
                        throw ApiException.notFound("管控时段不属于频道: " + windowId);
                    }
                    if (current.revoked()) {
                        throw ApiException.conflict("RATING_WINDOW_REVOKED",
                                "管控时段已删除: " + windowId);
                    }
                    repo.lockChannel(channelId);
                    List<RatingWindowRow> existing = repo.findActiveRatingWindowsForUpdate(channelId);
                    checkWindowOverlap(existing, windowId, request.startMinute(), request.endMinute());
                    int updated = repo.updateRatingWindow(windowId, request.startMinute(),
                            request.endMinute(), request.maxRating().name(),
                            expectedVersion, nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("RATING_WINDOW_VERSION_CONFLICT",
                                "管控时段版本不符，期望 " + expectedVersion);
                    }
                    return new RatingWindowResponse(windowId, channelId, request.startMinute(),
                            request.endMinute(), request.maxRating(), expectedVersion + 1, false);
                });
    }

    /** 查询频道全部生效中的管控时段配置。 */
    @Transactional(readOnly = true)
    public List<RatingWindowResponse> listRatingWindows(String channelId) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        return repo.findActiveRatingWindows(channelId).stream()
                .map(w -> new RatingWindowResponse(w.id(), w.channelId(), w.startMinute(),
                        w.endMinute(), ContentRating.valueOf(w.maxRating()), w.version(), false))
                .toList();
    }

    // ---------- 紧急插播 ----------

    /**
     * 创建紧急插播：插播时刻所属运营日的管控时段对素材有效分级（未声明按 MATURE）生效，
     * 超限 422 且不创建插播。与时段变更经频道行锁按提交顺序串行，判定基于提交时刻一致配置。
     */
    @Transactional
    public InterruptionResponse createInterruption(String channelId,
                                                   CreateInterruptionRequest request) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        AssetRow asset = repo.findAsset(request.assetId())
                .orElseThrow(() -> ApiException.notFound("素材不存在: " + request.assetId()));
        long atMs = toMs(request.at());

        String hash = sha256(OP_CREATE_INTERRUPTION + "|" + channelId + "|" + request.assetId()
                + "|" + atMs);
        return idempotent(request.requestId(), OP_CREATE_INTERRUPTION, hash,
                InterruptionResponse.class, () -> {
                    repo.lockChannel(channelId);
                    List<RatingWindowRow> windows = repo.findActiveRatingWindows(channelId);
                    RatingWindowRow hit = findWindowAt(windows, atMs);
                    ContentRating effective = effectiveRating(asset.rating());
                    if (hit != null
                            && effective.exceeds(ContentRating.valueOf(hit.maxRating()))) {
                        throw ApiException.ratingExceeded(
                                "插播素材分级 " + effective + " 超过命中管控时段允许的最高分级 "
                                        + hit.maxRating(),
                                List.of(new RatingViolation(null, asset.id(), effective,
                                        hit.id(), hit.startMinute(), hit.endMinute(),
                                        ContentRating.valueOf(hit.maxRating()))));
                    }
                    long id = repo.insertInterruption(channelId, asset.id(), atMs,
                            effective.name(), hit == null ? null : hit.id(), nowMs());
                    return new InterruptionResponse(id, channelId, asset.id(), request.at(),
                            effective, hit == null ? null : hit.id());
                });
    }

    // ---------- 历史发布分级校验记录 ----------

    /** 查询频道某业务日的历史发布分级校验记录。 */
    @Transactional(readOnly = true)
    public List<RatingCheckRecordResponse> listRatingChecks(String channelId,
                                                            LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        return repo.findRatingChecks(channelId, businessDay).stream()
                .map(r -> new RatingCheckRecordResponse(r.publicationId(), r.publishedVersion(),
                        r.segmentId(), r.assetId(), ContentRating.valueOf(r.assetRating()),
                        atMs(r.startMs()), atMs(r.endMs()),
                        r.windowId(), r.windowStartMinute(), r.windowEndMinute(),
                        r.allowedRating() == null ? null : ContentRating.valueOf(r.allowedRating())))
                .toList();
    }

    // ---------- 内部方法 ----------

    /** 素材有效分级：未声明（NULL）的历史素材按最高分级 MATURE 参与校验。 */
    private static ContentRating effectiveRating(String rating) {
        return rating == null ? ContentRating.MATURE : ContentRating.valueOf(rating);
    }

    /** 时刻（UTC 毫秒）对应的 Asia/Shanghai 运营日内分钟数。 */
    private static int minuteOfDay(long epochMs) {
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
        return local.getHour() * 60 + local.getMinute();
    }

    /** 查找覆盖指定时刻的生效管控时段（左闭右开）；未命中返回 null 表示无限制。 */
    private static RatingWindowRow findWindowAt(List<RatingWindowRow> windows, long atMs) {
        int minute = minuteOfDay(atMs);
        return windows.stream()
                .filter(w -> w.startMinute() <= minute && minute < w.endMinute())
                .findFirst()
                .orElse(null);
    }

    /** 逐片段判定分级：计划播出开始时刻命中的时段对素材有效分级生效。 */
    private List<RatingCheckDecision> checkRatings(List<SegmentRow> segments,
                                                   List<RatingWindowRow> windows) {
        List<RatingCheckDecision> decisions = new ArrayList<>();
        for (SegmentRow segment : segments) {
            AssetRow asset = repo.findAsset(segment.assetId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "草稿片段引用的素材不存在: " + segment.assetId()));
            ContentRating effective = effectiveRating(asset.rating());
            RatingWindowRow hit = findWindowAt(windows, segment.startMs());
            List<RatingViolation> violations = new ArrayList<>();
            if (hit != null && effective.exceeds(ContentRating.valueOf(hit.maxRating()))) {
                violations.add(new RatingViolation(segment.id(), segment.assetId(), effective,
                        hit.id(), hit.startMinute(), hit.endMinute(),
                        ContentRating.valueOf(hit.maxRating())));
            }
            decisions.add(new RatingCheckDecision(segment, effective, hit, violations));
        }
        return decisions;
    }

    /** 校验时段范围：0 <= start < end <= 1440（同一运营日内，左闭右开）。 */
    private static void validateWindowRange(int startMinute, int endMinute) {
        if (startMinute < 0 || startMinute > 1439) {
            throw ApiException.badRequest("时段起点须在 0-1439 分钟之间: " + startMinute);
        }
        if (endMinute < 1 || endMinute > 1440) {
            throw ApiException.badRequest("时段终点须在 1-1440 分钟之间: " + endMinute);
        }
        if (endMinute <= startMinute) {
            throw ApiException.badRequest("时段终点必须大于起点（左闭右开）");
        }
    }

    /** 重叠校验：与既有生效时段区间相交即拒绝，仅端点相接合法；excludeId 用于修改时排除自身。 */
    private static void checkWindowOverlap(List<RatingWindowRow> existing, Long excludeId,
                                           int startMinute, int endMinute) {
        for (RatingWindowRow w : existing) {
            if (excludeId != null && w.id() == excludeId) {
                continue;
            }
            if (startMinute < w.endMinute() && w.startMinute() < endMinute) {
                throw ApiException.unprocessable("RATING_WINDOW_OVERLAP",
                        "管控时段与既有时段重叠: [" + startMinute + "," + endMinute + ") 与 ["
                                + w.startMinute() + "," + w.endMinute() + ")");
            }
        }
    }

    /** 为播出决定响应补充素材有效分级与查询时刻命中的管控时段。 */
    private PlayoutDecisionResponse withRating(PlayoutDecisionResponse response,
                                               String channelId, long atMs) {
        ContentRating effective = repo.findAsset(response.assetId())
                .map(a -> effectiveRating(a.rating()))
                .orElse(ContentRating.MATURE);
        RatingWindowRow hit = findWindowAt(repo.findActiveRatingWindows(channelId), atMs);
        return new PlayoutDecisionResponse(response.channelId(), response.at(),
                response.assetId(), response.source(), response.reason(),
                response.publicationId(), response.segmentId(), effective,
                hit == null ? null : hit.id(),
                hit == null ? null : hit.startMinute(),
                hit == null ? null : hit.endMinute(),
                hit == null ? null : ContentRating.valueOf(hit.maxRating()));
    }

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
        return withRating(new PlayoutDecisionResponse(channelId, at, assetId,
                DecisionSource.FALLBACK, reason, null, null,
                null, null, null, null, null), channelId, toMs(at));
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

    /** 单片段的分级判定结果：素材有效分级、命中时段（可空）与越级明细。 */
    private record RatingCheckDecision(SegmentRow segment, ContentRating effectiveRating,
                                       RatingWindowRow window, List<RatingViolation> violations) {
    }
}
