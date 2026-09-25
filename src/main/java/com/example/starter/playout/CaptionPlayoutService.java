package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.BlackoutRow;
import com.example.starter.playout.PlayoutRepository.CaptionRow;
import com.example.starter.playout.PlayoutRepository.CaptionTextRow;
import com.example.starter.playout.PlayoutRepository.CrawlRecordRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.PlayoutReceiptRow;
import com.example.starter.playout.PlayoutRepository.PublicationCaptionRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.BlackoutResponse;
import com.example.starter.playout.api.Dtos.BlockingReasonResponse;
import com.example.starter.playout.api.Dtos.CaptionDecisionResponse;
import com.example.starter.playout.api.Dtos.CaptionPublishResponse;
import com.example.starter.playout.api.Dtos.CaptionPublishRequest;
import com.example.starter.playout.api.Dtos.CaptionReviewStatus;
import com.example.starter.playout.api.Dtos.CaptionSnapshotResponse;
import com.example.starter.playout.api.Dtos.CaptionStatus;
import com.example.starter.playout.api.Dtos.CaptionTextResponse;
import com.example.starter.playout.api.Dtos.ConfirmPlayoutRequest;
import com.example.starter.playout.api.Dtos.CreateBlackoutRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyCaptionRequest;
import com.example.starter.playout.api.Dtos.CreateCaptionTextRequest;
import com.example.starter.playout.api.Dtos.EmergencyCaptionResponse;
import com.example.starter.playout.api.Dtos.PlayoutReceiptResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.ReviewCaptionTextRequest;
import com.example.starter.playout.api.Dtos.RevokeCaptionRequest;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 紧急字幕与节目回执核心服务。
 *
 * <p>字幕窗口为 UTC 左闭右开（数据库内统一为 UTC 纪元毫秒）；优先级为任意整数，越大越高；
 * 区域集合在写入前排序去重规范化。同一区域且同一优先级的 ACTIVE 有效窗口不得重叠，端点相接合法。</p>
 *
 * <p>发布节目单时对每个区域和时间片选择优先级最高的有效字幕；字幕窗口与黑屏窗口冲突或文本版本
 * 未审核则整次发布 422 并稳定列出阻断区域与窗口，不发布任何部分。成功发布固化节目素材、字幕版本、
 * 优先级与解析原因；撤销字幕、修改文本（产生新版本）或新建更高优先级字幕均不改写已发布快照。
 * 播放回执按发布快照确认，字幕结束端点恰好时不再覆盖。</p>
 *
 * <p>所有写操作以 crawlKey 幂等：去重记录与业务结果同事务提交，同键重放返回首次结果，
 * 改参数 409，业务失败回滚不占用 crawlKey。字幕、审核、黑屏、发布、回执按数据库提交顺序裁决。</p>
 */
@Service
public class CaptionPlayoutService {

    /** 业务时区，与 {@link PlayoutService} 一致。 */
    public static final ZoneId ZONE = PlayoutService.ZONE;

    private static final String OP_CREATE_CAPTION_TEXT = "CREATE_CAPTION_TEXT";
    private static final String OP_REVIEW_CAPTION_TEXT = "REVIEW_CAPTION_TEXT";
    private static final String OP_CREATE_CAPTION = "CREATE_CAPTION";
    private static final String OP_REVOKE_CAPTION = "REVOKE_CAPTION";
    private static final String OP_CREATE_BLACKOUT = "CREATE_BLACKOUT";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CONFIRM_PLAYOUT = "CONFIRM_PLAYOUT";

    /** 解析原因：窗口内最高优先级有效字幕。 */
    static final String REASON_CAPTION_WIN = "CAPTION_WIN";
    /** 解析原因：时间片由字幕窗口端点切片而来（恰覆盖到片终点，端点相接合法）。 */
    static final String REASON_CAPTION_BOUNDARY = "CAPTION_BOUNDARY";
    /** 实时决策原因：最高优先级字幕文本未审核。 */
    static final String REASON_TEXT_NOT_APPROVED = "TEXT_NOT_APPROVED";
    /** 实时决策原因：该时刻无有效字幕。 */
    static final String REASON_NO_CAPTION = "NO_CAPTION";
    /** 回执原因：按快照确认到字幕覆盖。 */
    static final String REASON_CAPTION_CONFIRMED = "CAPTION_CONFIRMED";
    /** 回执原因：确认时刻恰为字幕结束端点（左闭右开，不再覆盖）。 */
    static final String REASON_CAPTION_END_EXACT = "CAPTION_END_EXACT";

    /** 阻断原因：文本版本未审核。 */
    private static final String BLOCK_TEXT_NOT_APPROVED = "TEXT_NOT_APPROVED";
    /** 阻断原因：字幕窗口与黑屏窗口冲突。 */
    private static final String BLOCK_BLACKOUT_CONFLICT = "BLACKOUT_CONFLICT";

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;

    public CaptionPlayoutService(PlayoutRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------- 字幕文本版本与审核 ----------

    /** 创建字幕文本版本：内容创建即冻结，状态为 PENDING。crawlKey 幂等。 */
    @Transactional
    public CaptionTextResponse createCaptionText(CreateCaptionTextRequest request) {
        String hash = sha256(OP_CREATE_CAPTION_TEXT + "|" + request.versionId() + "|" + request.content());
        return crawlIdempotent(request.crawlKey(), OP_CREATE_CAPTION_TEXT, hash,
                CaptionTextResponse.class, () -> {
                    long now = nowMs();
                    try {
                        repo.insertCaptionText(request.versionId(), request.content(), now);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("DUPLICATE_TEXT_VERSION",
                                "字幕文本版本已存在: " + request.versionId());
                    }
                    return new CaptionTextResponse(request.versionId(), request.content(),
                            CaptionReviewStatus.PENDING, null, atMs(now));
                });
    }

    /**
     * 审核字幕文本版本：PENDING 只能审核一次，流转为 APPROVED / REJECTED。
     * 重复审核（含同结果不同 crawlKey）为 409；crawlKey 同键重放返回首次审核结果。
     */
    @Transactional
    public CaptionTextResponse reviewCaptionText(String versionId, ReviewCaptionTextRequest request) {
        if (request.decision() != CaptionReviewStatus.APPROVED
                && request.decision() != CaptionReviewStatus.REJECTED) {
            throw ApiException.badRequest("审核结论必须为 APPROVED 或 REJECTED");
        }
        String hash = sha256(OP_REVIEW_CAPTION_TEXT + "|" + versionId + "|" + request.decision());
        return crawlIdempotent(request.crawlKey(), OP_REVIEW_CAPTION_TEXT, hash,
                CaptionTextResponse.class, () -> {
                    CaptionTextRow row = repo.findCaptionTextForUpdate(versionId)
                            .orElseThrow(() -> ApiException.notFound("字幕文本版本不存在: " + versionId));
                    int updated = repo.reviewCaptionText(versionId, request.decision().name(), nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("TEXT_ALREADY_REVIEWED",
                                "字幕文本版本已审核，不能重复审核: " + versionId);
                    }
                    return toTextResponse(new CaptionTextRow(row.versionId(), row.content(),
                            request.decision().name(), nowMs(), row.createdAtMs()));
                });
    }

    /** 查询字幕文本版本明细（含审核状态）。 */
    @Transactional(readOnly = true)
    public CaptionTextResponse getCaptionText(String versionId) {
        return toTextResponse(repo.findCaptionText(versionId)
                .orElseThrow(() -> ApiException.notFound("字幕文本版本不存在: " + versionId)));
    }

    // ---------- 紧急字幕 ----------

    /**
     * 创建紧急字幕：区域集合排序去重规范化；校验窗口左闭右开、频道与文本版本存在；
     * 同区域同优先级 ACTIVE 窗口不得重叠（端点相接合法）；同 captionKey 不可重复（撤销不复活）。
     * 不要求文本版本已审核（未审核字幕会在发布时 422 阻断）。crawlKey 幂等，失败不占键。
     */
    @Transactional
    public EmergencyCaptionResponse createEmergencyCaption(CreateEmergencyCaptionRequest request) {
        List<String> regions = normalizeRegions(request.regions());
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        String hash = sha256(OP_CREATE_CAPTION + "|" + request.captionKey() + "|"
                + request.channelId() + "|" + request.priority() + "|" + request.textVersionId()
                + "|" + startMs + "|" + endMs + "|" + String.join(",", regions));
        return crawlIdempotent(request.crawlKey(), OP_CREATE_CAPTION, hash,
                EmergencyCaptionResponse.class, () ->
                        doCreateCaption(request, regions, startMs, endMs));
    }

    private EmergencyCaptionResponse doCreateCaption(CreateEmergencyCaptionRequest request,
                                                     List<String> regions,
                                                     long startMs, long endMs) {
        if (endMs <= startMs) {
            throw ApiException.badRequest("紧急字幕窗口结束时间必须大于开始时间");
        }
        repo.lockChannelForUpdate(request.channelId());
        repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        repo.findCaptionText(request.textVersionId())
                .orElseThrow(() -> ApiException.notFound(
                        "字幕文本版本不存在: " + request.textVersionId()));

        // 同键语义：已存在（含已撤销）即冲突，重放创建不能复活已撤销字幕。
        if (repo.findCaptionForUpdate(request.captionKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_CAPTION_KEY",
                    "紧急字幕 captionKey 已存在: " + request.captionKey());
        }

        // 逐区域判同级重叠：H2 无间隙锁，须在频道行锁内完成判定与写入。
        for (String region : regions) {
            List<CaptionRow> overlapping = repo.findActiveCaptionsOverlappingForUpdate(
                    request.channelId(), region, request.priority(), startMs, endMs);
            if (!overlapping.isEmpty()) {
                throw ApiException.conflict("CAPTION_INTERVAL_CONFLICT",
                        "区域 " + region + " 同优先级 ACTIVE 字幕窗口重叠: "
                                + overlapping.get(0).captionKey());
            }
        }

        long createdAtMs = nowMs();
        try {
            long id = repo.insertCaption(request.captionKey(), request.channelId(),
                    request.priority(), request.textVersionId(), startMs, endMs, regions, createdAtMs);
            return new EmergencyCaptionResponse(id, request.captionKey(), request.channelId(),
                    request.priority(), request.textVersionId(),
                    atMs(startMs), atMs(endMs), regions, CaptionStatus.ACTIVE,
                    null, null, atMs(createdAtMs));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_CAPTION_KEY",
                    "紧急字幕 captionKey 已存在: " + request.captionKey());
        }
    }

    /**
     * 撤销紧急字幕（终态）：仅 ACTIVE 可撤销；撤销提交后释放同区域同优先级冲突窗口，
     * 但不改写任何已发布快照。crawlKey 幂等，失败不占键。
     */
    @Transactional
    public EmergencyCaptionResponse revokeCaption(String captionKey, RevokeCaptionRequest request) {
        String hash = sha256(OP_REVOKE_CAPTION + "|" + captionKey);
        return crawlIdempotent(request.crawlKey(), OP_REVOKE_CAPTION, hash,
                EmergencyCaptionResponse.class, () -> {
                    CaptionRow existing = repo.findCaption(captionKey)
                            .orElseThrow(() -> ApiException.notFound("紧急字幕不存在: " + captionKey));
                    repo.lockChannelForUpdate(existing.channelId());
                    int updated = repo.revokeCaption(captionKey, request.crawlKey(), nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("CAPTION_NOT_ACTIVE",
                                "紧急字幕已撤销，不能重复撤销: " + captionKey);
                    }
                    return toCaptionResponse(repo.findCaptionForUpdate(captionKey).orElseThrow());
                });
    }

    /** 查询紧急字幕明细，ACTIVE / REVOKED 均返回（含撤销 crawlKey 与规范化区域集合）。 */
    @Transactional(readOnly = true)
    public EmergencyCaptionResponse getEmergencyCaption(String captionKey) {
        return toCaptionResponse(repo.findCaption(captionKey)
                .orElseThrow(() -> ApiException.notFound("紧急字幕不存在: " + captionKey)));
    }

    // ---------- 黑屏窗口 ----------

    /** 创建黑屏窗口（不可变）：窗口 UTC 左闭右开。crawlKey 幂等，失败不占键。 */
    @Transactional
    public BlackoutResponse createBlackout(CreateBlackoutRequest request) {
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        String hash = sha256(OP_CREATE_BLACKOUT + "|" + request.blackoutKey() + "|"
                + request.channelId() + "|" + request.region() + "|" + startMs + "|" + endMs);
        return crawlIdempotent(request.crawlKey(), OP_CREATE_BLACKOUT, hash,
                BlackoutResponse.class, () -> {
                    if (endMs <= startMs) {
                        throw ApiException.badRequest("黑屏窗口结束时间必须大于开始时间");
                    }
                    repo.lockChannelForUpdate(request.channelId());
                    repo.findChannel(request.channelId())
                            .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
                    long now = nowMs();
                    try {
                        long id = repo.insertBlackout(request.blackoutKey(), request.channelId(),
                                request.region(), startMs, endMs, now);
                        return new BlackoutResponse(id, request.blackoutKey(), request.channelId(),
                                request.region(), atMs(startMs), atMs(endMs), atMs(now));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("DUPLICATE_BLACKOUT_KEY",
                                "黑屏窗口 blackoutKey 已存在: " + request.blackoutKey());
                    }
                });
    }

    /** 查询黑屏窗口明细。 */
    @Transactional(readOnly = true)
    public BlackoutResponse getBlackout(String blackoutKey) {
        BlackoutRow row = repo.findBlackout(blackoutKey)
                .orElseThrow(() -> ApiException.notFound("黑屏窗口不存在: " + blackoutKey));
        return new BlackoutResponse(row.id(), row.blackoutKey(), row.channelId(), row.region(),
                atMs(row.startMs()), atMs(row.endMs()), atMs(row.createdAtMs()));
    }

    // ---------- 字幕感知发布 ----------

    /**
     * 发布节目单并固化字幕决策。沿用既有发布的草稿版本/发布版本乐观校验与授权实时校验；
     * regions 非空时对每个区域 × 每个时间片选择优先级最高的有效字幕，选定字幕文本未审核或其
     * 窗口与黑屏窗口冲突则整次发布 422（稳定列出全部阻断区域与窗口，不写入任何发布数据）。
     * 成功后节目素材、字幕版本、优先级、文本内容与解析原因随快照只读固化。crawlKey 幂等。
     */
    @Transactional
    public CaptionPublishResponse publishWithCaptions(String channelId, LocalDate businessDay,
                                                      CaptionPublishRequest request) {
        List<String> regions = request.regions() == null ? List.of()
                : normalizeRegions(request.regions());
        String hash = sha256(OP_PUBLISH + "|" + channelId + "|" + businessDay
                + "|" + request.draftVersion() + "|" + request.expectedPublishedVersion()
                + "|" + String.join(",", regions));
        return crawlIdempotent(request.crawlKey(), OP_PUBLISH, hash,
                CaptionPublishResponse.class,
                () -> doPublish(channelId, businessDay, request, regions));
    }

    private CaptionPublishResponse doPublish(String channelId, LocalDate businessDay,
                                             CaptionPublishRequest request,
                                             List<String> regions) {
        repo.lockChannelForUpdate(channelId);
        DraftRow draft = repo.findDraft(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound(
                        "草稿不存在: " + channelId + " " + businessDay));
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
        // 逐片段加锁校验授权仍有效：与授权撤销按提交顺序串行，撤销先提交则整次发布 422。
        List<Long> grantIds = new ArrayList<>();
        for (SegmentRow segment : segments) {
            List<GrantRow> candidates = repo.findCoveringGrantsForUpdate(channelId,
                    segment.assetId(), segment.startMs(), segment.endMs());
            GrantRow grant = candidates.stream().filter(g -> !g.revoked()).findFirst()
                    .orElseThrow(() -> ApiException.unprocessable("GRANT_INVALID",
                            "片段授权已失效，发布拒绝: " + segment.id()));
            grantIds.add(grant.id());
        }

        // 锁定与该频道 UTC 日窗口相交的全部字幕行，与字幕撤销/新建按提交顺序串行。
        long dayStartMs = businessDay.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEndMs = businessDay.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<CaptionRow> channelCaptions =
                repo.findChannelCaptionsIntersectingForUpdate(channelId, dayStartMs, dayEndMs);

        List<CaptionSnapshotResponse> snapshots = new ArrayList<>();
        List<BlockingReasonResponse> blocking = new ArrayList<>();
        for (String region : regions) {
            // 该区域相关字幕（ACTIVE 且含该区域）；时间片由节目片段边界与这些字幕窗口边界联合切割，
            // 保证进入/离开字幕窗口或切换优先级时决策可随时间片变化。
            List<CaptionRow> regionCaptions = channelCaptions.stream()
                    .filter(CaptionRow::active)
                    .filter(c -> c.regions().contains(region))
                    .toList();
            for (SegmentRow segment : segments) {
                for (long[] slice : buildSlices(segment, regionCaptions)) {
                    CaptionChoice choice = resolveCaptionForSlice(regionCaptions, channelId, region,
                            segment.id(), slice[0], slice[1], blocking);
                    if (choice != null) {
                        snapshots.add(new CaptionSnapshotResponse(region, segment.id(),
                                atMs(slice[0]), atMs(slice[1]),
                                choice.caption().captionKey(), choice.caption().priority(),
                                choice.caption().textVersionId(), choice.textContent(),
                                choice.reason()));
                    }
                }
            }
        }
        if (!blocking.isEmpty()) {
            // 阻断项去重并稳定排序：区域、窗口起、窗口止、原因；整次发布拒绝，不留下半成品。
            List<BlockingReasonResponse> deduped = new ArrayList<>(new LinkedHashSet<>(blocking));
            deduped.sort(Comparator.comparing(BlockingReasonResponse::region)
                    .thenComparing(b -> b.windowStart().toInstant().toEpochMilli())
                    .thenComparing(b -> b.windowEnd().toInstant().toEpochMilli())
                    .thenComparing(BlockingReasonResponse::reason)
                    .thenComparing(b -> b.detail() == null ? "" : b.detail()));
            throw ApiException.publishBlocked(deduped);
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
        for (CaptionSnapshotResponse snap : snapshots) {
            repo.insertPublicationCaption(publicationId, snap.region(), snap.segmentId(),
                    toMs(snap.start()), toMs(snap.end()),
                    findCaptionId(channelCaptions, snap.captionKey()),
                    snap.captionKey(), snap.priority(), snap.textVersionId(), snap.text(),
                    snap.reason());
        }
        return new CaptionPublishResponse(publicationId, channelId, businessDay.toString(),
                newVersion, draft.version(), snapshots.stream()
                .sorted(Comparator.comparing(CaptionSnapshotResponse::region)
                        .thenComparing(s -> s.start().toInstant().toEpochMilli())
                        .thenComparing(CaptionSnapshotResponse::reason))
                .toList());
    }

    /**
     * 以节目片段 [segStart, segEnd) 为基础，用与其相交的字幕窗口端点（落在片段内部的
     * caption.start / caption.end）联合切割出互不重叠的时间片；无任何字幕端点时返回整段一片。
     */
    private static List<long[]> buildSlices(SegmentRow segment, List<CaptionRow> regionCaptions) {
        TreeOfBounds bounds = new TreeOfBounds();
        bounds.add(segment.startMs());
        bounds.add(segment.endMs());
        for (CaptionRow caption : regionCaptions) {
            if (caption.startMs() < segment.endMs() && caption.endMs() > segment.startMs()) {
                if (caption.startMs() > segment.startMs() && caption.startMs() < segment.endMs()) {
                    bounds.add(caption.startMs());
                }
                if (caption.endMs() > segment.startMs() && caption.endMs() < segment.endMs()) {
                    bounds.add(caption.endMs());
                }
            }
        }
        long[] points = bounds.toArray();
        List<long[]> slices = new ArrayList<>();
        for (int i = 1; i < points.length; i++) {
            slices.add(new long[]{points[i - 1], points[i]});
        }
        return slices;
    }

    /** 简单的有序去重长整集合。 */
    private static final class TreeOfBounds {
        private final TreeSet<Long> values = new TreeSet<>();

        void add(long value) {
            values.add(value);
        }

        long[] toArray() {
            long[] result = new long[values.size()];
            int i = 0;
            for (Long value : values) {
                result[i++] = value;
            }
            return result;
        }
    }

    /** 时间片字幕选择结果；同时携带审核锁定后的文本内容与解析原因。 */
    private record CaptionChoice(CaptionRow caption, String textContent, String reason) {
    }

    /**
     * 为某区域某时间片 [sliceStart, sliceEnd) 选择优先级最高的有效字幕：候选须完整覆盖该时间片
     * （caption.start <= 片起点 且 caption.end >= 片终点，端点相接合法），按优先级降序取首。
     * 选定后校验文本版本已审核（锁定文本行，与审核按提交顺序串行）且该时间片不与黑屏窗口冲突；
     * 任一不满足则登记一条阻断原因并返回 null（整次发布稍后 422）。
     */
    private CaptionChoice resolveCaptionForSlice(List<CaptionRow> regionCaptions, String channelId,
                                                 String region, String segmentId,
                                                 long sliceStart, long sliceEnd,
                                                 List<BlockingReasonResponse> blocking) {
        List<CaptionRow> candidates = regionCaptions.stream()
                .filter(c -> c.startMs() <= sliceStart && c.endMs() >= sliceEnd)
                .sorted(Comparator.comparingInt(CaptionRow::priority).reversed()
                        .thenComparingLong(CaptionRow::startMs)
                        .thenComparing(CaptionRow::captionKey))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        CaptionRow top = candidates.get(0);

        CaptionTextRow text = repo.findCaptionTextForUpdate(top.textVersionId())
                .orElseThrow(() -> ApiException.notFound(
                        "字幕文本版本不存在: " + top.textVersionId()));
        if (!"APPROVED".equals(text.reviewStatus())) {
            blocking.add(new BlockingReasonResponse(region, atMs(top.startMs()), atMs(top.endMs()),
                    BLOCK_TEXT_NOT_APPROVED,
                    "字幕 " + top.captionKey() + " 文本版本 " + top.textVersionId()
                            + " 审核状态为 " + text.reviewStatus()));
            return null;
        }

        // 黑屏冲突判定区间为字幕窗口与时间片的交集（左闭右开：黑屏端点与片端点相接合法）。
        List<BlackoutRow> blackouts = repo.findBlackoutsOverlapping(channelId, region,
                Math.max(top.startMs(), sliceStart), Math.min(top.endMs(), sliceEnd));
        if (!blackouts.isEmpty()) {
            for (BlackoutRow blackout : blackouts) {
                blocking.add(new BlockingReasonResponse(region,
                        atMs(blackout.startMs()), atMs(blackout.endMs()),
                        BLOCK_BLACKOUT_CONFLICT,
                        "黑屏窗口 " + blackout.blackoutKey() + " 与字幕 " + top.captionKey()
                                + " 在区域 " + region + " 冲突"));
            }
            return null;
        }

        // 解析原因：时间片由字幕窗口端点切出时（字幕起点或终点恰为片边界）记 CAPTION_BOUNDARY；
        // 该时间片完全落在字幕窗口内部时记 CAPTION_WIN。
        String reason = top.endMs() == sliceEnd || top.startMs() == sliceStart
                ? REASON_CAPTION_BOUNDARY : REASON_CAPTION_WIN;
        return new CaptionChoice(top, text.content(), reason);
    }

    private static long findCaptionId(List<CaptionRow> captions, String captionKey) {
        return captions.stream().filter(c -> c.captionKey().equals(captionKey)).findFirst()
                .orElseThrow(() -> new IllegalStateException("选定字幕不在锁定集合内: " + captionKey))
                .id();
    }

    // ---------- 查询：区域决策 / 发布快照 ----------

    /**
     * 查询某频道某区域在指定时刻的实时字幕决策：取窗口命中（start &lt;= at &lt; end）的
     * ACTIVE 字幕中优先级最高者；文本未审核时原因标注 TEXT_NOT_APPROVED；无有效字幕时
     * 返回 reason=NO_CAPTION 且字幕字段为空。查询不改变任何状态。
     */
    @Transactional(readOnly = true)
    public CaptionDecisionResponse captionDecision(String channelId, String region,
                                                   OffsetDateTime at) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        long atMs = toMs(at);
        List<CaptionRow> hits = repo.findActiveCaptionsAt(channelId, region, atMs);
        if (hits.isEmpty()) {
            return new CaptionDecisionResponse(channelId, region, at, null, null, null, null,
                    REASON_NO_CAPTION);
        }
        CaptionRow top = hits.get(0);
        CaptionTextRow text = repo.findCaptionText(top.textVersionId())
                .orElseThrow(() -> ApiException.notFound(
                        "字幕文本版本不存在: " + top.textVersionId()));
        String reason = "APPROVED".equals(text.reviewStatus())
                ? REASON_CAPTION_WIN : REASON_TEXT_NOT_APPROVED;
        return new CaptionDecisionResponse(channelId, region, at, top.captionKey(),
                top.priority(), top.textVersionId(), text.content(), reason);
    }

    /** 查询某次发布的只读快照（节目素材与固化字幕决策）。 */
    @Transactional(readOnly = true)
    public CaptionPublishResponse getPublication(long publicationId) {
        PublicationRow publication = repo.findPublicationById(publicationId)
                .orElseThrow(() -> ApiException.notFound("发布快照不存在: " + publicationId));
        return assemblePublication(publication);
    }

    /** 查询频道某业务日最新一次发布的只读快照。 */
    @Transactional(readOnly = true)
    public CaptionPublishResponse getLatestPublication(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        PublicationRow publication = repo.findLatestPublication(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound(
                        "发布快照不存在: " + channelId + " " + businessDay));
        return assemblePublication(publication);
    }

    private CaptionPublishResponse assemblePublication(PublicationRow publication) {
        List<CaptionSnapshotResponse> snapshots = repo.findPublicationCaptions(publication.id())
                .stream()
                .map(row -> new CaptionSnapshotResponse(row.region(), row.segmentId(),
                        atMs(row.startMs()), atMs(row.endMs()), row.captionKey(), row.priority(),
                        row.textVersionId(), row.textContent(), row.reason()))
                .toList();
        return new CaptionPublishResponse(publication.id(), publication.channelId(),
                publication.businessDay().toString(), publication.publishedVersion(),
                publication.draftVersion(), snapshots);
    }

    // ---------- 播放回执 ----------

    /**
     * 按发布快照确认播放：取确认时刻所属业务日的最新发布，确认节目素材与固化字幕决策。
     * 字幕结束端点恰好（at == caption.end）时不再覆盖，原因标注 CAPTION_END_EXACT。
     * crawlKey 幂等：同键重放返回首次回执，改参数 409，失败不占键。
     */
    @Transactional
    public PlayoutReceiptResponse confirmPlayout(ConfirmPlayoutRequest request) {
        long atMs = toMs(request.at());
        String hash = sha256(OP_CONFIRM_PLAYOUT + "|" + request.channelId() + "|"
                + request.region() + "|" + atMs);
        return crawlIdempotent(request.crawlKey(), OP_CONFIRM_PLAYOUT, hash,
                PlayoutReceiptResponse.class, () -> doConfirm(request, atMs));
    }

    private PlayoutReceiptResponse doConfirm(ConfirmPlayoutRequest request, long atMs) {
        String channelId = request.channelId();
        String region = request.region();
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));

        LocalDate businessDay = request.at().atZoneSameInstant(ZONE).toLocalDate();
        PublicationRow publication = repo.findLatestPublication(channelId, businessDay)
                .orElseThrow(() -> ApiException.unprocessable("PLAYOUT_NO_SCHEDULE",
                        "该业务日无已发布节目单: " + businessDay));
        PublicationSegmentRow segment = repo.findPublicationSegmentAtForUpdate(
                publication.id(), atMs)
                .orElseThrow(() -> ApiException.unprocessable("PLAYOUT_GAP",
                        "确认时刻处于节目空档，无快照可确认"));

        Optional<PublicationCaptionRow> caption =
                repo.findPublicationCaptionAt(publication.id(), region, atMs);
        Long captionRecordId = null;
        String captionKey = null;
        Integer priority = null;
        String textVersionId = null;
        String captionText = null;
        Long captionStartMs = null;
        Long captionEndMs = null;
        String reason;
        if (caption.isPresent()) {
            PublicationCaptionRow row = caption.get();
            captionRecordId = row.id();
            captionKey = row.captionKey();
            priority = row.priority();
            textVersionId = row.textVersionId();
            captionText = row.textContent();
            captionStartMs = row.startMs();
            captionEndMs = row.endMs();
            reason = REASON_CAPTION_CONFIRMED;
        } else {
            // 区分“恰为字幕结束端点”与“本无字幕覆盖”：查同区域起点 <= at 且终点恰为 at 的快照决策。
            boolean endsExactly = repo.existsPublicationCaptionEndingAt(
                    publication.id(), region, atMs);
            reason = endsExactly ? REASON_CAPTION_END_EXACT : REASON_NO_CAPTION;
        }

        long createdAtMs = nowMs();
        long receiptId;
        try {
            receiptId = repo.insertReceipt(request.crawlKey(), channelId, publication.id(),
                    publication.publishedVersion(), region, atMs, segment.assetId(),
                    segment.segmentId(), captionRecordId, captionKey, priority, textVersionId,
                    captionText, captionStartMs, captionEndMs, reason, createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CRAWL_KEY_CONFLICT",
                    "crawlKey 并发冲突: " + request.crawlKey());
        }
        return toReceiptResponse(new PlayoutReceiptRow(receiptId, request.crawlKey(), channelId,
                publication.id(), publication.publishedVersion(), region, atMs, segment.assetId(),
                segment.segmentId(), captionRecordId, captionKey, priority, textVersionId,
                captionText, captionStartMs, captionEndMs, reason, createdAtMs));
    }

    // ---------- 内部方法 ----------

    /** 区域规范化：去空白、去空、去重、字典序升序。 */
    static List<String> normalizeRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            throw ApiException.badRequest("区域集合不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String region : regions) {
            if (region == null || region.isBlank()) {
                throw ApiException.badRequest("区域标识不能为空");
            }
            normalized.add(region.trim());
        }
        return normalized.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /**
     * crawlKey 幂等执行：去重记录与业务结果同事务提交；同 crawlKey 同参数返回原结果，
     * 同 crawlKey 不同参数 409；业务失败抛异常回滚，不占用 crawlKey。
     */
    private <T> T crawlIdempotent(String crawlKey, String operation, String paramsHash,
                                  Class<T> type, Supplier<T> action) {
        if (crawlKey == null || crawlKey.isBlank()) {
            throw ApiException.badRequest("crawlKey 不能为空");
        }
        Optional<CrawlRecordRow> existing = repo.findCrawlRecordForUpdate(crawlKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, paramsHash, type);
        }
        try {
            repo.insertCrawlRecord(crawlKey, operation, paramsHash, nowMs());
        } catch (DuplicateKeyException e) {
            // 并发同 crawlKey：等待对方事务结束后读取已提交记录
            CrawlRecordRow committed = repo.findCrawlRecordForUpdate(crawlKey)
                    .orElseThrow(() -> ApiException.conflict("CRAWL_KEY_CONFLICT",
                            "crawlKey 并发冲突: " + crawlKey));
            return replay(committed, operation, paramsHash, type);
        }
        T result = action.get();
        try {
            repo.completeCrawlRecord(crawlKey, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
        return result;
    }

    private <T> T replay(CrawlRecordRow row, String operation, String paramsHash, Class<T> type) {
        if (!row.operation().equals(operation) || !row.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("CRAWL_KEY_CONFLICT",
                    "crawlKey 已使用且参数不一致: " + row.crawlKey());
        }
        if (row.responseBody() == null) {
            throw ApiException.conflict("CRAWL_KEY_CONFLICT",
                    "crawlKey 请求尚未完成: " + row.crawlKey());
        }
        try {
            return objectMapper.readValue(row.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private static CaptionTextResponse toTextResponse(CaptionTextRow row) {
        CaptionReviewStatus status = CaptionReviewStatus.valueOf(row.reviewStatus());
        return new CaptionTextResponse(row.versionId(), row.content(), status,
                row.reviewedAtMs() == null ? null : atMs(row.reviewedAtMs()),
                atMs(row.createdAtMs()));
    }

    private static EmergencyCaptionResponse toCaptionResponse(CaptionRow row) {
        return new EmergencyCaptionResponse(row.id(), row.captionKey(), row.channelId(),
                row.priority(), row.textVersionId(), atMs(row.startMs()), atMs(row.endMs()),
                row.regions(),
                row.active() ? CaptionStatus.ACTIVE : CaptionStatus.REVOKED,
                row.revokeCrawlKey(),
                row.revokedAtMs() == null ? null : atMs(row.revokedAtMs()),
                atMs(row.createdAtMs()));
    }

    private static PlayoutReceiptResponse toReceiptResponse(PlayoutReceiptRow row) {
        return new PlayoutReceiptResponse(row.id(), row.crawlKey(), row.channelId(),
                row.publicationId(), row.publishedVersion(), row.region(),
                atMs(row.atMs()), row.assetId(), row.segmentId(), row.captionKey(),
                row.priority(), row.textVersionId(), row.captionText(),
                row.captionStartMs() == null ? null : atMs(row.captionStartMs()),
                row.captionEndMs() == null ? null : atMs(row.captionEndMs()),
                row.confirmReason());
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
