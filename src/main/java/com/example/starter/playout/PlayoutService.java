package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.PlayoutRepository.TransitionRow;
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
import com.example.starter.playout.api.Dtos.MixDecision;
import com.example.starter.playout.api.Dtos.MixSegment;
import com.example.starter.playout.api.Dtos.OverrideStatus;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.SegmentInput;
import com.example.starter.playout.api.Dtos.SegmentResponse;
import com.example.starter.playout.api.Dtos.TransitionInput;
import com.example.starter.playout.api.Dtos.TransitionResponse;
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

    /** 紧急插播时长上限（含）：30 分钟，单位毫秒。 */
    private static final long OVERRIDE_MAX_DURATION_MS = 30L * 60L * 1000L;

    /** 交叉淡化时长上限（含）：10 秒，单位毫秒。 */
    private static final long CROSSFADE_MAX_FADE_MS = 10_000L;

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
                + "|" + canonicalDraftParams(request));
        return idempotent(request.requestId(), OP_REPLACE_DRAFT, hash, DraftResponse.class, () -> {
            List<ValidatedSegment> segments = validateSegments(channelId, businessDay, request.segments());
            List<ValidatedTransition> transitions = validateTransitions(request.transitions(), segments);
            validateSegmentGrants(channelId, segments);
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
            repo.deleteDraftTransitions(channelId, businessDay);
            for (ValidatedSegment segment : segments) {
                repo.insertDraftSegment(segment.id(), channelId, businessDay,
                        segment.assetId(), segment.startMs(), segment.endMs());
            }
            for (ValidatedTransition transition : transitions) {
                repo.insertDraftTransition(transition.id(), channelId, businessDay,
                        transition.fromSegmentId(), transition.toSegmentId(), transition.fadeMs());
            }
            return new DraftResponse(channelId, businessDay.toString(), newVersion,
                    segments.stream().map(ValidatedSegment::toResponse).toList(),
                    transitions.stream().map(ValidatedTransition::toResponse).toList());
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
            // 交叉淡化随发布冻结进快照，历史不因草稿后续替换而重算
            for (TransitionRow transition : repo.findDraftTransitions(channelId, businessDay)) {
                repo.insertPublicationTransition(publicationId, transition.fromSegmentId(),
                        transition.toSegmentId(), transition.fadeMs());
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
                    DecisionSource.EMERGENCY, null, null, null, hit.overrideKey(), null);
        }

        LocalDate businessDay = at.atZoneSameInstant(ZONE).toLocalDate();

        Optional<PublicationRow> publication = repo.findLatestPublication(channelId, businessDay);
        if (publication.isEmpty()) {
            return fallback(channelId, at, channel.fallbackAssetId(),
                    FallbackReason.NO_PUBLISHED_SCHEDULE);
        }
        // 交叉淡化重叠区优先于单片段判定：重叠区内必须给出整组混音决策
        Optional<PlayoutDecisionResponse> mix =
                mixDecisionAt(channelId, at, atMs, publication.get(), channel.fallbackAssetId());
        if (mix.isPresent()) {
            return mix.get();
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
                    publication.get().id(), hit.segmentId(), null, null);
        }
        return new PlayoutDecisionResponse(channelId, at, hit.assetId(),
                DecisionSource.PROGRAM, null,
                publication.get().id(), hit.segmentId(), null, null);
    }

    /**
     * 时刻落在某条已发布交叉淡化的重叠区 [后片段开始, 前片段结束) 时，返回整组混音决策：
     * 重叠进度 u 从 0（左端）线性趋近 1（右端），前素材增益 1-u、后素材增益 u。
     * 任一重叠素材的发布授权已撤销时，整组回退频道保底素材（CROSSFADE_GRANT_REVOKED），
     * 不能只播另一条。不在任何重叠区时返回空。
     */
    private Optional<PlayoutDecisionResponse> mixDecisionAt(String channelId, OffsetDateTime at,
                                                            long atMs, PublicationRow publication,
                                                            String fallbackAssetId) {
        List<TransitionRow> transitions = repo.findPublicationTransitions(publication.id());
        for (TransitionRow transition : transitions) {
            PublicationSegmentRow next = repo.findPublicationSegmentBySegmentId(
                    publication.id(), transition.toSegmentId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照缺少淡化后片段: " + transition.toSegmentId()));
            PublicationSegmentRow prev = repo.findPublicationSegmentBySegmentId(
                    publication.id(), transition.fromSegmentId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照缺少淡化前片段: " + transition.fromSegmentId()));
            long overlapStartMs = next.startMs();
            long overlapEndMs = prev.endMs();
            if (atMs < overlapStartMs || atMs >= overlapEndMs) {
                continue;
            }
            GrantRow prevGrant = repo.findGrant(prev.grantId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照引用的授权不存在: " + prev.grantId()));
            GrantRow nextGrant = repo.findGrant(next.grantId())
                    .orElseThrow(() -> ApiException.unprocessable("SNAPSHOT_INCOMPLETE",
                            "发布快照引用的授权不存在: " + next.grantId()));
            if (prevGrant.revoked() || nextGrant.revoked()) {
                return Optional.of(new PlayoutDecisionResponse(channelId, at, fallbackAssetId,
                        DecisionSource.FALLBACK, FallbackReason.CROSSFADE_GRANT_REVOKED,
                        publication.id(), null, null, null));
            }
            double u = (double) (atMs - overlapStartMs) / (double) transition.fadeMs();
            MixDecision mix = new MixDecision(
                    new MixSegment(prev.segmentId(), prev.assetId(), prev.grantId(), 1.0 - u),
                    new MixSegment(next.segmentId(), next.assetId(), next.grantId(), u),
                    u);
            return Optional.of(new PlayoutDecisionResponse(channelId, at, null,
                    DecisionSource.MIX, null, publication.id(), null, null, mix));
        }
        return Optional.empty();
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

    // ---------- 内部方法 ----------

    /** 校验并规范化草稿片段基础字段：非空、终点大于起点、不跨日；重叠规则见 validateTransitions。 */
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
        return segments;
    }

    /**
     * 校验交叉淡化并检查片段重叠规则：普通草稿（无淡化声明）保持任意片段不得重叠；
     * 声明淡化时要求前后片段在时间上相邻、后一片段开始 = 前一片段结束 - 淡化时长、
     * 淡化时长 0~10 秒且不超过两片段任一时长、任意时刻不得三片段同时存在；
     * 参与淡化的片段时长必须等于已登记素材的整秒时长。
     */
    private List<ValidatedTransition> validateTransitions(List<TransitionInput> inputs,
                                                          List<ValidatedSegment> segments) {
        List<TransitionInput> declared = inputs == null ? List.of() : inputs;
        Map<String, ValidatedSegment> byId = new HashMap<>();
        for (ValidatedSegment segment : segments) {
            byId.put(segment.id(), segment);
        }

        List<ValidatedTransition> transitions = new ArrayList<>();
        Map<String, ValidatedTransition> byNextId = new HashMap<>();
        for (TransitionInput input : declared) {
            if (input.fromSegmentId() == null || input.fromSegmentId().isBlank()
                    || input.toSegmentId() == null || input.toSegmentId().isBlank()
                    || input.durationMs() == null) {
                throw ApiException.badRequest("淡化的前后片段与时长不能为空");
            }
            ValidatedSegment prev = byId.get(input.fromSegmentId());
            ValidatedSegment next = byId.get(input.toSegmentId());
            if (prev == null || next == null) {
                throw ApiException.badRequest("淡化引用的片段不在本草稿中: "
                        + input.fromSegmentId() + " -> " + input.toSegmentId());
            }
            if (prev.id().equals(next.id())) {
                throw ApiException.badRequest("淡化前后片段不能相同: " + prev.id());
            }
            long fadeMs = input.durationMs();
            if (fadeMs < 0 || fadeMs > CROSSFADE_MAX_FADE_MS) {
                throw ApiException.unprocessable("CROSSFADE_TOO_LONG",
                        "淡化时长须在 0~10000 毫秒之间: " + fadeMs);
            }
            if (byNextId.containsKey(next.id())) {
                throw ApiException.badRequest("同一片段被重复声明为淡化后片段: " + next.id());
            }
            String id = input.id() == null || input.id().isBlank()
                    ? UUID.randomUUID().toString() : input.id();
            ValidatedTransition transition =
                    new ValidatedTransition(id, prev.id(), next.id(), fadeMs);
            transitions.add(transition);
            byNextId.put(next.id(), transition);
        }

        List<ValidatedSegment> sorted = segments.stream()
                .sorted(Comparator.comparingLong(ValidatedSegment::startMs)
                        .thenComparing(ValidatedSegment::id))
                .toList();
        long maxEndBeforePrev = Long.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            ValidatedSegment current = sorted.get(i);
            if (i >= 2 && current.startMs() < maxEndBeforePrev) {
                throw ApiException.unprocessable("CROSSFADE_TRIPLE_OVERLAP",
                        "任意时刻不得有三个片段同时存在: " + current.id());
            }
            ValidatedTransition incoming = byNextId.get(current.id());
            if (i == 0) {
                if (incoming != null) {
                    throw ApiException.unprocessable("TRANSITION_NOT_ADJACENT",
                            "淡化前片段不是后片段的时间前驱: " + incoming.fromSegmentId()
                                    + " -> " + incoming.toSegmentId());
                }
            } else {
                ValidatedSegment predecessor = sorted.get(i - 1);
                if (incoming != null && !incoming.fromSegmentId().equals(predecessor.id())) {
                    throw ApiException.unprocessable("TRANSITION_NOT_ADJACENT",
                            "淡化前片段不是后片段的时间前驱: " + incoming.fromSegmentId()
                                    + " -> " + incoming.toSegmentId());
                }
                if (incoming != null) {
                    if (current.startMs() != predecessor.endMs() - incoming.fadeMs()) {
                        throw ApiException.unprocessable("CROSSFADE_TIMING_MISMATCH",
                                "后一片段开始须等于前一片段结束减淡化时长: " + incoming.toSegmentId());
                    }
                    if (incoming.fadeMs() > predecessor.durationMs()
                            || incoming.fadeMs() > current.durationMs()) {
                        throw ApiException.unprocessable("CROSSFADE_TOO_LONG",
                                "淡化时长不得超过两片段任一时长: " + incoming.toSegmentId());
                    }
                } else if (current.startMs() < predecessor.endMs()) {
                    throw ApiException.unprocessable("SEGMENT_OVERLAP",
                            "片段时间重叠且未声明交叉淡化: " + predecessor.id() + " 与 " + current.id());
                }
                maxEndBeforePrev = Math.max(maxEndBeforePrev, predecessor.endMs());
            }
        }

        // 混音草稿：参与淡化的片段时长取已登记素材的整秒时长
        for (ValidatedTransition transition : transitions) {
            for (String segmentId : List.of(transition.fromSegmentId(), transition.toSegmentId())) {
                ValidatedSegment segment = byId.get(segmentId);
                var asset = repo.findAsset(segment.assetId())
                        .orElseThrow(() -> ApiException.notFound("素材不存在: " + segment.assetId()));
                if (asset.durationMs() % 1000 != 0) {
                    throw ApiException.unprocessable("ASSET_DURATION_NOT_WHOLE_SECONDS",
                            "参与淡化的素材时长须为整秒: " + segment.assetId());
                }
                if (segment.durationMs() != asset.durationMs()) {
                    throw ApiException.unprocessable("SEGMENT_DURATION_MISMATCH",
                            "参与淡化的片段时长须等于素材登记时长: " + segmentId);
                }
            }
        }
        return transitions;
    }

    /** 校验片段素材存在且被单条未撤销授权完整覆盖（覆盖含淡化重叠区在内的完整实际播放区间）。 */
    private void validateSegmentGrants(String channelId, List<ValidatedSegment> segments) {
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
     * 草稿参数的结构化规范化形式（JSON）：包含期望版本、片段与淡化全部参数，
     * 用于幂等参数哈希，避免分隔符拼接造成参数碰撞。
     */
    private String canonicalDraftParams(ReplaceDraftRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expectedDraftVersion", request.expectedDraftVersion());
        List<Map<String, Object>> segmentParams = new ArrayList<>();
        for (SegmentInput segment : request.segments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", segment.id());
            item.put("assetId", segment.assetId());
            item.put("startMs", segment.start() == null ? null : toMs(segment.start()));
            item.put("endMs", segment.end() == null ? null : toMs(segment.end()));
            segmentParams.add(item);
        }
        params.put("segments", segmentParams);
        List<Map<String, Object>> transitionParams = new ArrayList<>();
        for (TransitionInput transition
                : request.transitions() == null ? List.<TransitionInput>of() : request.transitions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", transition.id());
            item.put("fromSegmentId", transition.fromSegmentId());
            item.put("toSegmentId", transition.toSegmentId());
            item.put("durationMs", transition.durationMs());
            transitionParams.add(item);
        }
        params.put("transitions", transitionParams);
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("草稿参数序列化失败", e);
        }
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
        long durationMs() {
            return endMs - startMs;
        }

        SegmentResponse toResponse() {
            return new SegmentResponse(id, assetId, atMs(startMs), atMs(endMs));
        }
    }

    /** 校验后的交叉淡化。 */
    private record ValidatedTransition(String id, String fromSegmentId, String toSegmentId,
                                       long fadeMs) {
        TransitionResponse toResponse() {
            return new TransitionResponse(id, fromSegmentId, toSegmentId, fadeMs);
        }
    }
}
