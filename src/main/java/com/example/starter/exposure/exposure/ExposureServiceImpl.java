package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.ChannelCapConfig;
import com.example.starter.exposure.domain.ChannelReservation;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.ChannelCapConfigRepository;
import com.example.starter.exposure.repo.ChannelLedgerRepository;
import com.example.starter.exposure.repo.ChannelLedgerRepository.ChannelUsage;
import com.example.starter.exposure.repo.ChannelReservationRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelConfigResponse;
import com.example.starter.exposure.web.ChannelReservationResponse;
import com.example.starter.exposure.web.ChannelStatsResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateChannelRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertChannelConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>渠道日总量频控：公告归属已配置渠道时，申请须在同一事务内原子预占渠道当日
 * 一个名额；任一额度不足返回 429 且两侧均不消耗。确认结算渠道已确认数，
 * 取消/过期同时释放公告与渠道名额。渠道预占记录在创建时固化公告、访客、
 * UTC 日与预占时刻，公告迁移渠道不影响既有预占的结算口径。</p>
 *
 * <p>全局行锁顺序固定为：预占单行 -&gt; 渠道日账 -&gt; 公告日总账 -&gt; 访客日账，
 * 配置写路径为 配置行 -&gt; 渠道日账，避免并发死锁。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ChannelCapConfigRepository channelCapConfigRepository;
    private final ChannelLedgerRepository channelLedgerRepository;
    private final ChannelReservationRepository channelReservationRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ChannelCapConfigRepository channelCapConfigRepository,
                               ChannelLedgerRepository channelLedgerRepository,
                               ChannelReservationRepository channelReservationRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.channelCapConfigRepository = channelCapConfigRepository;
        this.channelLedgerRepository = channelLedgerRepository;
        this.channelReservationRepository = channelReservationRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String channelKey = request.normalizedChannelKey();
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + channelKey;
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

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 公告归属渠道且渠道已配置时，申请须同时占用渠道当日名额
                    String channelKey = campaign.channelKey();
                    ChannelCapConfig channelConfig = channelKey == null ? null
                            : channelCapConfigRepository.findByKey(channelKey).orElse(null);

                    // 固定加锁顺序：渠道日账 -> 公告当日总账 -> 访客当日账，避免死锁
                    if (channelConfig != null) {
                        channelLedgerRepository.ensureRow(channelKey, utcDate);
                    }
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    ChannelUsage channelUsage = channelConfig == null ? null
                            : channelLedgerRepository.lockUsage(channelKey, utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429；异常回滚保证两侧额度均不增加、不创建预占
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()
                            || (channelUsage != null
                                    && channelUsage.usedTotal() + 1 > channelConfig.dailyCap())) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    if (channelConfig != null) {
                        channelLedgerRepository.addUsed(channelKey, utcDate);
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            ReservationStatus.RESERVED,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null);
                    reservationRepository.insert(reservation);
                    if (channelConfig != null) {
                        // 渠道预占记录固化公告、访客、日期、渠道与预占时刻，不随迁移变更
                        channelReservationRepository.insert(new ChannelReservation(
                                reservationId, channelKey, campaign.campaignId(),
                                request.visitorId(), java.sql.Date.valueOf(utcDate),
                                ReservationStatus.RESERVED, now, null));
                    }
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
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            expireIfDue(locked, clock.millis());
            return ReservationResponse.from(
                    reservationRepository.lockById(reservationId).orElseThrow());
        });
    }

    @Override
    public QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpired(campaignId, now);

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
    public ChannelConfigResponse upsertChannelConfig(String channelKey, UpsertChannelConfigRequest request) {
        String fingerprint = channelKey + "|" + request.dailyCap() + "|" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.UPSERT_CHANNEL_CONFIG, fingerprint,
                ChannelConfigResponse.class, () -> {
                    long now = clock.millis();
                    var existing = channelCapConfigRepository.lockByKey(channelKey);
                    if (request.expectedVersion() == null) {
                        // 新建：渠道已配置视为冲突
                        if (existing.isPresent()) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "channel config already exists: " + channelKey);
                        }
                        try {
                            channelCapConfigRepository.insert(channelKey, request.dailyCap(), now);
                        } catch (DuplicateKeyException duplicate) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "channel config already exists: " + channelKey);
                        }
                    } else {
                        // 修改：渠道须已配置，版本须匹配，且不得下调到低于当前已确认数
                        ChannelCapConfig config = existing.orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND, "channel config not found: " + channelKey));
                        LocalDate today = LocalDate.now(clock);
                        int confirmed = channelLedgerRepository.lockUsage(channelKey, today).confirmed();
                        if (request.dailyCap() < confirmed) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "dailyCap " + request.dailyCap()
                                            + " is below current confirmed " + confirmed
                                            + " for channel " + channelKey);
                        }
                        if (!channelCapConfigRepository.compareAndSetCap(
                                channelKey, request.dailyCap(), request.expectedVersion(), now)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "channel config version conflict for " + channelKey
                                            + ", expectedVersion=" + request.expectedVersion()
                                            + ", current=" + config.version());
                        }
                    }
                    return ChannelConfigResponse.from(
                            channelCapConfigRepository.findByKey(channelKey).orElseThrow());
                });
    }

    @Override
    public CampaignResponse migrateCampaignChannel(String campaignId, MigrateChannelRequest request) {
        String channelKey = request.normalizedChannelKey();
        String fingerprint = campaignId + "|" + channelKey;
        return runIdempotent(request.requestId(), Operation.MIGRATE_CAMPAIGN_CHANNEL, fingerprint,
                CampaignResponse.class, () -> {
                    campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    campaignRepository.updateChannelKey(campaignId, channelKey);
                    return CampaignResponse.from(campaignRepository.findById(campaignId).orElseThrow());
                });
    }

    @Override
    public ChannelUsageResponse queryChannelUsage(String channelKey, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            ChannelCapConfig config = channelCapConfigRepository.findByKey(channelKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "channel config not found: " + channelKey));
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpiredForChannel(channelKey, now);

            ChannelUsage usage = channelLedgerRepository.getUsage(channelKey, utcDate);
            return new ChannelUsageResponse(
                    channelKey, utcDate, config.dailyCap(),
                    usage.usedTotal(), usage.confirmed(),
                    config.dailyCap() - usage.usedTotal(), now);
        });
    }

    @Override
    public List<ChannelReservationResponse> listChannelReservations(String channelKey,
                                                                    LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            settleExpiredForChannel(channelKey, now);
            return channelReservationRepository.listByChannelAndDate(channelKey, utcDate)
                    .stream()
                    .map(ChannelReservationResponse::from)
                    .toList();
        });
    }

    @Override
    public ChannelStatsResponse queryChannelStats(String channelKey, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            settleExpiredForChannel(channelKey, now);
            List<ChannelStatsResponse.Item> items = channelReservationRepository
                    .statsByCampaign(channelKey, utcDate)
                    .stream()
                    .map(s -> new ChannelStatsResponse.Item(
                            s.campaignId(), s.reserved(), s.confirmed(), s.cancelled(), s.expired()))
                    .toList();
            return new ChannelStatsResponse(channelKey, utcDate, items);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        UPSERT_CHANNEL_CONFIG,
        MIGRATE_CAMPAIGN_CHANNEL
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。
     */
    private <T> T runIdempotent(String requestId, Operation operation, String fingerprint,
                                Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    if (existing.isPresent()) {
                        IdempotencyRecord record = existing.get();
                        if (!record.operation().equals(operation.name())
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.get();
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, writeJson(result)), clock.millis());
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后重放
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
                }
            }
        }
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 渠道日账 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认结算渠道名额：已确认数 +1（占用数在预占时已计入）
                settleChannelOnConfirm(reservationId, now);
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消同时释放渠道与两级公告额度；固定顺序先渠道、再总账、后访客账
                releaseChannelIfPresent(reservationId, ReservationStatus.CANCELLED, now);
                ledgerRepository.releaseTotal(current.campaignId(), current.utcDate().toLocalDate());
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), current.utcDate().toLocalDate());
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
            // 重复同类终态操作：返回原状态，不重复扣减或释放
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 结算某渠道下已到期的预占：渠道预占记录仍为 RESERVED 的逐张锁主单判定，
     * 仅 CAS 成功者释放渠道与公告两侧额度。
     */
    private void settleExpiredForChannel(String channelKey, long now) {
        for (ChannelReservation record : channelReservationRepository.listReservedByChannel(channelKey)) {
            reservationRepository.lockById(record.reservationId())
                    .ifPresent(reservation -> expireIfDue(reservation, now));
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放渠道与两级额度；
     * 未到期或已非 RESERVED 则不做任何变更。
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
                LocalDate utcDate = reservation.utcDate().toLocalDate();
                releaseChannelIfPresent(reservation.reservationId(), ReservationStatus.EXPIRED, now);
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    /**
     * 确认成功时结算渠道名额：渠道预占记录同步转 CONFIRMED，渠道当日已确认数 +1。
     * 调用方已持主预占单行锁且主单 CAS 成功，渠道记录必为 RESERVED。
     */
    private void settleChannelOnConfirm(String reservationId, long now) {
        channelReservationRepository.findById(reservationId).ifPresent(record -> {
            if (!channelReservationRepository.compareAndSetStatus(
                    reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                throw new IllegalStateException(
                        "channel reservation out of sync for " + reservationId);
            }
            channelLedgerRepository.addConfirmed(
                    record.channelKey(), record.utcDate().toLocalDate());
        });
    }

    /**
     * 取消/过期时释放渠道名额：渠道预占记录同步转终态，渠道当日占用数 -1。
     * 按创建时固化的渠道与日期结算，不随公告渠道迁移变更。
     */
    private void releaseChannelIfPresent(String reservationId, ReservationStatus target, long now) {
        channelReservationRepository.findById(reservationId).ifPresent(record -> {
            if (!channelReservationRepository.compareAndSetStatus(
                    reservationId, ReservationStatus.RESERVED, target, now)) {
                throw new IllegalStateException(
                        "channel reservation out of sync for " + reservationId);
            }
            channelLedgerRepository.releaseUsed(
                    record.channelKey(), record.utcDate().toLocalDate());
        });
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
