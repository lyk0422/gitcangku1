package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.IdentityGroup;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.IdentityRepository;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GroupQuotaResponse;
import com.example.starter.exposure.web.IdentityGroupResponse;
import com.example.starter.exposure.web.MergeIdentityRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 归并组最大成员编号数。 */
    static final int MAX_GROUP_MEMBERS = 10;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final IdentityRepository identityRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               IdentityRepository identityRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.identityRepository = identityRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = fingerprint("campaign:" + request.campaignId(), Map.of(
                "dailyTotalCap", request.dailyTotalCap(),
                "perVisitorDailyCap", request.perVisitorDailyCap()));
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
        String fingerprint = fingerprint("campaign:" + request.campaignId(),
                Map.of("visitorId", request.visitorId()));
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 固定加锁顺序：归并组行 -> 公告当日总账 -> 组内成员当日账（编号升序），避免死锁
                    List<String> groupMembers = lockGroupMembers(request.visitorId());
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    for (String member : groupMembers) {
                        ledgerRepository.ensureVisitorRow(campaign.campaignId(), member, utcDate);
                    }
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedGroup = 0;
                    for (String member : groupMembers) {
                        usedGroup += ledgerRepository.lockUsedVisitor(
                                campaign.campaignId(), member, utcDate);
                    }

                    // 总额度或组内合计（所有编号当日已确认+有效预占）任一达上限则 429，均不增加
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedGroup + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    // 每个预占保留最初申请编号，只记到申请者本人账上
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
        String fingerprint = fingerprint("reservation:" + reservationId, Map.of());
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        String fingerprint = fingerprint("reservation:" + reservationId, Map.of());
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
    public IdentityGroupResponse mergeIdentities(MergeIdentityRequest request) {
        if (request.visitorIdA().equals(request.visitorIdB())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "visitorIdA and visitorIdB must differ");
        }
        // 编号对无方向：按升序归一化，保证指纹与加锁顺序确定
        String lo = request.visitorIdA().compareTo(request.visitorIdB()) < 0
                ? request.visitorIdA() : request.visitorIdB();
        String hi = lo.equals(request.visitorIdA()) ? request.visitorIdB() : request.visitorIdA();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operator", request.operator());
        params.put("visitorIdA", lo);
        params.put("visitorIdB", hi);
        params.put("expectedVersion", request.expectedVersion());
        String fingerprint = fingerprint("identity-merge", params);
        return runIdempotent(request.requestId(), Operation.MERGE_IDENTITIES, fingerprint,
                IdentityGroupResponse.class,
                () -> doMerge(lo, hi, request.operator(), request.expectedVersion()));
    }

    @Override
    public GroupQuotaResponse queryGroupQuota(String visitorId, String campaignId,
                                              LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            IdentityGroup group = identityRepository.findGroupByVisitor(visitorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "visitor not in any identity group: " + visitorId));
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpired(campaignId, now);

            List<String> members = identityRepository.listMembers(group.groupId());
            List<GroupQuotaResponse.MemberUsage> breakdown = new ArrayList<>();
            int usedGroup = 0;
            for (String member : members) {
                int used = ledgerRepository.getUsedVisitor(campaignId, member, utcDate);
                breakdown.add(new GroupQuotaResponse.MemberUsage(member, used));
                usedGroup += used;
            }
            return new GroupQuotaResponse(
                    group.groupId(), group.version(), members, campaignId, utcDate,
                    campaign.perVisitorDailyCap(), usedGroup,
                    Math.max(0, campaign.perVisitorDailyCap() - usedGroup),
                    breakdown, now);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        MERGE_IDENTITIES
    }

    /**
     * 结构化幂等指纹：资源标识 + 全部参数序列化为 JSON，
     * 避免分隔符拼接造成的碰撞（如参数值本身含分隔符）。
     */
    private String fingerprint(String resource, Map<String, ?> params) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("resource", resource);
        structure.put("params", params);
        return writeJson(structure);
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

    /**
     * 解析并锁定访客所属归并组的全部成员编号（升序）；未归并返回仅含自身的单元素列表。
     * 锁定组行后复核归属，归属在加锁期间被并发归并改变时重试，保证与归并形成串行组账。
     */
    private List<String> lockGroupMembers(String visitorId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<IdentityGroup> group = identityRepository.findGroupByVisitor(visitorId);
            if (group.isEmpty()) {
                return List.of(visitorId);
            }
            String groupId = group.get().groupId();
            identityRepository.lockGroupById(groupId);
            Optional<IdentityGroup> recheck = identityRepository.findGroupByVisitor(visitorId);
            if (recheck.isPresent() && recheck.get().groupId().equals(groupId)) {
                return identityRepository.listMembers(groupId);
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "identity group changed concurrently");
    }

    /**
     * 归并两个访客编号所在的组。固定按 group_id 升序加锁；期望版本不匹配、
     * 合并后超过 {@link #MAX_GROUP_MEMBERS} 个编号时返回 409（不占幂等键）。
     * 归并只改组关系，不迁移任何历史账目；归并时合计超限仍生效，后续申请自然被 429 拦截。
     */
    private IdentityGroupResponse doMerge(String lo, String hi, String operator, int expectedVersion) {
        long now = clock.millis();
        Optional<IdentityGroup> groupLo = identityRepository.findGroupByVisitor(lo);
        Optional<IdentityGroup> groupHi = identityRepository.findGroupByVisitor(hi);

        if (groupLo.isPresent() && groupHi.isPresent()
                && groupLo.get().groupId().equals(groupHi.get().groupId())) {
            // 已在一组：不新增成员，校验期望版本后返回当前组
            IdentityGroup current = identityRepository.lockGroupById(groupLo.get().groupId())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "identity group changed concurrently"));
            if (expectedVersion != current.version()) {
                throw versionConflict(expectedVersion, current.version());
            }
            return toGroupResponse(current);
        }

        List<String> groupIds = new ArrayList<>();
        groupLo.ifPresent(g -> groupIds.add(g.groupId()));
        groupHi.ifPresent(g -> groupIds.add(g.groupId()));
        groupIds.sort(String::compareTo);
        Map<String, IdentityGroup> locked = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            locked.put(groupId, identityRepository.lockGroupById(groupId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "identity group changed concurrently")));
        }
        int versionLo = groupLo.map(g -> locked.get(g.groupId()).version()).orElse(0);
        int versionHi = groupHi.map(g -> locked.get(g.groupId()).version()).orElse(0);
        if (expectedVersion != versionLo + versionHi) {
            throw versionConflict(expectedVersion, versionLo + versionHi);
        }
        int sizeLo = groupLo.map(g -> locked.get(g.groupId()).memberCount()).orElse(1);
        int sizeHi = groupHi.map(g -> locked.get(g.groupId()).memberCount()).orElse(1);
        if (sizeLo + sizeHi > MAX_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "identity group member limit exceeded: max " + MAX_GROUP_MEMBERS);
        }

        IdentityGroup result;
        try {
            if (groupLo.isEmpty() && groupHi.isEmpty()) {
                result = new IdentityGroup(UUID.randomUUID().toString().replace("-", ""),
                        1, 2, operator, now);
                identityRepository.insertGroup(result);
                identityRepository.insertMember(lo, result.groupId());
                identityRepository.insertMember(hi, result.groupId());
            } else {
                // 目标组取 group_id 较小者；另一组（或单边未归并编号）并入
                IdentityGroup target = groupLo.map(g -> locked.get(g.groupId()))
                        .orElseGet(() -> locked.get(groupHi.get().groupId()));
                if (groupLo.isPresent() && groupHi.isPresent()
                        && locked.get(groupHi.get().groupId()).groupId()
                                .compareTo(target.groupId()) < 0) {
                    target = locked.get(groupHi.get().groupId());
                }
                IdentityGroup source = groupLo.isPresent() && groupHi.isPresent()
                        ? (target.groupId().equals(groupLo.get().groupId())
                                ? locked.get(groupHi.get().groupId())
                                : locked.get(groupLo.get().groupId()))
                        : null;
                if (source != null) {
                    for (String member : identityRepository.listMembers(source.groupId())) {
                        identityRepository.moveMember(member, target.groupId());
                    }
                    identityRepository.deleteGroup(source.groupId());
                } else {
                    identityRepository.insertMember(
                            groupLo.isEmpty() ? lo : hi, target.groupId());
                }
                int newVersion = versionLo + versionHi + 1;
                identityRepository.updateGroupAfterMerge(
                        target.groupId(), newVersion, sizeLo + sizeHi, operator, now);
                result = new IdentityGroup(target.groupId(), newVersion,
                        sizeLo + sizeHi, operator, now);
            }
        } catch (DuplicateKeyException duplicate) {
            // 并发归并同一编号：唯一约束拦截，明确返回 409 而非泄露唯一键异常
            throw new ApiException(HttpStatus.CONFLICT,
                    "identity group changed concurrently, please retry");
        }
        return toGroupResponse(result);
    }

    private IdentityGroupResponse toGroupResponse(IdentityGroup group) {
        return new IdentityGroupResponse(group.groupId(), group.version(),
                identityRepository.listMembers(group.groupId()), group.updatedAtUtc());
    }

    private ApiException versionConflict(int expected, int actual) {
        return new ApiException(HttpStatus.CONFLICT,
                "identity group version mismatch: expected " + expected + " but was " + actual);
    }
}
