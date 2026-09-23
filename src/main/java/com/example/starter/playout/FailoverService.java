package com.example.starter.playout;

import com.example.starter.playout.FailoverRepository.FailoverOrderRow;
import com.example.starter.playout.FailoverRepository.LeaseRow;
import com.example.starter.playout.FailoverRepository.LinkRow;
import com.example.starter.playout.FailoverRepository.ReceiptRow;
import com.example.starter.playout.PlayoutRepository.ChannelRow;
import com.example.starter.playout.PlayoutRepository.OverrideRow;
import com.example.starter.playout.PlayoutRepository.RequestRow;
import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.FailoverDtos.ActivateFailoverRequest;
import com.example.starter.playout.api.FailoverDtos.BootstrapLeaseRequest;
import com.example.starter.playout.api.FailoverDtos.CreateFailoverOrderRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverOrderResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewResponse;
import com.example.starter.playout.api.FailoverDtos.LateReceiptItem;
import com.example.starter.playout.api.FailoverDtos.LeaseResponse;
import com.example.starter.playout.api.FailoverDtos.LinkResponse;
import com.example.starter.playout.api.FailoverDtos.OrderStatus;
import com.example.starter.playout.api.FailoverDtos.OverrideStackItem;
import com.example.starter.playout.api.FailoverDtos.ReceiptDisposition;
import com.example.starter.playout.api.FailoverDtos.ReceiptRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptResponse;
import com.example.starter.playout.api.FailoverDtos.RegisterLinkRequest;
import com.example.starter.playout.api.FailoverDtos.ScheduleEvidence;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 频道主备播出链路租约切换服务：链路注册与健康上报、初始租约引导、回执仲裁、
 * 安全切点预览（只读）、切换单创建与激活（单事务重读全部证据）、只读明细查询。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>同一频道同一时刻至多一条 ACTIVE 租约（数据库 uk_active_slot 唯一索引强制），
 *       结束旧租约与创建新租约在同一事务内完成，不出现双活或空窗；</li>
 *   <li>租约世代每频道单调递增，新世代租约继承公共前缀游标，切点为下一条应播 sequence；</li>
 *   <li>公开游标只能连续递增：仅当回执 seq 恰为当前游标 +1 时结算，重复为 DUPLICATE，
 *       乱序跳跃被拒；旧世代回执只存档 LATE，不推进游标；</li>
 *   <li>激活在单事务内锁频道/租约/双方链路/切换单行并重读频道版本、回执与插播，
 *       与发布、插播开始/结束、回执和另一切换按数据库提交顺序仲裁，任一变化整单 409/422。</li>
 * </ul>
 */
@Service
public class FailoverService {

    /** 业务时区，与播出编排一致。 */
    public static final ZoneId ZONE = PlayoutService.ZONE;

    private static final String OP_CREATE_FAILOVER = "CREATE_FAILOVER";
    private static final String OP_ACTIVATE_FAILOVER = "ACTIVATE_FAILOVER";
    private static final String OP_LINK_RECEIPT = "LINK_RECEIPT";

    private final FailoverRepository failoverRepo;
    private final PlayoutRepository playoutRepo;
    private final ObjectMapper objectMapper;

    public FailoverService(FailoverRepository failoverRepo, PlayoutRepository playoutRepo,
                           ObjectMapper objectMapper) {
        this.failoverRepo = failoverRepo;
        this.playoutRepo = playoutRepo;
        this.objectMapper = objectMapper;
    }

    // ---------- 链路注册与上报 ----------

    /** 注册频道主/备链路；频道必须存在，(频道,链路) 与 (频道,角色) 均唯一。 */
    @Transactional
    public LinkResponse registerLink(String channelId, RegisterLinkRequest request) {
        requireChannel(channelId);
        try {
            failoverRepo.insertLink(channelId, request.linkId(), request.role().name(), nowMs());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_LINK",
                    "链路已存在或角色冲突: channel=" + channelId + " link=" + request.linkId());
        }
        return toLinkResponse(failoverRepo.findLink(channelId, request.linkId()).orElseThrow());
    }

    /** 上报链路健康与已缓存编排版本。 */
    @Transactional
    public LinkResponse reportHealth(String channelId, String linkId, boolean healthy,
                                     long cachedVersion) {
        long now = nowMs();
        // 锁链路行：与激活事务对目标缓存/健康的重读按提交顺序串行。
        LinkRow link = failoverRepo.findLinkForUpdate(channelId, linkId)
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + channelId + " link=" + linkId));
        if (cachedVersion < link.cachedVersion()) {
            throw ApiException.conflict("CACHED_VERSION_REGRESS",
                    "缓存编排版本不能回退: 当前 " + link.cachedVersion() + " 上报 " + cachedVersion);
        }
        failoverRepo.updateLinkReport(channelId, linkId, healthy, cachedVersion, now);
        return toLinkResponse(failoverRepo.findLink(channelId, linkId).orElseThrow());
    }

    /** 上报紧急插播已同步到链路；插播必须存在且属于该频道。 */
    @Transactional
    public void reportOverrideSynced(String channelId, String linkId, String overrideKey) {
        requireLink(channelId, linkId);
        OverrideRow override = playoutRepo.findOverride(overrideKey)
                .orElseThrow(() -> ApiException.notFound("紧急插播不存在: " + overrideKey));
        if (!override.channelId().equals(channelId)) {
            throw ApiException.unprocessable("OVERRIDE_NOT_MATCHED",
                    "紧急插播不属于该频道: " + overrideKey);
        }
        failoverRepo.upsertOverrideSynced(channelId, linkId, overrideKey, nowMs());
    }

    // ---------- 初始租约 ----------

    /** 引导频道初始世代（generation=1）ACTIVE 租约；频道不得已有 ACTIVE 租约，链路必须存在。 */
    @Transactional
    public LeaseResponse bootstrapLease(String channelId, BootstrapLeaseRequest request) {
        requireChannel(channelId);
        long now = nowMs();
        // 统一加锁顺序：频道行 → ACTIVE 租约行 → 链路行，避免与回执/激活事务交叉死锁。
        playoutRepo.lockChannelForUpdate(channelId);
        if (failoverRepo.findActiveLeaseForUpdate(channelId).isPresent()) {
            throw ApiException.conflict("LEASE_ALREADY_ACTIVE",
                    "频道已存在 ACTIVE 租约: " + channelId);
        }
        LinkRow link = failoverRepo.findLinkForUpdate(channelId, request.linkId())
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + channelId + " link=" + request.linkId()));
        long version = playoutRepo.getScheduleVersion(channelId);
        try {
            failoverRepo.insertLease(channelId, link.linkId(), 1, 0, version, null, null, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LEASE_ALREADY_ACTIVE",
                    "频道已存在 ACTIVE 租约: " + channelId);
        }
        return toLeaseResponse(failoverRepo.findActiveLease(channelId).orElseThrow());
    }

    /** 查询当前 ACTIVE 租约。 */
    @Transactional(readOnly = true)
    public LeaseResponse activeLease(String channelId) {
        requireChannel(channelId);
        return toLeaseResponse(failoverRepo.findActiveLease(channelId)
                .orElseThrow(() -> ApiException.notFound("频道尚无 ACTIVE 租约: " + channelId)));
    }

    /** 查询频道全部链路（主/备）及其健康与缓存版本。 */
    @Transactional(readOnly = true)
    public List<LinkResponse> listLinks(String channelId) {
        requireChannel(channelId);
        return failoverRepo.findLinks(channelId).stream()
                .map(FailoverService::toLinkResponse)
                .toList();
    }

    // ---------- 回执仲裁 ----------

    /**
     * 链路播放回执仲裁（幂等）：
     * 持约链路 + 当前世代 + seq=游标+1 才 SETTLED 并推进游标；重复回执 DUPLICATE（只结算一次）；
     * 跳跃回执 422（游标只能连续）；旧世代回执 LATE 存档；非持约链路当前世代回执 STANDBY 存档
     * （作为备链路已缓存连续序列的证据）；未来世代回执 409。
     */
    @Transactional
    public ReceiptResponse submitReceipt(String channelId, String linkId, ReceiptRequest request) {
        String hash = sha256(OP_LINK_RECEIPT + "|" + channelId + "|" + linkId + "|"
                + request.generation() + "|" + request.seq());
        return idempotent(request.requestId(), OP_LINK_RECEIPT, hash, ReceiptResponse.class,
                () -> doSubmitReceipt(channelId, linkId, request.generation(), request.seq()));
    }

    private ReceiptResponse doSubmitReceipt(String channelId, String linkId, long generation,
                                            long seq) {
        long now = nowMs();
        // 统一加锁顺序：频道行 → ACTIVE 租约行 → 链路行。
        playoutRepo.lockChannelForUpdate(channelId);
        LeaseRow active = failoverRepo.findActiveLeaseForUpdate(channelId)
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_LEASE",
                        "频道尚无 ACTIVE 租约: " + channelId));
        LinkRow link = failoverRepo.findLinkForUpdate(channelId, linkId)
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + channelId + " link=" + linkId));

        // 同链路同世代同 seq 的重复回执：只结算/存档一次，返回裁决（已结算过的重复投递记为 DUPLICATE）。
        Optional<ReceiptRow> existing = failoverRepo.findReceipt(channelId, linkId, generation, seq);
        if (existing.isPresent()) {
            ReceiptDisposition stored = dispositionOf(existing.get());
            ReceiptDisposition shown = stored == ReceiptDisposition.SETTLED
                    ? ReceiptDisposition.DUPLICATE : stored;
            return receiptResponse(channelId, linkId, generation, seq, shown, active);
        }

        ReceiptDisposition disposition;
        if (generation < active.generation()) {
            // 提交后到达的旧世代回执（源链路被切走后迟到）：只存档 LATE，不推进游标。
            disposition = ReceiptDisposition.LATE;
        } else if (generation > active.generation()) {
            throw ApiException.conflict("UNKNOWN_GENERATION",
                    "回执世代大于当前持约世代: " + generation + " > " + active.generation());
        } else if (!link.linkId().equals(active.linkId())) {
            // 备链路在当前世代的缓存回执：存档但不推进频道游标。
            disposition = ReceiptDisposition.STANDBY;
        } else if (seq <= active.confirmedSeq()) {
            // 新链路重复回执（切点之前的 seq 已由源链路确认）：只结算一次。
            disposition = ReceiptDisposition.DUPLICATE;
        } else if (seq > active.confirmedSeq() + 1) {
            // 公开游标只能连续递增，禁止跳过未播内容。
            throw ApiException.unprocessable("RECEIPT_OUT_OF_ORDER",
                    "回执 seq " + seq + " 跳跃，当前应确认 " + (active.confirmedSeq() + 1));
        } else {
            disposition = ReceiptDisposition.SETTLED;
        }
        failoverRepo.insertReceipt(channelId, linkId, generation, seq, disposition.name(), now);
        if (disposition == ReceiptDisposition.SETTLED) {
            failoverRepo.advanceLeaseConfirmedSeq(active.id(), seq);
        }
        LeaseRow refreshed = failoverRepo.findActiveLease(channelId).orElse(active);
        return receiptResponse(channelId, linkId, generation, seq, disposition, refreshed);
    }

    private ReceiptDisposition dispositionOf(ReceiptRow row) {
        return ReceiptDisposition.valueOf(row.disposition());
    }

    private ReceiptResponse receiptResponse(String channelId, String linkId, long generation,
                                            long seq, ReceiptDisposition disposition,
                                            LeaseRow active) {
        return new ReceiptResponse(channelId, linkId, generation, seq, disposition,
                active.linkId(), active.generation(), active.confirmedSeq());
    }

    // ---------- 安全切点预览（只读） ----------

    /**
     * 预览安全切点：源已确认序列与目标已缓存连续序列的最大公共前缀为 k，
     * 下一条应播 sequence = k+1；缺口为目标在 (k, 源已确认] 内缺失的序号。只读不写数据。
     */
    @Transactional(readOnly = true)
    public FailoverPreviewResponse preview(FailoverPreviewRequest request) {
        requireChannel(request.channelId());
        LeaseRow active = failoverRepo.findActiveLease(request.channelId())
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_LEASE",
                        "频道尚无 ACTIVE 租约: " + request.channelId()));
        LinkRow source = requireLink(request.channelId(), request.sourceLinkId());
        LinkRow target = requireLink(request.channelId(), request.targetLinkId());
        if (!source.linkId().equals(active.linkId())) {
            throw ApiException.unprocessable("SOURCE_NOT_ACTIVE",
                    "源链路不是当前持约链路: " + source.linkId());
        }
        if (source.linkId().equals(target.linkId())) {
            throw ApiException.unprocessable("SAME_LINK", "源链路与目标链路不能相同");
        }

        long sourceConfirmed = active.confirmedSeq();
        long targetContiguous = failoverRepo.contiguousReceiptSeq(
                request.channelId(), target.linkId());
        long targetMax = failoverRepo.maxReceiptSeq(
                request.channelId(), target.linkId());
        long commonPrefix = Math.min(sourceConfirmed, targetContiguous);
        // 缺口：目标收到过更大 seq 但连续前缀之后存在空洞。
        List<Long> gaps = failoverRepo.findReceiptHoles(
                request.channelId(), target.linkId(), targetContiguous, targetMax);
        long channelVersion = playoutRepo.getScheduleVersion(request.channelId());
        return new FailoverPreviewResponse(request.channelId(), source.linkId(), target.linkId(),
                channelVersion, target.healthy(), target.cachedVersion(),
                sourceConfirmed, targetContiguous, commonPrefix, commonPrefix + 1, gaps);
    }

    // ---------- 切换单 ----------

    /**
     * 监控员创建切换单：校验频道版本一致、源为当前持约链路、目标健康且缓存版本与频道一致、
     * 双方提交的最后回执与当前仲裁状态一致。failoverKey 唯一；携带 requestId 幂等。
     */
    @Transactional
    public FailoverOrderResponse createOrder(CreateFailoverOrderRequest request) {
        String hash = sha256(OP_CREATE_FAILOVER + "|" + request.failoverKey() + "|"
                + request.channelId() + "|" + request.channelVersion() + "|"
                + request.sourceLinkId() + "|" + request.targetLinkId() + "|"
                + request.sourceLastSeq() + "|" + request.targetLastSeq() + "|"
                + request.cutoverAt().toInstant().toEpochMilli() + "|" + request.maxLag());
        return idempotent(request.requestId(), OP_CREATE_FAILOVER, hash,
                FailoverOrderResponse.class, () -> doCreateOrder(request));
    }

    private FailoverOrderResponse doCreateOrder(CreateFailoverOrderRequest request) {
        requireChannel(request.channelId());
        long cutoverAtMs = request.cutoverAt().toInstant().toEpochMilli();
        long now = nowMs();

        // 锁频道/双方链路/当前租约，与发布、健康上报、回执按提交顺序读到一致快照。
        playoutRepo.lockChannelForUpdate(request.channelId());
        long currentVersion = playoutRepo.getScheduleVersion(request.channelId());
        if (currentVersion != request.channelVersion()) {
            throw ApiException.conflict("CHANNEL_VERSION_CONFLICT",
                    "频道编排版本已变化，当前 " + currentVersion + " 提交 " + request.channelVersion());
        }
        LeaseRow active = failoverRepo.findActiveLeaseForUpdate(request.channelId())
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_LEASE",
                        "频道尚无 ACTIVE 租约: " + request.channelId()));
        LinkRow source = failoverRepo.findLinkForUpdate(request.channelId(), request.sourceLinkId())
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + request.channelId()
                                + " link=" + request.sourceLinkId()));
        LinkRow target = failoverRepo.findLinkForUpdate(request.channelId(), request.targetLinkId())
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + request.channelId()
                                + " link=" + request.targetLinkId()));
        validatePair(active, source, target);
        validateTarget(target, currentVersion);

        long sourceConfirmed = active.confirmedSeq();
        long targetLast = failoverRepo.maxReceiptSeq(request.channelId(), target.linkId());
        if (request.sourceLastSeq() != sourceConfirmed) {
            throw ApiException.conflict("SOURCE_SEQ_MOVED",
                    "源链路最后回执已变化，当前已确认 " + sourceConfirmed
                            + " 提交 " + request.sourceLastSeq());
        }
        if (request.targetLastSeq() != targetLast) {
            throw ApiException.conflict("TARGET_SEQ_MOVED",
                    "目标链路最后回执已变化，当前 " + targetLast
                            + " 提交 " + request.targetLastSeq());
        }

        try {
            failoverRepo.insertFailoverOrder(request.failoverKey(), request.channelId(),
                    currentVersion, source.linkId(), target.linkId(), sourceConfirmed,
                    targetLast, cutoverAtMs, request.maxLag(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_FAILOVER_KEY",
                    "failoverKey 已存在: " + request.failoverKey());
        }
        return toOrderResponse(failoverRepo.findFailoverOrder(request.failoverKey()).orElseThrow());
    }

    /**
     * 激活切换单：单事务内锁定并重新读取频道、编排、插播、租约与双方回执，任一变化整单 409/422；
     * 成功时原子结束源租约、创建世代 +1 的目标 ACTIVE 租约，并冻结切点、编排版本与插播栈。
     */
    @Transactional
    public FailoverOrderResponse activate(String failoverKey, ActivateFailoverRequest request) {
        String hash = sha256(OP_ACTIVATE_FAILOVER + "|" + failoverKey);
        return idempotent(request.requestId(), OP_ACTIVATE_FAILOVER, hash,
                FailoverOrderResponse.class, () -> doActivate(failoverKey));
    }

    private FailoverOrderResponse doActivate(String failoverKey) {
        long now = nowMs();

        // 1. 锁切换单行：两个激活并发按提交顺序串行，后到者见到 ACTIVATED 直接 409。
        FailoverOrderRow order = failoverRepo.findFailoverOrderForUpdate(failoverKey)
                .orElseThrow(() -> ApiException.notFound("切换单不存在: " + failoverKey));
        if (order.activated()) {
            throw ApiException.conflict("ORDER_ALREADY_ACTIVATED",
                    "切换单已激活: " + failoverKey);
        }
        if (!order.created()) {
            throw ApiException.conflict("ORDER_NOT_ACTIVATABLE",
                    "切换单状态不允许激活: " + order.status());
        }

        // 2. 锁频道行并重读编排版本：与编排发布、插播开始/结束（均锁频道）按提交顺序仲裁。
        playoutRepo.lockChannelForUpdate(order.channelId());
        long currentVersion = playoutRepo.getScheduleVersion(order.channelId());
        if (currentVersion != order.channelVersion()) {
            throw ApiException.conflict("CHANNEL_VERSION_CONFLICT",
                    "频道编排版本自创建单后已变化，当前 " + currentVersion
                            + " 创建时 " + order.channelVersion());
        }

        // 3. 锁当前租约与双方链路行并重读全部证据。
        LeaseRow active = failoverRepo.findActiveLeaseForUpdate(order.channelId())
                .orElseThrow(() -> ApiException.unprocessable("NO_ACTIVE_LEASE",
                        "频道已无 ACTIVE 租约: " + order.channelId()));
        LinkRow source = failoverRepo.findLinkForUpdate(order.channelId(), order.sourceLinkId())
                .orElseThrow(() -> ApiException.notFound("源链路不存在: " + order.sourceLinkId()));
        LinkRow target = failoverRepo.findLinkForUpdate(order.channelId(), order.targetLinkId())
                .orElseThrow(() -> ApiException.notFound("目标链路不存在: " + order.targetLinkId()));
        validatePair(active, source, target);

        // 目标必须健康且缓存编排版本仍与频道一致。
        validateTarget(target, currentVersion);

        // 4. 重读双方回执：任一变化整单 409。
        long sourceConfirmed = active.confirmedSeq();
        long targetContiguous = failoverRepo.contiguousReceiptSeq(
                order.channelId(), target.linkId());
        long targetLast = failoverRepo.maxReceiptSeq(order.channelId(), target.linkId());
        if (sourceConfirmed != order.sourceLastSeq()) {
            throw ApiException.conflict("SOURCE_SEQ_MOVED",
                    "源链路回执自创建单后已变化，当前 " + sourceConfirmed
                            + " 创建时 " + order.sourceLastSeq());
        }
        if (targetLast != order.targetLastSeq()) {
            throw ApiException.conflict("TARGET_SEQ_MOVED",
                    "目标链路回执自创建单后已变化，当前 " + targetLast
                            + " 创建时 " + order.targetLastSeq());
        }

        // 5. 缺口：目标收到过更大 seq 但连续前缀之后存在空洞（区别于纯粹落后）。
        List<Long> gaps = failoverRepo.findReceiptHoles(
                order.channelId(), target.linkId(), targetContiguous, targetLast);
        if (!gaps.isEmpty()) {
            throw ApiException.unprocessable("RECEIPT_GAP",
                    "目标链路回执存在缺口，无法安全切换: " + gaps);
        }

        // 6. 落后上限：源已确认超出目标连续前缀超过 maxLag 不得激活。
        long lag = sourceConfirmed - targetContiguous;
        if (lag > order.maxLag()) {
            throw ApiException.unprocessable("TARGET_LAG_EXCEEDED",
                    "目标回执落后 " + lag + " 条，超过上限 " + order.maxLag());
        }

        // 7. 切点时刻仍未决（ACTIVE 且区间命中 cutoverAt）的紧急插播必须全部已同步到目标。
        List<OverrideRow> pending = playoutRepo.findActiveOverridesAt(
                order.channelId(), order.cutoverAtMs());
        List<OverrideRow> unsynced = new ArrayList<>();
        for (OverrideRow override : pending) {
            if (!failoverRepo.isOverrideSynced(order.channelId(), target.linkId(),
                    override.overrideKey())) {
                unsynced.add(override);
            }
        }
        if (!unsynced.isEmpty()) {
            throw ApiException.unprocessable("OVERRIDE_NOT_SYNCED",
                    "存在未同步到目标链路的未决紧急插播: "
                            + unsynced.stream().map(OverrideRow::overrideKey).toList());
        }

        // 8. 冻结证据：切点、编排版本、插播栈。
        long commonPrefix = Math.min(sourceConfirmed, targetContiguous);
        long safeCutSeq = commonPrefix + 1;
        long newGeneration = failoverRepo.maxGeneration(order.channelId()) + 1;
        List<OverrideStackItem> stack = pending.stream()
                .map(o -> new OverrideStackItem(o.overrideKey(), o.assetId(), o.grantId(),
                        o.priority(), atMs(o.startMs()), atMs(o.endMs())))
                .toList();
        String stackJson;
        try {
            stackJson = objectMapper.writeValueAsString(stack);
        } catch (Exception e) {
            throw new IllegalStateException("插播栈快照序列化失败", e);
        }

        // 9. 原子换约：先结束源租约（释放 uk_active_slot），再插入递增世代目标租约。
        int ended = failoverRepo.endLease(active.id(), now);
        if (ended == 0) {
            throw ApiException.conflict("LEASE_STATE_CHANGED",
                    "源租约已被其他事务结束: " + active.id());
        }
        try {
            failoverRepo.insertLease(order.channelId(), target.linkId(), newGeneration,
                    commonPrefix, currentVersion, safeCutSeq, order.id(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("LEADER_ELECTION_CONFLICT",
                    "激活并发冲突，频道 ACTIVE 租约已被占用: " + order.channelId());
        }
        int marked = failoverRepo.markOrderActivated(order.id(), newGeneration, safeCutSeq,
                currentVersion, stackJson, now);
        if (marked == 0) {
            throw ApiException.conflict("ORDER_STATE_CHANGED",
                    "切换单状态已变化: " + failoverKey);
        }

        return toOrderResponse(failoverRepo.findFailoverOrder(failoverKey).orElseThrow());
    }

    /** 查询切换单明细（只读）：世代、切点、迟到回执、冻结插播栈与编排证据。 */
    @Transactional(readOnly = true)
    public FailoverOrderResponse getOrder(String failoverKey) {
        FailoverOrderRow order = failoverRepo.findFailoverOrder(failoverKey)
                .orElseThrow(() -> ApiException.notFound("切换单不存在: " + failoverKey));
        return toOrderResponse(order);
    }

    // ---------- 校验与组装 ----------

    private ChannelRow requireChannel(String channelId) {
        return playoutRepo.findChannel(channelId)
                .orElseThrow(() -> ApiException.notFound("频道不存在: " + channelId));
    }

    private LinkRow requireLink(String channelId, String linkId) {
        return failoverRepo.findLink(channelId, linkId)
                .orElseThrow(() -> ApiException.notFound(
                        "链路不存在: channel=" + channelId + " link=" + linkId));
    }

    /** 源必须为当前持约链路，源/目标不得相同，且角色互为主备。 */
    private void validatePair(LeaseRow active, LinkRow source, LinkRow target) {
        if (!source.linkId().equals(active.linkId())) {
            throw ApiException.unprocessable("SOURCE_NOT_ACTIVE",
                    "源链路不是当前持约链路: " + source.linkId());
        }
        if (source.linkId().equals(target.linkId())) {
            throw ApiException.unprocessable("SAME_LINK", "源链路与目标链路不能相同");
        }
        if (source.role().equals(target.role())) {
            throw ApiException.unprocessable("LINK_ROLE_CONFLICT",
                    "源链路与目标链路角色相同: " + source.role());
        }
    }

    /** 目标必须健康且缓存编排版本与当前频道一致。 */
    private void validateTarget(LinkRow target, long currentVersion) {
        if (!target.healthy()) {
            throw ApiException.unprocessable("TARGET_NOT_HEALTHY",
                    "目标链路不健康: " + target.linkId());
        }
        if (target.cachedVersion() != currentVersion) {
            throw ApiException.unprocessable("TARGET_CACHE_STALE",
                    "目标缓存编排版本 " + target.cachedVersion() + " 与频道当前版本 "
                            + currentVersion + " 不一致");
        }
    }

    private static LinkResponse toLinkResponse(LinkRow row) {
        return new LinkResponse(row.channelId(), row.linkId(),
                com.example.starter.playout.api.FailoverDtos.LinkRole.valueOf(row.role()),
                row.healthy(), row.cachedVersion(), atMs(row.updatedAtMs()));
    }

    private static LeaseResponse toLeaseResponse(LeaseRow row) {
        return new LeaseResponse(row.channelId(), row.linkId(), row.generation(),
                row.active()
                        ? com.example.starter.playout.api.FailoverDtos.LeaseStatus.ACTIVE
                        : com.example.starter.playout.api.FailoverDtos.LeaseStatus.ENDED,
                row.confirmedSeq(), row.cutoverSeq(), row.scheduleVersion(), row.orderId(),
                atMs(row.createdAtMs()), row.endedAtMs() == null ? null : atMs(row.endedAtMs()));
    }

    private FailoverOrderResponse toOrderResponse(FailoverOrderRow order) {
        LeaseRow active = failoverRepo.findActiveLease(order.channelId()).orElse(null);
        LeaseResponse activeLease = active == null ? null : toLeaseResponse(active);

        List<LateReceiptItem> lateItems;
        List<OverrideStackItem> stack;
        if (order.activated() && order.generation() != null) {
            lateItems = failoverRepo.findLateReceipts(order.channelId(), order.generation()).stream()
                    .map(r -> new LateReceiptItem(r.linkId(), r.generation(), r.seq(),
                            atMs(r.receivedAtMs())))
                    .toList();
            stack = parseStack(order.frozenStackJson());
        } else {
            lateItems = List.of();
            stack = List.of();
        }

        LocalDate businessDay = Instant.ofEpochMilli(order.cutoverAtMs()).atZone(ZONE).toLocalDate();
        List<Long> publicationVersions = playoutRepo.findPublicationVersions(
                order.channelId(), businessDay);
        LinkRow source = failoverRepo.findLink(order.channelId(), order.sourceLinkId()).orElse(null);
        LinkRow target = failoverRepo.findLink(order.channelId(), order.targetLinkId()).orElse(null);
        long currentVersion = playoutRepo.getScheduleVersion(order.channelId());
        ScheduleEvidence evidence = new ScheduleEvidence(currentVersion,
                source == null ? null : source.cachedVersion(),
                target == null ? null : target.cachedVersion(),
                publicationVersions);

        return new FailoverOrderResponse(order.failoverKey(), order.channelId(),
                order.channelVersion(), order.sourceLinkId(), order.targetLinkId(),
                order.sourceLastSeq(), order.targetLastSeq(), atMs(order.cutoverAtMs()),
                order.maxLag(), OrderStatus.valueOf(order.status()), order.generation(),
                order.safeCutSeq(), order.scheduleVersion(), stack, activeLease, lateItems,
                evidence, atMs(order.createdAtMs()),
                order.activatedAtMs() == null ? null : atMs(order.activatedAtMs()));
    }

    private List<OverrideStackItem> parseStack(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<OverrideStackItem>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("插播栈快照反序列化失败", e);
        }
    }

    /**
     * 幂等执行：与播出编排共用 playout_request 去重表；同 requestId 同参返回首次快照，
     * 同 requestId 异参 409；业务失败回滚，不占用 requestId。
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
