package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.ChannelConfig;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.ChannelConfigRepository;
import com.example.starter.exposure.repo.ChannelLedgerRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.ReservationRepository.AttributionCount;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignAttributionResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateChannelRequest;
import com.example.starter.exposure.web.MigrateCampaignChannelRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateChannelCapRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>在公告/访客两级额度之上新增渠道总量频控：申请时在同一事务内原子预占渠道、公告、
 * 访客三个名额，任一不足回滚全部；预占单固化创建时的渠道，公告迁移与渠道配置变更
 * 均不改变既有预占的结算渠道。所有写操作以 requestId 为全局幂等键：同键同参重放原
 * 成功结果，异参 409；业务失败随事务回滚，不占幂等键。</p>
 *
 * <p>全局加锁顺序（杜绝跨公告/跨渠道死锁）：幂等行 -> 渠道配置行 ->
 * 渠道日账 -> 公告日总账 -> 访客日账 -> 预占单行；同类多行均按键升序获取。
 * 终态竞争由预占单行锁 + 状态 CAS 保证只允许一个终态，渠道名额不重复扣减或释放。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ChannelConfigRepository channelConfigRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final ChannelLedgerRepository channelLedgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ChannelConfigRepository channelConfigRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               ChannelLedgerRepository channelLedgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.channelConfigRepository = channelConfigRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.channelLedgerRepository = channelLedgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String channelKey = normalizeChannel(request.channelKey());
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + nullable(channelKey);
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            channelKey,
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 无锁找出该公告已到期预占（随后按全局顺序加锁后再结算）
                    List<Reservation> expired =
                            reservationRepository.findExpiredReserved(campaign.campaignId(), now);

                    // 渠道配置行锁（全局顺序中先于账目行）：公告归属渠道且该渠道已配置日额度时
                    // 才受渠道总量频控；公告归属渠道但渠道未配置时不受限制（frozenChannel 为 null）
                    Optional<ChannelConfig> channelConfig = campaign.channelKey() == null
                            ? Optional.empty()
                            : channelConfigRepository.lockById(campaign.channelKey());
                    String frozenChannel = channelConfig.map(ChannelConfig::channelKey).orElse(null);

                    // 渠道名额可能被同渠道其他公告的到期预占占用：一并找出，稍后统一加锁结算
                    List<Reservation> channelExpired = frozenChannel == null
                            ? List.of()
                            : reservationRepository.findExpiredReservedByChannel(frozenChannel, now);

                    // 本次申请涉及的账目行先确保存在（顺序：渠道 -> 公告总账 -> 访客账）
                    if (frozenChannel != null) {
                        channelLedgerRepository.ensureRow(frozenChannel, utcDate);
                    }
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);

                    // 到期预占账目行（公告维度 + 渠道跨公告维度）与本次申请账目行，
                    // 按全局顺序一次性加锁
                    Set<LedgerTarget> targets = ledgerTargets(expired);
                    targets.addAll(ledgerTargets(channelExpired));
                    if (frozenChannel != null) {
                        targets.add(LedgerTarget.channel(frozenChannel, utcDate));
                    }
                    targets.add(LedgerTarget.total(campaign.campaignId(), utcDate));
                    targets.add(LedgerTarget.visitor(campaign.campaignId(), request.visitorId(), utcDate));
                    lockLedgerTargets(targets);

                    // 预占单行最后加锁（reservationId 升序），结算到期预占
                    List<Reservation> allExpired = new ArrayList<>(expired.size() + channelExpired.size());
                    allExpired.addAll(expired);
                    allExpired.addAll(channelExpired);
                    settleExpiredLocked(allExpired, now);

                    // 行锁下读取三侧已用量并校验容量
                    if (frozenChannel != null) {
                        int usedChannel = channelLedgerRepository.lockUsed(frozenChannel, utcDate);
                        if (usedChannel + 1 > channelConfig.orElseThrow().dailyTotalCap()) {
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "channel daily quota exhausted: " + frozenChannel);
                        }
                    }
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429，三个名额均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    if (frozenChannel != null) {
                        channelLedgerRepository.add(frozenChannel, utcDate, 1);
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            frozenChannel,
                            java.sql.Date.valueOf(utcDate),
                            ReservationStatus.RESERVED,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null);
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation snapshot = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            // 先按全局顺序锁账目行，再锁预占单行后结算，避免与申请/取消交叉死锁
            lockLedgerTargets(ledgerTargets(List.of(snapshot)));
            expireIfDue(requireLocked(reservationId), clock.millis());
            return ReservationResponse.from(requireLocked(reservationId));
        });
    }

    @Override
    public QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpiredWithLocks(
                    reservationRepository.findExpiredReserved(campaignId, now), now);

            int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);
            if (visitorId == null || visitorId.isBlank()) {
                return new QuotaResponse(
                        campaignId, null, utcDate,
                        campaign.dailyTotalCap(), usedTotal,
                        campaign.dailyTotalCap() - usedTotal,
                        null, null, null, now);
            }
            int usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);
            return new QuotaResponse(
                    campaignId, visitorId, utcDate,
                    campaign.dailyTotalCap(), usedTotal,
                    campaign.dailyTotalCap() - usedTotal,
                    campaign.perVisitorDailyCap(), usedVisitor,
                    campaign.perVisitorDailyCap() - usedVisitor,
                    now);
        });
    }

    @Override
    public ChannelResponse createChannel(CreateChannelRequest request) {
        String fingerprint = request.channelKey() + "|" + request.dailyTotalCap();
        return runIdempotent(request.requestId(), Operation.CREATE_CHANNEL, fingerprint,
                ChannelResponse.class, () -> {
                    if (channelConfigRepository.findById(request.channelKey()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "channel already exists: " + request.channelKey());
                    }
                    long now = clock.millis();
                    ChannelConfig config = new ChannelConfig(
                            request.channelKey(), request.dailyTotalCap(), 0, now, now);
                    try {
                        channelConfigRepository.insert(config);
                    } catch (DuplicateKeyException duplicate) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "channel already exists: " + request.channelKey());
                    }
                    return ChannelResponse.from(config);
                });
    }

    @Override
    public ChannelResponse updateChannelCap(String channelKey, UpdateChannelCapRequest request) {
        String fingerprint = channelKey + "|" + request.dailyTotalCap() + "|" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.UPDATE_CHANNEL, fingerprint,
                ChannelResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);

                    // 配置行锁先行，与申请事务在配置行上串行化，按事务提交顺序裁决
                    ChannelConfig config = channelConfigRepository.lockById(channelKey)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "channel not found: " + channelKey));

                    // 渠道当日账目行 -> 渠道当日全部预占行（顺序与全局加锁顺序一致），
                    // 与并发确认/取消串行，确保“当前已确认数”裁决准确
                    channelLedgerRepository.ensureRow(channelKey, utcDate);
                    channelLedgerRepository.lockUsed(channelKey, utcDate);
                    List<Reservation> todayReservations =
                            reservationRepository.lockByChannelDate(channelKey, utcDate);
                    int confirmedCount = 0;
                    for (Reservation reservation : todayReservations) {
                        if (reservation.status() == ReservationStatus.CONFIRMED) {
                            confirmedCount++;
                        }
                    }
                    if (request.dailyTotalCap() < confirmedCount) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "new cap " + request.dailyTotalCap()
                                        + " is below current confirmed count " + confirmedCount
                                        + " for channel " + channelKey);
                    }

                    boolean updated = channelConfigRepository.compareAndUpdateCap(
                            channelKey, request.dailyTotalCap(), request.expectedVersion(), now);
                    if (!updated) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "channel config version conflict: expected " + request.expectedVersion()
                                        + ", actual " + config.version());
                    }
                    return ChannelResponse.from(channelConfigRepository.lockById(channelKey).orElseThrow());
                });
    }

    @Override
    public CampaignResponse migrateCampaignChannel(String campaignId,
                                                   MigrateCampaignChannelRequest request) {
        String targetChannel = normalizeChannel(request.channelKey());
        String fingerprint = campaignId + "|" + nullable(targetChannel);
        return runIdempotent(request.requestId(), Operation.MIGRATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    Campaign campaign = requireCampaign(campaignId);
                    // 仅改公告当前归属；既有预占固化的 channel_key 不变，仍按原渠道结算
                    campaignRepository.updateChannel(campaignId, targetChannel);
                    return CampaignResponse.from(new Campaign(
                            campaign.campaignId(),
                            campaign.dailyTotalCap(),
                            campaign.perVisitorDailyCap(),
                            targetChannel,
                            campaign.createdAtUtc()));
                });
    }

    @Override
    public ChannelUsageResponse queryChannelUsage(String channelKey, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            ChannelConfig config = channelConfigRepository.findById(channelKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "channel not found: " + channelKey));
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            // 先按全局顺序结算该渠道当日到期预占，释放渠道及其各公告两侧名额
            settleExpiredWithLocks(
                    reservationRepository.findExpiredReservedByChannel(channelKey, now), now);

            int used = channelLedgerRepository.getUsed(channelKey, utcDate);
            return new ChannelUsageResponse(
                    channelKey, utcDate,
                    config.dailyTotalCap(), used,
                    config.dailyTotalCap() - used, now);
        });
    }

    @Override
    public List<ReservationResponse> listReservations(String campaignId, String channelKey,
                                                      LocalDate utcDate) {
        return txTemplate.execute(status -> {
            long now = clock.millis();
            // 惰性结算：按过滤条件覆盖到的范围先结算到期预占，保证明细状态鲜活
            if (campaignId != null) {
                requireCampaign(campaignId);
                settleExpiredWithLocks(
                        reservationRepository.findExpiredReserved(campaignId, now), now);
            } else if (channelKey != null) {
                settleExpiredWithLocks(
                        reservationRepository.findExpiredReservedByChannel(channelKey, now), now);
            } else {
                settleExpiredWithLocks(reservationRepository.findAllExpiredReserved(now), now);
            }
            return reservationRepository.search(campaignId, channelKey, utcDate).stream()
                    .map(ReservationResponse::from)
                    .toList();
        });
    }

    @Override
    public CampaignAttributionResponse attributionStats(String campaignId) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            // 先结算到期预占，RESERVED/EXPIRED 分组才反映当前状态
            settleExpiredWithLocks(
                    reservationRepository.findExpiredReserved(campaignId, clock.millis()),
                    clock.millis());

            Map<String, int[]> countsByChannel = new LinkedHashMap<>();
            for (AttributionCount count : reservationRepository.groupAttribution(campaignId)) {
                int[] counters = countsByChannel.computeIfAbsent(
                        count.channelKey() == null ? "" : count.channelKey(), key -> new int[4]);
                counters[statusIndex(ReservationStatus.valueOf(count.status()))] += count.count();
            }
            List<CampaignAttributionResponse.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, int[]> group : countsByChannel.entrySet()) {
                int[] counters = group.getValue();
                entries.add(new CampaignAttributionResponse.Entry(
                        group.getKey().isEmpty() ? null : group.getKey(),
                        counters[0], counters[1], counters[2], counters[3]));
            }
            return new CampaignAttributionResponse(campaignId, entries);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        CREATE_CHANNEL,
        UPDATE_CHANNEL,
        MIGRATE_CAMPAIGN
    }

    /**
     * 账目行加锁目标。kind 决定加锁顺序（渠道日账 -&gt; 公告日总账 -&gt; 访客日账），
     * 同类按 sortKey 升序，配合预占单行最后加锁构成全局无死锁顺序。
     */
    private record LedgerTarget(Kind kind, String sortKey, String primary,
                                String visitor, LocalDate utcDate) {
        private enum Kind {CHANNEL, TOTAL, VISITOR}

        static LedgerTarget channel(String channelKey, LocalDate utcDate) {
            return new LedgerTarget(Kind.CHANNEL, "0|" + channelKey + "|" + utcDate,
                    channelKey, null, utcDate);
        }

        static LedgerTarget total(String campaignId, LocalDate utcDate) {
            return new LedgerTarget(Kind.TOTAL, "1|" + campaignId + "|" + utcDate,
                    campaignId, null, utcDate);
        }

        static LedgerTarget visitor(String campaignId, String visitorId, LocalDate utcDate) {
            return new LedgerTarget(Kind.VISITOR, "2|" + campaignId + "|" + visitorId + "|" + utcDate,
                    campaignId, visitorId, utcDate);
        }
    }

    /**
     * 在事务内执行业务并维护幂等记录。先以 pending 行抢占 requestId，再执行业务，
     * 成功响应与业务变更在同一事务原子提交：这样乐观锁类操作（如渠道改额）在并发同键
     * 下不会有第二个事务先执行业务再撞唯一键，所有后来者只重放胜出者结果。
     * 业务失败随事务回滚（pending 行一并消失），不占幂等键。
     */
    private <T> T runIdempotent(String requestId, Operation operation, String fingerprint,
                                Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    if (existing.isPresent()) {
                        return replayOrConflict(existing.get(), operation, fingerprint,
                                requestId, responseType);
                    }
                    // 先抢占幂等键（response 暂空）：与并发同键事务在唯一约束上仲裁
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, null), clock.millis());
                    T result = action.get();
                    idempotencyRepository.updateResponse(requestId, writeJson(result));
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后由 lockById 阻塞重放
                if (!awaitNextAttempt(deadline, requestId)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
            } catch (PessimisticLockingFailureException lockTimeout) {
                // 胜出事务仍未提交导致行锁等待超时：等待后重试重放
                if (!awaitNextAttempt(deadline, requestId)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
            }
        }
    }

    /** 已存在幂等记录：同操作同参重放原响应，异参 409。 */
    private <T> T replayOrConflict(IdempotencyRecord record, Operation operation, String fingerprint,
                                   String requestId, Class<T> responseType) {
        if (!record.operation().equals(operation.name())
                || !record.requestFingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "idempotency key reused with different parameters: " + requestId);
        }
        if (record.responseJson() == null) {
            // 抢到键的事务仍在执行业务（理论上行锁释放即已提交，防御性等待）
            throw new PessimisticLockingFailureException(
                    "idempotent response pending: " + requestId);
        }
        try {
            return objectMapper.readValue(record.responseJson(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("failed to replay idempotent response", e);
        }
    }

    /** 同键竞争退避；超出等待预算返回 false。 */
    private boolean awaitNextAttempt(long deadline, String requestId) {
        if (System.currentTimeMillis() >= deadline) {
            return false;
        }
        try {
            Thread.sleep(5L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
        }
        return true;
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；按全局顺序先锁账目行再锁预占单行，
     * 行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation snapshot = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        lockLedgerTargets(ledgerTargets(List.of(snapshot)));
        Reservation current = requireLocked(reservationId);

        // 本单若已到期先结算（本事务已持其全部账目行锁）
        expireIfDue(current, now);
        current = requireLocked(reservationId);

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认：渠道、公告、访客三个名额均保持占用，无账目变更
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消按预占单固化的渠道同时释放三侧名额
                releaseAllQuotas(current);
            }
        } else if (current.status() == ReservationStatus.CONFIRMED
                || current.status() == ReservationStatus.CANCELLED
                || current.status() == ReservationStatus.EXPIRED) {
            boolean sameTerminal = isConfirm
                    ? current.status() == ReservationStatus.CONFIRMED
                    : current.status() == ReservationStatus.CANCELLED;
            if (!sameTerminal) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "reservation is " + current.status() + ", cannot "
                                + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放名额
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        return ReservationResponse.from(requireLocked(reservationId));
    }

    /**
     * 收集一批预占单占用的全部账目行（渠道行仅收集固化了渠道的预占）。
     */
    private Set<LedgerTarget> ledgerTargets(List<Reservation> reservations) {
        Set<LedgerTarget> targets = new TreeSet<>(Comparator.comparing(LedgerTarget::sortKey));
        for (Reservation reservation : reservations) {
            LocalDate utcDate = reservation.utcDate().toLocalDate();
            if (reservation.channelKey() != null) {
                targets.add(LedgerTarget.channel(reservation.channelKey(), utcDate));
            }
            targets.add(LedgerTarget.total(reservation.campaignId(), utcDate));
            targets.add(LedgerTarget.visitor(reservation.campaignId(), reservation.visitorId(), utcDate));
        }
        return targets;
    }

    /** 按全局顺序（渠道 -&gt; 公告总账 -&gt; 访客账，同类按键升序）对账目行加锁。 */
    private void lockLedgerTargets(Set<LedgerTarget> targets) {
        for (LedgerTarget target : targets) {
            switch (target.kind()) {
                case CHANNEL -> channelLedgerRepository.lockUsed(target.primary(), target.utcDate());
                case TOTAL -> ledgerRepository.lockUsedTotal(target.primary(), target.utcDate());
                case VISITOR -> ledgerRepository.lockUsedVisitor(
                        target.primary(), target.visitor(), target.utcDate());
            }
        }
    }

    /**
     * 读路径结算：按全局顺序先锁账目行再锁预占单行（reservationId 升序），
     * 仅 CAS 成功者释放名额，避免与申请/取消/修改额度交叉死锁。
     */
    private void settleExpiredWithLocks(List<Reservation> snapshots, long now) {
        if (snapshots.isEmpty()) {
            return;
        }
        lockLedgerTargets(ledgerTargets(snapshots));
        settleExpiredLocked(snapshots, now);
    }

    /**
     * 结算到期预占：调用方须已按全局顺序持有全部相关账目行锁；预占单行按
     * reservationId 升序加锁后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放名额。
     */
    private void settleExpiredLocked(List<Reservation> snapshots, long now) {
        snapshots.stream()
                .map(Reservation::reservationId)
                .distinct()
                .sorted()
                .map(reservationRepository::lockById)
                .flatMap(Optional::stream)
                .forEach(locked -> expireIfDue(locked, now));
    }

    /**
     * 若传入预占单（调用方已持其行锁与账目行锁）已到期，则 CAS 转 EXPIRED 并释放
     * 固化渠道/公告/访客三侧名额；未到期或已非 RESERVED 则不做任何变更。
     */
    private void expireIfDue(Reservation reservation, long now) {
        if (reservation.status() == ReservationStatus.RESERVED
                && now >= reservation.expiresAtUtc()) {
            boolean won = reservationRepository.compareAndSetStatus(
                    reservation.reservationId(),
                    ReservationStatus.RESERVED,
                    ReservationStatus.EXPIRED,
                    now);
            if (won) {
                releaseAllQuotas(reservation);
            }
        }
    }

    /**
     * 释放预占单占用的全部名额：渠道（仅固化了渠道的预占）-&gt; 公告总账 -&gt; 访客账。
     * 仅在取消/过期 CAS 胜出后调用，重复终态操作不会走到这里。
     */
    private void releaseAllQuotas(Reservation reservation) {
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        if (reservation.channelKey() != null) {
            channelLedgerRepository.release(reservation.channelKey(), utcDate);
        }
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        ledgerRepository.releaseVisitor(
                reservation.campaignId(), reservation.visitorId(), utcDate);
    }

    private int statusIndex(ReservationStatus status) {
        return switch (status) {
            case RESERVED -> 0;
            case CONFIRMED -> 1;
            case CANCELLED -> 2;
            case EXPIRED -> 3;
        };
    }

    private String normalizeChannel(String channelKey) {
        if (channelKey == null || channelKey.isBlank()) {
            return null;
        }
        return channelKey.trim();
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private Reservation requireLocked(String reservationId) {
        return reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
