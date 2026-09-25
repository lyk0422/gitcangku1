package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.PublicationSegmentRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.PlayoutRepository.SimulcastGroupRow;
import com.example.starter.playout.PlayoutRepository.SimulcastMemberRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateSimulcastLockRequest;
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
import com.example.starter.playout.api.Dtos.SimulcastChannelFailure;
import com.example.starter.playout.api.Dtos.SimulcastLockResponse;
import com.example.starter.playout.api.Dtos.SimulcastPlaceholderResponse;
import com.example.starter.playout.api.Dtos.SimulcastRevocationResponse;
import com.example.starter.playout.api.Dtos.SimulcastStatus;
import com.example.starter.playout.api.SimulcastLockException;
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
import java.time.format.DateTimeParseException;
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
    private static final String OP_CREATE_SIMULCAST = "CREATE_SIMULCAST";
    private static final String OP_REVOKE_SIMULCAST = "REVOKE_SIMULCAST";

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
            // 锁定频道行：与联播创建/撤销、插播创建按提交顺序串行。
            repo.lockChannelForUpdate(channelId);
            List<SimulcastMemberRow> members =
                    repo.findActiveMembersForChannelDay(channelId, businessDay);
            Map<String, SimulcastMemberRow> memberBySegmentId = new LinkedHashMap<>();
            for (SimulcastMemberRow member : members) {
                memberBySegmentId.put(member.placeholderSegmentId(), member);
            }
            List<SegmentInput> normalInputs = new ArrayList<>();
            Map<String, SegmentInput> placeholderInputs = new LinkedHashMap<>();
            for (SegmentInput input : request.segments()) {
                if (input.id() != null && memberBySegmentId.containsKey(input.id())) {
                    placeholderInputs.put(input.id(), input);
                } else {
                    normalInputs.add(input);
                }
            }
            // 联播占位不可独立修改：必须原样携带，删除或改时（含改素材）返回 409。
            for (SimulcastMemberRow member : members) {
                SegmentInput echo = placeholderInputs.get(member.placeholderSegmentId());
                if (echo == null) {
                    throw ApiException.conflict("SIMULCAST_PLACEHOLDER_REQUIRED",
                            "草稿必须包含联播占位片段: " + member.placeholderSegmentId());
                }
                if (!member.assetId().equals(echo.assetId())
                        || echo.start() == null || echo.end() == null
                        || toMs(echo.start()) != member.atMs() || toMs(echo.end()) != member.atMs()) {
                    throw ApiException.conflict("SIMULCAST_PLACEHOLDER_MODIFIED",
                            "联播占位片段不可修改素材或时刻: " + member.placeholderSegmentId());
                }
            }
            List<ValidatedSegment> segments = validateSegments(channelId, businessDay, normalInputs);
            // 普通片段不得覆盖联播占位时刻。
            for (ValidatedSegment segment : segments) {
                for (SimulcastMemberRow member : members) {
                    if (segment.startMs() <= member.atMs() && member.atMs() < segment.endMs()) {
                        throw ApiException.unprocessable("SIMULCAST_MOMENT_OCCUPIED",
                                "片段覆盖了联播占位时刻: " + segment.id());
                    }
                }
            }
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
                    segments.stream().map(ValidatedSegment::toResponse).toList(),
                    members.stream().map(PlayoutService::toPlaceholderResponse).toList());
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
            // 发布必须包含当前生效的联播占位：以固化的每频道授权版本写入只读快照，
            // 撤销联播组不改写已发布快照。
            for (SimulcastMemberRow member : repo.findActiveMembersForChannelDay(channelId, businessDay)) {
                repo.insertPublicationSegment(publicationId, member.placeholderSegmentId(),
                        member.assetId(), member.grantId(), member.atMs(), member.atMs());
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

        // 插播区间不得覆盖生效中的联播占位时刻（持频道锁，与联播创建/撤销按提交顺序串行）。
        if (!repo.findActiveMembersInRange(request.channelId(), startMs, endMs).isEmpty()) {
            throw ApiException.unprocessable("SIMULCAST_MOMENT_OCCUPIED",
                    "插播区间覆盖联播占位时刻: " + request.channelId());
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

    // ---------- 联播锁定 ----------

    /**
     * 创建联播锁定：同一业务日 2～8 个频道共用同一素材与计划播出时刻。一个事务内逐频道校验
     * 草稿存在、时刻未被片段/插播/其他联播占位占用、素材授权覆盖该频道该时刻；任一频道不满足
     * 则整次 422 并返回逐频道原因，不写入任何锁定。全部通过后写入同一联播组并为每个频道写入
     * 不可独立修改的联播占位，固化频道集合、素材、时刻与每频道授权版本。
     * 携带 requestId 幂等：同键同参返回首次结果，改参 409，失败不占键。
     */
    @Transactional
    public SimulcastLockResponse createSimulcastLock(CreateSimulcastLockRequest request) {
        LocalDate businessDay = parseDay(request.businessDay());
        long atMs = toMs(request.at());
        if (!request.at().atZoneSameInstant(ZONE).toLocalDate().equals(businessDay)) {
            throw ApiException.badRequest("计划播出时刻必须落在业务日内: " + businessDay);
        }
        List<String> channelIds = request.channelIds().stream().distinct().sorted().toList();
        if (channelIds.size() != request.channelIds().size()) {
            throw ApiException.badRequest("联播频道列表存在重复频道");
        }
        if (channelIds.size() < 2 || channelIds.size() > 8) {
            throw ApiException.badRequest("联播频道数须在 2～8 之间");
        }
        String hash = sha256(OP_CREATE_SIMULCAST + "|" + request.simulcastKey() + "|" + businessDay
                + "|" + String.join(",", channelIds) + "|" + request.assetId() + "|" + atMs);
        return idempotent(request.requestId(), OP_CREATE_SIMULCAST, hash, SimulcastLockResponse.class,
                () -> doCreateSimulcastLock(request, businessDay, atMs, channelIds));
    }

    private SimulcastLockResponse doCreateSimulcastLock(CreateSimulcastLockRequest request,
                                                        LocalDate businessDay, long atMs,
                                                        List<String> channelIds) {
        if (repo.findAsset(request.assetId()).isEmpty()) {
            throw ApiException.notFound("素材不存在: " + request.assetId());
        }
        // 同键语义：已存在（含已撤销）即冲突，撤销后 simulcastKey 不可复用；行锁串行并发同键创建。
        if (repo.findSimulcastGroupForUpdate(request.simulcastKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_SIMULCAST_KEY",
                    "联播组 simulcastKey 已存在: " + request.simulcastKey());
        }
        // 按字典序锁定全部频道行：与单频道草稿替换、插播创建串行，多组并发创建同序加锁避免死锁。
        for (String channelId : channelIds) {
            repo.lockChannelForUpdate(channelId);
        }

        List<SimulcastChannelFailure> failures = new ArrayList<>();
        Map<String, GrantRow> selectedGrants = new LinkedHashMap<>();
        for (String channelId : channelIds) {
            if (repo.findChannel(channelId).isEmpty()) {
                failures.add(new SimulcastChannelFailure(channelId, "CHANNEL_NOT_FOUND",
                        "频道不存在: " + channelId));
                continue;
            }
            if (repo.findDraft(channelId, businessDay).isEmpty()) {
                failures.add(new SimulcastChannelFailure(channelId, "DRAFT_NOT_FOUND",
                        "频道在该业务日无可用草稿: " + channelId));
                continue;
            }
            boolean occupiedBySegment = repo.findDraftSegments(channelId, businessDay).stream()
                    .anyMatch(s -> s.startMs() <= atMs && atMs < s.endMs());
            if (occupiedBySegment) {
                failures.add(new SimulcastChannelFailure(channelId, "SEGMENT_OCCUPIED",
                        "该时刻已被草稿片段占用: " + channelId));
                continue;
            }
            if (!repo.findActiveOverridesAt(channelId, atMs).isEmpty()) {
                failures.add(new SimulcastChannelFailure(channelId, "OVERRIDE_OCCUPIED",
                        "该时刻已被生效中的紧急插播占用: " + channelId));
                continue;
            }
            if (repo.existsActiveMemberAt(channelId, businessDay, atMs)) {
                failures.add(new SimulcastChannelFailure(channelId, "SIMULCAST_OCCUPIED",
                        "该时刻已被其他联播组占位: " + channelId));
                continue;
            }
            // 锁定覆盖该时刻的授权行：与授权撤销按提交顺序串行，撤销先提交则此处判为无有效授权。
            Optional<GrantRow> grant = repo.findCoveringGrantsForUpdate(channelId, request.assetId(),
                            atMs, atMs + 1).stream()
                    .filter(g -> !g.revoked())
                    .findFirst();
            if (grant.isEmpty()) {
                failures.add(new SimulcastChannelFailure(channelId, "NO_COVERING_GRANT",
                        "素材授权未覆盖该频道该时刻: " + channelId));
                continue;
            }
            selectedGrants.put(channelId, grant.get());
        }
        if (!failures.isEmpty()) {
            throw new SimulcastLockException("SIMULCAST_VALIDATION_FAILED",
                    "联播锁定校验失败，" + failures.size() + " 个频道不满足条件", failures);
        }

        long createdAtMs = nowMs();
        try {
            repo.insertSimulcastGroup(request.simulcastKey(), businessDay, request.assetId(),
                    atMs, channelIds.size(), createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_SIMULCAST_KEY",
                    "联播组 simulcastKey 已存在: " + request.simulcastKey());
        }
        List<SimulcastPlaceholderResponse> placeholders = new ArrayList<>();
        for (String channelId : channelIds) {
            String placeholderSegmentId = UUID.randomUUID().toString();
            GrantRow grant = selectedGrants.get(channelId);
            try {
                repo.insertSimulcastMember(request.simulcastKey(), channelId, businessDay,
                        placeholderSegmentId, grant.id(), request.assetId(), atMs, createdAtMs);
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底：同频道同时刻已被其他联播组占用（正常路径已被频道锁串行拦截）
                throw new SimulcastLockException("SIMULCAST_VALIDATION_FAILED",
                        "联播锁定校验失败，1 个频道不满足条件",
                        List.of(new SimulcastChannelFailure(channelId, "SIMULCAST_OCCUPIED",
                                "该时刻已被其他联播组占位: " + channelId)));
            }
            placeholders.add(new SimulcastPlaceholderResponse(request.simulcastKey(), channelId,
                    placeholderSegmentId, request.assetId(), request.at(), grant.id()));
        }
        return new SimulcastLockResponse(request.simulcastKey(), businessDay.toString(),
                request.assetId(), request.at(), SimulcastStatus.ACTIVE, placeholders,
                null, null, atMs(createdAtMs));
    }

    /**
     * 撤销整个联播组：须在计划播出时刻尚未到来前进行（now < at），开始后返回 409；
     * 同事务写入不可变撤销历史并释放全部频道占位，不改写已发布快照。
     * 携带 requestId 幂等：同键同参返回首次结果，改参 409，失败不占键。
     */
    @Transactional
    public SimulcastLockResponse revokeSimulcastLock(String simulcastKey, String requestId) {
        String hash = sha256(OP_REVOKE_SIMULCAST + "|" + simulcastKey);
        return idempotent(requestId, OP_REVOKE_SIMULCAST, hash, SimulcastLockResponse.class, () -> {
            SimulcastGroupRow group = repo.findSimulcastGroupForUpdate(simulcastKey)
                    .orElseThrow(() -> ApiException.notFound("联播组不存在: " + simulcastKey));
            if (!group.active()) {
                throw ApiException.conflict("SIMULCAST_ALREADY_REVOKED",
                        "联播组已撤销: " + simulcastKey);
            }
            long now = nowMs();
            if (now >= group.atMs()) {
                throw ApiException.conflict("SIMULCAST_ALREADY_STARTED",
                        "联播已开始播出，不能撤销: " + simulcastKey);
            }
            int updated = repo.revokeSimulcastGroup(simulcastKey, requestId, now);
            if (updated == 0) {
                throw ApiException.conflict("SIMULCAST_ALREADY_REVOKED",
                        "联播组已撤销: " + simulcastKey);
            }
            // 按字典序锁定全部频道行，与草稿替换/插播创建按提交顺序串行后，同事务释放全部占位。
            repo.findSimulcastMembers(simulcastKey).stream()
                    .map(SimulcastMemberRow::channelId)
                    .sorted()
                    .forEach(repo::lockChannelForUpdate);
            repo.insertSimulcastRevocation(simulcastKey, requestId, group.channelCount(), now);
            // 同事务释放全部频道占位：任何时刻不得只留下部分频道占位。
            repo.deleteSimulcastMembers(simulcastKey);
            return new SimulcastLockResponse(simulcastKey, group.businessDay().toString(),
                    group.assetId(), atMs(group.atMs()), SimulcastStatus.REVOKED, List.of(),
                    requestId, atMs(now), atMs(group.createdAtMs()));
        });
    }

    /** 查询联播组：ACTIVE 时随附全部频道占位，REVOKED 时占位已释放并随附撤销情况。 */
    @Transactional(readOnly = true)
    public SimulcastLockResponse getSimulcastLock(String simulcastKey) {
        SimulcastGroupRow group = repo.findSimulcastGroup(simulcastKey)
                .orElseThrow(() -> ApiException.notFound("联播组不存在: " + simulcastKey));
        List<SimulcastPlaceholderResponse> placeholders = repo.findSimulcastMembers(simulcastKey)
                .stream().map(PlayoutService::toPlaceholderResponse).toList();
        return new SimulcastLockResponse(simulcastKey, group.businessDay().toString(),
                group.assetId(), atMs(group.atMs()),
                group.active() ? SimulcastStatus.ACTIVE : SimulcastStatus.REVOKED, placeholders,
                group.revokeRequestId(),
                group.revokedAtMs() == null ? null : atMs(group.revokedAtMs()),
                atMs(group.createdAtMs()));
    }

    /** 查询频道当前生效的联播占位；businessDay 为 null 时返回全部业务日。 */
    @Transactional(readOnly = true)
    public List<SimulcastPlaceholderResponse> listChannelPlaceholders(String channelId,
                                                                      LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        return repo.findChannelPlaceholders(channelId, businessDay).stream()
                .map(PlayoutService::toPlaceholderResponse).toList();
    }

    /** 查询联播撤销历史；simulcastKey 为 null 时返回全部，只追加不改写。 */
    @Transactional(readOnly = true)
    public List<SimulcastRevocationResponse> listSimulcastRevocations(String simulcastKey) {
        return repo.findSimulcastRevocations(simulcastKey).stream()
                .map(r -> new SimulcastRevocationResponse(r.simulcastKey(), r.revokeRequestId(),
                        r.channelCount(), atMs(r.revokedAtMs())))
                .toList();
    }

    private static SimulcastPlaceholderResponse toPlaceholderResponse(SimulcastMemberRow member) {
        return new SimulcastPlaceholderResponse(member.simulcastKey(), member.channelId(),
                member.placeholderSegmentId(), member.assetId(), atMs(member.atMs()),
                member.grantId());
    }

    private static LocalDate parseDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
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
