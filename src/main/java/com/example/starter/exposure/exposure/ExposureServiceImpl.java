package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.RotationPool;
import com.example.starter.exposure.domain.RotationPoolMember;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.RotationPoolRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SelectExposureRequest;
import com.example.starter.exposure.web.SelectExposureResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
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

    /** 池配置版本：成员与权重创建后固定，当前恒为 1。 */
    static final int POOL_CONFIG_VERSION = 1;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final RotationPoolRepository rotationPoolRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               RotationPoolRepository rotationPoolRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.rotationPoolRepository = rotationPoolRepository;
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
    public PoolResponse createPool(CreatePoolRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("members", canonicalMembers(request));
        String fingerprint = structuredFingerprint(Operation.CREATE_POOL, request.poolId(),
                request.operator(), params);
        return runIdempotent(request.requestId(), Operation.CREATE_POOL, fingerprint,
                PoolResponse.class, () -> {
                    if (rotationPoolRepository.findPoolById(request.poolId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "pool already exists: " + request.poolId());
                    }
                    // 成员公告号必须互不重复且公告已存在
                    Map<String, Integer> weightByCampaign = new LinkedHashMap<>();
                    for (CreatePoolRequest.PoolMemberRequest member : request.members()) {
                        if (weightByCampaign.putIfAbsent(member.campaignId(), member.weight()) != null) {
                            throw new ApiException(HttpStatus.BAD_REQUEST,
                                    "duplicate member campaign: " + member.campaignId());
                        }
                        requireCampaign(member.campaignId());
                    }
                    RotationPool pool = new RotationPool(
                            request.poolId(), POOL_CONFIG_VERSION, null, clock.millis());
                    try {
                        rotationPoolRepository.insertPool(pool);
                    } catch (DuplicateKeyException duplicatePool) {
                        // 并发创建同一 poolId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "pool already exists: " + request.poolId());
                    }
                    List<RotationPoolMember> members = weightByCampaign.entrySet().stream()
                            .map(e -> new RotationPoolMember(request.poolId(), e.getKey(), e.getValue(), 0L))
                            .sorted(java.util.Comparator.comparing(RotationPoolMember::campaignId))
                            .toList();
                    members.forEach(rotationPoolRepository::insertMember);
                    return PoolResponse.from(pool, members);
                });
    }

    @Override
    public SelectExposureResponse selectFromPool(String poolId, SelectExposureRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("visitorId", request.visitorId());
        params.put("expectedVersion", request.expectedVersion());
        String fingerprint = structuredFingerprint(Operation.SELECT, poolId,
                request.operator(), params);
        return runIdempotent(request.requestId(), Operation.SELECT, fingerprint,
                SelectExposureResponse.class, () -> doSelect(poolId, request));
    }

    @Override
    public PoolResponse getPool(String poolId) {
        return txTemplate.execute(status -> {
            RotationPool pool = lockPool(poolId);
            pool = settlePoolPending(pool, clock.millis());
            return PoolResponse.from(pool, rotationPoolRepository.listMembers(poolId));
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
        SELECT
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 先插入占位记录再执行业务：并发同键在唯一键上串行等待，胜出事务提交后
     * 其余事务插入冲突，等待重放其结果；业务失败整体回滚，不占键。
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
                    // 占位插入：并发同键在此阻塞至胜出事务提交/回滚
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, null), clock.millis());
                    T result = action.get();
                    idempotencyRepository.updateResponse(requestId, writeJson(result));
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务已提交占位记录，等待后重放其结果
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
     * 池预占额外维护缺额：确认时提交临时缺额，取消时丢弃；固定加锁顺序
     * 池 -&gt; 预占单 -&gt; 总账 -&gt; 访客账，与池选择一致，避免死锁。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        // 预读来源池（poolId 创建后不变），先持池锁再锁预占单
        String poolId = reservationRepository.findById(reservationId)
                .map(Reservation::poolId)
                .orElse(null);
        if (poolId != null) {
            rotationPoolRepository.lockPoolById(poolId);
        }
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
                if (poolId != null) {
                    // 缺额只在 CONFIRMED 时正式提交
                    rotationPoolRepository.commitPendingDeficits(poolId);
                    rotationPoolRepository.clearPendingReservation(poolId);
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
                if (poolId != null) {
                    // 取消丢弃临时缺额，已提交缺额不变，下一次重选同一位置
                    rotationPoolRepository.discardPendingDeficits(poolId);
                    rotationPoolRepository.clearPendingReservation(poolId);
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

    private Reservation requireReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));
    }

    /**
     * 缺额轮转选择（调用方已持幂等键，本方法在事务内执行）。
     *
     * <p>流程：持池行锁串行化同池选择 → 结算待决预占 → 校验期望版本 → 结算成员公告
     * 过期预占 → 按公告号顺序锁定两级账目 → 全部成员缺额加权重得临时缺额 →
     * 在当日额度足够的成员中取缺额最大者（同分取公告号字典序小者）→
     * 选中者减全池权重之和 → 原子占用额度、创建 60 秒预占并保存新缺额。
     * 全部不可选抛 429，临时增加随事务回滚不保存。</p>
     */
    private SelectExposureResponse doSelect(String poolId, SelectExposureRequest request) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.now(clock);
        RotationPool pool = settlePoolPending(lockPool(poolId), now);
        if (request.expectedVersion() != pool.version()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "pool version mismatch for " + poolId + ": expected "
                            + request.expectedVersion() + ", actual " + pool.version());
        }
        if (pool.pendingReservationId() != null) {
            // RESERVED 期间池禁止另一轮转选择
            throw new ApiException(HttpStatus.CONFLICT,
                    "pool " + poolId + " has a pending selection: " + pool.pendingReservationId());
        }

        List<RotationPoolMember> members = rotationPoolRepository.lockMembers(poolId);
        // 先结算各成员公告的过期预占，再按公告号顺序锁定两级账目（与 apply 的账目加锁顺序一致）
        for (RotationPoolMember member : members) {
            settleExpired(member.campaignId(), now);
        }
        Map<String, Campaign> campaigns = new LinkedHashMap<>();
        Map<String, Integer> usedTotals = new LinkedHashMap<>();
        Map<String, Integer> usedVisitors = new LinkedHashMap<>();
        for (RotationPoolMember member : members) {
            String campaignId = member.campaignId();
            campaigns.put(campaignId, requireCampaign(campaignId));
            ledgerRepository.ensureTotalRow(campaignId, utcDate);
            ledgerRepository.ensureVisitorRow(campaignId, request.visitorId(), utcDate);
            usedTotals.put(campaignId, ledgerRepository.lockUsedTotal(campaignId, utcDate));
            usedVisitors.put(campaignId,
                    ledgerRepository.lockUsedVisitor(campaignId, request.visitorId(), utcDate));
        }

        // 精确整数缺额轮转：先全员加权重，再仅在有额度的成员中取最大者
        long totalWeight = members.stream().mapToLong(RotationPoolMember::weight).sum();
        Map<String, Long> tempDeficits = new LinkedHashMap<>();
        String winner = null;
        long winnerTemp = Long.MIN_VALUE;
        for (RotationPoolMember member : members) {
            String campaignId = member.campaignId();
            long temp = member.committedDeficit() + member.weight();
            tempDeficits.put(campaignId, temp);
            Campaign campaign = campaigns.get(campaignId);
            boolean eligible = usedTotals.get(campaignId) + 1 <= campaign.dailyTotalCap()
                    && usedVisitors.get(campaignId) + 1 <= campaign.perVisitorDailyCap();
            // 成员按公告号升序遍历，严格大于才替换 → 同分取字典序小者
            if (eligible && (winner == null || temp > winnerTemp)) {
                winner = campaignId;
                winnerTemp = temp;
            }
        }
        if (winner == null) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "no eligible member in pool " + poolId + " for visitor " + request.visitorId());
        }
        // 选中者减所有权重之和
        tempDeficits.compute(winner, (campaignId, temp) -> temp - totalWeight);

        // 行锁已持有，占用选中公告两级额度
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
                null,
                poolId);
        reservationRepository.insert(reservation);
        // 与预占原子保存新缺额（临时缺额，CONFIRMED 才正式提交）
        rotationPoolRepository.replacePendingDeficits(poolId, tempDeficits);
        rotationPoolRepository.updatePendingReservation(poolId, reservationId);

        return new SelectExposureResponse(
                poolId, pool.version(), request.visitorId(), winner,
                ReservationResponse.from(reservation), tempDeficits, now);
    }

    /**
     * 结算池的待决选择（调用方已持池行锁）：待决预占仍 RESERVED 且未到期则保持阻塞；
     * 已到期则结算过期并丢弃临时缺额；已 CONFIRMED 则补提交缺额；已 CANCELLED/EXPIRED
     * 则丢弃临时缺额。返回清理后的池状态。
     */
    private RotationPool settlePoolPending(RotationPool pool, long now) {
        String pendingId = pool.pendingReservationId();
        if (pendingId == null) {
            return pool;
        }
        var pendingOpt = reservationRepository.lockById(pendingId);
        if (pendingOpt.isEmpty()) {
            // 数据不一致：清理待决引用，避免永久阻塞
            rotationPoolRepository.discardPendingDeficits(pool.poolId());
            rotationPoolRepository.clearPendingReservation(pool.poolId());
            return lockPool(pool.poolId());
        }
        Reservation pending = pendingOpt.get();
        if (pending.status() == ReservationStatus.RESERVED) {
            if (now < pending.expiresAtUtc()) {
                return pool;
            }
            // 到期：转 EXPIRED 并释放两级额度，丢弃临时缺额
            expireIfDue(pending, now);
            rotationPoolRepository.discardPendingDeficits(pool.poolId());
        } else if (pending.status() == ReservationStatus.CONFIRMED) {
            // 确认时缺额已随确认事务提交；此处为兜底（如历史遗留），幂等
            rotationPoolRepository.commitPendingDeficits(pool.poolId());
        } else {
            // CANCELLED / EXPIRED：丢弃临时缺额，已提交缺额不变
            rotationPoolRepository.discardPendingDeficits(pool.poolId());
        }
        rotationPoolRepository.clearPendingReservation(pool.poolId());
        return lockPool(pool.poolId());
    }

    private RotationPool lockPool(String poolId) {
        return rotationPoolRepository.lockPoolById(poolId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "pool not found: " + poolId));
    }

    /**
     * 结构化指纹：操作、资源、操作者与全参数以 JSON 编码，字段无碰撞；
     * 数值按值比较、文本原样。成员列表按公告号排序，与请求中的书写顺序无关。
     */
    private String structuredFingerprint(Operation operation, String resource,
                                         String operator, Object params) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("operation", operation.name());
        fingerprint.put("resource", resource);
        fingerprint.put("operator", operator);
        fingerprint.put("params", params);
        return writeJson(fingerprint);
    }

    /** 池成员参数规范化：按公告号排序的 [公告号, 权重] 列表。 */
    private List<List<Object>> canonicalMembers(CreatePoolRequest request) {
        return request.members().stream()
                .map(m -> List.<Object>of(m.campaignId(), m.weight()))
                .sorted(java.util.Comparator.comparing(a -> (String) a.get(0)))
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
