package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.BlackoutRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.PublicationSubtitleRow;
import com.example.starter.playout.PlayoutRepository.ReceiptRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.PlayoutRepository.SubtitleRow;
import com.example.starter.playout.PlayoutRepository.SubtitleTextRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.ApproveSubtitleTextRequest;
import com.example.starter.playout.api.Dtos.BlackoutResponse;
import com.example.starter.playout.api.Dtos.CreateBlackoutRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencySubtitleRequest;
import com.example.starter.playout.api.Dtos.CreateSubtitleTextRequest;
import com.example.starter.playout.api.Dtos.EmergencySubtitleResponse;
import com.example.starter.playout.api.Dtos.PlaybackReceiptRequest;
import com.example.starter.playout.api.Dtos.PlaybackReceiptResponse;
import com.example.starter.playout.api.Dtos.PublicationSnapshotResponse;
import com.example.starter.playout.api.Dtos.PublishBlockDetail;
import com.example.starter.playout.api.Dtos.PublishBlockResponse;
import com.example.starter.playout.api.Dtos.RegionSubtitleDecisionResponse;
import com.example.starter.playout.api.Dtos.SnapshotSegmentResponse;
import com.example.starter.playout.api.Dtos.SubtitleStatus;
import com.example.starter.playout.api.Dtos.SubtitleTextResponse;
import com.example.starter.playout.api.Dtos.SubtitleTextStatus;
import com.example.starter.playout.api.Dtos.PublicationSubtitleResponse;
import com.example.starter.playout.api.PublishBlockedException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 紧急字幕（crawl）领域服务：黑屏窗口、字幕文本审核、紧急字幕、发布固化、
 * 区域字幕决策、发布快照查询与播放回执。
 *
 * <p>时间约定与 {@link PlayoutService} 一致：库内 UTC 纪元毫秒，API 边界 Asia/Shanghai。
 * 幂等去重复用 playout_request 表，与业务结果同事务提交，失败不占键；
 * 播放回执以 playout_receipt 的 crawl_key 主键自身去重。</p>
 */
@Service
public class CrawlSubtitleService {

    public static final String OP_CREATE_BLACKOUT = "CREATE_BLACKOUT";
    public static final String OP_CREATE_SUBTITLE_TEXT = "CREATE_SUBTITLE_TEXT";
    public static final String OP_APPROVE_SUBTITLE_TEXT = "APPROVE_SUBTITLE_TEXT";
    public static final String OP_CREATE_SUBTITLE = "CREATE_SUBTITLE";
    public static final String OP_REVOKE_SUBTITLE = "REVOKE_SUBTITLE";

    /** 发布阻断原因码：命中字幕的文本版本未审核。 */
    public static final String BLOCK_TEXT_NOT_APPROVED = "SUBTITLE_TEXT_NOT_APPROVED";
    /** 发布阻断原因码：字幕窗口与黑屏窗口在共同区域正重叠。 */
    public static final String BLOCK_BLACKOUT_CONFLICT = "SUBTITLE_BLACKOUT_CONFLICT";

    private static final ZoneId ZONE = PlayoutService.ZONE;

    private final PlayoutRepository repo;
    private final ObjectMapper objectMapper;
    private final PublishBlockRecorder blockRecorder;

    public CrawlSubtitleService(PlayoutRepository repo, ObjectMapper objectMapper,
                                PublishBlockRecorder blockRecorder) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.blockRecorder = blockRecorder;
    }

    // ---------- 黑屏窗口 ----------

    /** 创建黑屏窗口；区域集合去空白、去重并按字典序规范化。携带 requestId 幂等。 */
    @Transactional
    public BlackoutResponse createBlackout(CreateBlackoutRequest request) {
        List<String> regions = normalizeRegions(request.regions());
        String hash = sha256(OP_CREATE_BLACKOUT + "|" + request.channelId() + "|"
                + toMs(request.start()) + "|" + toMs(request.end()) + "|" + String.join(",", regions));
        return idempotent(request.requestId(), OP_CREATE_BLACKOUT, hash, BlackoutResponse.class, () -> {
            long startMs = toMs(request.start());
            long endMs = toMs(request.end());
            if (endMs <= startMs) {
                throw ApiException.badRequest("黑屏窗口结束时间必须大于开始时间");
            }
            repo.lockChannelForUpdate(request.channelId());
            repo.findChannel(request.channelId())
                    .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
            long createdAtMs = nowMs();
            long id = repo.insertBlackout(request.channelId(), startMs, endMs,
                    String.join(",", regions), request.requestId(), createdAtMs);
            for (String region : regions) {
                repo.insertBlackoutRegion(id, region);
            }
            return new BlackoutResponse(id, request.channelId(), request.start(), request.end(),
                    regions, atMs(createdAtMs));
        });
    }

    // ---------- 字幕文本与审核 ----------

    /** 创建不可变字幕文本版本，初始 PENDING；同 textKey+version 重复创建为 409。 */
    @Transactional
    public SubtitleTextResponse createSubtitleText(CreateSubtitleTextRequest request) {
        String hash = sha256(OP_CREATE_SUBTITLE_TEXT + "|" + request.textKey() + "|"
                + request.version() + "|" + request.content());
        return idempotent(request.requestId(), OP_CREATE_SUBTITLE_TEXT, hash,
                SubtitleTextResponse.class, () -> {
                    long createdAtMs = nowMs();
                    try {
                        repo.insertSubtitleText(request.textKey(), request.version(),
                                request.content(), createdAtMs);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("DUPLICATE_TEXT_VERSION",
                                "字幕文本版本已存在: " + request.textKey() + "#" + request.version());
                    }
                    return new SubtitleTextResponse(request.textKey(), request.version(),
                            request.content(), SubtitleTextStatus.PENDING, atMs(createdAtMs), null);
                });
    }

    /** 审核文本版本；仅 PENDING 可审核，新 requestId 重复审核为 409，同 requestId 重放原结果。 */
    @Transactional
    public SubtitleTextResponse approveSubtitleText(String textKey, int version,
                                                    ApproveSubtitleTextRequest request) {
        String hash = sha256(OP_APPROVE_SUBTITLE_TEXT + "|" + textKey + "|" + version);
        return idempotent(request.requestId(), OP_APPROVE_SUBTITLE_TEXT, hash,
                SubtitleTextResponse.class, () -> {
                    SubtitleTextRow row = repo.findSubtitleTextForUpdate(textKey, version)
                            .orElseThrow(() -> ApiException.notFound(
                                    "字幕文本版本不存在: " + textKey + "#" + version));
                    if (row.approved()) {
                        // 同 requestId 重放在幂等层直接返回历史结果；能走到这里必为新 requestId。
                        throw ApiException.conflict("SUBTITLE_TEXT_ALREADY_APPROVED",
                                "字幕文本版本已审核: " + textKey + "#" + version);
                    }
                    int updated = repo.approveSubtitleText(textKey, version,
                            request.requestId(), nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("SUBTITLE_TEXT_ALREADY_APPROVED",
                                "字幕文本版本已审核: " + textKey + "#" + version);
                    }
                    return toTextResponse(repo.findSubtitleText(textKey, version).orElseThrow());
                });
    }

    /** 查询字幕文本版本明细。 */
    @Transactional(readOnly = true)
    public SubtitleTextResponse getSubtitleText(String textKey, int version) {
        return toTextResponse(repo.findSubtitleText(textKey, version)
                .orElseThrow(() -> ApiException.notFound(
                        "字幕文本版本不存在: " + textKey + "#" + version)));
    }

    // ---------- 紧急字幕 ----------

    /**
     * 创建紧急字幕：整数优先级、引用文本版本（须已审核）、UTC 左闭右开窗口与规范化区域集合。
     * 同一区域且同一优先级的 ACTIVE 窗口不得正重叠（端点相接合法）。
     * 携带 requestId 幂等：同键同参返回首次结果，改参 409，失败不占键；
     * 已撤销字幕不能用相同 subtitleKey 重放复活。
     */
    @Transactional
    public EmergencySubtitleResponse createSubtitle(CreateEmergencySubtitleRequest request) {
        List<String> regions = normalizeRegions(request.regions());
        String hash = sha256(OP_CREATE_SUBTITLE + "|" + request.subtitleKey() + "|"
                + request.channelId() + "|" + request.priority() + "|" + request.textKey() + "|"
                + request.textVersion() + "|" + toMs(request.start()) + "|" + toMs(request.end())
                + "|" + String.join(",", regions));
        return idempotent(request.requestId(), OP_CREATE_SUBTITLE, hash,
                EmergencySubtitleResponse.class, () -> doCreateSubtitle(request, regions));
    }

    private EmergencySubtitleResponse doCreateSubtitle(CreateEmergencySubtitleRequest request,
                                                       List<String> regions) {
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        if (endMs <= startMs) {
            throw ApiException.badRequest("紧急字幕结束时间必须大于开始时间");
        }

        repo.lockChannelForUpdate(request.channelId());
        repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));

        // 同键语义：已存在（含已撤销）即冲突，重放创建不能复活已撤销字幕。
        if (repo.findSubtitleForUpdate(request.subtitleKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_SUBTITLE_KEY",
                    "紧急字幕键已存在: " + request.subtitleKey());
        }

        // 文本版本必须存在；是否已审核是发布门禁而非创建门禁（题干允许先建未审核字幕）。
        // 行锁与审核事务在发布固化时按提交顺序串行；创建路径不加锁等待审核。
        if (repo.findSubtitleText(request.textKey(), request.textVersion()).isEmpty()) {
            throw ApiException.notFound("字幕文本版本不存在: "
                    + request.textKey() + "#" + request.textVersion());
        }

        // 时间重叠候选在 SQL 层过滤，区域交集在内存判定；频道锁保证并发创建不漏判。
        List<SubtitleRow> overlapping = repo.findActiveSubtitlesOverlappingForUpdate(
                request.channelId(), request.priority(), startMs, endMs);
        for (SubtitleRow candidate : overlapping) {
            List<String> candidateRegions = repo.findSubtitleRegions(candidate.subtitleKey());
            if (candidateRegions.stream().anyMatch(regions::contains)) {
                throw ApiException.conflict("SUBTITLE_WINDOW_CONFLICT",
                        "同区域同优先级 ACTIVE 字幕窗口重叠: " + candidate.subtitleKey());
            }
        }

        long createdAtMs = nowMs();
        try {
            repo.insertSubtitle(request.subtitleKey(), request.channelId(), request.priority(),
                    request.textKey(), request.textVersion(), startMs, endMs,
                    String.join(",", regions), createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_SUBTITLE_KEY",
                    "紧急字幕键已存在: " + request.subtitleKey());
        }
        for (String region : regions) {
            repo.insertSubtitleRegion(request.subtitleKey(), region);
        }
        return new EmergencySubtitleResponse(request.subtitleKey(), request.channelId(),
                request.priority(), request.textKey(), request.textVersion(),
                request.start(), request.end(), regions, SubtitleStatus.ACTIVE,
                null, null, atMs(createdAtMs));
    }

    /**
     * 撤销紧急字幕：仅 ACTIVE 可撤销，已撤销再撤销为 409；撤销提交后释放同区域同优先级窗口。
     * 撤销不影响已发布快照。携带 requestId 幂等。
     */
    @Transactional
    public EmergencySubtitleResponse revokeSubtitle(String subtitleKey, String requestId) {
        String hash = sha256(OP_REVOKE_SUBTITLE + "|" + subtitleKey);
        return idempotent(requestId, OP_REVOKE_SUBTITLE, hash,
                EmergencySubtitleResponse.class, () -> {
                    SubtitleRow existing = repo.findSubtitle(subtitleKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "紧急字幕不存在: " + subtitleKey));
                    repo.lockChannelForUpdate(existing.channelId());
                    int updated = repo.revokeSubtitle(subtitleKey, requestId, nowMs());
                    if (updated == 0) {
                        throw ApiException.conflict("SUBTITLE_NOT_ACTIVE",
                                "紧急字幕已撤销，不能重复撤销: " + subtitleKey);
                    }
                    return toSubtitleResponse(repo.findSubtitleForUpdate(subtitleKey).orElseThrow());
                });
    }

    /** 查询紧急字幕明细，ACTIVE/REVOKED 均返回。 */
    @Transactional(readOnly = true)
    public EmergencySubtitleResponse getSubtitle(String subtitleKey) {
        return toSubtitleResponse(repo.findSubtitle(subtitleKey)
                .orElseThrow(() -> ApiException.notFound("紧急字幕不存在: " + subtitleKey)));
    }

    // ---------- 区域字幕决策（实时） ----------

    /**
     * 查询某频道某区域某时刻的实时字幕决策：候选为命中该时刻的 ACTIVE 字幕，
     * 按优先级降序取第一条；到结束时刻（左闭右开）不再命中。决策反映查询时状态，
     * 与发布快照相互独立。
     */
    @Transactional(readOnly = true)
    public RegionSubtitleDecisionResponse regionDecision(String channelId, String region,
                                                         OffsetDateTime at) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        String normalized = normalizeRegion(region);
        long atMs = toMs(at);
        List<SubtitleRow> candidates = repo.findActiveSubtitlesAt(channelId, normalized, atMs);
        if (candidates.isEmpty()) {
            return new RegionSubtitleDecisionResponse(channelId, normalized, at,
                    null, null, null, null, null, null, null);
        }
        SubtitleRow winner = candidates.get(0);
        SubtitleTextRow text = repo.findSubtitleText(winner.textKey(), winner.textVersion())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "字幕引用的文本版本不存在: " + winner.textKey() + "#" + winner.textVersion()));
        return new RegionSubtitleDecisionResponse(channelId, normalized, at,
                winner.subtitleKey(), winner.textKey(), winner.textVersion(), text.content(),
                winner.priority(), atMs(winner.startMs()), atMs(winner.endMs()));
    }

    // ---------- 发布快照查询 ----------

    /** 查询某业务日最新发布快照（节目素材 + 固化字幕）。 */
    @Transactional(readOnly = true)
    public PublicationSnapshotResponse latestSnapshot(String channelId, LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        PublicationRow publication = repo.findLatestPublication(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound(
                        "发布快照不存在: " + channelId + " " + businessDay));
        return buildSnapshot(publication);
    }

    /** 按发布 ID 查询快照。 */
    @Transactional(readOnly = true)
    public PublicationSnapshotResponse snapshot(long publicationId) {
        return buildSnapshot(repo.findPublicationById(publicationId)
                .orElseThrow(() -> ApiException.notFound("发布快照不存在: " + publicationId)));
    }

    private PublicationSnapshotResponse buildSnapshot(PublicationRow publication) {
        List<SnapshotSegmentResponse> segments = repo.findPublicationSegments(publication.id())
                .stream()
                .map(s -> new SnapshotSegmentResponse(s.segmentId(), s.assetId(), s.grantId(),
                        atMs(s.startMs()), atMs(s.endMs())))
                .toList();
        List<PublicationSubtitleResponse> subtitles =
                repo.findPublicationSubtitles(publication.id()).stream()
                        .map(this::toPublicationSubtitleResponse)
                        .toList();
        return new PublicationSnapshotResponse(publication.id(), publication.channelId(),
                publication.businessDay().toString(), publication.publishedVersion(),
                publication.draftVersion(), segments, subtitles);
    }

    // ---------- 发布固化（发布事务内调用） ----------

    /**
     * 发布事务内调用：对每个有字幕的区域与每个节目片，按时间边界切片并选择优先级最高的
     * 有效字幕，将子片与字幕版本、内容、优先级、解析原因固化为只读快照。
     *
     * <p>门禁：胜出字幕引用的文本版本必须已审核；胜出字幕窗口不得与黑屏窗口在同区域正重叠。
     * 任一不满足时先在独立事务写入稳定排序的阻断明细（区域与窗口），再抛 422，
     * 由发布主事务整体回滚，不发布部分区域、不留半成品。</p>
     */
    @Transactional
    public void freezeForPublication(String channelId, LocalDate businessDay, long publicationId,
                                     List<SegmentRow> segments, String requestId) {
        // 频道锁 + 全部 ACTIVE 字幕行锁：与字幕创建/撤销、黑屏创建按提交顺序串行裁决。
        repo.lockChannelForUpdate(channelId);
        List<SubtitleRow> activeSubtitles = repo.findActiveSubtitlesForChannelForUpdate(channelId);
        if (activeSubtitles.isEmpty() || segments.isEmpty()) {
            return;
        }

        Map<String, List<String>> subtitleRegions = new HashMap<>();
        Map<String, SubtitleTextRow> texts = new HashMap<>();
        TreeSet<String> regionSet = new TreeSet<>();
        for (SubtitleRow subtitle : activeSubtitles) {
            List<String> regions = repo.findSubtitleRegions(subtitle.subtitleKey());
            subtitleRegions.put(subtitle.subtitleKey(), regions);
            regionSet.addAll(regions);
            texts.computeIfAbsent(textKey(subtitle.textKey(), subtitle.textVersion()),
                    k -> repo.findSubtitleTextForUpdate(subtitle.textKey(), subtitle.textVersion())
                            .orElse(null));
        }

        // 频道内黑屏窗口（不可变）及其区域，仅在存在胜出字幕时用于冲突判定。
        List<BlackoutRow> blackouts = repo.findBlackoutsOverlapping(channelId,
                segments.get(0).startMs(), segments.get(segments.size() - 1).endMs());
        Map<Long, List<String>> blackoutRegions = new HashMap<>();
        for (BlackoutRow blackout : blackouts) {
            blackoutRegions.put(blackout.id(), repo.findBlackoutRegions(blackout.id()));
        }

        List<FrozenPiece> pieces = new ArrayList<>();
        for (String region : regionSet) {
            List<SubtitleRow> inRegion = activeSubtitles.stream()
                    .filter(s -> subtitleRegions.get(s.subtitleKey()).contains(region))
                    .sorted(Comparator.comparing(SubtitleRow::priority).reversed()
                            .thenComparing(SubtitleRow::startMs)
                            .thenComparing(SubtitleRow::subtitleKey))
                    .toList();
            for (SegmentRow segment : segments) {
                pieces.addAll(planRegionSegment(region, segment, inRegion));
            }
        }
        pieces.sort(Comparator.comparing(FrozenPiece::region)
                .thenComparingLong(FrozenPiece::startMs)
                .thenComparingLong(FrozenPiece::endMs)
                .thenComparing(p -> p.winner.subtitleKey()));

        // 门禁一：文本版本审核。先整表判定审核，未审核优先作为阻断码。
        List<PublishBlockDetail> notApproved = new ArrayList<>();
        for (FrozenPiece piece : pieces) {
            SubtitleTextRow text = texts.get(textKey(piece.winner.textKey(), piece.winner.textVersion()));
            if (text == null || !text.approved()) {
                notApproved.add(new PublishBlockDetail(piece.region, atMs(piece.startMs),
                        atMs(piece.endMs), piece.winner.subtitleKey(), piece.winner.textVersion(),
                        "字幕文本版本未审核: " + piece.winner.textKey() + "#" + piece.winner.textVersion()));
            }
        }
        if (!notApproved.isEmpty()) {
            block(channelId, businessDay, requestId, BLOCK_TEXT_NOT_APPROVED, notApproved);
        }

        // 门禁二：胜出字幕窗口与黑屏窗口在共同区域正重叠（端点相接合法）。
        List<PublishBlockDetail> conflicts = new ArrayList<>();
        for (FrozenPiece piece : pieces) {
            for (BlackoutRow blackout : blackouts) {
                if (!blackoutRegions.get(blackout.id()).contains(piece.region)) {
                    continue;
                }
                long from = Math.max(Math.max(piece.startMs, piece.winner.startMs()),
                        blackout.startMs());
                long to = Math.min(Math.min(piece.endMs, piece.winner.endMs()), blackout.endMs());
                if (from < to) {
                    conflicts.add(new PublishBlockDetail(piece.region, atMs(from), atMs(to),
                            piece.winner.subtitleKey(), piece.winner.textVersion(),
                            "字幕窗口与黑屏窗口 #" + blackout.id() + " 在区域 " + piece.region()
                                    + " 正重叠"));
                }
            }
        }
        conflicts.sort(Comparator.comparing(PublishBlockDetail::region)
                .thenComparing(d -> d.start())
                .thenComparing(PublishBlockDetail::end)
                .thenComparing(PublishBlockDetail::subtitleKey));
        if (!conflicts.isEmpty()) {
            block(channelId, businessDay, requestId, BLOCK_BLACKOUT_CONFLICT, conflicts);
        }

        // 固化：同一节目片内相邻且胜出字幕相同的子片合并为一条覆盖记录（不得跨片合并）。
        FrozenPiece previous = null;
        for (FrozenPiece piece : pieces) {
            if (previous != null && previous.region.equals(piece.region)
                    && previous.segmentId.equals(piece.segmentId)
                    && previous.endMs == piece.startMs
                    && previous.winner.subtitleKey().equals(piece.winner.subtitleKey())
                    && previous.reason.equals(piece.reason)) {
                previous.extendEnd(piece.endMs);
                continue;
            }
            insertPiece(publicationId, piece);
            previous = piece;
        }
    }

    /** 单区域单节目片内按候选字幕边界切片并选优；仅返回存在胜出字幕的子片。 */
    private List<FrozenPiece> planRegionSegment(String region, SegmentRow segment,
                                                List<SubtitleRow> inRegion) {
        record Candidate(SubtitleRow row, long startMs, long endMs) {
        }
        List<Candidate> candidates = new ArrayList<>();
        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(segment.startMs());
        boundaries.add(segment.endMs());
        for (SubtitleRow subtitle : inRegion) {
            long startMs = Math.max(segment.startMs(), subtitle.startMs());
            long endMs = Math.min(segment.endMs(), subtitle.endMs());
            if (startMs < endMs) {
                candidates.add(new Candidate(subtitle, startMs, endMs));
                boundaries.add(startMs);
                boundaries.add(endMs);
            }
        }
        List<FrozenPiece> result = new ArrayList<>();
        Long[] points = boundaries.toArray(new Long[0]);
        for (int i = 0; i + 1 < points.length; i++) {
            long sliceStart = points[i];
            long sliceEnd = points[i + 1];
            if (sliceStart >= sliceEnd) {
                continue;
            }
            SubtitleRow winner = null;
            List<SubtitleRow> covering = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (candidate.startMs <= sliceStart && candidate.endMs >= sliceEnd) {
                    covering.add(candidate.row);
                }
            }
            // inRegion 已按优先级降序、起点升序、键升序排序；取首个覆盖者即最高优先级。
            for (SubtitleRow row : inRegion) {
                if (covering.contains(row)) {
                    winner = row;
                    break;
                }
            }
            if (winner == null) {
                continue;
            }
            List<SubtitleRow> ordered = inRegion.stream().filter(covering::contains).toList();
            String priorities = ordered.stream().map(s -> String.valueOf(s.priority()))
                    .collect(Collectors.joining(","));
            String reason = "region=" + region + ";candidates=" + ordered.size()
                    + ";priorities=" + priorities + ";selected=" + winner.subtitleKey()
                    + "@priority" + winner.priority();
            result.add(new FrozenPiece(region, sliceStart, sliceEnd, segment.id(), winner, reason));
        }
        return result;
    }

    private void insertPiece(long publicationId, FrozenPiece piece) {
        SubtitleTextRow text = repo.findSubtitleText(piece.winner.textKey(), piece.winner.textVersion())
                .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                        "字幕引用的文本版本不存在: "
                                + piece.winner.textKey() + "#" + piece.winner.textVersion()));
        repo.insertPublicationSubtitle(publicationId, piece.region, piece.segmentId,
                piece.startMs, piece.endMs, piece.winner.subtitleKey(),
                piece.winner.textKey(), piece.winner.textVersion(), text.content(),
                piece.winner.priority(), piece.reason);
    }

    private void block(String channelId, LocalDate businessDay, String requestId,
                       String code, List<PublishBlockDetail> items) {
        try {
            blockRecorder.record(channelId, businessDay, requestId, code,
                    objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException("阻断明细序列化失败", e);
        }
        throw new PublishBlockedException(code, "发布被阻断，共 " + items.size()
                + " 个区域/窗口命中阻断原因 " + code, items);
    }

    /** 查询最近一次发布阻断原因；从独立事务审计表读取稳定的区域与窗口明细。 */
    @Transactional(readOnly = true)
    public PublishBlockResponse latestBlock(String channelId, LocalDate businessDay) {
        var row = repo.findLatestPublishBlock(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound(
                        "无发布阻断记录: " + channelId + " " + businessDay));
        try {
            List<PublishBlockDetail> items = objectMapper.readValue(row.detailJson(),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, PublishBlockDetail.class));
            return new PublishBlockResponse(row.channelId(), row.businessDay().toString(),
                    row.code(), items);
        } catch (Exception e) {
            throw new IllegalStateException("阻断明细反序列化失败", e);
        }
    }

    // ---------- 播放回执 ----------

    /**
     * 按发布快照确认播放回执：只依据已固化的节目片段与字幕子片，字幕结束端点恰好时不覆盖。
     * crawlKey 为幂等键：同键同参重放首次结果，同键改参 409；确认失败抛异常、不占键。
     * 指纹含节目单版本、规范化区域、窗口、优先级与文本版本。
     */
    @Transactional
    public PlaybackReceiptResponse confirmReceipt(PlaybackReceiptRequest request) {
        String region = normalizeRegion(request.region());
        long atMs = toMs(request.at());

        Optional<ReceiptRow> existing = repo.findReceiptForUpdate(request.crawlKey());
        if (existing.isPresent()) {
            return replayReceipt(existing.get(), request, region, atMs);
        }

        repo.findChannel(request.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + request.channelId()));
        LocalDate businessDay = request.at().atZoneSameInstant(ZONE).toLocalDate();
        PublicationRow publication = repo.findLatestPublication(request.channelId(), businessDay)
                .orElseThrow(() -> ApiException.unprocessable("NO_PUBLISHED_SCHEDULE",
                        "该业务日无已发布节目单，无法确认回执"));
        PublicationSegmentRow segment = repo.findPublicationSegmentAt(publication.id(), atMs)
                .orElseThrow(() -> ApiException.unprocessable("RECEIPT_PLAYOUT_GAP",
                        "确认时刻不落在任何已发布节目片内"));
        Optional<PublicationSubtitleRow> overlay =
                repo.findPublicationSubtitleAt(publication.id(), region, atMs);

        String fingerprint = receiptFingerprint(publication.publishedVersion(), region, overlay);
        long createdAtMs = nowMs();
        try {
            if (overlay.isPresent()) {
                PublicationSubtitleRow o = overlay.get();
                repo.insertReceipt(request.crawlKey(), request.channelId(), businessDay,
                        publication.id(), publication.publishedVersion(), region, atMs,
                        segment.assetId(), segment.segmentId(), o.subtitleKey(), o.textKey(),
                        o.textVersion(), o.priority(), o.startMs(), o.endMs(),
                        fingerprint, createdAtMs);
            } else {
                repo.insertReceipt(request.crawlKey(), request.channelId(), businessDay,
                        publication.id(), publication.publishedVersion(), region, atMs,
                        segment.assetId(), segment.segmentId(), null, null, null, null, null, null,
                        fingerprint, createdAtMs);
            }
        } catch (DuplicateKeyException e) {
            // 同 crawlKey 并发：等待对方事务提交后读取已确认记录重放。
            ReceiptRow committed = repo.findReceiptForUpdate(request.crawlKey())
                    .orElseThrow(() -> ApiException.conflict("CRAWL_KEY_CONFLICT",
                            "crawlKey 并发冲突: " + request.crawlKey()));
            return replayReceipt(committed, request, region, atMs);
        }
        return toReceiptResponse(repo.findReceipt(request.crawlKey()).orElseThrow());
    }

    private PlaybackReceiptResponse replayReceipt(ReceiptRow row, PlaybackReceiptRequest request,
                                                  String region, long atMs) {
        if (!row.channelId().equals(request.channelId()) || !row.region().equals(region)
                || row.atMs() != atMs) {
            throw ApiException.conflict("CRAWL_KEY_CONFLICT",
                    "crawlKey 已使用且参数不一致: " + request.crawlKey());
        }
        return toReceiptResponse(row);
    }

    /** 指纹：节目单发布版本 | 规范化区域 | 覆盖窗口 | 优先级 | 文本版本；无覆盖时窗口/优先级/版本为空段。 */
    private static String receiptFingerprint(long publishedVersion, String region,
                                             Optional<PublicationSubtitleRow> overlay) {
        String window = "";
        String priority = "";
        String textVersion = "";
        if (overlay.isPresent()) {
            PublicationSubtitleRow o = overlay.get();
            window = o.startMs() + ":" + o.endMs();
            priority = String.valueOf(o.priority());
            textVersion = String.valueOf(o.textVersion());
        }
        return sha256(publishedVersion + "|" + region + "|" + window + "|"
                + priority + "|" + textVersion);
    }

    // ---------- 转换与工具 ----------

    private static SubtitleTextResponse toTextResponse(SubtitleTextRow row) {
        return new SubtitleTextResponse(row.textKey(), row.version(), row.content(),
                row.approved() ? SubtitleTextStatus.APPROVED : SubtitleTextStatus.PENDING,
                atMs(row.createdAtMs()),
                row.approvedAtMs() == null ? null : atMs(row.approvedAtMs()));
    }

    private EmergencySubtitleResponse toSubtitleResponse(SubtitleRow row) {
        return new EmergencySubtitleResponse(row.subtitleKey(), row.channelId(), row.priority(),
                row.textKey(), row.textVersion(), atMs(row.startMs()), atMs(row.endMs()),
                splitRegions(row.regionsText()),
                row.active() ? SubtitleStatus.ACTIVE : SubtitleStatus.REVOKED,
                row.revokeRequestId(),
                row.revokedAtMs() == null ? null : atMs(row.revokedAtMs()),
                atMs(row.createdAtMs()));
    }

    private PublicationSubtitleResponse toPublicationSubtitleResponse(PublicationSubtitleRow row) {
        return new PublicationSubtitleResponse(row.region(), atMs(row.startMs()), atMs(row.endMs()),
                row.segmentId(), row.subtitleKey(), row.textKey(), row.textVersion(),
                row.textContent(), row.priority(), row.reason());
    }

    private static PlaybackReceiptResponse toReceiptResponse(ReceiptRow row) {
        return new PlaybackReceiptResponse(row.crawlKey(), row.channelId(),
                row.businessDay().toString(), row.publicationId(), row.publishedVersion(),
                row.region(), atMs(row.atMs()), row.assetId(), row.segmentId(),
                row.subtitleKey(), row.textVersion(), row.priority(),
                row.overlayStartMs() == null ? null : atMs(row.overlayStartMs()),
                row.overlayEndMs() == null ? null : atMs(row.overlayEndMs()),
                row.fingerprint());
    }

    /** 区域规范化：逐项去空白，去重，按字典序排序；不能为空。 */
    static List<String> normalizeRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            throw ApiException.badRequest("区域集合不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String region : regions) {
            String value = normalizeRegion(region);
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /** 单区域规范化：去首尾空白，不允许含逗号（逗号为库内拼接分隔符）。 */
    static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            throw ApiException.badRequest("区域代码不能为空");
        }
        String value = region.trim();
        if (value.contains(",")) {
            throw ApiException.badRequest("区域代码不得包含逗号: " + value);
        }
        return value;
    }

    private static List<String> splitRegions(String regionsText) {
        return List.of(regionsText.split(","));
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

    private static String textKey(String textKeyName, int version) {
        return textKeyName + "#" + version;
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

    /** 计划期内的一个胜出字幕子片；endMs 在相邻合并时扩展。 */
    private static final class FrozenPiece {
        private final String region;
        private long startMs;
        private long endMs;
        private final String segmentId;
        private final SubtitleRow winner;
        private final String reason;

        private FrozenPiece(String region, long startMs, long endMs, String segmentId,
                            SubtitleRow winner, String reason) {
            this.region = region;
            this.startMs = startMs;
            this.endMs = endMs;
            this.segmentId = segmentId;
            this.winner = winner;
            this.reason = reason;
        }

        private String region() {
            return region;
        }

        private long startMs() {
            return startMs;
        }

        private long endMs() {
            return endMs;
        }

        private void extendEnd(long newEndMs) {
            this.endMs = newEndMs;
        }
    }
}
