package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.IdentityGroup;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.IdentityGroupRepository;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    /** 归并组内编号上限。 */
    static final int MAX_GROUP_MEMBERS = 10;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final IdentityGroupRepository identityGroupRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               IdentityGroupRepository identityGroupRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.identityGroupRepository = identityGroupRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("dailyTotalCap", request.dailyTotalCap())
                .put("perVisitorDailyCap", request.perVisitorDailyCap());
        JsonNode identity = identity("campaign:" + request.campaignId(), params);
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, identity,
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
        ObjectNode params = objectMapper.createObjectNode()
                .put("campaignId", request.campaignId())
                .put("visitorId", request.visitorId());
        JsonNode identity = identity("reservation-apply", params);
        return runIdempotent(request.requestId(), Operation.APPLY, identity,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 固定加锁顺序：公告当日总账 -> 身份串行锁 -> 组内成员当日账（字典序），避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);

                    // 组账串行：与归并共用单行锁，组成员当日账合计执行每访客上限
                    identityGroupRepository.lockGlobal();
                    List<String> members = groupMembersOf(request.visitorId());
                    for (String member : members) {
                        ledgerRepository.ensureVisitorRow(campaign.campaignId(), member, utcDate);
                    }
                    int usedGroup = 0;
                    for (String member : members) {
                        usedGroup += ledgerRepository.lockUsedVisitor(
                                campaign.campaignId(), member, utcDate);
                    }

                    // 任一额度已满则 429，两个额度均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedGroup + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    // 预占与账目都保留最初申请编号，仅计入申请人自己的分账
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
        JsonNode identity = identity("reservation:" + reservationId,
                objectMapper.createObjectNode());
        return runIdempotent(request.requestId(), Operation.CONFIRM, identity,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        JsonNode identity = identity("reservation:" + reservationId,
                objectMapper.createObjectNode());
        return runIdempotent(request.requestId(), Operation.CANCEL, identity,
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

    // ---- 身份归并 ----

    @Override
    public IdentityGroupResponse mergeIdentities(MergeIdentityRequest request) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("visitorIdA", request.visitorIdA())
                .put("visitorIdB", request.visitorIdB());
        ObjectNode identity = identity("identity-union", params);
        identity.put("operator", request.operator());
        identity.put("expectedVersion", request.expectedVersion());
        return runIdempotent(request.requestId(), Operation.MERGE_IDENTITY, identity,
                IdentityGroupResponse.class, () -> doMerge(request));
    }

    @Override
    public IdentityGroupResponse getIdentityGroup(String visitorId) {
        return txTemplate.execute(status -> {
            String groupId = identityGroupRepository.findGroupIdByVisitor(visitorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "visitor not in any identity group: " + visitorId));
            IdentityGroup group = identityGroupRepository.lockGroupById(groupId).orElseThrow();
            return new IdentityGroupResponse(group.groupId(), group.version(),
                    identityGroupRepository.findMembers(groupId), false, group.createdAtUtc());
        });
    }

    @Override
    public GroupQuotaResponse queryGroupQuota(String campaignId, String visitorId,
                                              LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpired(campaignId, now);

            Optional<String> groupId = identityGroupRepository.findGroupIdByVisitor(visitorId);
            List<String> members = groupId
                    .map(identityGroupRepository::findMembers)
                    .orElse(List.of(visitorId));
            List<GroupQuotaResponse.MemberUsage> usages = new ArrayList<>();
            int usedGroup = 0;
            for (String member : members) {
                int used = ledgerRepository.getUsedVisitor(campaignId, member, utcDate);
                usages.add(new GroupQuotaResponse.MemberUsage(member, used));
                usedGroup += used;
            }
            return new GroupQuotaResponse(
                    campaignId, visitorId, groupId.orElse(null), utcDate,
                    campaign.perVisitorDailyCap(), usedGroup,
                    Math.max(0, campaign.perVisitorDailyCap() - usedGroup),
                    usages, now);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        MERGE_IDENTITY
    }

    /**
     * 构造结构化幂等身份（资源 + 参数），避免分隔符拼接碰撞；
     * 归并等操作可继续追加 operator、expectedVersion 等字段。
     */
    private ObjectNode identity(String resource, ObjectNode params) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resource", resource);
        node.set("params", params);
        return node;
    }

    /**
     * 在事务内执行业务并维护幂等记录：先插占位行（response_json 为 null），业务成功后
     * 回填响应并随同事务原子提交；业务失败整体回滚，不占键。并发同键在唯一索引上冲突，
     * 等待胜出事务提交后重放其完整响应；身份按 JSON 结构化比较，异参 409。
     */
    private <T> T runIdempotent(String requestId, Operation operation, JsonNode identity,
                                Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            var committed = idempotencyRepository.findById(requestId);
            if (committed.isPresent() && committed.get().responseJson() != null) {
                return replay(committed.get(), operation, identity, responseType, requestId);
            }
            try {
                return txTemplate.execute(status -> {
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), writeJson(identity), null),
                            clock.millis());
                    T result = action.get();
                    idempotencyRepository.updateResponse(requestId, writeJson(result));
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先占位，等待其提交后回到循环头部重放
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

    /** 重放已提交记录：操作与结构化身份一致返回原响应，否则 409。 */
    private <T> T replay(IdempotencyRecord record, Operation operation, JsonNode identity,
                         Class<T> responseType, String requestId) {
        JsonNode storedIdentity;
        try {
            storedIdentity = objectMapper.readTree(record.requestFingerprint());
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse stored identity", e);
        }
        if (!record.operation().equals(operation.name()) || !storedIdentity.equals(identity)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "idempotency key reused with different parameters: " + requestId);
        }
        try {
            return objectMapper.readValue(record.responseJson(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("failed to replay idempotent response", e);
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

    /** 访客所在组的成员列表（字典序）；未入组时返回仅含自身的单元素列表。 */
    private List<String> groupMembersOf(String visitorId) {
        return identityGroupRepository.findGroupIdByVisitor(visitorId)
                .map(identityGroupRepository::findMembers)
                .orElse(List.of(visitorId));
    }

    /**
     * 显式身份归并（调用方已持幂等键，本方法在事务内执行）。
     *
     * <p>规则：两边均未入组则新建组（期望版本须为 0）；仅一边入组则另一边加入该组；
     * 两边已在同一组则不新增成员、版本不变；两边在不同组则将 B 侧组整体并入 A 侧组。
     * 存续组版本须等于期望版本，否则 409；成员变更后版本 +1。组合计超过 10 个编号拒绝；
     * 合计占用超过当日上限不影响归并生效，仅由申请时的组合计检查禁止新增。</p>
     */
    private IdentityGroupResponse doMerge(MergeIdentityRequest request) {
        if (request.visitorIdA().equals(request.visitorIdB())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "visitorIdA and visitorIdB must differ");
        }
        long now = clock.millis();
        identityGroupRepository.lockGlobal();
        Optional<String> groupA = identityGroupRepository.lockGroupIdByVisitor(request.visitorIdA());
        Optional<String> groupB = identityGroupRepository.lockGroupIdByVisitor(request.visitorIdB());

        if (groupA.isPresent() && groupA.equals(groupB)) {
            IdentityGroup group = identityGroupRepository.lockGroupById(groupA.get()).orElseThrow();
            requireVersion(group, request.expectedVersion());
            return new IdentityGroupResponse(group.groupId(), group.version(),
                    identityGroupRepository.findMembers(group.groupId()), false, now);
        }
        if (groupA.isEmpty() && groupB.isEmpty()) {
            if (request.expectedVersion() != 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "identity group version conflict: expected " + request.expectedVersion()
                                + " but no group exists");
            }
            IdentityGroup group = new IdentityGroup(
                    UUID.randomUUID().toString().replace("-", ""), 1, now);
            identityGroupRepository.insertGroup(group);
            identityGroupRepository.insertMember(group.groupId(), request.visitorIdA(), now);
            identityGroupRepository.insertMember(group.groupId(), request.visitorIdB(), now);
            return new IdentityGroupResponse(group.groupId(), group.version(),
                    identityGroupRepository.findMembers(group.groupId()), true, now);
        }

        // 存续组：A 侧优先；A 未入组时为 B 侧组
        String survivorId = groupA.orElseGet(groupB::get);
        IdentityGroup survivor = identityGroupRepository.lockGroupById(survivorId).orElseThrow();
        requireVersion(survivor, request.expectedVersion());
        List<String> incoming = groupA.isPresent()
                ? (groupB.isPresent() ? identityGroupRepository.findMembers(groupB.get())
                        : List.of(request.visitorIdB()))
                : List.of(request.visitorIdA());
        if (identityGroupRepository.countMembers(survivorId) + incoming.size()
                > MAX_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "identity group member limit exceeded: max " + MAX_GROUP_MEMBERS);
        }
        if (groupA.isPresent() && groupB.isPresent()) {
            identityGroupRepository.moveMembers(groupB.get(), survivorId);
            identityGroupRepository.deleteGroup(groupB.get());
        } else {
            for (String visitor : incoming) {
                identityGroupRepository.insertMember(survivorId, visitor, now);
            }
        }
        identityGroupRepository.incrementVersion(survivorId);
        return new IdentityGroupResponse(survivorId, survivor.version() + 1,
                identityGroupRepository.findMembers(survivorId), true, now);
    }

    private void requireVersion(IdentityGroup group, int expectedVersion) {
        if (group.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "identity group version conflict: expected " + expectedVersion
                            + " but was " + group.version());
        }
    }
}
