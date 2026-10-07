package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationBundle;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.BundleRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleActionRequest;
import com.example.starter.exposure.web.BundleResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final BundleRepository bundleRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               BundleRepository bundleRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.bundleRepository = bundleRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap();
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

                    // 固定加锁顺序：公告当日总账 -> 访客当日账，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429，两个额度均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
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
                            null,
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
            Reservation found = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            long now = clock.millis();
            if (found.bundleId() != null) {
                // 组成员随整组过期；先取组行锁再结算，不持成员行锁
                bundleRepository.lockById(found.bundleId())
                        .ifPresent(bundle -> expireBundleIfDue(bundle, now));
            } else {
                Reservation locked = reservationRepository.lockById(reservationId).orElseThrow();
                expireIfDue(locked, now);
            }
            return ReservationResponse.from(
                    reservationRepository.findById(reservationId).orElseThrow());
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

    // ---- 组合预占 ----

    @Override
    public BundleResponse applyBundle(ApplyBundleRequest request) {
        List<String> campaignIds = request.campaignIds();
        if (campaignIds.size() < 2 || campaignIds.size() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "bundle requires 2 to 5 campaigns, got " + campaignIds.size());
        }
        if (new HashSet<>(campaignIds).size() != campaignIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bundle campaignIds must be distinct");
        }
        // 集合换序同参：排序后参与指纹与加锁
        List<String> sorted = campaignIds.stream().sorted().toList();
        String fingerprint = bundleApplyFingerprint(request, sorted);
        return runIdempotent(request.requestId(), Operation.APPLY_BUNDLE, fingerprint,
                BundleResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    List<Campaign> campaigns = new ArrayList<>();
                    for (String campaignId : sorted) {
                        campaigns.add(requireCampaign(campaignId));
                    }
                    // 先结算所有成员公告相关到期项（含其他组合的整组过期）
                    for (Campaign campaign : campaigns) {
                        settleExpired(campaign.campaignId(), now);
                    }
                    // 固定加锁顺序：按公告号排序，逐个 总账 -> 访客账，先全部加锁再统一校验
                    for (Campaign campaign : campaigns) {
                        ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                        ledgerRepository.ensureVisitorRow(
                                campaign.campaignId(), request.visitorId(), utcDate);
                    }
                    for (Campaign campaign : campaigns) {
                        int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                        int usedVisitor = ledgerRepository.lockUsedVisitor(
                                campaign.campaignId(), request.visitorId(), utcDate);
                        // 任一成员额度不足则 429，整组不建预占、不占额度（尚未写入）
                        if (usedTotal + 1 > campaign.dailyTotalCap()
                                || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "exposure quota exhausted for campaign " + campaign.campaignId());
                        }
                    }
                    for (Campaign campaign : campaigns) {
                        ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                        ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);
                    }

                    String bundleId = UUID.randomUUID().toString().replace("-", "");
                    ReservationBundle bundle = new ReservationBundle(
                            bundleId,
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            ReservationStatus.RESERVED,
                            0L,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null);
                    bundleRepository.insert(bundle);
                    List<Reservation> members = new ArrayList<>();
                    for (Campaign campaign : campaigns) {
                        Reservation member = new Reservation(
                                UUID.randomUUID().toString().replace("-", ""),
                                campaign.campaignId(),
                                request.visitorId(),
                                java.sql.Date.valueOf(utcDate),
                                ReservationStatus.RESERVED,
                                now,
                                now + RESERVATION_TTL_MILLIS,
                                null,
                                bundleId);
                        reservationRepository.insert(member);
                        members.add(member);
                    }
                    return BundleResponse.from(bundle, members);
                });
    }

    @Override
    public BundleResponse confirmBundle(String bundleId, BundleActionRequest request) {
        String fingerprint = bundleActionFingerprint(request.operator(), bundleId,
                request.expectedVersion());
        return runIdempotent(request.requestId(), Operation.CONFIRM_BUNDLE, fingerprint,
                BundleResponse.class, () -> transitionBundle(bundleId, request.expectedVersion(), true));
    }

    @Override
    public BundleResponse cancelBundle(String bundleId, BundleActionRequest request) {
        String fingerprint = bundleActionFingerprint(request.operator(), bundleId,
                request.expectedVersion());
        return runIdempotent(request.requestId(), Operation.CANCEL_BUNDLE, fingerprint,
                BundleResponse.class, () -> transitionBundle(bundleId, request.expectedVersion(), false));
    }

    @Override
    public BundleResponse getBundle(String bundleId) {
        return txTemplate.execute(status -> {
            ReservationBundle bundle = bundleRepository.lockById(bundleId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "bundle not found: " + bundleId));
            expireBundleIfDue(bundle, clock.millis());
            return loadBundleResponse(bundleId);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        APPLY_BUNDLE,
        CONFIRM_BUNDLE,
        CANCEL_BUNDLE
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

        // 组合成员不允许用单条入口单独改变状态，必须走整组确认/取消
        if (current.bundleId() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "reservation belongs to bundle " + current.bundleId()
                            + "; use bundle confirm/cancel instead");
        }

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放两级额度；固定顺序先总账后访客账
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
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 普通单条逐单 CAS 为 EXPIRED 并释放两级额度；组合成员触发整组过期，
     * 一次释放全部成员额度。仅 CAS 成功者释放额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.findExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            if (reservation.bundleId() == null) {
                reservationRepository.lockById(reservation.reservationId())
                        .ifPresent(locked -> expireIfDue(locked, now));
            } else {
                bundleRepository.lockById(reservation.bundleId())
                        .ifPresent(bundle -> expireBundleIfDue(bundle, now));
            }
        }
    }

    /**
     * 组合确认/取消状态机。调用前已持幂等键；组行锁 + 状态/版本 CAS 保证并发只有一个终态。
     * 取消时整组一次释放全部成员的两级额度；确认不释放。跨日不迁移日账。
     *
     * @param expectedVersion 调用方期望的组版本（乐观锁），仅对 RESERVED 迁移校验
     * @param isConfirm       true=确认，false=取消
     */
    private BundleResponse transitionBundle(String bundleId, long expectedVersion, boolean isConfirm) {
        long now = clock.millis();
        ReservationBundle current = bundleRepository.lockById(bundleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "bundle not found: " + bundleId));

        // 先结算本组到期（本事务已持组行锁）
        expireBundleIfDue(current, now);
        current = bundleRepository.lockById(bundleId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            if (current.version() != expectedVersion) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "bundle version mismatch: expected " + expectedVersion
                                + ", actual " + current.version());
            }
            ReservationStatus target = isConfirm
                    ? ReservationStatus.CONFIRMED : ReservationStatus.CANCELLED;
            if (!bundleRepository.compareAndSetStatus(
                    bundleId, ReservationStatus.RESERVED, target, expectedVersion, now)) {
                throw new ApiException(HttpStatus.CONFLICT, "bundle state changed concurrently");
            }
            for (Reservation member : reservationRepository.findByBundleId(bundleId)) {
                boolean won = reservationRepository.compareAndSetStatus(
                        member.reservationId(), ReservationStatus.RESERVED, target, now);
                if (won && !isConfirm) {
                    // 取消：释放该成员两级额度；固定顺序先总账后访客账
                    LocalDate utcDate = member.utcDate().toLocalDate();
                    ledgerRepository.releaseTotal(member.campaignId(), utcDate);
                    ledgerRepository.releaseVisitor(member.campaignId(), member.visitorId(), utcDate);
                }
            }
        } else if (current.status() == ReservationStatus.CONFIRMED
                || current.status() == ReservationStatus.CANCELLED
                || current.status() == ReservationStatus.EXPIRED) {
            boolean sameTerminal = isConfirm
                    ? current.status() == ReservationStatus.CONFIRMED
                    : current.status() == ReservationStatus.CANCELLED;
            if (!sameTerminal) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "bundle is " + current.status() + ", cannot "
                                + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected bundle status: " + current.status());
        }

        return loadBundleResponse(bundleId);
    }

    /**
     * 若传入组合（调用方已持其组行锁）已到期，则 CAS 转 EXPIRED，
     * 全部成员一并转 EXPIRED 并逐个释放两级额度；未到期或已非 RESERVED 则不变更。
     */
    private void expireBundleIfDue(ReservationBundle bundle, long now) {
        if (bundle.status() == ReservationStatus.RESERVED
                && now >= bundle.expiresAtUtc()) {
            boolean won = bundleRepository.compareAndSetStatus(
                    bundle.bundleId(),
                    ReservationStatus.RESERVED,
                    ReservationStatus.EXPIRED,
                    bundle.version(),
                    now);
            if (won) {
                for (Reservation member : reservationRepository.findByBundleId(bundle.bundleId())) {
                    boolean memberWon = reservationRepository.compareAndSetStatus(
                            member.reservationId(),
                            ReservationStatus.RESERVED,
                            ReservationStatus.EXPIRED,
                            now);
                    if (memberWon) {
                        LocalDate utcDate = member.utcDate().toLocalDate();
                        ledgerRepository.releaseTotal(member.campaignId(), utcDate);
                        ledgerRepository.releaseVisitor(
                                member.campaignId(), member.visitorId(), utcDate);
                    }
                }
            }
        }
    }

    private BundleResponse loadBundleResponse(String bundleId) {
        ReservationBundle bundle = bundleRepository.findById(bundleId).orElseThrow();
        return BundleResponse.from(bundle, reservationRepository.findByBundleId(bundleId));
    }

    /** 组合申请幂等指纹：结构化 JSON，含操作者、访客、排序后公告集合与期望版本，防碰撞。 */
    private String bundleApplyFingerprint(ApplyBundleRequest request, List<String> sortedCampaignIds) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", request.operator());
        identity.put("visitorId", request.visitorId());
        identity.put("campaignIds", sortedCampaignIds);
        identity.put("expectedVersion", request.expectedVersion());
        return writeJson(identity);
    }

    /** 组合确认/取消幂等指纹：结构化 JSON，含操作者、组编号与期望版本。 */
    private String bundleActionFingerprint(String operator, String bundleId, long expectedVersion) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", operator);
        identity.put("bundleId", bundleId);
        identity.put("expectedVersion", expectedVersion);
        return writeJson(identity);
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放两级额度；
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
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private Reservation requireReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
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
