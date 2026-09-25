package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.AssetRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RatingCheckRow;
import com.example.starter.playout.PlayoutRepository.RatingWindowRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.BreakinRequest;
import com.example.starter.playout.api.Dtos.BreakinResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.ControlWindowInfo;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateRatingWindowRequest;
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.RatingCheckResponse;
import com.example.starter.playout.api.Dtos.RatingViolationInfo;
import com.example.starter.playout.api.Dtos.RatingWindowResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.example.starter.playout.api.Dtos.UpdateRatingWindowRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 播出编排核心服务：素材、频道、授权、草稿、发布、播出决定、内容分级管控与紧急插播。
 *
 * <p>约定：数据库内时间统一为 UTC 纪元毫秒；API 边界统一为 Asia/Shanghai、毫秒精度 ISO 8601；
 * 业务日为 Asia/Shanghai 日历日。授权区间与管控时段均为左闭右开。所有写操作与幂等去重记录在同一事务提交，
 * 失败整体回滚、不占用 requestId。</p>
 *
 * <p>分级管控：素材分级 G 低于 PG 低于 MATURE，未声明分级的素材按 MATURE 参与校验。发布与插播前
 * 对频道行加锁（FOR UPDATE），管控时段配置变更同样先取该锁，二者按事务提交顺序裁决，
 * 单次发布/插播的分级判定基于同一一致的时段配置。配置变更不改写已发布快照。</p>
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
    private static final String OP_BREAKIN = "BREAKIN";

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PlayoutService> self;
    private final RatingCheckAudit ratingCheckAudit;

    public PlayoutService(PlayoutRepository repo, ObjectMapper objectMapper,
                          ObjectProvider<PlayoutService> self, RatingCheckAudit ratingCheckAudit) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.self = self;
        this.ratingCheckAudit = ratingCheckAudit;
    }

    // ---------- 素材 ----------

    /** 创建素材；id 为空时生成稳定 ID；rating 为空表示不声明分级。 */
    @Transactional
    public AssetResponse createAsset(CreateAssetRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString() : request.id();
        try {
            repo.insertAsset(id, request.durationMs(), request.rating(), nowMs());
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
     * 发布入口（非事务）：执行事务化发布；若分级校验未通过，发布事务回滚后以独立事务
     * 补写 FAIL 校验记录（越权拦截可追溯），再向客户端返回 422 及全部越级明细。
     */
    public PublishResponse publish(String channelId, LocalDate businessDay, PublishRequest request) {
        try {
            return self.getObject().publishInTransaction(channelId, businessDay, request);
        } catch (RatingViolationException e) {
            ratingCheckAudit.recordFailure(channelId, businessDay, e.violations());
            throw e.toApiException();
        }
    }

    /**
     * 事务化发布：校验草稿版本与发布版本，逐片段加锁校验授权仍有效，并按提交时刻一致的
     * 管控时段配置做分级校验后原子生成只读快照。与授权撤销、管控时段配置变更并发时按数据库
     * 提交顺序生效：撤销先提交则因授权失效 422 拒绝；时段配置先提交则按新配置判定。
     */
    @Transactional
    public PublishResponse publishInTransaction(String channelId, LocalDate businessDay,
                                                PublishRequest request) {
        // 频道行锁：与管控时段创建/修改串行化，保证本次发布使用一致的时段配置
        repo.findChannelForUpdate(channelId)
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
            // 分级校验：逐片段计算播出区间命中的管控时段，任一时段越级则整次发布拒绝
            List<PendingCheck> checks = new ArrayList<>();
            List<RatingViolationInfo> violations = new ArrayList<>();
            for (SegmentRow segment : segments) {
                Rating rating = effectiveRatingOf(segment.assetId());
                List<RatingWindowRow> windows = repo.findOverlappingWindows(channelId, businessDay,
                        segment.startMs(), segment.endMs());
                if (windows.isEmpty()) {
                    checks.add(new PendingCheck(segment, rating, null, "PASS"));
                    continue;
                }
                for (RatingWindowRow window : windows) {
                    boolean exceeded = rating.exceeds(window.maxRating());
                    checks.add(new PendingCheck(segment, rating, window,
                            exceeded ? "FAIL" : "PASS"));
                    if (exceeded) {
                        violations.add(toViolation(segment, rating, window));
                    }
                }
            }
            if (!violations.isEmpty()) {
                throw new RatingViolationException(violations);
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
            for (PendingCheck check : checks) {
                repo.insertRatingCheck(publicationId, channelId, businessDay,
                        check.segment().id(), check.segment().assetId(), check.rating(),
                        check.window() == null ? null : check.window().id(),
                        check.window() == null ? null : check.window().maxRating(),
                        check.verdict(), nowMs());
            }
            return new PublishResponse(publicationId, channelId, businessDay.toString(),
                    newVersion, draft.version());
        });
    }

    // ---------- 管控时段 ----------

    /** 创建管控时段：同运营日内左闭右开，不得与同频道同时段其他时段重叠（端点相接合法）。 */
    @Transactional
    public RatingWindowResponse createRatingWindow(String channelId,
                                                   CreateRatingWindowRequest request) {
        repo.findChannelForUpdate(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String hash = sha256(OP_CREATE_RATING_WINDOW + "|" + channelId + "|" + request.businessDay()
                + "|" + toMs(request.start()) + "|" + toMs(request.end()) + "|" + request.maxRating());
        return idempotent(request.requestId(), OP_CREATE_RATING_WINDOW, hash,
                RatingWindowResponse.class, () -> {
                    validateWindowTiming(request.businessDay(), request.start(), request.end());
                    long startMs = toMs(request.start());
                    long endMs = toMs(request.end());
                    List<RatingWindowRow> overlaps = repo.findOverlappingWindows(channelId,
                            request.businessDay(), startMs, endMs);
                    if (!overlaps.isEmpty()) {
                        throw ApiException.unprocessable("WINDOW_OVERLAP",
                                "管控时段与已有时段重叠: " + overlaps.get(0).id());
                    }
                    long id = repo.insertRatingWindow(channelId, request.businessDay(),
                            startMs, endMs, request.maxRating(), nowMs());
                    return new RatingWindowResponse(id, channelId, request.businessDay().toString(),
                            atMs(startMs), atMs(endMs), request.maxRating());
                });
    }

    /** 修改管控时段起止与最高分级；运营日不可变，重叠校验排除自身。 */
    @Transactional
    public RatingWindowResponse updateRatingWindow(String channelId, long windowId,
                                                   UpdateRatingWindowRequest request) {
        repo.findChannelForUpdate(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String hash = sha256(OP_UPDATE_RATING_WINDOW + "|" + windowId
                + "|" + toMs(request.start()) + "|" + toMs(request.end()) + "|" + request.maxRating());
        return idempotent(request.requestId(), OP_UPDATE_RATING_WINDOW, hash,
                RatingWindowResponse.class, () -> {
                    RatingWindowRow existing = repo.findRatingWindow(windowId)
                            .orElseThrow(() -> ApiException.notFound("管控时段不存在: " + windowId));
                    if (!existing.channelId().equals(channelId)) {
                        throw ApiException.notFound("管控时段不属于频道 " + channelId + ": " + windowId);
                    }
                    validateWindowTiming(existing.businessDay(), request.start(), request.end());
                    long startMs = toMs(request.start());
                    long endMs = toMs(request.end());
                    List<RatingWindowRow> overlaps = repo.findOverlappingWindowsExcluding(channelId,
                            existing.businessDay(), startMs, endMs, windowId);
                    if (!overlaps.isEmpty()) {
                        throw ApiException.unprocessable("WINDOW_OVERLAP",
                                "管控时段与已有时段重叠: " + overlaps.get(0).id());
                    }
                    repo.updateRatingWindow(windowId, startMs, endMs, request.maxRating(), nowMs());
                    return new RatingWindowResponse(windowId, channelId,
                            existing.businessDay().toString(), atMs(startMs), atMs(endMs),
                            request.maxRating());
                });
    }

    /** 查询频道某运营日的管控时段配置，按开始时刻升序。 */
    @Transactional(readOnly = true)
    public List<RatingWindowResponse> listRatingWindows(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        return repo.findRatingWindows(channelId, businessDay).stream()
                .map(PlayoutService::toWindowResponse)
                .toList();
    }

    // ---------- 发布分级校验记录 ----------

    /** 查询频道某业务日的发布分级校验记录（含被拦截的 FAIL 记录），按记录 ID 升序。 */
    @Transactional(readOnly = true)
    public List<RatingCheckResponse> listRatingChecks(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        return repo.findRatingChecks(channelId, businessDay).stream()
                .map(PlayoutService::toCheckResponse)
                .toList();
    }

    // ---------- 紧急插播 ----------

    /**
     * 创建紧急插播：插播素材须满足插播时刻所属管控时段的分级限制，未声明分级的素材按
     * MATURE 参与校验；超限返回 422 且不创建插播。与管控时段配置变更按频道行锁串行化。
     */
    @Transactional
    public BreakinResponse createBreakin(String channelId, BreakinRequest request) {
        repo.findChannelForUpdate(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        String hash = sha256(OP_BREAKIN + "|" + channelId + "|" + request.assetId()
                + "|" + toMs(request.at()));
        return idempotent(request.requestId(), OP_BREAKIN, hash, BreakinResponse.class, () -> {
            AssetRow asset = repo.findAsset(request.assetId())
                    .orElseThrow(() -> ApiException.notFound("素材不存在: " + request.assetId()));
            Rating rating = Rating.effective(asset.rating());
            long atMs = toMs(request.at());
            LocalDate day = request.at().atZoneSameInstant(ZONE).toLocalDate();
            Optional<RatingWindowRow> window = repo.findRatingWindowAt(channelId, day, atMs);
            if (window.isPresent() && rating.exceeds(window.get().maxRating())) {
                RatingWindowRow hit = window.get();
                throw ApiException.ratingExceeded(
                        "插播素材分级 " + rating + " 超过插播时刻管控时段允许的最高分级 "
                                + hit.maxRating(),
                        List.of(new RatingViolationInfo(null, asset.id(), rating, hit.id(),
                                atMs(hit.startMs()), atMs(hit.endMs()), hit.maxRating())));
            }
            long id = repo.insertBreakin(channelId, asset.id(), atMs, request.requestId(), nowMs());
            return new BreakinResponse(id, channelId, asset.id(), atMs(atMs), rating,
                    window.map(RatingWindowRow::id).orElse(null));
        });
    }

    // ---------- 播出决定 ----------

    /**
     * 按频道与时刻查询播出决定。命中有效片段返回节目素材；无已发布编排、空档或授权已撤销时
     * 返回保底素材及明确原因。撤销判定基于授权当前状态，不改写历史快照。响应同时携带返回素材的
     * 有效分级与 at 时刻命中的管控时段（如有），供下游过滤展示。
     */
    @Transactional(readOnly = true)
    public PlayoutDecisionResponse playoutDecision(String channelId, OffsetDateTime at) {
        var channel = repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);
        LocalDate businessDay = at.atZoneSameInstant(ZONE).toLocalDate();
        ControlWindowInfo window = controlWindowAt(channelId, businessDay, atMs);

        Optional<PublicationRow> publication = repo.findLatestPublication(channelId, businessDay);
        if (publication.isEmpty()) {
            return fallback(channelId, at, channel.fallbackAssetId(),
                    FallbackReason.NO_PUBLISHED_SCHEDULE, window);
        }
        Optional<PublicationSegmentRow> segment =
                repo.findPublicationSegmentAt(publication.get().id(), atMs);
        if (segment.isEmpty()) {
            return fallback(channelId, at, channel.fallbackAssetId(),
                    FallbackReason.GAP, window);
        }
        PublicationSegmentRow hit = segment.get();
        GrantRow grant = repo.findGrant(hit.grantId())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "发布快照引用的授权不存在: " + hit.grantId()));
        if (grant.revoked()) {
            return new PlayoutDecisionResponse(channelId, at, channel.fallbackAssetId(),
                    DecisionSource.FALLBACK,
                    FallbackReason.GRANT_REVOKED,
                    publication.get().id(), hit.segmentId(),
                    effectiveRatingOf(channel.fallbackAssetId()), window);
        }
        return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(),
                effectiveRatingOf(hit.assetId()), window);
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

    /** 校验管控时段起止：终点大于起点，且整体落在运营日（Asia/Shanghai）内。 */
    private static void validateWindowTiming(LocalDate businessDay, OffsetDateTime start,
                                             OffsetDateTime end) {
        long startMs = toMs(start);
        long endMs = toMs(end);
        if (endMs <= startMs) {
            throw ApiException.badRequest("管控时段结束时间必须大于开始时间");
        }
        long dayStartMs = businessDay.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEndMs = businessDay.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        if (startMs < dayStartMs || endMs > dayEndMs) {
            throw ApiException.unprocessable("WINDOW_OUT_OF_DAY",
                    "管控时段须落在运营日 " + businessDay + " 内: " + start + " ~ " + end);
        }
    }

    /** 素材有效分级；素材不存在时按 MATURE 处理（兜底，正常流程素材已校验存在）。 */
    private Rating effectiveRatingOf(String assetId) {
        return repo.findAsset(assetId)
                .map(asset -> Rating.effective(asset.rating()))
                .orElse(Rating.MATURE);
    }

    /** at 时刻命中的管控时段摘要；未落入任何时段（无限制）时为 null。 */
    private ControlWindowInfo controlWindowAt(String channelId, LocalDate businessDay, long atMs) {
        return repo.findRatingWindowAt(channelId, businessDay, atMs)
                .map(window -> new ControlWindowInfo(window.id(), atMs(window.startMs()),
                        atMs(window.endMs()), window.maxRating()))
                .orElse(null);
    }

    private static RatingViolationInfo toViolation(SegmentRow segment, Rating rating,
                                                   RatingWindowRow window) {
        return new RatingViolationInfo(segment.id(), segment.assetId(), rating, window.id(),
                atMs(window.startMs()), atMs(window.endMs()), window.maxRating());
    }

    private static RatingWindowResponse toWindowResponse(RatingWindowRow row) {
        return new RatingWindowResponse(row.id(), row.channelId(), row.businessDay().toString(),
                atMs(row.startMs()), atMs(row.endMs()), row.maxRating());
    }

    private static RatingCheckResponse toCheckResponse(RatingCheckRow row) {
        return new RatingCheckResponse(row.id(), row.publicationId(), row.channelId(),
                row.businessDay().toString(), row.segmentId(), row.assetId(), row.rating(),
                row.windowId(), row.windowMaxRating(), row.verdict(), atMs(row.createdAtMs()));
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
                                             FallbackReason reason, ControlWindowInfo window) {
        return new PlayoutDecisionResponse(channelId, at, assetId,
                DecisionSource.FALLBACK, reason, null, null,
                effectiveRatingOf(assetId), window);
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

    /** 发布时待写入的分级校验记录；window 为 null 表示片段未落入任何管控时段。 */
    private record PendingCheck(SegmentRow segment, Rating rating, RatingWindowRow window,
                                String verdict) {
    }

    /** 发布分级校验未通过：携带全部越级明细，由发布入口补写审计记录后转换为 422。 */
    static final class RatingViolationException extends RuntimeException {

        private final List<RatingViolationInfo> violations;

        RatingViolationException(List<RatingViolationInfo> violations) {
            super("草稿含 " + violations.size() + " 条素材分级超过命中管控时段允许的最高分级");
            this.violations = violations;
        }

        List<RatingViolationInfo> violations() {
            return violations;
        }

        ApiException toApiException() {
            return ApiException.ratingExceeded(getMessage(), violations);
        }
    }
}
