package com.example.starter.playout;

import com.example.starter.playout.PlayoutRepository.AssetRow;
import com.example.starter.playout.PlayoutRepository.DraftRow;
import com.example.starter.playout.PlayoutRepository.GrantRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.SegmentRow;
import com.example.starter.playout.PlayoutRepository.SimulcastGroupRow;
import com.example.starter.playout.PlayoutRepository.SimulcastPlaceholderRow;
import com.example.starter.playout.PlayoutRepository.SimulcastRevocationRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.ChannelSimulcastPlaceholdersResponse;
import com.example.starter.playout.api.Dtos.CreateSimulcastLockRequest;
import com.example.starter.playout.api.Dtos.SimulcastChannelEntry;
import com.example.starter.playout.api.Dtos.SimulcastLockResponse;
import com.example.starter.playout.api.Dtos.SimulcastPlaceholderItem;
import com.example.starter.playout.api.Dtos.SimulcastPlaceholderStatus;
import com.example.starter.playout.api.Dtos.SimulcastRejection;
import com.example.starter.playout.api.Dtos.SimulcastRevocationHistoryResponse;
import com.example.starter.playout.api.Dtos.SimulcastRevocationItem;
import com.example.starter.playout.api.Dtos.SimulcastStatus;
import com.example.starter.playout.api.SimulcastValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 多频道联播锁定服务：联播组创建、整组原子撤销与查询。
 *
 * <p>创建时在一个事务内逐频道校验：频道存在且当日有可用草稿、计划时刻未被草稿素材或
 * ACTIVE 紧急插播占用、素材授权完整覆盖每个频道与占位区间 [plannedAt, plannedAt+素材时长)；
 * 任一频道不满足即整次 422 并返回逐频道原因，不写入任何锁定。成功后写入同一联播组记录，
 * 并在各频道草稿加入不可独立修改的联播占位片段（草稿版本随之递增），固化频道集合、素材、
 * 时刻与每频道授权（grantId）。撤销为整组原子操作：须在计划播出时刻之前进行，同时释放全部
 * 频道占位并写入不可变撤销历史，不改写已发布快照。并发按事务提交顺序裁决：频道行锁与
 * 授权行锁保证任何时刻不会只留下部分频道占位。</p>
 */
@Service
public class SimulcastService {

    /** 业务时区，与播出编排一致。 */
    public static final ZoneId ZONE = PlayoutService.ZONE;

    private static final String OP_SIMULCAST_CREATE = "SIMULCAST_CREATE";
    private static final String OP_SIMULCAST_REVOKE = "SIMULCAST_REVOKE";

    private final PlayoutRepository repo;
    private final IdempotentExecutor idempotency;
    private final Clock clock;

    public SimulcastService(PlayoutRepository repo, IdempotentExecutor idempotency, Clock clock) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 创建联播锁定 ----------

    /**
     * 创建联播锁定：同 requestId 同参数重放首次结果，改参 409，失败不占键。
     * simulcastKey 全局唯一（含已撤销组），重复返回 409。
     */
    @Transactional
    public SimulcastLockResponse createSimulcastLock(CreateSimulcastLockRequest request) {
        List<String> channelIds = request.channelIds();
        if (channelIds.size() != new TreeSet<>(channelIds).size()) {
            throw ApiException.badRequest("频道列表存在重复频道");
        }
        AssetRow asset = repo.findAsset(request.assetId())
                .orElseThrow(() -> ApiException.notFound("素材不存在: " + request.assetId()));
        long plannedAtMs = toMs(request.plannedAt());
        long endMs = plannedAtMs + asset.durationMs();
        LocalDate businessDay = request.plannedAt().atZoneSameInstant(ZONE).toLocalDate();
        List<String> sortedChannels = channelIds.stream().sorted().toList();

        String hash = sha256(OP_SIMULCAST_CREATE + "|" + request.simulcastKey() + "|"
                + request.assetId() + "|" + plannedAtMs + "|" + String.join(",", sortedChannels));
        return idempotency.execute(request.requestId(), OP_SIMULCAST_CREATE, hash,
                SimulcastLockResponse.class,
                () -> doCreate(request, asset, plannedAtMs, endMs, businessDay, sortedChannels));
    }

    private SimulcastLockResponse doCreate(CreateSimulcastLockRequest request, AssetRow asset,
                                           long plannedAtMs, long endMs, LocalDate businessDay,
                                           List<String> sortedChannels) {
        // 同键语义：已存在（含已撤销）即冲突，撤销后键不释放。
        if (repo.findSimulcastGroup(request.simulcastKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_SIMULCAST_KEY",
                    "联播 simulcastKey 已存在: " + request.simulcastKey());
        }

        // 按频道 ID 排序依次加行锁，与草稿替换、紧急插播、撤销串行化，避免加锁顺序死锁。
        for (String channelId : sortedChannels) {
            repo.lockChannelForUpdate(channelId);
        }

        List<SimulcastRejection> rejections = new ArrayList<>();
        Map<String, Long> selectedGrants = new LinkedHashMap<>();
        for (String channelId : sortedChannels) {
            if (repo.findChannel(channelId).isEmpty()) {
                rejections.add(new SimulcastRejection(channelId, "CHANNEL_NOT_FOUND",
                        "频道不存在: " + channelId));
                continue;
            }
            Optional<DraftRow> draft = repo.findDraft(channelId, businessDay);
            if (draft.isEmpty()) {
                rejections.add(new SimulcastRejection(channelId, "DRAFT_NOT_FOUND",
                        "频道当日无可用草稿: " + channelId + " " + businessDay));
                continue;
            }
            for (SegmentRow segment : repo.findDraftSegments(channelId, businessDay)) {
                if (segment.startMs() < endMs && segment.endMs() > plannedAtMs) {
                    rejections.add(new SimulcastRejection(channelId, "TIME_OCCUPIED_BY_SEGMENT",
                            "计划时刻已被草稿素材占用，片段: " + segment.id()));
                    break;
                }
            }
            List<OverrideRow> overrides =
                    repo.findActiveOverridesOverlappingForUpdate(channelId, plannedAtMs, endMs);
            if (!overrides.isEmpty()) {
                rejections.add(new SimulcastRejection(channelId, "TIME_OCCUPIED_BY_OVERRIDE",
                        "计划时刻已被紧急插播占用: " + overrides.get(0).overrideKey()));
            }
            // 锁定候选授权行：与授权撤销按提交顺序串行，撤销先提交时此处判为无有效授权。
            GrantRow grant = repo.findCoveringGrantsForUpdate(channelId, request.assetId(),
                            plannedAtMs, endMs).stream()
                    .filter(g -> !g.revoked())
                    .findFirst()
                    .orElse(null);
            if (grant == null) {
                rejections.add(new SimulcastRejection(channelId, "NO_COVERING_GRANT",
                        "素材授权未覆盖该频道与计划时刻: " + channelId));
            } else {
                selectedGrants.put(channelId, grant.id());
            }
        }
        if (!rejections.isEmpty()) {
            throw new SimulcastValidationException(rejections);
        }

        long createdAtMs = nowMs();
        List<SimulcastChannelEntry> entries = new ArrayList<>();
        try {
            repo.insertSimulcastGroup(request.simulcastKey(), request.assetId(), businessDay,
                    plannedAtMs, endMs, createdAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_SIMULCAST_KEY",
                    "联播 simulcastKey 已存在: " + request.simulcastKey());
        }
        for (String channelId : sortedChannels) {
            String segmentId = UUID.randomUUID().toString();
            long grantId = selectedGrants.get(channelId);
            repo.insertSimulcastPlaceholder(request.simulcastKey(), channelId, businessDay,
                    segmentId, request.assetId(), grantId, plannedAtMs, endMs, createdAtMs);
            // 占位写入频道草稿：不可独立修改，草稿版本随占位写入递增。
            repo.insertDraftSegment(segmentId, channelId, businessDay, request.assetId(),
                    plannedAtMs, endMs);
            repo.touchDraftVersion(channelId, businessDay, createdAtMs);
            entries.add(new SimulcastChannelEntry(channelId, segmentId, grantId,
                    atMs(plannedAtMs), atMs(endMs), SimulcastPlaceholderStatus.ACTIVE));
        }
        return new SimulcastLockResponse(request.simulcastKey(), request.assetId(),
                businessDay.toString(), atMs(plannedAtMs), atMs(endMs), SimulcastStatus.ACTIVE,
                entries, null, null, atMs(createdAtMs));
    }

    // ---------- 整组撤销 ----------

    /**
     * 撤销整个联播组：须在计划播出时刻之前进行，开始后返回 409；撤销同时释放全部频道占位，
     * 写入不可变撤销历史，不改写已发布快照。同 requestId 同参数重放首次结果，改参 409。
     */
    @Transactional
    public SimulcastLockResponse revokeSimulcastLock(String simulcastKey, String requestId) {
        String hash = sha256(OP_SIMULCAST_REVOKE + "|" + simulcastKey);
        return idempotency.execute(requestId, OP_SIMULCAST_REVOKE, hash,
                SimulcastLockResponse.class, () -> doRevoke(simulcastKey, requestId));
    }

    private SimulcastLockResponse doRevoke(String simulcastKey, String requestId) {
        SimulcastGroupRow group = repo.findSimulcastGroupForUpdate(simulcastKey)
                .orElseThrow(() -> ApiException.notFound("联播组不存在: " + simulcastKey));
        if (!group.active()) {
            throw ApiException.conflict("SIMULCAST_NOT_ACTIVE",
                    "联播组已撤销，不能重复撤销: " + simulcastKey);
        }
        long nowMs = nowMs();
        if (nowMs >= group.plannedAtMs()) {
            throw ApiException.conflict("SIMULCAST_ALREADY_STARTED",
                    "联播已开始播出，不能撤销: " + simulcastKey);
        }

        List<SimulcastPlaceholderRow> placeholders =
                repo.findSimulcastPlaceholdersByGroup(simulcastKey);
        // 与创建相同的加锁顺序：按频道 ID 排序加行锁，保证整组原子释放。
        for (SimulcastPlaceholderRow placeholder : placeholders) {
            repo.lockChannelForUpdate(placeholder.channelId());
        }
        int updated = repo.revokeSimulcastGroup(simulcastKey);
        if (updated == 0) {
            throw ApiException.conflict("SIMULCAST_NOT_ACTIVE",
                    "联播组已撤销，不能重复撤销: " + simulcastKey);
        }
        for (SimulcastPlaceholderRow placeholder : placeholders) {
            if (!placeholder.active()) {
                continue;
            }
            repo.deleteDraftSegment(placeholder.channelId(), placeholder.businessDay(),
                    placeholder.segmentId());
            repo.touchDraftVersion(placeholder.channelId(), placeholder.businessDay(), nowMs);
        }
        repo.releaseSimulcastPlaceholders(simulcastKey);
        repo.insertSimulcastRevocation(simulcastKey, requestId, nowMs);

        List<SimulcastChannelEntry> entries = placeholders.stream()
                .map(p -> new SimulcastChannelEntry(p.channelId(), p.segmentId(), p.grantId(),
                        atMs(p.startMs()), atMs(p.endMs()), SimulcastPlaceholderStatus.RELEASED))
                .toList();
        return new SimulcastLockResponse(group.simulcastKey(), group.assetId(),
                group.businessDay().toString(), atMs(group.plannedAtMs()), atMs(group.endMs()),
                SimulcastStatus.REVOKED, entries, requestId, atMs(nowMs), atMs(group.createdAtMs()));
    }

    // ---------- 查询 ----------

    /** 查询联播组明细，含逐频道占位与固化授权；已撤销组附带撤销信息。 */
    @Transactional(readOnly = true)
    public SimulcastLockResponse getSimulcastLock(String simulcastKey) {
        SimulcastGroupRow group = repo.findSimulcastGroup(simulcastKey)
                .orElseThrow(() -> ApiException.notFound("联播组不存在: " + simulcastKey));
        List<SimulcastChannelEntry> entries = repo.findSimulcastPlaceholdersByGroup(simulcastKey)
                .stream()
                .map(p -> new SimulcastChannelEntry(p.channelId(), p.segmentId(), p.grantId(),
                        atMs(p.startMs()), atMs(p.endMs()),
                        p.active() ? SimulcastPlaceholderStatus.ACTIVE
                                : SimulcastPlaceholderStatus.RELEASED))
                .toList();
        SimulcastRevocationRow revocation = repo.findSimulcastRevocation(simulcastKey).orElse(null);
        return new SimulcastLockResponse(group.simulcastKey(), group.assetId(),
                group.businessDay().toString(), atMs(group.plannedAtMs()), atMs(group.endMs()),
                group.active() ? SimulcastStatus.ACTIVE : SimulcastStatus.REVOKED, entries,
                revocation == null ? null : revocation.revokeRequestId(),
                revocation == null ? null : atMs(revocation.revokedAtMs()),
                atMs(group.createdAtMs()));
    }

    /** 查询频道+业务日下仍生效的联播占位。 */
    @Transactional(readOnly = true)
    public ChannelSimulcastPlaceholdersResponse getChannelPlaceholders(String channelId,
                                                                       LocalDate businessDay) {
        repo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
        List<SimulcastPlaceholderItem> items =
                repo.findActiveSimulcastPlaceholders(channelId, businessDay).stream()
                        .map(p -> new SimulcastPlaceholderItem(p.simulcastKey(), p.segmentId(),
                                p.assetId(), p.grantId(), atMs(p.startMs()), atMs(p.endMs()),
                                SimulcastPlaceholderStatus.ACTIVE))
                        .toList();
        return new ChannelSimulcastPlaceholdersResponse(channelId, businessDay.toString(), items);
    }

    /** 查询全部撤销历史，记录不可变，按撤销时间倒序。 */
    @Transactional(readOnly = true)
    public SimulcastRevocationHistoryResponse listRevocations() {
        List<SimulcastRevocationItem> items = repo.findAllSimulcastRevocations().stream()
                .map(r -> new SimulcastRevocationItem(r.simulcastKey(), r.revokeRequestId(),
                        atMs(r.revokedAtMs())))
                .toList();
        return new SimulcastRevocationHistoryResponse(items);
    }

    // ---------- 内部方法 ----------

    private static long toMs(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private static OffsetDateTime atMs(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }

    private long nowMs() {
        return clock.millis();
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
