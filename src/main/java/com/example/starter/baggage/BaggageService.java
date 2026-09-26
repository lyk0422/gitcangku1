package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldEventItem;
import com.example.starter.baggage.BaggageDtos.ClaimHoldHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldListResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TraceEvent;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、精确/差异到达、补到与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 差异到达仅允许 SEALED 航段提交封舱清单子集；缺失行李转 SHORT_UNLOADED 并冻结待乘索引，
 * 补到前无法装载任何后续航段，补到提交后才恢复参与装载。
 */
@Service
public class BaggageService {

    private static final String LEG_OPEN = "OPEN";
    private static final String LEG_SEALED = "SEALED";
    private static final String LEG_ARRIVED = "ARRIVED";
    private static final String ARRIVAL_EXACT = "EXACT";
    private static final String ARRIVAL_DIFF = "DIFF";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_SHORT_UNLOADED = "SHORT_UNLOADED";
    private static final String BAG_RECOVERED = "RECOVERED";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String BAG_CLAIM_HOLD = "CLAIM_HOLD";
    private static final String BAG_LOST = "LOST";

    private static final String HOLD_ACTIVE = "ACTIVE";
    private static final String HOLD_REVIEWED = "REVIEWED";
    private static final String HOLD_RELEASED = "RELEASED";

    private static final String CHAIN_HOLD = "HOLD";
    private static final String CHAIN_MANIFEST_REMOVE = "MANIFEST_REMOVE";
    private static final String CHAIN_REVIEW = "REVIEW";
    private static final String CHAIN_RELEASE = "RELEASE";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";
    private static final String EVT_CLAIM_HOLD = "CLAIM_HOLD";
    private static final String EVT_CLAIM_RELEASE = "CLAIM_RELEASE";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<ClaimHoldRow> HOLD_MAPPER = (rs, rowNum) -> new ClaimHoldRow(
            rs.getLong("hold_id"), rs.getString("bag_tag"), rs.getString("claim_key"),
            rs.getString("passenger_digest"), rs.getString("reason"), rs.getString("prev_status"),
            rs.getString("removed_leg_id"), rs.getString("status"), rs.getString("hold_agent"),
            rs.getString("review_agent"), rs.getString("release_agent"),
            getInstant(rs, "held_at"), getInstant(rs, "reviewed_at"), getInstant(rs, "released_at"));

    private static final RowMapper<ClaimHoldEventItem> CHAIN_EVENT_MAPPER = (rs, rowNum) -> new ClaimHoldEventItem(
            rs.getInt("seq"), rs.getString("event_type"), rs.getLong("hold_id"),
            rs.getString("leg_id"), rs.getString("reason"), rs.getString("agent_id"),
            getInstant(rs, "event_time").toString());

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                request, LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /** 封舱：校验版本，保存只读装载清单并转 SEALED；空清单也可封舱。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.expectedVersion());
        return idempotencyService.execute(request.requestId(), "SEAL", 200,
                payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /** 到达确认：实际袋号集合须与封舱清单完全一致，匹配则原子转 ARRIVED 并推进各行李。 */
    public ArriveResponse arrive(String legId, ArriveRequest request) {
        ArrivePayload payload = new ArrivePayload(legId, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE", 200,
                payload, ArriveResponse.class, () -> doArrive(legId, request));
    }

    /**
     * 差异到达：仅 SEALED 航段可提交，actual 为封舱清单子集（允许空集）且不得重复。
     * 实际到达行李推进到到达站并推进待乘索引；缺失行李转 SHORT_UNLOADED、冻结索引；
     * 航段保存只读差异快照后转 ARRIVED，不能再执行另一种到达确认。
     */
    public DifferenceArriveResponse arriveDifference(String legId, DifferenceArriveRequest request) {
        DifferenceArrivePayload payload = new DifferenceArrivePayload(
                legId, request.expectedVersion(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE_DIFFERENCE", 200,
                payload, DifferenceArriveResponse.class, () -> doArriveDifference(legId, request));
    }

    /**
     * 补到：仅 SHORT_UNLOADED 且缺失航段匹配的行李可补到，实际站必须等于该航段到达站（否则 422）。
     * 成功后移动行李、推进待乘索引并标记 RECOVERED（完成行程者 DELIVERED）；
     * 相同补到同参由 requestId 重放原结果，已补到不得再次推进。
     */
    public RecoverResponse recover(RecoverRequest request) {
        return idempotencyService.execute(request.requestId(), "RECOVER", 200,
                request, RecoverResponse.class, () -> doRecover(request));
    }

    /** 行李完整轨迹查询。 */
    public BagResponse getBagTrace(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        return toBagResponse(bag);
    }

    /** 封舱清单查询：未封舱时返回空清单。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest);
    }

    /** 航段差异快照查询：返回只读封舱清单、到达类型及差异到达实际集合。 */
    public LegDifferenceResponse getDifference(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        List<String> actual = switch (leg.arrivalType() == null ? "" : leg.arrivalType()) {
            case ARRIVAL_DIFF -> leg.arrivalActual() == null ? List.of() : readJsonList(leg.arrivalActual());
            case ARRIVAL_EXACT -> manifest;
            default -> null;
        };
        return new LegDifferenceResponse(leg.legId(), leg.status(), leg.version(),
                manifest, leg.arrivalType(), actual);
    }

    /** 未补到短卸行李清单查询：仅含当前仍为 SHORT_UNLOADED 的行李。 */
    public ShortListResponse listShortUnloaded() {
        List<ShortItem> items = jdbcTemplate.query(
                "SELECT bag_tag, short_leg_id, short_destination, short_registered_at,"
                        + " next_leg_index, current_location FROM bag"
                        + " WHERE status = ? ORDER BY short_registered_at, bag_tag",
                SHORT_MAPPER, BAG_SHORT_UNLOADED);
        return new ShortListResponse(items);
    }

    /**
     * 认领冻结：对未到达最终目的地的行李登记冻结，行李转 CLAIM_HOLD。
     * 已最终交付、已丢失或已有生效冻结的行李返回 409；行李位于 OPEN 航段清单时，
     * 在同一事务内先移出清单（航段版本递增）并写入链记录；所在航段已 SEALED 时返回 409。
     * 冻结与装载/封舱按行锁提交顺序裁决：先取航段锁再取行李锁，与装载/到达的加锁顺序一致，
     * 读取与加锁间隙状态变化时整体重试。
     */
    public ClaimHoldResponse hold(String bagTag, ClaimHoldRequest request) {
        ClaimHoldPayload payload = new ClaimHoldPayload(bagTag, request.claimKey(),
                request.agentId(), request.passengerDigest(), request.reason());
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return idempotencyService.execute(request.requestId(), "CLAIM_HOLD", 201,
                        payload, ClaimHoldResponse.class, () -> doHold(bagTag, request));
            } catch (StaleLockException stale) {
                // 加锁间隙行李的装载航段发生变化，事务已回滚，按最新状态重试
            }
        }
        throw ApiException.conflict("行李 " + bagTag + " 状态变更竞争激烈，请重新提交");
    }

    /** 冻结复核：须由不同于登记客服的客服核对乘客核验摘要，摘要不匹配返回 422。 */
    public ClaimReviewResponse review(String bagTag, ClaimReviewRequest request) {
        ClaimReviewPayload payload = new ClaimReviewPayload(bagTag, request.agentId(), request.passengerDigest());
        return idempotencyService.execute(request.requestId(), "CLAIM_REVIEW", 200,
                payload, ClaimReviewResponse.class, () -> doReview(bagTag, request));
    }

    /** 冻结解除确认：复核客服本人第二次确认，原子恢复行李为冻结前状态。 */
    public ClaimReleaseResponse release(String bagTag, ClaimReleaseRequest request) {
        ClaimReleasePayload payload = new ClaimReleasePayload(bagTag, request.agentId());
        return idempotencyService.execute(request.requestId(), "CLAIM_RELEASE", 200,
                payload, ClaimReleaseResponse.class, () -> doRelease(bagTag, request));
    }

    /** 认领冻结明细查询：返回该行李最近一次冻结记录，不改变任何状态。 */
    public ClaimHoldResponse getClaimHold(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ClaimHoldRow hold = findLatestHold(bagTag);
        if (hold == null) {
            throw ApiException.notFound("行李无认领冻结记录: " + bagTag);
        }
        return toClaimHoldResponse(hold);
    }

    /** 认领冻结不可变链历史查询：按链内顺序返回，不改变任何状态。 */
    public ClaimHoldHistoryResponse getClaimHoldHistory(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ClaimHoldEventItem> events = jdbcTemplate.query(
                "SELECT seq, event_type, hold_id, leg_id, reason, agent_id, event_time"
                        + " FROM claim_hold_event WHERE bag_tag = ? ORDER BY seq",
                CHAIN_EVENT_MAPPER, bagTag);
        return new ClaimHoldHistoryResponse(bagTag, events);
    }

    /** 认领冻结诊断查询：按冻结状态过滤（缺省返回全部），按 holdId 升序，不改变任何状态。 */
    public ClaimHoldListResponse listClaimHolds(String status) {
        String sql = "SELECT hold_id, bag_tag, claim_key, passenger_digest, reason, prev_status,"
                + " removed_leg_id, status, hold_agent, review_agent, release_agent,"
                + " held_at, reviewed_at, released_at FROM claim_hold";
        List<ClaimHoldRow> rows;
        if (status == null || status.isBlank()) {
            rows = jdbcTemplate.query(sql + " ORDER BY hold_id", HOLD_MAPPER);
        } else {
            if (!HOLD_ACTIVE.equals(status) && !HOLD_REVIEWED.equals(status) && !HOLD_RELEASED.equals(status)) {
                throw ApiException.unprocessable(
                        "冻结状态过滤值非法: " + status + "，允许 ACTIVE/REVIEWED/RELEASED");
            }
            rows = jdbcTemplate.query(sql + " WHERE status = ? ORDER BY hold_id", HOLD_MAPPER, status);
        }
        return new ClaimHoldListResponse(rows.stream().map(BaggageService::toClaimHoldResponse).toList());
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version) VALUES (?, ?, ?, ?, 1)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN);
        return new LegResponse(request.legId(), request.origin(), request.destination(), LEG_OPEN, 1);
    }

    private BagResponse doRegisterBag(RegisterBagRequest request) {
        List<String> legIds = request.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw ApiException.unprocessable("行程航段不得重复");
        }
        if (findBag(request.bagTag()) != null) {
            throw ApiException.conflict("行李已存在: " + request.bagTag());
        }
        List<LegRow> legs = new ArrayList<>();
        for (String legId : legIds) {
            LegRow leg = findLeg(legId);
            if (leg == null) {
                throw ApiException.unprocessable("行程引用的航段不存在: " + legId);
            }
            legs.add(leg);
        }
        for (int i = 0; i + 1 < legs.size(); i++) {
            if (!legs.get(i).destination().equals(legs.get(i + 1).origin())) {
                throw ApiException.unprocessable(
                        "相邻航段首尾站必须衔接: " + legs.get(i).legId() + " -> " + legs.get(i + 1).legId());
            }
        }
        String startStation = legs.get(0).origin();
        jdbcTemplate.update(
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status) VALUES (?, ?, 0, ?)",
                request.bagTag(), startStation, BAG_IN_TRANSIT);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    request.bagTag(), i, leg.legId(), leg.origin(), leg.destination());
        }
        insertEvent(request.bagTag(), EVT_REGISTERED, null, startStation);
        return toBagResponse(findBag(request.bagTag()));
    }

    private LoadResponse doLoad(String legId, LoadRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止装载");
        }
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("批量装载的 bagTag 不得重复");
        }
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateLoadable(bag, leg);
            bags.add(bag);
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted);
    }

    private SealResponse doSeal(String legId, SealRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止封舱");
        }
        List<String> manifest = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag", String.class, legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET status = ?, version = ?, sealed_manifest = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(manifest), legId);
        return new SealResponse(legId, LEG_SEALED, newVersion, manifest);
    }

    private ArriveResponse doArrive(String legId, ArriveRequest request) {
        LegRow leg = lockLeg(legId);
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止到达确认");
        }
        List<String> manifest = readJsonList(leg.sealedManifest());
        List<String> actual = request.bagTags();
        Set<String> actualSet = new HashSet<>(actual);
        if (actualSet.size() != actual.size() || !actualSet.equals(new HashSet<>(manifest))) {
            throw ApiException.unprocessable("实际袋号集合与封舱清单不一致");
        }
        for (String bagTag : manifest) {
            BagRow bag = lockBag(bagTag);
            advanceArrivedBag(bag, leg.destination(), legId);
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = NULL WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_EXACT, legId);
        return new ArriveResponse(legId, LEG_ARRIVED, newVersion, sortedCopy(manifest));
    }

    private DifferenceArriveResponse doArriveDifference(String legId, DifferenceArriveRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止差异到达");
        }
        List<String> manifest = readJsonList(leg.sealedManifest());
        List<String> actual = request.bagTags();
        Set<String> actualSet = new HashSet<>(actual);
        if (actualSet.size() != actual.size()) {
            throw ApiException.unprocessable("实际袋号集合不得重复");
        }
        if (!new HashSet<>(manifest).containsAll(actualSet)) {
            throw ApiException.unprocessable("实际袋号存在封舱清单外袋号，整次差异到达拒绝");
        }
        List<String> arrivedSorted = sortedCopy(actual);
        List<String> shortList = manifest.stream()
                .filter(bagTag -> !actualSet.contains(bagTag))
                .sorted()
                .toList();
        OffsetDateTime registeredAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (String bagTag : manifest) {
            BagRow bag = lockBag(bagTag);
            if (actualSet.contains(bagTag)) {
                advanceArrivedBag(bag, leg.destination(), legId);
            } else {
                jdbcTemplate.update(
                        "UPDATE bag SET status = ?, loaded_leg_id = NULL, short_leg_id = ?,"
                                + " short_destination = ?, short_registered_at = ? WHERE bag_tag = ?",
                        BAG_SHORT_UNLOADED, legId, leg.destination(), registeredAt, bagTag);
                insertEvent(bagTag, EVT_SHORT, legId, bag.currentLocation());
            }
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_DIFF, writeJson(arrivedSorted), legId);
        return new DifferenceArriveResponse(legId, LEG_ARRIVED, newVersion, arrivedSorted, shortList);
    }

    private RecoverResponse doRecover(RecoverRequest request) {
        BagRow bag = lockBag(request.bagTag());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        if (BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 处于认领冻结状态，禁止补到确认");
        }
        if (!BAG_SHORT_UNLOADED.equals(bag.status())) {
            if (BAG_RECOVERED.equals(bag.status()) || BAG_DELIVERED.equals(bag.status())) {
                throw ApiException.conflict("行李 " + bag.bagTag() + " 已补到，不得再次推进");
            }
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，不可补到");
        }
        if (!bag.shortLegId().equals(request.missingLegId())) {
            throw ApiException.conflict(
                    "缺失航段不匹配: 登记为 " + bag.shortLegId() + "，提交为 " + request.missingLegId());
        }
        LegRow missingLeg = findLeg(request.missingLegId());
        if (missingLeg == null) {
            throw ApiException.unprocessable("缺失航段不存在: " + request.missingLegId());
        }
        if (!missingLeg.destination().equals(request.actualStation())) {
            throw ApiException.unprocessable(
                    "实际到站 " + request.actualStation() + " 与缺失航段应到站 "
                            + missingLeg.destination() + " 不一致");
        }
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bag.bagTag());
        String newStatus = nextIndex >= total ? BAG_DELIVERED : BAG_RECOVERED;
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                        + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL"
                        + " WHERE bag_tag = ?",
                request.actualStation(), nextIndex, newStatus, bag.bagTag());
        insertEvent(bag.bagTag(), EVT_RECOVERED, request.missingLegId(), request.actualStation());
        if (BAG_DELIVERED.equals(newStatus)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, request.missingLegId(), request.actualStation());
        }
        return new RecoverResponse(bag.bagTag(), newStatus, request.actualStation(),
                nextIndex, request.missingLegId());
    }

    private ClaimHoldResponse doHold(String bagTag, ClaimHoldRequest request) {
        BagRow preview = findBag(bagTag);
        if (preview == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        // 与装载/到达保持同一加锁顺序（先航段后行李），避免与到达确认互相等待
        String previewLegId = preview.loadedLegId();
        LegRow leg = previewLegId == null ? null : lockLeg(previewLegId);
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (!Objects.equals(bag.loadedLegId(), previewLegId)) {
            throw new StaleLockException();
        }
        if (BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已存在生效认领冻结");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已最终交付，不可冻结");
        }
        if (BAG_LOST.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已丢失，不可冻结");
        }
        String removedLegId = null;
        if (bag.loadedLegId() != null) {
            if (!LEG_OPEN.equals(leg.status())) {
                throw ApiException.conflict("行李 " + bagTag + " 所在航段 " + leg.legId()
                        + " 状态为 " + leg.status() + "，已封舱不可冻结");
            }
            removedLegId = leg.legId();
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bagTag);
            jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?",
                    leg.version() + 1, leg.legId());
        }
        OffsetDateTime heldAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO claim_hold (bag_tag, claim_key, passenger_digest, reason, prev_status,"
                        + " removed_leg_id, status, hold_agent, active_bag_tag, held_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, request.claimKey(), request.passengerDigest(), request.reason(),
                bag.status(), removedLegId, HOLD_ACTIVE, request.agentId(), bagTag, heldAt);
        Long holdId = jdbcTemplate.queryForObject(
                "SELECT hold_id FROM claim_hold WHERE active_bag_tag = ?", Long.class, bagTag);
        jdbcTemplate.update("UPDATE bag SET status = ?, loaded_leg_id = NULL WHERE bag_tag = ?",
                BAG_CLAIM_HOLD, bagTag);
        if (removedLegId != null) {
            insertChainEvent(bagTag, holdId, CHAIN_MANIFEST_REMOVE, removedLegId, null, request.agentId());
        }
        insertChainEvent(bagTag, holdId, CHAIN_HOLD, removedLegId, request.reason(), request.agentId());
        insertEvent(bagTag, EVT_CLAIM_HOLD, removedLegId, bag.currentLocation());
        return toClaimHoldResponse(findHold(holdId));
    }

    private ClaimReviewResponse doReview(String bagTag, ClaimReviewRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ClaimHoldRow hold = lockActiveHold(bag, bagTag);
        if (!HOLD_ACTIVE.equals(hold.status())) {
            throw ApiException.conflict("冻结 " + hold.holdId() + " 已复核，不得重复复核");
        }
        if (hold.holdAgent().equals(request.agentId())) {
            throw ApiException.conflict("复核客服须不同于登记客服: " + request.agentId());
        }
        if (!hold.passengerDigest().equals(request.passengerDigest())) {
            throw ApiException.unprocessable("乘客核验摘要不匹配");
        }
        OffsetDateTime reviewedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, review_agent = ?, reviewed_at = ? WHERE hold_id = ?",
                HOLD_REVIEWED, request.agentId(), reviewedAt, hold.holdId());
        insertChainEvent(bagTag, hold.holdId(), CHAIN_REVIEW, null, null, request.agentId());
        return new ClaimReviewResponse(hold.holdId(), bagTag, HOLD_REVIEWED,
                request.agentId(), reviewedAt.toInstant().toString());
    }

    private ClaimReleaseResponse doRelease(String bagTag, ClaimReleaseRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ClaimHoldRow hold = lockActiveHold(bag, bagTag);
        if (!HOLD_REVIEWED.equals(hold.status())) {
            throw ApiException.conflict("冻结 " + hold.holdId() + " 尚未完成复核，不得解除");
        }
        if (!hold.reviewAgent().equals(request.agentId())) {
            throw ApiException.conflict(
                    "解除确认须由复核客服本人提交: 期望 " + hold.reviewAgent() + "，提交为 " + request.agentId());
        }
        OffsetDateTime releasedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, release_agent = ?, released_at = ?,"
                        + " active_bag_tag = NULL WHERE hold_id = ?",
                HOLD_RELEASED, request.agentId(), releasedAt, hold.holdId());
        // 原子恢复为冻结前状态；冻结期间被拒绝的装载不自动恢复，须重新提交
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?", hold.prevStatus(), bagTag);
        insertChainEvent(bagTag, hold.holdId(), CHAIN_RELEASE, null, null, request.agentId());
        insertEvent(bagTag, EVT_CLAIM_RELEASE, null, bag.currentLocation());
        return new ClaimReleaseResponse(hold.holdId(), bagTag, HOLD_RELEASED, hold.prevStatus(),
                request.agentId(), releasedAt.toInstant().toString());
    }

    /** 读取并锁定当前生效冻结；行李不处于 CLAIM_HOLD 或无生效冻结时抛 409。 */
    private ClaimHoldRow lockActiveHold(BagRow bag, String bagTag) {
        if (!BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 当前无生效认领冻结");
        }
        List<ClaimHoldRow> rows = jdbcTemplate.query(holdSelect("WHERE active_bag_tag = ?", true),
                HOLD_MAPPER, bagTag);
        if (rows.isEmpty()) {
            throw ApiException.conflict("行李 " + bagTag + " 当前无生效认领冻结");
        }
        return rows.get(0);
    }

    private ClaimHoldRow findHold(long holdId) {
        List<ClaimHoldRow> rows = jdbcTemplate.query(holdSelect("WHERE hold_id = ?", false),
                HOLD_MAPPER, holdId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimHoldRow findLatestHold(String bagTag) {
        List<ClaimHoldRow> rows = jdbcTemplate.query(
                holdSelect("WHERE bag_tag = ? ORDER BY hold_id DESC LIMIT 1", false), HOLD_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String holdSelect(String suffix, boolean forUpdate) {
        return "SELECT hold_id, bag_tag, claim_key, passenger_digest, reason, prev_status,"
                + " removed_leg_id, status, hold_agent, review_agent, release_agent,"
                + " held_at, reviewed_at, released_at FROM claim_hold " + suffix
                + (forUpdate ? " FOR UPDATE" : "");
    }

    /** 在持有行李行锁后追加认领链记录，seq 按该行李已有链记录数递增。 */
    private void insertChainEvent(String bagTag, long holdId, String eventType,
                                  String legId, String reason, String agentId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM claim_hold_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO claim_hold_event (bag_tag, seq, hold_id, event_type, leg_id, reason,"
                        + " agent_id, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, holdId, eventType, legId, reason, agentId,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private static ClaimHoldResponse toClaimHoldResponse(ClaimHoldRow hold) {
        return new ClaimHoldResponse(hold.holdId(), hold.bagTag(), hold.claimKey(), hold.status(),
                hold.reason(), hold.prevStatus(), hold.removedLegId(),
                hold.holdAgent(), hold.reviewAgent(), hold.releaseAgent(),
                hold.heldAt().toString(),
                hold.reviewedAt() == null ? null : hold.reviewedAt().toString(),
                hold.releasedAt() == null ? null : hold.releasedAt().toString());
    }

    /** 实际到达行李的统一推进：移动到到达站、推进待乘索引，完成行程者交付。 */
    private void advanceArrivedBag(BagRow bag, String destination, String legId) {
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bag.bagTag());
        String status = nextIndex >= total ? BAG_DELIVERED : BAG_IN_TRANSIT;
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL"
                        + " WHERE bag_tag = ?",
                destination, nextIndex, status, bag.bagTag());
        insertEvent(bag.bagTag(), EVT_UNLOADED, legId, destination);
        if (BAG_DELIVERED.equals(status)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, legId, destination);
        }
    }

    private void validateLoadable(BagRow bag, LegRow leg) {
        if (BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 处于认领冻结状态，禁止装载任何后续航段");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能装载后续航段");
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 已装载到航段 " + bag.loadedLegId());
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 已完成全部行程");
        }
        if (!next.legId().equals(leg.legId())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 当前待乘航段为 " + next.legId() + "，不能装载到 " + leg.legId());
        }
        if (!bag.currentLocation().equals(leg.origin())) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 不在航段始发站 " + leg.origin());
        }
    }

    private void checkVersion(LegRow leg, int expectedVersion) {
        if (leg.version() != expectedVersion) {
            throw ApiException.conflict(
                    "航段版本冲突: 期望 " + expectedVersion + "，当前 " + leg.version());
        }
    }

    private LegRow findLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(legSelect(false), LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(legSelect(true), LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private static String legSelect(boolean forUpdate) {
        return "SELECT leg_id, origin, destination, status, version, sealed_manifest,"
                + " arrival_type, arrival_actual FROM leg WHERE leg_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(false), BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(true), BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String bagSelect(boolean forUpdate) {
        return "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
                + " short_leg_id, short_destination, short_registered_at FROM bag WHERE bag_tag = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private ItineraryItem nextItinerary(String bagTag, int nextLegIndex) {
        List<ItineraryItem> rows = jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_tag = ? AND seq = ?",
                ITINERARY_MAPPER, bagTag, nextLegIndex);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int itineraryCount(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE bag_tag = ?", Integer.class, bagTag);
        return count == null ? 0 : count;
    }

    private List<ItineraryItem> toItinerary(String bagTag) {
        return jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_tag = ? ORDER BY seq",
                ITINERARY_MAPPER, bagTag);
    }

    private List<TraceEvent> toEvents(String bagTag) {
        return jdbcTemplate.query(
                "SELECT seq, event_type, leg_id, location, event_time FROM bag_event"
                        + " WHERE bag_tag = ? ORDER BY seq",
                EVENT_MAPPER, bagTag);
    }

    /** 在持有行李行锁后追加事件，seq 按该行李已有事件数递增。 */
    private void insertEvent(String bagTag, String eventType, String legId, String location) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_tag, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, legId, location,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toEvents(bag.bagTag()));
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("清单序列化失败", ex);
        }
    }

    private List<String> readJsonList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            throw new IllegalStateException("清单反序列化失败", ex);
        }
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, List<String> bagTags) {
    }

    /** 封舱幂等摘要参数。 */
    private record SealPayload(String legId, int expectedVersion) {
    }

    /** 到达确认幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record ArrivePayload(String legId, List<String> bagTags) {
    }

    /** 差异到达幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DifferenceArrivePayload(String legId, int expectedVersion, List<String> bagTags) {
    }

    /** 认领冻结幂等摘要参数。 */
    private record ClaimHoldPayload(String bagTag, String claimKey, String agentId,
                                    String passengerDigest, String reason) {
    }

    /** 冻结复核幂等摘要参数。 */
    private record ClaimReviewPayload(String bagTag, String agentId, String passengerDigest) {
    }

    /** 冻结解除确认幂等摘要参数。 */
    private record ClaimReleasePayload(String bagTag, String agentId) {
    }

    private record ClaimHoldRow(long holdId, String bagTag, String claimKey, String passengerDigest,
                                String reason, String prevStatus, String removedLegId, String status,
                                String holdAgent, String reviewAgent, String releaseAgent,
                                Instant heldAt, Instant reviewedAt, Instant releasedAt) {
    }

    /** 加锁间隙目标状态已变化的信号，触发整体重试。 */
    private static final class StaleLockException extends RuntimeException {
    }
}
