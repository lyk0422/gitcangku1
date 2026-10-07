package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
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
import com.example.starter.playout.api.Dtos.DecisionSource;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.FallbackReason;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.OverrideStatus;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
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
    private static final String OP_CREATE_OVERRIDE = "CREATE_OVERRIDE";
    private static final String OP_CANCEL_OVERRIDE = "CANCEL_OVERRIDE";

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
    public GrantResponse revokeGrant(long grantId, String requestId, String operator) {
        String resource = "grant:" + grantId;
        String hash = sha256(OP_REVOKE_GRANT + "|" + resource);
        return idempotent(requestId, operator, OP_REVOKE_GRANT, resource, hash,
                GrantResponse.class, () -> {
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

        // 先规范化（含速率/源区间规则校验），再对规范化结果做幂等哈希：
        // 速率按约分值、时长按推导值参与比较，2/4 与 1/2 视为同参。
        List<NormalizedSegment> normalized = normalizeSegments(request.segments());
        String resource = channelId + "|" + businessDay;
        String hash = sha256(OP_REPLACE_DRAFT + "|" + resource
                + "|" + request.expectedDraftVersion() + "|" + canonicalSegments(normalized));
        return idempotent(request.requestId(), request.operator(), OP_REPLACE_DRAFT, resource, hash,
                DraftResponse.class, () -> {
                    List<ValidatedSegment> segments = validateSegments(channelId, businessDay, normalized);
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
                                segment.assetId(), segment.startMs(), segment.endMs(),
                                segment.sourceStartMs(), segment.sourceEndMs(),
                                segment.rateP(), segment.rateQ());
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

        String resource = channelId + "|" + businessDay;
        String hash = sha256(OP_PUBLISH + "|" + resource
                + "|" + request.draftVersion() + "|" + request.expectedPublishedVersion());
        return idempotent(request.requestId(), request.operator(), OP_PUBLISH, resource, hash,
                PublishResponse.class, () -> {
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
                // 发布冻结源区间、约分速率与授权，历史快照不随后续草稿或速率变化更新
                repo.insertPublicationSegment(publicationId, segment.id(), segment.assetId(),
                        grantIds.get(i), segment.startMs(), segment.endMs(),
                        segment.sourceStartMs(), segment.sourceEndMs(),
                        segment.rateP(), segment.rateQ());
            }
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
                    DecisionSource.EMERGENCY, null, null, null, hit.overrideKey(),
                    null, null, null);
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
                    publication.get().id(), hit.segmentId(), null, null, null, null);
        }
        // 源偏移 = 源起点 + (at - 片段播放起点) × p/q，按墙钟映射，插播期间不暂停源时间；
        // 精确分数与向下取整显示毫秒同时返回，越界判断基于精确分数而非显示值。
        long[] offset = sourceOffset(hit, atMs);
        return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(), null,
                offset[0], offset[1], offset[2]);
    }

    /**
     * 计算时刻 atMs 在已发布片段上的源内容偏移：返回 {约分分子, 约分分母, 向下取整毫秒}。
     * 精确值为 sourceStart + (atMs - startMs) × rateP / rateQ，全程整数运算。
     */
    private static long[] sourceOffset(PublicationSegmentRow segment, long atMs) {
        long numerator = segment.sourceStartMs() * segment.rateQ()
                + (atMs - segment.startMs()) * segment.rateP();
        long denominator = segment.rateQ();
        long floorMs = Math.floorDiv(numerator, denominator);
        long gcd = gcd(numerator, denominator);
        return new long[]{numerator / gcd, denominator / gcd, floorMs};
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
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
        String resource = "override:" + request.overrideKey();
        String hash = sha256(OP_CREATE_OVERRIDE + "|" + resource + "|"
                + request.channelId() + "|" + request.assetId() + "|" + request.grantId() + "|"
                + request.priority() + "|" + toMs(request.start()) + "|" + toMs(request.end()));
        return idempotent(request.requestId(), request.operator(), OP_CREATE_OVERRIDE, resource, hash,
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
    public EmergencyOverrideResponse cancelEmergencyOverride(String overrideKey, String requestId,
                                                             String operator) {
        String resource = "override:" + overrideKey;
        String hash = sha256(OP_CANCEL_OVERRIDE + "|" + resource);
        return idempotent(requestId, operator, OP_CANCEL_OVERRIDE, resource, hash,
                EmergencyOverrideResponse.class, () -> {
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

    // ---------- 内部方法 ----------

    /**
     * 规范化草稿片段输入（不访问数据库）：普通片段速率恒 1/1、源区间 [0, 播放时长)；
     * 变速片段校验速率与源区间规则并推导播放终点。结构性问题 400，速率/源区间/时长规则 422。
     */
    private List<NormalizedSegment> normalizeSegments(List<SegmentInput> inputs) {
        List<NormalizedSegment> result = new ArrayList<>();
        for (SegmentInput input : inputs) {
            if (input.start() == null) {
                throw ApiException.badRequest("片段开始时间不能为空");
            }
            if (input.assetId() == null || input.assetId().isBlank()) {
                throw ApiException.badRequest("片段素材 ID 不能为空");
            }
            long startMs = toMs(input.start());
            boolean rateSpec = input.sourceStartMs() != null || input.sourceEndMs() != null
                    || input.rateP() != null || input.rateQ() != null;
            if (!rateSpec) {
                if (input.end() == null) {
                    throw ApiException.badRequest("片段结束时间不能为空");
                }
                long endMs = toMs(input.end());
                if (endMs <= startMs) {
                    throw ApiException.badRequest("片段结束时间必须大于开始时间");
                }
                result.add(new NormalizedSegment(input.id(), input.assetId(), startMs, endMs,
                        0, endMs - startMs, 1, 1, false));
                continue;
            }
            if (input.sourceStartMs() == null || input.sourceEndMs() == null
                    || input.rateP() == null || input.rateQ() == null) {
                throw ApiException.badRequest(
                        "变速片段须同时提供 sourceStartMs/sourceEndMs/rateP/rateQ");
            }
            long sourceStart = input.sourceStartMs();
            long sourceEnd = input.sourceEndMs();
            int p = input.rateP();
            int q = input.rateQ();
            if (p < 1 || p > 10 || q < 1 || q > 10 || p * 2 < q || q * 2 < p) {
                throw ApiException.unprocessable("INVALID_RATE",
                        "速率 p/q 须为 1～10 整数且比率在 1/2 至 2 之间: " + p + "/" + q);
            }
            if (sourceStart < 0 || sourceEnd <= sourceStart) {
                throw ApiException.unprocessable("INVALID_SOURCE_INTERVAL",
                        "源区间须满足 0 <= sourceStartMs < sourceEndMs: "
                                + sourceStart + " ~ " + sourceEnd);
            }
            long sourceLen = sourceEnd - sourceStart;
            if (sourceLen * q % p != 0) {
                throw ApiException.unprocessable("NON_INTEGER_DURATION",
                        "播放时长（源长度乘 q 除 p）不是正整数毫秒: " + sourceLen + "*" + q + "/" + p);
            }
            long durationMs = sourceLen * q / p;
            long endMs = startMs + durationMs;
            if (input.end() != null && toMs(input.end()) != endMs) {
                throw ApiException.unprocessable("INVALID_PLAY_INTERVAL",
                        "片段结束时间与速率推导时长不一致，期望结束偏移 " + durationMs + " 毫秒");
            }
            int g = (int) gcd(p, q);
            result.add(new NormalizedSegment(input.id(), input.assetId(), startMs, endMs,
                    sourceStart, sourceEnd, p / g, q / g, true));
        }
        return result;
    }

    /** 校验并规范化草稿片段：非空、区间内、不跨日、不重叠、素材存在且被单条未撤销授权完整覆盖。 */
    private List<ValidatedSegment> validateSegments(String channelId, LocalDate businessDay,
                                                    List<NormalizedSegment> inputs) {
        long dayStartMs = businessDay.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEndMs = businessDay.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<ValidatedSegment> segments = new ArrayList<>();
        for (NormalizedSegment input : inputs) {
            if (input.startMs() < dayStartMs || input.endMs() > dayEndMs) {
                throw ApiException.unprocessable("SEGMENT_CROSSES_DAY",
                        "片段不得跨业务日: " + atMs(input.startMs()) + " ~ " + atMs(input.endMs()));
            }
            var asset = repo.findAsset(input.assetId())
                    .orElseThrow(() -> ApiException.notFound("素材不存在: " + input.assetId()));
            if (input.explicitRate()) {
                // 仅变速片段校验源区间与播放时长边界；普通片段速率恒 1，不做该限制
                if (input.sourceEndMs() > asset.durationMs()) {
                    throw ApiException.unprocessable("INVALID_SOURCE_INTERVAL",
                            "源区间超出素材总长 " + asset.durationMs() + " 毫秒: " + input.assetId());
                }
                if (input.endMs() - input.startMs() > asset.durationMs()) {
                    throw ApiException.unprocessable("DURATION_EXCEEDS_ASSET",
                            "播放时长不得超过素材原总长 " + asset.durationMs() + " 毫秒: "
                                    + input.assetId());
                }
            }
            String id = input.id() == null || input.id().isBlank()
                    ? UUID.randomUUID().toString() : input.id();
            segments.add(new ValidatedSegment(id, input.assetId(), input.startMs(), input.endMs(),
                    input.sourceStartMs(), input.sourceEndMs(), input.rateP(), input.rateQ()));
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
     * 幂等执行：去重记录与业务结果同事务提交；同 requestId 按操作、资源、全参数哈希结构化比较，
     * 三者一致返回首次完整结果，任一不同返回 409；业务失败抛异常回滚，不占用 requestId；
     * 并发同键由唯一约束串行化，读取已提交记录后按同规则比较，不报唯一键 500。
     */
    private <T> T idempotent(String requestId, String operator, String operation, String resource,
                             String paramsHash, Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("operator 不能为空");
        }
        Optional<RequestRow> existing = repo.findRequestForUpdate(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, resource, paramsHash, type);
        }
        try {
            repo.insertRequest(requestId, operator, operation, resource, paramsHash, nowMs());
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：等待对方事务结束后读取已提交记录
            RequestRow committed = repo.findRequestForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                            "requestId 并发冲突: " + requestId));
            return replay(committed, operation, resource, paramsHash, type);
        }
        T result = action.get();
        try {
            repo.completeRequest(requestId, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
        return result;
    }

    private <T> T replay(RequestRow row, String operation, String resource, String paramsHash,
                         Class<T> type) {
        if (!row.operation().equals(operation) || !row.resource().equals(resource)
                || !row.paramsHash().equals(paramsHash)) {
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

    /** 规范化片段的幂等参数串：速率已约分、时长为推导值，文本原样拼接。 */
    private static String canonicalSegments(List<NormalizedSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (NormalizedSegment s : segments) {
            sb.append(s.id() == null ? "" : s.id()).append(',')
                    .append(s.assetId()).append(',')
                    .append(s.startMs()).append(',')
                    .append(s.endMs()).append(',')
                    .append(s.sourceStartMs()).append(',')
                    .append(s.sourceEndMs()).append(',')
                    .append(s.rateP()).append(',')
                    .append(s.rateQ()).append(';');
        }
        return sb.toString();
    }

    private PlayoutDecisionResponse fallback(String channelId, OffsetDateTime at, String assetId,
                                             FallbackReason reason) {
        return new PlayoutDecisionResponse(channelId, at, assetId,
                DecisionSource.FALLBACK, reason, null, null, null, null, null, null);
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

    /** 规范化后的片段输入：播放区间、源区间与约分速率均已确定。 */
    private record NormalizedSegment(String id, String assetId, long startMs, long endMs,
                                     long sourceStartMs, long sourceEndMs, int rateP, int rateQ,
                                     boolean explicitRate) {
    }

    /** 校验后的片段。 */
    private record ValidatedSegment(String id, String assetId, long startMs, long endMs,
                                    long sourceStartMs, long sourceEndMs, int rateP, int rateQ) {
        SegmentResponse toResponse() {
            return new SegmentResponse(id, assetId, atMs(startMs), atMs(endMs),
                    sourceStartMs, sourceEndMs, rateP, rateQ);
        }
    }
}
