package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.LiveOverrideChannelRow;
import com.example.starter.playout.PlayoutRepository.LiveOverrideRow;
import com.example.starter.playout.PlayoutRepository.LiveOverrideSegmentRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.CreateLiveOverrideRequest;
import com.example.starter.playout.api.Dtos.LiveOverrideChannelDiagnostics;
import com.example.starter.playout.api.Dtos.LiveOverrideChannelInput;
import com.example.starter.playout.api.Dtos.LiveOverrideChannelResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideDiagnostics;
import com.example.starter.playout.api.Dtos.LiveOverrideResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideSegmentResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideStatus;
import com.example.starter.playout.api.Dtos.LiveOverrideSummary;
import com.example.starter.playout.api.Dtos.LiveSegmentDisposition;
import com.example.starter.playout.api.Dtos.RevokeLiveOverrideRequest;
import com.example.starter.playout.api.Dtos.SettleLiveOverrideRequest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 直播覆盖服务：跨频道直播覆盖令的创建、撤销、结算与查询。
 *
 * <p>约定：数据库内时间统一为 UTC 纪元毫秒；API 边界统一为 Asia/Shanghai、毫秒精度 ISO 8601；
 * 覆盖区间左闭右开。覆盖内原编排素材不播出但保留占位；覆盖结束后仅恢复未开始的原计划素材，
 * 已错过素材写入 MISSED_BY_LIVE 且不自动补播。覆盖令、受影响素材、授权版本快照与恢复结果不可变；
 * 撤销只允许尚未开始的覆盖，且不能改写已错过记录。创建/撤销/结算与紧急插播、日草稿修改按事务
 * 提交顺序裁决，同一频道同一时段仅一个 ACTIVE 覆盖。幂等指纹为规范化 JSON 的 SHA-256，
 * 含操作者、操作类型、规范化频道集合与全部结果相关字段，不使用未转义分隔符拼接。
 */
@Service
public class LiveOverrideService {

    /** 业务时区。 */
    public static final java.time.ZoneId ZONE = PlayoutService.ZONE;

    private static final String OP_CREATE = "CREATE_LIVE_OVERRIDE";
    private static final String OP_REVOKE = "REVOKE_LIVE_OVERRIDE";
    private static final String OP_SETTLE = "SETTLE_LIVE_OVERRIDE";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PlayoutRepository repo;
    private final IdempotencyExecutor idempotency;
    private final PlayoutClock clock;
    private final ObjectMapper objectMapper;

    public LiveOverrideService(PlayoutRepository repo, IdempotencyExecutor idempotency,
                               PlayoutClock clock, ObjectMapper objectMapper) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ---------- 创建 ----------

    /**
     * 创建直播覆盖令：先校验完整最终状态（频道、直播源授权、区间重叠、已开始节目冲突），
     * 全部通过后在同一事务写入覆盖令、频道维度授权快照与受影响素材记录；任一失败整体回滚，
     * 不留部分覆盖，也不占用 requestId 与 overrideKey。
     */
    @Transactional
    public LiveOverrideResponse create(CreateLiveOverrideRequest request) {
        long startMs = toMs(request.start());
        long endMs = toMs(request.end());
        if (endMs <= startMs) {
            throw ApiException.badRequest("覆盖结束时间必须大于开始时间：实际时长 "
                    + (endMs - startMs) + "ms，要求大于 0ms");
        }
        List<LiveOverrideChannelInput> channels = sortedChannels(request.channels());
        List<Map<String, Object>> canonicalChannels = channels.stream()
                .map(c -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("channelId", c.channelId());
                    entry.put("grantId", c.grantId());
                    return entry;
                })
                .toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("overrideKey", request.overrideKey());
        params.put("liveAssetId", request.liveAssetId());
        params.put("startMs", startMs);
        params.put("endMs", endMs);
        params.put("channels", canonicalChannels);
        String fingerprint = fingerprint(OP_CREATE, request.operator(), params);
        return idempotency.execute(request.requestId(), OP_CREATE, fingerprint,
                LiveOverrideResponse.class, () -> doCreate(request, channels, startMs, endMs));
    }

    private LiveOverrideResponse doCreate(CreateLiveOverrideRequest request,
                                          List<LiveOverrideChannelInput> channels,
                                          long startMs, long endMs) {
        long nowMs = clock.nowMs();
        if (repo.findAsset(request.liveAssetId()).isEmpty()) {
            throw ApiException.notFound("直播源素材不存在: " + request.liveAssetId());
        }
        // 同键语义：已存在（含已撤销/已结算）即冲突，重放创建不能复活旧覆盖令。
        if (repo.findLiveOverrideForUpdate(request.overrideKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_OVERRIDE_KEY",
                    "直播覆盖令 overrideKey 已存在: " + request.overrideKey());
        }
        // 按频道 ID 排序依次加锁，串行化同频道的覆盖创建/撤销与紧急插播，避免并发漏判与死锁。
        for (LiveOverrideChannelInput channel : channels) {
            repo.lockChannelForUpdate(channel.channelId());
        }

        List<ValidatedChannel> validated = new ArrayList<>();
        for (LiveOverrideChannelInput input : channels) {
            validated.add(validateChannel(request, input, startMs, endMs, nowMs));
        }

        long createdAtMs = clock.nowMs();
        try {
            repo.insertLiveOverride(request.overrideKey(), request.liveAssetId(),
                    request.operator(), startMs, endMs, createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_OVERRIDE_KEY",
                    "直播覆盖令 overrideKey 已存在: " + request.overrideKey());
        }
        for (ValidatedChannel channel : validated) {
            repo.insertLiveOverrideChannel(request.overrideKey(), channel.input().channelId(),
                    channel.grant().id(), channel.grant().validFromMs(), channel.grant().validToMs());
            for (PublicationSegmentRow segment : channel.affectedSegments()) {
                repo.insertLiveOverrideSegment(request.overrideKey(), channel.input().channelId(),
                        segment.publicationId(), segment.segmentId(), segment.assetId(),
                        segment.startMs(), segment.endMs());
            }
        }
        return toResponse(repo.findLiveOverride(request.overrideKey()).orElseThrow());
    }

    /** 校验单频道最终状态：频道与授权、区间重叠、已开始节目冲突，并收集受影响素材。 */
    private ValidatedChannel validateChannel(CreateLiveOverrideRequest request,
                                             LiveOverrideChannelInput input,
                                             long startMs, long endMs, long nowMs) {
        var channel = repo.findChannel(input.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + input.channelId()));
        if (channel.fallbackAssetId().equals(request.liveAssetId())) {
            throw ApiException.unprocessable("FALLBACK_ASSET_NOT_GRANTABLE",
                    "保底素材不得作为直播源: " + input.channelId());
        }
        // 锁定授权行：与授权撤销按提交顺序串行，撤销先提交时此处读到 revoked=1 而 422 拒绝。
        GrantRow grant = repo.findGrantForUpdate(input.grantId())
                .orElseThrow(() -> ApiException.notFound("授权不存在: " + input.grantId()));
        if (!grant.channelId().equals(input.channelId())
                || !grant.assetId().equals(request.liveAssetId())) {
            throw ApiException.unprocessable("GRANT_NOT_MATCHED",
                    "指定授权不属于该频道或直播源素材: " + input.grantId());
        }
        if (grant.revoked()) {
            throw ApiException.unprocessable("GRANT_INVALID",
                    "指定授权已撤销: " + input.grantId());
        }
        if (grant.validFromMs() > startMs || grant.validToMs() < endMs) {
            long headGapMs = Math.max(0, startMs - grant.validFromMs());
            long tailGapMs = Math.max(0, endMs - grant.validToMs());
            throw ApiException.unprocessable("GRANT_NOT_COVERING",
                    "指定授权未完整覆盖直播区间: " + input.grantId()
                            + "，起点缺口 " + headGapMs + "ms，终点缺口 " + tailGapMs + "ms");
        }
        List<LiveOverrideRow> overlapping = repo.findActiveLiveOverlappingForUpdate(
                input.channelId(), startMs, endMs);
        if (!overlapping.isEmpty()) {
            throw ApiException.conflict("LIVE_OVERRIDE_INTERVAL_CONFLICT",
                    "同频道 ACTIVE 直播覆盖区间重叠: " + overlapping.get(0).overrideKey());
        }
        List<PublicationSegmentRow> affected = findAffectedSegments(input.channelId(), startMs, endMs);
        for (PublicationSegmentRow segment : affected) {
            if (segment.startMs() <= nowMs) {
                throw ApiException.conflict("LIVE_OVERRIDE_PROGRAM_STARTED",
                        "覆盖区间与已开始节目冲突: 片段 " + segment.segmentId()
                                + " 开始于 " + atMs(segment.startMs())
                                + "，当前时间 " + atMs(nowMs)
                                + "，已超过 " + (nowMs - segment.startMs()) + "ms");
            }
        }
        return new ValidatedChannel(input, grant, affected);
    }

    /** 受影响素材：覆盖区间跨业务日时逐日取最新发布快照，收集与区间相交的片段。 */
    private List<PublicationSegmentRow> findAffectedSegments(String channelId,
                                                             long startMs, long endMs) {
        LocalDate firstDay = Instant.ofEpochMilli(startMs).atZone(ZONE).toLocalDate();
        LocalDate lastDay = Instant.ofEpochMilli(endMs - 1).atZone(ZONE).toLocalDate();
        List<PublicationSegmentRow> affected = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            Optional<PublicationRow> publication = repo.findLatestPublication(channelId, day);
            if (publication.isEmpty()) {
                continue;
            }
            affected.addAll(repo.findPublicationSegmentsOverlapping(
                    publication.get().id(), startMs, endMs));
        }
        return affected;
    }

    // ---------- 撤销 ----------

    /**
     * 撤销直播覆盖令：仅 ACTIVE 且尚未开始（当前时间早于覆盖开始）可撤销；撤销后受影响素材
     * 记录从 PENDING 推进为 RESTORED，已错过记录（MISSED_BY_LIVE）不可被撤销改写。
     */
    @Transactional
    public LiveOverrideResponse revoke(String overrideKey, RevokeLiveOverrideRequest request) {
        String fingerprint = fingerprint(OP_REVOKE, request.operator(),
                Map.of("overrideKey", overrideKey));
        return idempotency.execute(request.requestId(), OP_REVOKE, fingerprint,
                LiveOverrideResponse.class, () -> {
                    LiveOverrideRow row = lockForTransition(overrideKey);
                    long nowMs = clock.nowMs();
                    if (nowMs >= row.startMs()) {
                        throw ApiException.conflict("LIVE_OVERRIDE_ALREADY_STARTED",
                                "覆盖已开始，禁止撤销: 当前时间 " + atMs(nowMs)
                                        + "，覆盖开始 " + atMs(row.startMs())
                                        + "，已超过 " + (nowMs - row.startMs()) + "ms");
                    }
                    int updated = repo.revokeLiveOverride(overrideKey, request.requestId(), nowMs);
                    if (updated == 0) {
                        throw ApiException.conflict("LIVE_OVERRIDE_NOT_ACTIVE",
                                "直播覆盖令非 ACTIVE，不能撤销: " + overrideKey);
                    }
                    repo.updateLiveSegmentDisposition(overrideKey, "RESTORED",
                            request.requestId(), nowMs);
                    return toResponse(repo.findLiveOverride(overrideKey).orElseThrow());
                });
    }

    // ---------- 结算 ----------

    /**
     * 结算直播覆盖令：仅 ACTIVE 且覆盖已结束（当前时间不早于覆盖结束）可结算；结算后受影响
     * 素材记录从 PENDING 推进为 MISSED_BY_LIVE，恢复结果不可变，已错过素材不自动补播。
     */
    @Transactional
    public LiveOverrideResponse settle(String overrideKey, SettleLiveOverrideRequest request) {
        String fingerprint = fingerprint(OP_SETTLE, request.operator(),
                Map.of("overrideKey", overrideKey));
        return idempotency.execute(request.requestId(), OP_SETTLE, fingerprint,
                LiveOverrideResponse.class, () -> {
                    LiveOverrideRow row = lockForTransition(overrideKey);
                    long nowMs = clock.nowMs();
                    if (nowMs < row.endMs()) {
                        throw ApiException.conflict("LIVE_OVERRIDE_NOT_ENDED",
                                "覆盖尚未结束，不能结算: 当前时间 " + atMs(nowMs)
                                        + "，覆盖结束 " + atMs(row.endMs())
                                        + "，剩余 " + (row.endMs() - nowMs) + "ms");
                    }
                    int updated = repo.settleLiveOverride(overrideKey, request.requestId(), nowMs);
                    if (updated == 0) {
                        throw ApiException.conflict("LIVE_OVERRIDE_NOT_ACTIVE",
                                "直播覆盖令非 ACTIVE，不能结算: " + overrideKey);
                    }
                    repo.updateLiveSegmentDisposition(overrideKey, "MISSED_BY_LIVE",
                            request.requestId(), nowMs);
                    return toResponse(repo.findLiveOverride(overrideKey).orElseThrow());
                });
    }

    /** 锁定覆盖令及其全部频道（按频道 ID 排序），使状态推进与同频道创建按提交顺序串行。 */
    private LiveOverrideRow lockForTransition(String overrideKey) {
        LiveOverrideRow row = repo.findLiveOverride(overrideKey)
                .orElseThrow(() -> ApiException.notFound("直播覆盖令不存在: " + overrideKey));
        for (LiveOverrideChannelRow channel : repo.findLiveOverrideChannels(overrideKey)) {
            repo.lockChannelForUpdate(channel.channelId());
        }
        return repo.findLiveOverrideForUpdate(overrideKey).orElseThrow();
    }

    // ---------- 查询（只读，不改变状态） ----------

    /** 覆盖令明细：频道维度授权快照、受影响素材与撤销/结算证据。 */
    @Transactional(readOnly = true)
    public LiveOverrideResponse getLiveOverride(String overrideKey) {
        LiveOverrideRow row = repo.findLiveOverride(overrideKey)
                .orElseThrow(() -> ApiException.notFound("直播覆盖令不存在: " + overrideKey));
        return toResponse(row);
    }

    /** 频道覆盖令历史：按开始时间、覆盖键稳定排序。 */
    @Transactional(readOnly = true)
    public List<LiveOverrideSummary> listChannelHistory(String channelId) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        List<LiveOverrideSummary> history = new ArrayList<>();
        for (LiveOverrideRow row : repo.findLiveOverridesByChannel(channelId)) {
            List<String> channelIds = repo.findLiveOverrideChannels(row.overrideKey())
                    .stream().map(LiveOverrideChannelRow::channelId).toList();
            history.add(new LiveOverrideSummary(row.overrideKey(), row.liveAssetId(),
                    LiveOverrideStatus.valueOf(row.status()), atMs(row.startMs()),
                    atMs(row.endMs()), channelIds, atMs(row.createdAtMs())));
        }
        return history;
    }

    /** 覆盖令诊断：受影响素材数量、恢复结果计数与覆盖内节目时长（毫秒，实际值）。 */
    @Transactional(readOnly = true)
    public LiveOverrideDiagnostics getDiagnostics(String overrideKey) {
        LiveOverrideRow row = repo.findLiveOverride(overrideKey)
                .orElseThrow(() -> ApiException.notFound("直播覆盖令不存在: " + overrideKey));
        List<LiveOverrideSegmentRow> segments = repo.findLiveOverrideSegments(overrideKey);
        int pending = 0;
        int restored = 0;
        int missed = 0;
        Map<String, List<LiveOverrideSegmentRow>> byChannel = new LinkedHashMap<>();
        for (LiveOverrideSegmentRow segment : segments) {
            switch (segment.disposition()) {
                case "PENDING" -> pending++;
                case "RESTORED" -> restored++;
                case "MISSED_BY_LIVE" -> missed++;
                default -> throw new IllegalStateException("未知恢复结果: " + segment.disposition());
            }
            byChannel.computeIfAbsent(segment.channelId(), k -> new ArrayList<>()).add(segment);
        }
        long coveredTotal = 0;
        List<LiveOverrideChannelDiagnostics> channelDiagnostics = new ArrayList<>();
        for (Map.Entry<String, List<LiveOverrideSegmentRow>> entry : byChannel.entrySet()) {
            long covered = 0;
            for (LiveOverrideSegmentRow segment : entry.getValue()) {
                covered += Math.min(segment.endMs(), row.endMs())
                        - Math.max(segment.startMs(), row.startMs());
            }
            coveredTotal += covered;
            channelDiagnostics.add(new LiveOverrideChannelDiagnostics(
                    entry.getKey(), entry.getValue().size(), covered));
        }
        return new LiveOverrideDiagnostics(overrideKey, LiveOverrideStatus.valueOf(row.status()),
                byChannel.size(), row.endMs() - row.startMs(), segments.size(),
                pending, restored, missed, coveredTotal, channelDiagnostics);
    }

    // ---------- 内部方法 ----------

    private LiveOverrideResponse toResponse(LiveOverrideRow row) {
        List<LiveOverrideChannelResponse> channels = repo.findLiveOverrideChannels(row.overrideKey())
                .stream()
                .map(c -> new LiveOverrideChannelResponse(c.channelId(), c.grantId(),
                        atMs(c.grantValidFromMs()), atMs(c.grantValidToMs())))
                .toList();
        List<LiveOverrideSegmentResponse> segments = repo.findLiveOverrideSegments(row.overrideKey())
                .stream()
                .map(s -> new LiveOverrideSegmentResponse(s.id(), s.channelId(), s.publicationId(),
                        s.segmentId(), s.assetId(), atMs(s.startMs()), atMs(s.endMs()),
                        LiveSegmentDisposition.valueOf(s.disposition())))
                .toList();
        return new LiveOverrideResponse(row.overrideKey(), row.operator(), row.liveAssetId(),
                atMs(row.startMs()), atMs(row.endMs()), LiveOverrideStatus.valueOf(row.status()),
                channels, segments, row.revokeRequestId(),
                row.revokedAtMs() == null ? null : atMs(row.revokedAtMs()),
                row.settleRequestId(),
                row.settledAtMs() == null ? null : atMs(row.settledAtMs()),
                atMs(row.createdAtMs()));
    }

    /** 规范化频道集合：按 channelId 排序，拒绝重复频道。 */
    private static List<LiveOverrideChannelInput> sortedChannels(List<LiveOverrideChannelInput> inputs) {
        List<LiveOverrideChannelInput> sorted = inputs.stream()
                .sorted(Comparator.comparing(LiveOverrideChannelInput::channelId))
                .toList();
        Set<String> seen = new HashSet<>();
        for (LiveOverrideChannelInput input : sorted) {
            if (!seen.add(input.channelId())) {
                throw ApiException.badRequest("频道集合存在重复频道: " + input.channelId());
            }
        }
        return sorted;
    }

    /**
     * 幂等指纹：操作类型、操作者与业务参数的规范化 JSON（键序固定、集合已排序）的 SHA-256，
     * 不使用未转义分隔符拼接。
     */
    private String fingerprint(String operation, String operator, Map<String, Object> params) {
        Map<String, Object> canonical = new TreeMap<>(params);
        canonical.put("operation", operation);
        canonical.put("operator", operator);
        try {
            return sha256(objectMapper.writeValueAsString(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
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

    /** 单频道校验结果：输入、锁定后的授权与受影响素材。 */
    private record ValidatedChannel(LiveOverrideChannelInput input, GrantRow grant,
                                    List<PublicationSegmentRow> affectedSegments) {
    }
}
