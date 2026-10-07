package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Pool;
import com.example.starter.exposure.domain.PoolMember;
import com.example.starter.exposure.domain.PoolSelection;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.PoolRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.PoolSelectionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SelectFromPoolRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
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
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final PoolRepository poolRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               PoolRepository poolRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.poolRepository = poolRepository;
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

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        CREATE_POOL,
        POOL_SELECT
    }

    @Override
    public PoolResponse createPool(CreatePoolRequest request) {
        String fingerprint = structuredFingerprint(
                request.operator(), request.poolId(), request.members());
        return runIdempotent(request.requestId(), Operation.CREATE_POOL, fingerprint,
                PoolResponse.class, () -> {
                    if (poolRepository.findPoolById(request.poolId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "pool already exists: " + request.poolId());
                    }
                    List<String> campaignIds = request.members().stream()
                            .map(CreatePoolRequest.Member::campaignId)
                            .toList();
                    if (new HashSet<>(campaignIds).size() != campaignIds.size()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "duplicate member campaign in pool " + request.poolId());
                    }
                    for (String campaignId : campaignIds) {
                        requireCampaign(campaignId);
                    }
                    Pool pool = new Pool(request.poolId(), 1, request.operator(), clock.millis());
                    try {
                        poolRepository.insertPool(pool);
                    } catch (DuplicateKeyException duplicatePool) {
                        // 并发创建同一 poolId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "pool already exists: " + request.poolId());
                    }
                    for (CreatePoolRequest.Member member : request.members()) {
                        poolRepository.insertMember(new PoolMember(
                                request.poolId(), member.campaignId(), member.weight(), 0L));
                    }
                    return poolResponse(pool, null);
                });
    }

    @Override
    public PoolSelectionResponse selectFromPool(String poolId, SelectFromPoolRequest request) {
        String fingerprint = structuredFingerprint(
                request.operator(), poolId, request.visitorId(), request.expectedVersion());
        return runIdempotent(request.requestId(), Operation.POOL_SELECT, fingerprint,
                PoolSelectionResponse.class, () -> doSelectFromPool(poolId, request));
    }

    @Override
    public PoolResponse getPool(String poolId) {
        return txTemplate.execute(status -> {
            Pool pool = poolRepository.findPoolById(poolId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "pool not found: " + poolId));
            // 先结算各成员公告的过期预占，活动选择视图才准确
            long now = clock.millis();
            for (PoolMember member : poolRepository.findMembers(poolId)) {
                settleExpired(member.campaignId(), now);
            }
            String activeReservationId = poolRepository.findActiveSelection(poolId)
                    .map(PoolSelection::reservationId)
                    .orElse(null);
            return poolResponse(pool, activeReservationId);
        });
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
                // 池选择的预占确认时，将选择时冻结的临时缺额正式提交为成员缺额
                commitPoolDeficitsIfAny(reservationId);
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
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放两级额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
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

    /**
     * 缺额轮转选择（在持幂等键的事务内执行）。行锁池行串行化同池选择；
     * 全部成员先按权重加到临时缺额，仅当天仍有额度的成员参与竞选，
     * 取缺额最大者（同分取公告号字典序小者），选中者减去所有成员权重之和。
     */
    private PoolSelectionResponse doSelectFromPool(String poolId, SelectFromPoolRequest request) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.now(clock);
        Pool pool = poolRepository.lockPoolById(poolId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "pool not found: " + poolId));
        if (request.expectedVersion() != null && request.expectedVersion() != pool.poolVersion()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "pool version mismatch: expected " + request.expectedVersion()
                            + ", actual " + pool.poolVersion());
        }
        List<PoolMember> members = poolRepository.findMembers(poolId);

        // 先结算各成员公告的过期预占：释放两级额度并使其临时缺额被丢弃
        for (PoolMember member : members) {
            settleExpired(member.campaignId(), now);
        }

        // RESERVED 期间池禁止另一轮转选择
        if (poolRepository.findActiveSelection(poolId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "pool " + poolId + " has an active reserved selection");
        }

        // 固定加锁顺序：按公告号字典序逐公告 总账 -> 访客账，与申请/结算路径一致
        Map<String, Campaign> campaigns = new LinkedHashMap<>();
        Map<String, Integer> usedTotals = new LinkedHashMap<>();
        Map<String, Integer> usedVisitors = new LinkedHashMap<>();
        for (PoolMember member : members) {
            Campaign campaign = requireCampaign(member.campaignId());
            campaigns.put(member.campaignId(), campaign);
            ledgerRepository.ensureTotalRow(member.campaignId(), utcDate);
            ledgerRepository.ensureVisitorRow(member.campaignId(), request.visitorId(), utcDate);
            usedTotals.put(member.campaignId(),
                    ledgerRepository.lockUsedTotal(member.campaignId(), utcDate));
            usedVisitors.put(member.campaignId(),
                    ledgerRepository.lockUsedVisitor(member.campaignId(), request.visitorId(), utcDate));
        }

        // 全部成员按权重加到临时缺额（精确整数）；仅该访客与公告当天仍有额度的成员参与竞选
        Map<String, Long> tempDeficits = new LinkedHashMap<>();
        long totalWeight = 0L;
        for (PoolMember member : members) {
            tempDeficits.put(member.campaignId(), member.committedDeficit() + member.weight());
            totalWeight += member.weight();
        }
        String winner = null;
        for (PoolMember member : members) {
            Campaign campaign = campaigns.get(member.campaignId());
            boolean eligible = usedTotals.get(member.campaignId()) + 1 <= campaign.dailyTotalCap()
                    && usedVisitors.get(member.campaignId()) + 1 <= campaign.perVisitorDailyCap();
            // 成员按字典序遍历且严格大于才替换：同分时公告号小者胜出
            if (eligible && (winner == null
                    || tempDeficits.get(member.campaignId()) > tempDeficits.get(winner))) {
                winner = member.campaignId();
            }
        }
        if (winner == null) {
            // 全部不可选：429，临时增加的缺额不保存
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "no eligible campaign in pool " + poolId);
        }
        // 选中者减去所有成员权重之和
        tempDeficits.put(winner, tempDeficits.get(winner) - totalWeight);

        // 原子创建原 60 秒预占并保存新缺额（占用两级额度 + 预占单 + 选择记录 + 缺额快照）
        ledgerRepository.addTotal(winner, utcDate, 1);
        ledgerRepository.addVisitor(winner, request.visitorId(), utcDate, 1);
        String reservationId = UUID.randomUUID().toString().replace("-", "");
        Reservation reservation = new Reservation(
                reservationId,
                winner,
                request.visitorId(),
                java.sql.Date.valueOf(utcDate),
                ReservationStatus.RESERVED,
                now,
                now + RESERVATION_TTL_MILLIS,
                null);
        reservationRepository.insert(reservation);
        poolRepository.insertSelection(new PoolSelection(
                reservationId, poolId, winner, request.visitorId(), now));
        for (Map.Entry<String, Long> entry : tempDeficits.entrySet()) {
            poolRepository.insertDeficit(new PoolRepository.DeficitRow(
                    reservationId, entry.getKey(), entry.getValue()));
        }
        return PoolSelectionResponse.from(reservation, poolId, tempDeficits);
    }

    /** 预占确认时，将选择时冻结的临时缺额正式提交为池成员缺额；非池选择的预占无操作。 */
    private void commitPoolDeficitsIfAny(String reservationId) {
        var selection = poolRepository.findSelectionByReservation(reservationId);
        if (selection.isEmpty()) {
            return;
        }
        for (PoolRepository.DeficitRow row : poolRepository.findDeficits(reservationId)) {
            poolRepository.updateCommittedDeficit(
                    selection.get().poolId(), row.campaignId(), row.pendingDeficit());
        }
    }

    /** 组装选择池视图：成员按公告号字典序，含已提交缺额。 */
    private PoolResponse poolResponse(Pool pool, String activeReservationId) {
        List<PoolResponse.Member> members = poolRepository.findMembers(pool.poolId()).stream()
                .map(member -> new PoolResponse.Member(
                        member.campaignId(), member.weight(), member.committedDeficit()))
                .toList();
        return new PoolResponse(pool.poolId(), pool.poolVersion(), members,
                activeReservationId, pool.createdAtUtc());
    }

    /**
     * 结构化请求指纹：JSON 序列化保证各参数边界清晰、字段无碰撞；
     * 十进制同值同参、文本原样比对。
     */
    private String structuredFingerprint(Object... parts) {
        try {
            return objectMapper.writeValueAsString(Arrays.asList(parts));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build request fingerprint", e);
        }
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
