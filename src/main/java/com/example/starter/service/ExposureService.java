package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.Campaign;
import com.example.starter.domain.QuotaAccount;
import com.example.starter.domain.Reservation;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.repository.CampaignRepository;
import com.example.starter.repository.IdempotencyRepository;
import com.example.starter.repository.QuotaRepository;
import com.example.starter.repository.ReservationRepository;
import com.example.starter.service.IdempotencySupport.IdempotentReplayException;
import com.example.starter.web.dto.ApplyExposureRequest;
import com.example.starter.web.dto.CampaignResponse;
import com.example.starter.web.dto.CreateCampaignRequest;
import com.example.starter.web.dto.QuotaResponse;
import com.example.starter.web.dto.QuotaView;
import com.example.starter.web.dto.ReservationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 公告曝光频控核心服务。
 *
 * <p>规则要点：</p>
 * <ul>
 *   <li>预占有效期 60 秒，占用公告当日 + 访客当日两级额度；额度日固定为申请时 UTC 日。</li>
 *   <li>任一额度已满返回 429，两级额度均不增加（事务回滚）。</li>
 *   <li>所有操作先按需结算到期预占（now &gt;= expiresAt 即 EXPIRED），不依赖后台定时器。</li>
 *   <li>RESERVED 仅允许单次终态迁移；并发确认/取消/过期回收由行锁 + 条件 UPDATE 串行化。</li>
 *   <li>写操作 requestId 全局唯一，同键同参重放原成功结果，异参 409；失败不占键。</li>
 * </ul>
 */
@Service
public class ExposureService {

    /** 预占有效期：60 秒。 */
    public static final long RESERVATION_TTL_MS = 60_000L;

    private static final String OP_CREATE_CAMPAIGN = "CREATE_CAMPAIGN";
    private static final String OP_APPLY = "APPLY";
    private static final String OP_CONFIRM = "CONFIRM";
    private static final String OP_CANCEL = "CANCEL";

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final QuotaRepository quotaRepository;
    private final IdempotencySupport idempotency;
    private final TransactionTemplate transactionTemplate;

    public ExposureService(Clock clock,
                           CampaignRepository campaignRepository,
                           ReservationRepository reservationRepository,
                           QuotaRepository quotaRepository,
                           IdempotencySupport idempotency,
                           TransactionTemplate transactionTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.quotaRepository = quotaRepository;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建公告（幂等）。
     */
    public ServiceResult<Object> createCampaign(CreateCampaignRequest request) {
        String hash = idempotency.hash(List.of(
                request.getCampaignId(), request.getDailyTotalCap(), request.getVisitorCap()));
        return executeIdempotent(request.getRequestId(), OP_CREATE_CAMPAIGN, hash, () -> {
            long now = clock.millis();
            String campaignId = request.getCampaignId();
            if (campaignRepository.findByCampaignId(campaignId).isPresent()) {
                throw new ConflictException("campaign '" + campaignId + "' already exists");
            }
            Campaign campaign = new Campaign();
            campaign.setCampaignId(campaignId);
            campaign.setDailyTotalCap(request.getDailyTotalCap());
            campaign.setVisitorCap(request.getVisitorCap());
            campaign.setCreatedAt(now);
            campaignRepository.insert(campaign);
            return ServiceResult.created(new CampaignResponse(
                    campaignId, request.getDailyTotalCap(), request.getVisitorCap(), now));
        });
    }

    /**
     * 申请曝光：创建 60 秒预占并占用两级额度（幂等）。
     */
    public ServiceResult<Object> apply(ApplyExposureRequest request) {
        String hash = idempotency.hash(List.of(request.getCampaignId(), request.getVisitorId()));
        return executeIdempotent(request.getRequestId(), OP_APPLY, hash, () -> {
            long now = clock.millis();
            String utcDate = utcDate(now);
            String campaignId = request.getCampaignId();
            String visitorId = request.getVisitorId();

            // 公告额度创建后不可变，无需行锁；先统一结算当日到期预占。
            Campaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new NotFoundException("campaign '" + campaignId + "' not found"));
            settleExpired(campaignId, utcDate, now);

            // 固定加锁顺序：公告账户 → 访客账户，避免死锁。
            QuotaAccount campaignAccount = quotaRepository.getOrCreateForUpdate(
                    QuotaAccount.SCOPE_CAMPAIGN, campaignId, utcDate, campaign.getDailyTotalCap());
            QuotaAccount visitorAccount = quotaRepository.getOrCreateForUpdate(
                    QuotaAccount.SCOPE_VISITOR, visitorKey(campaignId, visitorId), utcDate,
                    campaign.getVisitorCap());

            // 条件占用本身防超卖；任一不足抛 429，事务回滚保证两个额度均不增加。
            if (!quotaRepository.tryHold(campaignAccount.getId())) {
                throw new RateLimitException(
                        "campaign daily total quota exhausted for " + utcDate);
            }
            if (!quotaRepository.tryHold(visitorAccount.getId())) {
                throw new RateLimitException(
                        "visitor daily quota exhausted for campaign '" + campaignId + "' on " + utcDate);
            }

            Reservation reservation = new Reservation();
            reservation.setReservationId(generateReservationId(campaignId, visitorId, now));
            reservation.setCampaignId(campaignId);
            reservation.setVisitorId(visitorId);
            reservation.setUtcDate(utcDate);
            reservation.setStatus(ReservationStatus.RESERVED);
            reservation.setCreatedAt(now);
            reservation.setExpiresAt(now + RESERVATION_TTL_MS);
            reservationRepository.insert(reservation);

            return ServiceResult.created(toResponse(reservation));
        });
    }

    /**
     * 确认预占（幂等）：必须在到期时刻之前；重复确认返回原状态。
     */
    public ServiceResult<Object> confirm(String reservationId, String requestId) {
        String hash = idempotency.hash(List.of(reservationId));
        return executeIdempotent(requestId, OP_CONFIRM, hash, () -> {
            long now = clock.millis();
            Reservation reservation = lockReservation(reservationId);
            settleOneIfDue(reservation, now);
            switch (reservation.getStatus()) {
                case CONFIRMED -> {
                    return ServiceResult.ok(toResponse(reservation));
                }
                case CANCELLED, EXPIRED -> throw new ConflictException(
                        "reservation '" + reservationId + "' is " + reservation.getStatus()
                                + ", cannot confirm");
                case RESERVED -> {
                    if (reservationRepository.compareAndSetStatus(
                            reservation.getId(), ReservationStatus.RESERVED, ReservationStatus.CONFIRMED) != 1) {
                        throw new ConflictException("reservation state changed concurrently");
                    }
                    reservation.setStatus(ReservationStatus.CONFIRMED);
                    return ServiceResult.ok(toResponse(reservation));
                }
                default -> throw new IllegalStateException(reservation.getStatus().name());
            }
        });
    }

    /**
     * 取消预占（幂等）：仅 RESERVED 可取消并释放两级额度；重复取消返回原状态。
     */
    public ServiceResult<Object> cancel(String reservationId, String requestId) {
        String hash = idempotency.hash(List.of(reservationId));
        return executeIdempotent(requestId, OP_CANCEL, hash, () -> {
            long now = clock.millis();
            Reservation reservation = lockReservation(reservationId);
            settleOneIfDue(reservation, now);
            switch (reservation.getStatus()) {
                case CANCELLED -> {
                    return ServiceResult.ok(toResponse(reservation));
                }
                case CONFIRMED, EXPIRED -> throw new ConflictException(
                        "reservation '" + reservationId + "' is " + reservation.getStatus()
                                + ", cannot cancel");
                case RESERVED -> {
                    if (reservationRepository.compareAndSetStatus(
                            reservation.getId(), ReservationStatus.RESERVED, ReservationStatus.CANCELLED) != 1) {
                        throw new ConflictException("reservation state changed concurrently");
                    }
                    reservation.setStatus(ReservationStatus.CANCELLED);
                    releaseQuotas(reservation);
                    return ServiceResult.ok(toResponse(reservation));
                }
                default -> throw new IllegalStateException(reservation.getStatus().name());
            }
        });
    }

    /**
     * 查询预占明细；先结算该预占过期状态，不做真实等待。
     */
    public ReservationResponse getReservation(String reservationId) {
        long now = clock.millis();
        Reservation reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "reservation '" + reservationId + "' not found"));
        if (reservation.getStatus() == ReservationStatus.RESERVED && now >= reservation.getExpiresAt()) {
            // 只读查询也负责惰性结算；独立短事务提交过期回收。
            long pk = reservation.getId();
            transactionTemplate.executeWithoutResult(status -> {
                Reservation locked = reservationRepository.findByIdForUpdate(pk).orElseThrow();
                settleOneIfDue(locked, now);
            });
            reservation = reservationRepository.findByReservationId(reservationId).orElseThrow();
        }
        return toResponse(reservation);
    }

    /**
     * 按公告/访客/UTC 日查询额度；查询前先结算当日到期预占。
     * visitorId 为空时返回该公告当日全部访客维度账目（历史日可查）。
     */
    public QuotaResponse getQuota(String campaignId, String visitorId, String utcDate) {
        String date = utcDate != null ? utcDate : utcDate(clock.millis());
        Campaign campaign = campaignRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new NotFoundException("campaign '" + campaignId + "' not found"));
        transactionTemplate.executeWithoutResult(status ->
                settleExpired(campaignId, date, clock.millis()));

        QuotaAccount campaignAccount = quotaRepository
                .find(QuotaAccount.SCOPE_CAMPAIGN, campaignId, date).orElse(null);
        QuotaView campaignView = new QuotaView(
                QuotaAccount.SCOPE_CAMPAIGN, campaignId, null, date,
                campaign.getDailyTotalCap(),
                campaignAccount != null ? campaignAccount.getHeldCount() : 0);

        List<QuotaView> visitorViews = new ArrayList<>();
        if (visitorId != null) {
            QuotaAccount visitorAccount = quotaRepository
                    .find(QuotaAccount.SCOPE_VISITOR, visitorKey(campaignId, visitorId), date)
                    .orElse(null);
            visitorViews.add(new QuotaView(
                    QuotaAccount.SCOPE_VISITOR, campaignId, visitorId, date,
                    campaign.getVisitorCap(),
                    visitorAccount != null ? visitorAccount.getHeldCount() : 0));
        } else {
            for (QuotaAccount account : quotaRepository.findVisitorAccounts(campaignId, date)) {
                String key = account.getQuotaKey();
                String vid = key.substring(campaignId.length() + 1);
                visitorViews.add(new QuotaView(
                        QuotaAccount.SCOPE_VISITOR, campaignId, vid, date,
                        account.getCap(), account.getHeldCount()));
            }
        }
        return new QuotaResponse(campaignView, visitorViews);
    }

    // ---------------------------------------------------------------------
    // 内部逻辑（调用方持有事务与行锁）
    // ---------------------------------------------------------------------

    /**
     * 幂等执行模板：事务内先查重放键，业务成功后原子写幂等记录；
     * 并发同键落败方回滚并重放先提交者的结果。
     */
    private ServiceResult<Object> executeIdempotent(String requestId, String operation, String hash,
                                                    TransactionalAction action) {
        try {
            return transactionTemplate.execute(status -> {
                IdempotencyRepository.Record existing = idempotency.requireMatch(requestId, operation, hash);
                if (existing != null) {
                    return replay(existing);
                }
                ServiceResult<Object> result = action.run();
                idempotency.commit(requestId, operation, hash, result.status(), result.body(),
                        clock.millis());
                return result;
            });
        } catch (IdempotentReplayException e) {
            // 业务事务已回滚；事务外重查并重放首个成功结果。
            IdempotencyRepository.Record record = idempotency.requireMatch(requestId, operation, hash);
            return replay(record);
        }
    }

    private static ServiceResult<Object> replay(IdempotencyRepository.Record record) {
        return new ServiceResult<>(record.httpStatus(), new RawJson(record.responseBody()));
    }

    /**
     * 结算指定公告某日全部到期 RESERVED 预占。
     * 两阶段执行以避免与单条取消/过期回收形成死锁环：
     * 先按 id 顺序锁定全部候选行，再逐行迁移 EXPIRED 并释放两级额度。
     */
    private void settleExpired(String campaignId, String utcDate, long now) {
        List<Reservation> candidates = reservationRepository.findExpired(campaignId, utcDate, now);
        List<Reservation> lockedRows = new ArrayList<>(candidates.size());
        for (Reservation candidate : candidates) {
            lockedRows.add(reservationRepository.findByIdForUpdate(candidate.getId()).orElseThrow());
        }
        for (Reservation locked : lockedRows) {
            settleOneIfDue(locked, now);
        }
    }

    /**
     * 结算单个预占（调用方已持有该行锁）：到期即转 EXPIRED 并释放额度。
     */
    private void settleOneIfDue(Reservation reservation, long now) {
        if (reservation.getStatus() == ReservationStatus.RESERVED && now >= reservation.getExpiresAt()) {
            if (reservationRepository.compareAndSetStatus(
                    reservation.getId(), ReservationStatus.RESERVED, ReservationStatus.EXPIRED) == 1) {
                reservation.setStatus(ReservationStatus.EXPIRED);
                releaseQuotas(reservation);
            } else if (reservation.getStatus() == ReservationStatus.RESERVED) {
                Reservation refreshed = reservationRepository
                        .findByIdForUpdate(reservation.getId()).orElseThrow();
                reservation.setStatus(refreshed.getStatus());
            }
        }
    }

    /**
     * 释放某预占占用的两级额度（各 1 次）；按固定顺序加账户行锁，条件 UPDATE 防止变负。
     */
    private void releaseQuotas(Reservation reservation) {
        QuotaAccount campaignAccount = quotaRepository.findForUpdate(
                QuotaAccount.SCOPE_CAMPAIGN, reservation.getCampaignId(), reservation.getUtcDate())
                .orElseThrow(() -> new IllegalStateException("campaign quota account missing"));
        QuotaAccount visitorAccount = quotaRepository.findForUpdate(
                QuotaAccount.SCOPE_VISITOR,
                visitorKey(reservation.getCampaignId(), reservation.getVisitorId()),
                reservation.getUtcDate())
                .orElseThrow(() -> new IllegalStateException("visitor quota account missing"));
        if (!quotaRepository.release(campaignAccount.getId())
                || !quotaRepository.release(visitorAccount.getId())) {
            throw new IllegalStateException("quota release without hold detected");
        }
    }

    private Reservation lockReservation(String reservationId) {
        return reservationRepository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException(
                        "reservation '" + reservationId + "' not found"));
    }

    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(
                r.getReservationId(), r.getCampaignId(), r.getVisitorId(), r.getUtcDate(),
                r.getStatus(), r.getCreatedAt(), r.getExpiresAt());
    }

    private static String visitorKey(String campaignId, String visitorId) {
        return campaignId + ":" + visitorId;
    }

    private static String utcDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * 预占编号：时间戳 + 业务键派生，进程内唯一（UUID 兜底冲突概率可忽略）。
     */
    private static String generateReservationId(String campaignId, String visitorId, long now) {
        return "rv-" + Long.toHexString(now) + "-" + Integer.toHexString(
                (campaignId + ":" + visitorId + ":" + java.util.UUID.randomUUID()).hashCode());
    }

    /**
     * 事务内业务动作。
     */
    @FunctionalInterface
    private interface TransactionalAction {
        ServiceResult<Object> run();
    }

    /**
     * 重放响应包装：内容已是原始成功响应 JSON，由控制器原样写出。
     */
    public record RawJson(String json) {
    }
}
