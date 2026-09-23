package com.example.starter.playout.failover;

import com.example.starter.playout.PlayoutRepository;
import com.example.starter.playout.PlayoutRepository.ChannelVersionRow;
import com.example.starter.playout.PlayoutRepository.PublicationRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.FailoverDtos.ActivateFailoverRequest;
import com.example.starter.playout.api.FailoverDtos.ConfigureLinksRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverOrderResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverStateResponse;
import com.example.starter.playout.api.FailoverDtos.LateReceiptView;
import com.example.starter.playout.api.FailoverDtos.LeaseView;
import com.example.starter.playout.api.FailoverDtos.LinkResponse;
import com.example.starter.playout.api.FailoverDtos.LinkStateRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptResponse;
import com.example.starter.playout.api.FailoverDtos.ScheduleEvidence;
import com.example.starter.playout.failover.FailoverRepository.ActiveOverrideRow;
import com.example.starter.playout.failover.FailoverRepository.FailoverOrderRow;
import com.example.starter.playout.failover.FailoverRepository.LeaseRow;
import com.example.starter.playout.failover.FailoverRepository.LinkRow;
import com.example.starter.playout.failover.FailoverRepository.PublicationVersionRow;
import com.example.starter.playout.failover.FailoverRepository.ReceiptRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 主备播出链路租约切换与游标回执仲裁核心服务。
 *
 * <p>同一频道任一时刻至多一条 ACTIVE 租约；切换在单事务内重新读取频道、编排、插播、租约与双方
 * 回执后整体重验，任一变化返回 409/422，成功时原子结束源租约、创建 generation+1 的目标租约并
 * 冻结切点、编排版本与插播栈，不出现两条活动租约或空窗。</p>
 *
 * <p>回执按世代仲裁：属于当前活动租约且来自持约链路的回执为 CURRENT，连续推进频道公开游标；
 * 来自对端备链路、世代相同的缓存回执为 CACHED，只参与切点计算不推进游标；旧世代回执为 LATE，
 * 仅存档不推进游标；同链路同世代同 sequence 唯一，重复回执只结算一次。</p>
 */
@Service
public class FailoverService {

    /** 业务时区，与编排服务一致。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 目标回执落后源已确认序列的上限（含），超过则不得激活。 */
    public static final long MAX_LAG = 32L;

    private static final String OP_ACTIVATE_FAILOVER = "ACTIVATE_FAILOVER";
    private static final String ROLE_PRIMARY = "PRIMARY";
    private static final String ROLE_BACKUP = "BACKUP";
    private static final String DISP_CURRENT = "CURRENT";
    private static final String DISP_CACHED = "CACHED";
    private static final String DISP_LATE = "LATE";

    private final FailoverRepository repo;
    private final PlayoutRepository playoutRepo;
    private final ObjectMapper objectMapper;

    public FailoverService(FailoverRepository repo, PlayoutRepository playoutRepo,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.playoutRepo = playoutRepo;
        this.objectMapper = objectMapper;
    }

    // ---------- 链路配置与状态 ----------

    /** 配置主备链路；首次配置为主链路创建 generation=1、cutSequence=0 的 ACTIVE 租约。 */
    @Transactional
    public List<LinkResponse> configureLinks(String channelId, ConfigureLinksRequest request) {
        if (playoutRepo.findChannel(channelId).isEmpty()) {
            throw ApiException.notFound("频道不存在: " + channelId);
        }
        if (request.primaryLinkId().equals(request.backupLinkId())) {
            throw ApiException.badRequest("主备 linkId 不能相同");
        }
        if (!repo.findLinks(channelId).isEmpty()) {
            throw ApiException.conflict("LINKS_ALREADY_CONFIGURED",
                    "频道主备链路已配置，不能重复配置: " + channelId);
        }
        long now = nowMs();
        try {
            repo.insertLink(channelId, ROLE_PRIMARY, request.primaryLinkId(), now);
            repo.insertLink(channelId, ROLE_BACKUP, request.backupLinkId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LINK_DUPLICATE", "主备链路配置冲突: " + channelId);
        }
        // 首代租约：generation=1，切点 0，尚无冻结快照。
        repo.insertLease(channelId, request.primaryLinkId(), 1L, 0L, null, null, now);
        return repo.findLinks(channelId).stream().map(FailoverService::toLinkResponse).toList();
    }

    /** 上报链路运行态（健康标志、已缓存编排版本、已同步未决插播栈签名）。 */
    @Transactional
    public List<LinkResponse> reportLinkState(String channelId, String linkId, LinkStateRequest request) {
        LinkRow link = repo.findLink(channelId, linkId)
                .orElseThrow(() -> ApiException.notFound(
                        "链路未配置: " + channelId + " / " + linkId));
        // null 表示从未上报同步；空串 "" 是“无未决插播”空栈的合法签名，必须保留以区别于未上报。
        String rawSignature = request.cachedOverrideSignature();
        String signature = rawSignature == null ? null : rawSignature.trim();
        int updated = repo.updateLinkState(channelId, linkId, request.healthy(),
                request.cachedScheduleVersion(), signature, nowMs());
        if (updated == 0) {
            throw ApiException.notFound("链路未配置: " + channelId + " / " + linkId);
        }
        return repo.findLinks(channelId).stream().map(FailoverService::toLinkResponse).toList();
    }

    // ---------- 预览 ----------

    /** 预览安全切点，只读不写数据；同时返回各项就绪标志，不抛 422。 */
    @Transactional(readOnly = true)
    public FailoverPreviewResponse preview(ActivateFailoverRequest wrapper) {
        FailoverRequest req = wrapper.order();
        Snapshot snap = evaluate(req, false);
        return new FailoverPreviewResponse(req.channelId(), req.sourceLinkId(), req.targetLinkId(),
                snap.sourceConfirmed, snap.targetPrefix, snap.commonPrefix,
                snap.commonPrefix + 1, snap.sourceConfirmed - snap.targetPrefix,
                snap.gaps, snap.targetHealthy, snap.scheduleMatched, snap.overridesSynced);
    }

    // ---------- 激活 ----------

    /**
     * 激活切换单：单事务内对频道行、链路、活动租约、未决插播加锁并重读双方回执，
     * 任一快照条件变化返回 409（版本/租约/回执位置变化）或 422（健康/缺口/落后/未同步/版本不一致），
     * 失败整体回滚、不占用 requestId 也不落切换单；成功原子切换并冻结证据。
     */
    @Transactional
    public FailoverOrderResponse activate(ActivateFailoverRequest wrapper) {
        FailoverRequest req = wrapper.order();
        String paramsHash = sha256(OP_ACTIVATE_FAILOVER + "|" + canonical(req));
        return idempotent(wrapper.requestId(), OP_ACTIVATE_FAILOVER, paramsHash,
                FailoverOrderResponse.class, () -> doActivate(req, wrapper.requestId()));
    }

    private FailoverOrderResponse doActivate(FailoverRequest req, String requestId) {
        long now = nowMs();

        // failoverKey 唯一：已激活的切换单不能复用键。
        Optional<FailoverOrderRow> existingOrder = repo.findOrder(req.failoverKey());
        if (existingOrder.isPresent()) {
            throw ApiException.conflict("FAILOVER_KEY_CONFLICT",
                    "failoverKey 已存在: " + req.failoverKey());
        }

        // 1. 锁频道行并重验频道版本（与编排发布、插播开始/结束按提交顺序串行）。
        ChannelVersionRow channel = playoutRepo.lockChannelVersion(req.channelId())
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + req.channelId()));
        if (channel.version() != req.channelVersion()) {
            throw ApiException.conflict("CHANNEL_VERSION_CONFLICT",
                    "频道版本已变化，提交 " + req.channelVersion() + " 当前 " + channel.version());
        }

        // 2. 锁链路行。
        List<LinkRow> links = repo.findLinksForUpdate(req.channelId());
        if (links.isEmpty()) {
            throw ApiException.notFound("频道未配置主备链路: " + req.channelId());
        }
        LinkRow source = links.stream()
                .filter(l -> l.linkId().equals(req.sourceLinkId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("源链路未配置: " + req.sourceLinkId()));
        LinkRow target = links.stream()
                .filter(l -> l.linkId().equals(req.targetLinkId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("目标链路未配置: " + req.targetLinkId()));
        if (source.linkId().equals(target.linkId())) {
            throw ApiException.badRequest("源/目标 linkId 不能相同");
        }

        // 3. 锁活动租约并重验源链路仍持约。
        LeaseRow activeLease = repo.findActiveLeaseForUpdate(req.channelId())
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_LEASE",
                        "频道当前无活动租约: " + req.channelId()));
        if (!activeLease.linkId().equals(source.linkId())) {
            throw ApiException.conflict("SOURCE_LEASE_CHANGED",
                    "源链路不再持有活动租约: " + source.linkId());
        }

        // 4. 锁未决插播并重读双方回执，重算切点。
        repo.findActiveOverridesForUpdate(req.channelId());
        Snapshot snap = evaluate(req, true);

        // 409：双方回执位置相对快照已变化（回执与切换按提交顺序，先到回执则整单冲突）。
        if (snap.sourceConfirmed != req.sourceLastSequence()) {
            throw ApiException.conflict("RECEIPT_POSITION_CHANGED",
                    "源链路回执位置已变化，提交 " + req.sourceLastSequence()
                            + " 当前 " + snap.sourceConfirmed);
        }
        if (snap.targetLastReceived != req.targetLastSequence()) {
            throw ApiException.conflict("RECEIPT_POSITION_CHANGED",
                    "目标链路最后回执位置已变化，提交 " + req.targetLastSequence()
                            + " 当前 " + snap.targetLastReceived);
        }

        // 422：业务安全条件。
        if (!snap.targetHealthy) {
            throw ApiException.unprocessable("TARGET_UNHEALTHY",
                    "目标链路不健康，不得激活: " + target.linkId());
        }
        if (!snap.scheduleMatched) {
            throw ApiException.unprocessable("SCHEDULE_VERSION_MISMATCH",
                    "目标已缓存编排版本与频道当前版本不一致");
        }
        if (!snap.overridesSynced) {
            throw ApiException.unprocessable("OVERRIDE_NOT_SYNCED",
                    "未决紧急插播尚未在目标链路同步完成");
        }
        if (!snap.gaps.isEmpty()) {
            throw ApiException.unprocessable("GAP_IN_TARGET_CACHE",
                    "目标已缓存序列相对源已确认序列存在缺口: " + snap.gaps);
        }
        long lag = snap.sourceConfirmed - snap.targetPrefix;
        if (lag > MAX_LAG) {
            throw ApiException.unprocessable("TARGET_LAG_TOO_LARGE",
                    "目标回执落后 " + lag + " 条，超过上限 " + MAX_LAG);
        }

        // 5. 原子切换：先结束源租约，再创建递增世代的目标租约并冻结切点/编排/插播。
        int ended = repo.endLease(activeLease.id(), now);
        if (ended == 0) {
            // 理论上被行锁与唯一索引保护，此处防御并发导致的双活动。
            throw ApiException.conflict("LEASE_STATE_CHANGED", "源租约状态已变化，切换拒绝");
        }
        long newGeneration = activeLease.generation() + 1;
        long cutSequence = snap.commonPrefix;
        String scheduleSnapshot = writeJson(snap.scheduleSnapshot);
        String overrideSnapshot = writeJson(snap.overrideSnapshot);
        try {
            repo.insertLease(req.channelId(), target.linkId(), newGeneration, cutSequence,
                    scheduleSnapshot, overrideSnapshot, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LEASE_GENERATION_CONFLICT",
                    "租约世代冲突，generation=" + newGeneration);
        }
        // 切换本身是频道级状态变化，递增版本；后续持有旧版本的切换单将在重验时 409。
        playoutRepo.incrementChannelVersion(req.channelId());

        FailoverOrderRow order = new FailoverOrderRow(req.failoverKey(), req.channelId(),
                req.channelVersion(), req.sourceLinkId(), req.targetLinkId(),
                req.sourceLastSequence(), req.targetLastSequence(),
                req.cutoverAt().toInstant().toEpochMilli(), "ACTIVATED", null,
                newGeneration, cutSequence, requestId, now, now);
        try {
            repo.insertActivatedOrder(order);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("FAILOVER_KEY_CONFLICT",
                    "failoverKey 已存在: " + req.failoverKey());
        }
        return new FailoverOrderResponse(req.failoverKey(), req.channelId(), req.channelVersion(),
                req.sourceLinkId(), req.targetLinkId(), req.sourceLastSequence(),
                req.targetLastSequence(), req.cutoverAt(), "ACTIVATED",
                newGeneration, cutSequence, atMs(now));
    }

    // ---------- 回执 ----------

    /**
     * 提交链路回执并仲裁：
     * CURRENT 连续推进公开游标（禁止跳号）；CACHED 为备链路同代缓存回执，只参与切点；
     * 旧世代回执记 LATE 仅存档；重复回执命中唯一键，只返回原结算结果、不重复推进。
     */
    @Transactional
    public ReceiptResponse submitReceipt(ReceiptRequest req) {
        long now = nowMs();
        LeaseRow active = repo.findActiveLeaseForUpdate(req.channelId())
                .orElseThrow(() -> {
                    if (repo.findLinks(req.channelId()).isEmpty()) {
                        return ApiException.notFound("频道未配置主备链路: " + req.channelId());
                    }
                    return ApiException.conflict("NO_ACTIVE_LEASE",
                            "频道当前无活动租约: " + req.channelId());
                });

        if (req.generation() > active.generation()) {
            throw ApiException.unprocessable("UNKNOWN_GENERATION",
                    "回执世代 " + req.generation() + " 高于当前活动世代 " + active.generation());
        }

        // 旧世代回执：只保存为 LATE，不推进频道游标。
        if (req.generation() < active.generation()) {
            return settleLate(req, active, now);
        }

        boolean fromLeaseHolder = req.linkId().equals(active.linkId());
        if (repo.findLink(req.channelId(), req.linkId()).isEmpty()) {
            throw ApiException.notFound("回执来源链路未配置: " + req.linkId());
        }

        // 重复回执只结算一次：返回既有归类与当前公开游标，不写入、不推进。
        Optional<ReceiptRow> duplicate = repo.findReceipt(req.channelId(), req.generation(),
                req.sequence()).filter(r -> r.linkId().equals(req.linkId()));
        if (duplicate.isPresent()) {
            return new ReceiptResponse(req.channelId(), req.linkId(), req.generation(),
                    req.sequence(), duplicate.get().disposition(), publicCursor(active));
        }

        String disposition = fromLeaseHolder ? DISP_CURRENT : DISP_CACHED;
        if (fromLeaseHolder) {
            // 持约链路回执必须连续：以租约冻结切点为基，公开游标只能连续递增，禁止跳过未播内容。
            long cursor = publicCursor(active);
            if (req.sequence() != cursor + 1) {
                throw ApiException.unprocessable("RECEIPT_OUT_OF_ORDER",
                        "回执 sequence 必须连续，期望 " + (cursor + 1) + " 实际 " + req.sequence());
            }
        }
        // 备链路缓存回执允许乱序（其缓存空洞留待切换时按缺口拦截），重复仍只结算一次。

        try {
            repo.insertReceipt(req.channelId(), req.linkId(), req.generation(), req.sequence(),
                    disposition, now);
        } catch (DuplicateKeyException e) {
            ReceiptRow prior = repo.findReceipt(req.channelId(), req.generation(), req.sequence())
                    .filter(r -> r.linkId().equals(req.linkId()))
                    .orElseThrow(() -> ApiException.conflict("RECEIPT_CONFLICT", "回执并发冲突"));
            return new ReceiptResponse(req.channelId(), req.linkId(), req.generation(),
                    req.sequence(), prior.disposition(), publicCursor(active));
        }
        long confirmed = publicCursor(active);
        return new ReceiptResponse(req.channelId(), req.linkId(), req.generation(),
                req.sequence(), disposition, confirmed);
    }

    /** 旧世代回执落 LATE；同一旧 sequence 已结算过（含切换前 CURRENT）则幂等返回、不重复存档。 */
    private ReceiptResponse settleLate(ReceiptRequest req, LeaseRow active, long now) {
        Optional<ReceiptRow> existing = repo.findReceipt(req.channelId(), req.generation(),
                req.sequence()).filter(r -> r.linkId().equals(req.linkId()));
        if (existing.isPresent()) {
            return new ReceiptResponse(req.channelId(), req.linkId(), req.generation(),
                    req.sequence(), existing.get().disposition(), publicCursor(active));
        }
        try {
            repo.insertReceipt(req.channelId(), req.linkId(), req.generation(), req.sequence(),
                    DISP_LATE, now);
        } catch (DuplicateKeyException e) {
            // 同键并发：以先提交者为准，不重复推进。
        }
        return new ReceiptResponse(req.channelId(), req.linkId(), req.generation(),
                req.sequence(), DISP_LATE, publicCursor(active));
    }

    // ---------- 状态查询 ----------

    /** 查询租约世代、切点、迟到回执与编排/插播证据，只读。 */
    @Transactional(readOnly = true)
    public FailoverStateResponse getState(String channelId) {
        if (playoutRepo.findChannel(channelId).isEmpty()) {
            throw ApiException.notFound("频道不存在: " + channelId);
        }
        long channelVersion = playoutRepo.findChannelVersion(channelId);
        List<LinkRow> links = repo.findLinks(channelId);
        LeaseRow active = repo.findActiveLease(channelId).orElse(null);

        List<LeaseView> leaseHistory = repo.findLeases(channelId).stream()
                .map(lease -> toLeaseView(lease, lease.cutSequence() + prefixFrom(
                        receiptSequences(channelId, lease.linkId(), lease.generation(),
                                DISP_CURRENT), lease.cutSequence())))
                .toList();
        LeaseView activeView = active == null ? null
                : toLeaseView(active, publicCursor(active));

        List<LateReceiptView> late = repo.findLateReceipts(channelId).stream()
                .map(r -> new LateReceiptView(r.linkId(), r.generation(), r.sequenceNo(),
                        atMs(r.receivedAtMs())))
                .toList();

        ScheduleEvidence evidence = buildEvidence(channelId, active);
        List<LinkResponse> linkResponses = links.stream()
                .map(FailoverService::toLinkResponse).toList();
        return new FailoverStateResponse(channelId, channelVersion, activeView, leaseHistory,
                late, evidence, linkResponses);
    }

    // ---------- 切点计算 ----------

    /** 一次切点评估所需的全部重读结果。 */
    private static final class Snapshot {
        long sourceConfirmed;
        long targetPrefix;
        long targetLastReceived;
        long commonPrefix;
        List<Long> gaps = List.of();
        boolean targetHealthy;
        boolean scheduleMatched;
        boolean overridesSynced;
        Map<String, Long> scheduleSnapshot = Map.of();
        List<Map<String, Object>> overrideSnapshot = List.of();
    }

    /**
     * 重读链路、租约、回执、编排与插播并计算安全切点。locking=true 时调用方已持有相关行锁。
     */
    private Snapshot evaluate(FailoverRequest req, boolean locking) {
        List<LinkRow> links = locking ? repo.findLinksForUpdate(req.channelId())
                : repo.findLinks(req.channelId());
        if (links.isEmpty()) {
            throw ApiException.notFound("频道未配置主备链路: " + req.channelId());
        }
        LinkRow source = links.stream()
                .filter(l -> l.linkId().equals(req.sourceLinkId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("源链路未配置: " + req.sourceLinkId()));
        LinkRow target = links.stream()
                .filter(l -> l.linkId().equals(req.targetLinkId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("目标链路未配置: " + req.targetLinkId()));
        if (source.linkId().equals(target.linkId())) {
            throw ApiException.badRequest("源/目标 linkId 不能相同");
        }

        LeaseRow active = (locking ? repo.findActiveLeaseForUpdate(req.channelId())
                : repo.findActiveLease(req.channelId()))
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_LEASE",
                        "频道当前无活动租约: " + req.channelId()));
        if (!active.linkId().equals(source.linkId())) {
            throw ApiException.conflict("SOURCE_LEASE_CHANGED",
                    "源链路不持有当前活动租约: " + source.linkId());
        }
        long generation = active.generation();

        Snapshot snap = new Snapshot();
        // 源已确认 / 目标已缓存游标：均以当前世代冻结切点为基，统计其后连续回执。
        snap.sourceConfirmed = active.cutSequence()
                + prefixFrom(receiptSequences(req.channelId(), source.linkId(),
                        generation, DISP_CURRENT), active.cutSequence());
        Set<Long> targetCachedSet = receiptSequences(req.channelId(), target.linkId(),
                generation, DISP_CACHED);
        snap.targetPrefix = active.cutSequence() + prefixFrom(targetCachedSet, active.cutSequence());
        snap.targetLastReceived = targetCachedSet.stream().mapToLong(Long::longValue).max().orElse(0L);

        // 缺口：目标连续前缀之后、其已缓存最大 sequence 之内仍缺失的 sequence
        //（纯落后——前缀更短但其后无任何缓存——不算缺口，只算 lag）。
        List<Long> gaps = new ArrayList<>();
        for (long seq = snap.targetPrefix + 1; seq < snap.targetLastReceived; seq++) {
            if (!targetCachedSet.contains(seq)) {
                gaps.add(seq);
            }
        }
        snap.gaps = gaps;
        // 公共前缀：源已确认与目标已缓存连续序列取较小者；新租约从其后一条续播。
        snap.commonPrefix = Math.min(snap.sourceConfirmed, snap.targetPrefix);

        // 目标健康与编排版本一致性（以 cutoverAt 所在业务日的最新发布版本为准）。
        snap.targetHealthy = target.healthy();
        LocalDate cutoverDay = req.cutoverAt().atZoneSameInstant(ZONE).toLocalDate();
        long currentVersion = playoutRepo.findLatestPublication(req.channelId(), cutoverDay)
                .map(PublicationRow::publishedVersion).orElse(0L);
        snap.scheduleMatched = target.cachedScheduleVersion() == currentVersion;

        // 未决紧急插播栈签名一致性：目标须上报过签名且与当前栈完全一致。
        List<ActiveOverrideRow> activeOverrides = locking
                ? repo.findActiveOverridesForUpdate(req.channelId())
                : repo.findActiveOverrides(req.channelId());
        String currentSignature = overrideSignature(activeOverrides);
        snap.overridesSynced = currentSignature.equals(target.cachedOverrideSignature());

        // 冻结证据：各业务日最新发布版本 + 当前未决插播栈。
        Map<String, Long> scheduleSnapshot = new LinkedHashMap<>();
        for (PublicationVersionRow pv : repo.findPublicationVersions(req.channelId())) {
            scheduleSnapshot.put(pv.businessDay().toString(), pv.publishedVersion());
        }
        snap.scheduleSnapshot = scheduleSnapshot;
        List<Map<String, Object>> overrideSnapshot = new ArrayList<>();
        for (ActiveOverrideRow o : activeOverrides) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("overrideKey", o.overrideKey());
            item.put("priority", o.priority());
            item.put("startMs", o.startMs());
            item.put("endMs", o.endMs());
            overrideSnapshot.add(item);
        }
        snap.overrideSnapshot = overrideSnapshot;
        return snap;
    }

    /** 某链路某世代某归类回执从起点 1 起的连续前缀长度。 */
    private long contiguousPrefix(String channelId, String linkId, long generation,
                                  String disposition) {
        return prefixLength(receiptSequences(channelId, linkId, generation, disposition));
    }

    /** 从给定基点 base 起的连续回执条数（base 本身不算）。 */
    private static long prefixFrom(Set<Long> sequences, long base) {
        long cursor = base;
        while (sequences.contains(cursor + 1)) {
            cursor++;
        }
        return cursor - base;
    }

    private Set<Long> receiptSequences(String channelId, String linkId, long generation,
                                       String disposition) {
        return repo.findReceipts(channelId, linkId, generation).stream()
                .filter(r -> r.disposition().equals(disposition))
                .map(ReceiptRow::sequenceNo)
                .collect(Collectors.toSet());
    }

    private static long prefixLength(Set<Long> sequences) {
        long prefix = 0;
        while (sequences.contains(prefix + 1)) {
            prefix++;
        }
        return prefix;
    }

    /**
     * 频道公开连续游标：以活动租约冻结切点为基，加上持约链路 CURRENT 回执自切点后的连续前缀。
     * 这样新世代从 cutSequence+1 续播，公开游标跨世代单调连续。
     */
    private long publicCursor(LeaseRow active) {
        return active.cutSequence() + prefixFrom(receiptSequences(active.channelId(),
                active.linkId(), active.generation(), DISP_CURRENT), active.cutSequence());
    }

    /** 未决插播栈的确定性签名：按优先级降序、开始升序的规范化拼接；空栈为空串。 */
    private static String overrideSignature(List<ActiveOverrideRow> overrides) {
        return overrides.stream()
                .map(o -> o.overrideKey() + ":" + o.priority() + ":" + o.startMs() + ":" + o.endMs())
                .collect(Collectors.joining("|"));
    }

    private ScheduleEvidence buildEvidence(String channelId, LeaseRow active) {
        List<PublicationVersionRow> publications = repo.findPublicationVersions(channelId);
        PublicationVersionRow latest = publications.isEmpty() ? null
                : publications.get(publications.size() - 1);
        List<ActiveOverrideRow> activeOverrides = repo.findActiveOverrides(channelId);
        List<String> overrideKeys = activeOverrides.stream()
                .map(ActiveOverrideRow::overrideKey).toList();
        String signature = overrideSignature(activeOverrides);
        if (latest == null) {
            return new ScheduleEvidence(null, 0L, 0L, overrideKeys, signature);
        }
        return new ScheduleEvidence(latest.businessDay().toString(), latest.publicationId(),
                latest.publishedVersion(), overrideKeys, signature);
    }

    // ---------- 幂等 ----------

    /**
     * 幂等执行：去重记录与切换结果同事务提交；同 requestId 同参数返回首次快照，改参数 409，
     * 业务失败回滚不占用 requestId。
     */
    private <T> T idempotent(String requestId, String operation, String paramsHash,
                             Class<T> type, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        Optional<RequestRow> existing = playoutRepo.findRequestForUpdate(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, paramsHash, type);
        }
        try {
            playoutRepo.insertRequest(requestId, operation, paramsHash, nowMs());
        } catch (DuplicateKeyException e) {
            RequestRow committed = playoutRepo.findRequestForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("REQUEST_ID_CONFLICT",
                            "requestId 并发冲突: " + requestId));
            return replay(committed, operation, paramsHash, type);
        }
        T result = action.get();
        try {
            playoutRepo.completeRequest(requestId, objectMapper.writeValueAsString(result));
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

    private static String canonical(FailoverRequest req) {
        return String.join("|",
                req.failoverKey(), req.channelId(), String.valueOf(req.channelVersion()),
                req.sourceLinkId(), req.targetLinkId(),
                String.valueOf(req.sourceLastSequence()), String.valueOf(req.targetLastSequence()),
                String.valueOf(req.cutoverAt().toInstant().toEpochMilli()));
    }

    // ---------- 转换与工具 ----------

    private static LinkResponse toLinkResponse(LinkRow row) {
        return new LinkResponse(row.channelId(), row.role(), row.linkId(), row.healthy(),
                row.cachedScheduleVersion(), row.cachedOverrideSignature());
    }

    private LeaseView toLeaseView(LeaseRow row, long confirmedSequence) {
        String role = repo.findLink(row.channelId(), row.linkId())
                .map(LinkRow::role).orElse(null);
        return new LeaseView(row.generation(), row.linkId(), role, row.status(),
                row.cutSequence(), confirmedSequence,
                row.scheduleSnapshot(), row.overrideSnapshot(),
                atMs(row.startedAtMs()),
                row.endedAtMs() == null ? null : atMs(row.endedAtMs()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("冻结快照序列化失败", e);
        }
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
