package com.example.starter.baggage;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ClaimChainEvent;
import com.example.starter.baggage.BaggageDtos.ClaimDetailResponse;
import com.example.starter.baggage.BaggageDtos.ClaimDiagnosticItem;
import com.example.starter.baggage.BaggageDtos.ClaimDiagnosticResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewResponse;
import com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest;
import com.example.starter.baggage.BaggageDtos.DeliveryDifferenceResponse;
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
    private static final String BAG_CLAIM_HOLD = "CLAIM_HOLD";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String BAG_LOST = "LOST";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_CLAIM_HELD = "CLAIM_HELD";
    private static final String EVT_CLAIM_RELEASED = "CLAIM_RELEASED";
    private static final String EVT_DELIVERY_DIFFERENCE = "DELIVERY_DIFFERENCE";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final String CLAIM_ACTIVE = "ACTIVE";
    private static final String CLAIM_REVIEWED = "REVIEWED";
    private static final String CLAIM_RELEASED = "RELEASED";
    private static final String CHAIN_HOLD = "HOLD";
    private static final String CHAIN_LIST_REMOVAL = "LIST_REMOVAL";
    private static final String CHAIN_REVIEW = "REVIEW";
    private static final String CHAIN_RELEASE = "RELEASE";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getObject("capacity", Integer.class),
            rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<ClaimRow> CLAIM_MAPPER = (rs, rowNum) -> new ClaimRow(
            rs.getString("claim_key"), rs.getString("bag_tag"),
            rs.getString("passenger_verification_summary"), rs.getString("reason"),
            rs.getString("hold_agent_id"), rs.getString("hold_agent_role"),
            rs.getString("status"), rs.getString("status_before_hold"),
            rs.getString("frozen_leg_id"), rs.getString("reviewer_id"),
            rs.getString("reviewer_role"), getInstant(rs, "hold_at"),
            getInstant(rs, "reviewed_at"), getInstant(rs, "released_at"));

    private static final RowMapper<ClaimChainEvent> CHAIN_MAPPER = (rs, rowNum) -> {
        Instant reviewedAt = getInstant(rs, "reviewed_at");
        Instant releasedAt = getInstant(rs, "released_at");
        return new ClaimChainEvent(
                rs.getInt("seq"), rs.getString("event_type"), rs.getString("bag_tag"),
                rs.getString("leg_id"), rs.getString("reason"),
                rs.getString("hold_agent_id"), rs.getString("hold_agent_role"),
                rs.getString("review_agent_id"), rs.getString("review_agent_role"),
                getInstant(rs, "hold_at").toString(),
                reviewedAt == null ? null : reviewedAt.toString(),
                releasedAt == null ? null : releasedAt.toString(),
                getInstant(rs, "event_time").toString());
    };

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

    /**
     * 认领冻结登记：仅未到达最终目的地的行李可冻结；SEALED 航段上的行李（封舱先提交）、
     * CLAIM_HOLD/DELIVERED/LOST 行李返回 409。冻结时若行李处于某 OPEN 航段清单，
     * 同一事务内移出清单、推进航段版本并写入 LIST_REMOVAL 证据。
     */
    public ClaimHoldResponse claimHold(ClaimHoldRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_HOLD", 200,
                request, ClaimHoldResponse.class, () -> doClaimHold(request));
    }

    /** 复核：不同客服角色 + 核验摘要逐字一致；摘要不匹配 422，状态非 ACTIVE 409。 */
    public ClaimReviewResponse claimReview(ClaimReviewRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_REVIEW", 200,
                request, ClaimReviewResponse.class, () -> doClaimReview(request));
    }

    /** 第二次确认解除：摘要一致且由复核人提交时，原子恢复为冻结前可交接状态；旧装载不自动恢复。 */
    public ClaimReleaseResponse claimRelease(ClaimReleaseRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_RELEASE", 200,
                request, ClaimReleaseResponse.class, () -> doClaimRelease(request));
    }

    /**
     * 误交付差异登记：已解除冻结的行李其后若被误交付，只能登记差异；
     * 认领记录与证据链原样保留，不删除、不改写。
     */
    public DeliveryDifferenceResponse registerDeliveryDifference(DeliveryDifferenceRequest request) {
        return idempotencyService.execute(request.requestId(), "DELIVERY_DIFFERENCE", 200,
                request, DeliveryDifferenceResponse.class, () -> doRegisterDeliveryDifference(request));
    }

    /** 冻结明细与不可变证据链查询（按 seq 排序），纯只读。 */
    public ClaimDetailResponse getClaimDetail(String claimKey) {
        ClaimRow claim = findClaim(claimKey);
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + claimKey);
        }
        return toClaimDetail(claim);
    }

    /** 只读诊断：定位当前各操作能否执行及原因（含容量实际值/要求值/差额），不改变任何状态。 */
    public ClaimDiagnosticResponse getClaimDiagnostic(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ClaimRow active = findActiveClaim(bagTag);
        List<ClaimDiagnosticItem> items = new ArrayList<>();
        items.add(diagnoseHold(bag, active));
        items.add(diagnoseLoad(bag, active));
        if (active != null) {
            items.add(diagnoseRelease(active));
        }
        return new ClaimDiagnosticResponse(bagTag, bag.status(),
                active == null ? null : active.claimKey(), items);
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

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, capacity)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN, request.capacity());
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, request.capacity(), 0);
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
        // 统一锁顺序：先航段后行李（装载/封舱/到达/认领冻结一致），行锁按提交顺序裁决
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
        // 容量按当前清单实时校验（航段行锁内）：冻结移出、解除后重提都会改变实际件数
        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
        int loaded = currentCount == null ? 0 : currentCount;
        if (leg.capacity() != null && (long) loaded + sorted.size() > leg.capacity()) {
            throw ApiException.unprocessable("航段 " + legId + " 容量不足: 当前已装载实际值 " + loaded
                    + " 件，本次要求 " + sorted.size() + " 件，容量上限 " + leg.capacity()
                    + " 件，差额 " + ((long) loaded + sorted.size() - leg.capacity()) + " 件");
        }
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
        if (!BAG_SHORT_UNLOADED.equals(bag.status())) {
            if (BAG_CLAIM_HOLD.equals(bag.status())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 处于认领冻结 CLAIM_HOLD，禁止补到确认");
            }
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
                    "行李 " + bag.bagTag() + " 处于认领冻结 CLAIM_HOLD，禁止装载后续航段");
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
        return "SELECT leg_id, origin, destination, status, version, capacity, sealed_manifest,"
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

    private ClaimHoldResponse doClaimHold(ClaimHoldRequest request) {
        if (findClaim(request.claimKey()) != null) {
            throw ApiException.conflict("claimKey 已存在: " + request.claimKey());
        }
        // 非锁读定位候选航段：装载所在航段 + 待乘下一航段，都可能与并发装载/封舱竞争
        BagRow preview = findBag(request.bagTag());
        if (preview == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        Set<String> candidateLegIds = new HashSet<>();
        if (preview.loadedLegId() != null) {
            candidateLegIds.add(preview.loadedLegId());
        }
        ItineraryItem next = nextItinerary(preview.bagTag(), preview.nextLegIndex());
        if (next != null) {
            candidateLegIds.add(next.legId());
        }
        // 统一锁顺序：先航段（按 legId 排序）后行李，与装载/封舱/到达一致，按提交顺序裁决且无死锁
        List<LegRow> lockedLegs = new ArrayList<>();
        for (String candidateId : candidateLegIds.stream().sorted().toList()) {
            lockedLegs.add(lockLeg(candidateId));
        }
        BagRow bag = lockBag(request.bagTag());
        if (findActiveClaim(bag.bagTag()) != null || BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已存在生效中的认领冻结");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已最终交付，不可冻结");
        }
        if (BAG_LOST.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已丢失，不可冻结");
        }
        String frozenLegId = null;
        int legNewVersion = -1;
        if (bag.loadedLegId() != null) {
            LegRow onListLeg = lockedLegs.stream()
                    .filter(leg -> leg.legId().equals(bag.loadedLegId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.unprocessable(
                            "行李装载航段数据不一致: " + bag.loadedLegId()));
            if (!LEG_OPEN.equals(onListLeg.status())) {
                // 封舱先提交：行李已固化进 SEALED 舱单，冻结裁决失败
                throw ApiException.conflict(
                        "航段 " + onListLeg.legId() + " 已 " + onListLeg.status()
                                + "（封舱先提交），行李不可冻结");
            }
            frozenLegId = onListLeg.legId();
            legNewVersion = onListLeg.version() + 1;
        }
        Instant holdAt = clock.get();
        try {
            jdbcTemplate.update(
                    "INSERT INTO claim_hold (claim_key, bag_tag, passenger_verification_summary, reason,"
                            + " hold_agent_id, hold_agent_role, status, status_before_hold, frozen_leg_id, hold_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    request.claimKey(), bag.bagTag(), request.passengerVerificationSummary(), request.reason(),
                    request.holdAgentId(), request.holdAgentRole(), CLAIM_ACTIVE,
                    bag.status(), frozenLegId, OffsetDateTime.ofInstant(holdAt, ZoneOffset.UTC));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 唯一索引兜底：并发下已有未解除冻结或 claimKey 冲突
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已存在生效中的认领冻结或 claimKey 冲突");
        }
        if (frozenLegId != null) {
            // 同一事务内先移出 OPEN 清单并推进航段版本，再写入冻结历史
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bag.bagTag());
            jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", legNewVersion, frozenLegId);
        }
        jdbcTemplate.update("UPDATE bag SET status = ?, loaded_leg_id = NULL WHERE bag_tag = ?",
                BAG_CLAIM_HOLD, bag.bagTag());
        insertChain(request.claimKey(), 0, CHAIN_HOLD, frozenLegId, request,
                holdAt, null, null, holdAt);
        if (frozenLegId != null) {
            insertChain(request.claimKey(), 1, CHAIN_LIST_REMOVAL, frozenLegId, request,
                    holdAt, null, null, holdAt);
        }
        insertEvent(bag.bagTag(), EVT_CLAIM_HELD, frozenLegId, bag.currentLocation());
        return new ClaimHoldResponse(request.claimKey(), bag.bagTag(), CLAIM_ACTIVE,
                bag.status(), frozenLegId, request.reason(),
                request.holdAgentId(), request.holdAgentRole(), holdAt.toString());
    }

    private ClaimReviewResponse doClaimReview(ClaimReviewRequest request) {
        ClaimRow claim = lockClaim(request.claimKey());
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + request.claimKey());
        }
        if (!CLAIM_ACTIVE.equals(claim.status())) {
            throw ApiException.conflict(
                    "认领冻结状态为 " + claim.status() + "，仅 ACTIVE 可复核");
        }
        if (request.reviewerRole().equals(claim.holdAgentRole())
                || request.reviewerId().equals(claim.holdAgentId())) {
            throw ApiException.unprocessable("复核客服必须是与登记人不同角色的另一位客服");
        }
        if (!request.passengerVerificationSummary().equals(claim.passengerVerificationSummary())) {
            throw ApiException.unprocessable("乘客核验摘要不匹配，复核拒绝");
        }
        Instant reviewedAt = clock.get();
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, reviewer_id = ?, reviewer_role = ?, reviewed_at = ?"
                        + " WHERE claim_key = ?",
                CLAIM_REVIEWED, request.reviewerId(), request.reviewerRole(),
                OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC), request.claimKey());
        appendChain(claim, CHAIN_REVIEW, claim.frozenLegId(),
                request.reviewerId(), request.reviewerRole(), reviewedAt, null, reviewedAt);
        long durationSeconds = Duration.between(claim.holdAt(), reviewedAt).getSeconds();
        return new ClaimReviewResponse(request.claimKey(), claim.bagTag(), CLAIM_REVIEWED,
                request.reviewerId(), request.reviewerRole(),
                claim.holdAt().toString(), reviewedAt.toString(), durationSeconds);
    }

    private ClaimReleaseResponse doClaimRelease(ClaimReleaseRequest request) {
        ClaimRow claim = lockClaim(request.claimKey());
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + request.claimKey());
        }
        if (CLAIM_RELEASED.equals(claim.status())) {
            throw ApiException.conflict("认领冻结已解除，不可重复解除");
        }
        if (!CLAIM_REVIEWED.equals(claim.status())) {
            throw ApiException.conflict("认领冻结尚未经不同角色客服复核，不能解除");
        }
        if (!request.releaseAgentRole().equals(claim.reviewerRole())
                || !request.releaseAgentId().equals(claim.reviewerId())) {
            throw ApiException.unprocessable("第二次确认须由复核客服本人提交");
        }
        if (!request.passengerVerificationSummary().equals(claim.passengerVerificationSummary())) {
            throw ApiException.unprocessable("乘客核验摘要不匹配，第二次确认拒绝");
        }
        BagRow bag = lockBag(claim.bagTag());
        if (!BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 当前状态为 " + bag.status() + "，与生效冻结不一致");
        }
        Instant releasedAt = clock.get();
        // 原子恢复为冻结前可交接状态；旧装载请求不自动恢复（loaded_leg_id 保持 NULL、不回插 load_record）
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?",
                claim.statusBeforeHold(), claim.bagTag());
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, released_at = ? WHERE claim_key = ?",
                CLAIM_RELEASED, OffsetDateTime.ofInstant(releasedAt, ZoneOffset.UTC), request.claimKey());
        appendChain(claim, CHAIN_RELEASE, claim.frozenLegId(),
                claim.reviewerId(), claim.reviewerRole(),
                claim.reviewedAt(), releasedAt, releasedAt);
        insertEvent(claim.bagTag(), EVT_CLAIM_RELEASED, null, bag.currentLocation());
        long durationSeconds = Duration.between(claim.holdAt(), releasedAt).getSeconds();
        return new ClaimReleaseResponse(request.claimKey(), claim.bagTag(), CLAIM_RELEASED,
                claim.statusBeforeHold(), claim.reviewerId(), claim.reviewerRole(),
                claim.holdAt().toString(), releasedAt.toString(), durationSeconds);
    }

    private DeliveryDifferenceResponse doRegisterDeliveryDifference(DeliveryDifferenceRequest request) {
        ClaimRow claim = findClaim(request.claimKey());
        if (claim == null || !claim.bagTag().equals(request.bagTag())) {
            throw ApiException.notFound(
                    "行李 " + request.bagTag() + " 的认领冻结不存在: " + request.claimKey());
        }
        if (!CLAIM_RELEASED.equals(claim.status())) {
            throw ApiException.conflict(
                    "认领冻结状态为 " + claim.status() + "，仅解除后的误交付可登记差异");
        }
        Instant registeredAt = clock.get();
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO delivery_difference (claim_key, bag_tag, delivered_to_station,"
                            + " expected_station, registered_by, note, registered_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.claimKey());
            ps.setString(2, request.bagTag());
            ps.setString(3, request.deliveredToStation());
            ps.setString(4, request.expectedStation());
            ps.setString(5, request.registeredBy());
            ps.setString(6, request.note());
            ps.setObject(7, OffsetDateTime.ofInstant(registeredAt, ZoneOffset.UTC));
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        insertEvent(request.bagTag(), EVT_DELIVERY_DIFFERENCE, null, request.deliveredToStation());
        return new DeliveryDifferenceResponse(generatedId == null ? -1 : generatedId.longValue(),
                request.claimKey(), request.bagTag(), request.deliveredToStation(),
                request.expectedStation(), request.registeredBy(), registeredAt.toString());
    }

    private ClaimDiagnosticItem diagnoseHold(BagRow bag, ClaimRow active) {
        if (active != null) {
            return new ClaimDiagnosticItem("CLAIM_HOLD", null, false, 409,
                    "行李已存在生效冻结 " + active.claimKey());
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            return new ClaimDiagnosticItem("CLAIM_HOLD", null, false, 409, "行李已最终交付，不可冻结");
        }
        if (BAG_LOST.equals(bag.status())) {
            return new ClaimDiagnosticItem("CLAIM_HOLD", null, false, 409, "行李已丢失，不可冻结");
        }
        if (bag.loadedLegId() != null) {
            LegRow leg = findLeg(bag.loadedLegId());
            if (leg != null && !LEG_OPEN.equals(leg.status())) {
                return new ClaimDiagnosticItem("CLAIM_HOLD", null, false, 409,
                        "所在航段 " + leg.legId() + " 已 " + leg.status() + "，封舱先提交");
            }
        }
        return new ClaimDiagnosticItem("CLAIM_HOLD", null, true, 200,
                "行李当前可冻结（状态 " + bag.status() + "）");
    }

    private ClaimDiagnosticItem diagnoseLoad(BagRow bag, ClaimRow active) {
        if (active != null || BAG_CLAIM_HOLD.equals(bag.status())) {
            return new ClaimDiagnosticItem("LOAD", null, false, 409, "行李处于 CLAIM_HOLD，装载裁决失败");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return new ClaimDiagnosticItem("LOAD", null, false, 422, "行李短卸待补，不可装载");
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null) {
            return new ClaimDiagnosticItem("LOAD", null, false, 422, "行李已完成全部行程");
        }
        LegRow leg = findLeg(next.legId());
        if (leg == null || !LEG_OPEN.equals(leg.status())) {
            return new ClaimDiagnosticItem("LOAD", null, false, 422,
                    "待乘航段 " + next.legId() + " 非 OPEN，装载需重提");
        }
        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, leg.legId());
        int loaded = currentCount == null ? 0 : currentCount;
        if (leg.capacity() != null && (long) loaded + 1 > leg.capacity()) {
            return new ClaimDiagnosticItem("LOAD", null, false, 422,
                    "容量不足: 实际已装 " + loaded + " 件，要求 1 件，上限 " + leg.capacity()
                            + " 件，差额 " + ((long) loaded + 1 - leg.capacity()) + " 件");
        }
        return new ClaimDiagnosticItem("LOAD", null, true, 200,
                "可重提装载到 " + leg.legId() + "（当前清单实际 " + loaded + " 件）");
    }

    private ClaimDiagnosticItem diagnoseRelease(ClaimRow active) {
        if (CLAIM_REVIEWED.equals(active.status())) {
            return new ClaimDiagnosticItem("CLAIM_RELEASE", null, true, 200,
                    "已复核，等待第二次确认解除；时长按 releasedAt - holdAt 计算");
        }
        return new ClaimDiagnosticItem("CLAIM_RELEASE", null, false, 409,
                "冻结状态为 " + active.status() + "，须先经不同角色客服复核");
    }

    private ClaimRow findClaim(String claimKey) {
        List<ClaimRow> rows = jdbcTemplate.query(claimSelect(false), CLAIM_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimRow lockClaim(String claimKey) {
        List<ClaimRow> rows = jdbcTemplate.query(claimSelect(true), CLAIM_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimRow findActiveClaim(String bagTag) {
        List<ClaimRow> rows = jdbcTemplate.query(
                "SELECT claim_key, bag_tag, passenger_verification_summary, reason, hold_agent_id,"
                        + " hold_agent_role, status, status_before_hold, frozen_leg_id, reviewer_id,"
                        + " reviewer_role, hold_at, reviewed_at, released_at FROM claim_hold"
                        + " WHERE bag_tag = ? AND status <> 'RELEASED'",
                CLAIM_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String claimSelect(boolean forUpdate) {
        return "SELECT claim_key, bag_tag, passenger_verification_summary, reason, hold_agent_id,"
                + " hold_agent_role, status, status_before_hold, frozen_leg_id, reviewer_id,"
                + " reviewer_role, hold_at, reviewed_at, released_at FROM claim_hold WHERE claim_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private ClaimDetailResponse toClaimDetail(ClaimRow claim) {
        List<ClaimChainEvent> chain = jdbcTemplate.query(
                "SELECT seq, event_type, bag_tag, leg_id, reason, hold_agent_id, hold_agent_role,"
                        + " review_agent_id, review_agent_role, hold_at, reviewed_at, released_at, event_time"
                        + " FROM claim_chain WHERE claim_key = ? ORDER BY seq",
                CHAIN_MAPPER, claim.claimKey());
        return new ClaimDetailResponse(claim.claimKey(), claim.bagTag(), claim.status(),
                claim.statusBeforeHold(), claim.frozenLegId(),
                claim.passengerVerificationSummary(), claim.reason(),
                claim.holdAgentId(), claim.holdAgentRole(),
                claim.reviewerId(), claim.reviewerRole(),
                claim.holdAt().toString(),
                claim.reviewedAt() == null ? null : claim.reviewedAt().toString(),
                claim.releasedAt() == null ? null : claim.releasedAt().toString(),
                chain);
    }

    /** 写入 HOLD/LIST_REMOVAL 行：时刻、原因、第一位操作人固化，第二位操作人相关字段为 NULL。 */
    private void insertChain(String claimKey, int seq, String eventType, String legId,
                             ClaimHoldRequest request, Instant holdAt, Instant reviewedAt,
                             Instant releasedAt, Instant eventTime) {
        jdbcTemplate.update(
                "INSERT INTO claim_chain (claim_key, seq, event_type, bag_tag, leg_id, reason,"
                        + " hold_agent_id, hold_agent_role, review_agent_id, review_agent_role,"
                        + " hold_at, reviewed_at, released_at, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
                claimKey, seq, eventType, request.bagTag(), legId, request.reason(),
                request.holdAgentId(), request.holdAgentRole(),
                OffsetDateTime.ofInstant(holdAt, ZoneOffset.UTC),
                reviewedAt == null ? null : OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC),
                releasedAt == null ? null : OffsetDateTime.ofInstant(releasedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(eventTime, ZoneOffset.UTC));
    }

    /** 追加 REVIEW/RELEASE 行：seq 按链内最大值递增，冻结航段与原因沿用首行固化值。 */
    private void appendChain(ClaimRow claim, String eventType, String legId,
                             String reviewAgentId, String reviewAgentRole,
                             Instant reviewedAt, Instant releasedAt, Instant eventTime) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM claim_chain WHERE claim_key = ?", Integer.class, claim.claimKey());
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO claim_chain (claim_key, seq, event_type, bag_tag, leg_id, reason,"
                        + " hold_agent_id, hold_agent_role, review_agent_id, review_agent_role,"
                        + " hold_at, reviewed_at, released_at, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                claim.claimKey(), nextSeq, eventType, claim.bagTag(), legId, claim.reason(),
                claim.holdAgentId(), claim.holdAgentRole(),
                reviewAgentId, reviewAgentRole,
                OffsetDateTime.ofInstant(claim.holdAt(), ZoneOffset.UTC),
                reviewedAt == null ? null : OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC),
                releasedAt == null ? null : OffsetDateTime.ofInstant(releasedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(eventTime, ZoneOffset.UTC));
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, Integer capacity, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 认领冻结当前状态行：时刻均为 UTC，未发生的环节对应字段为 null。 */
    private record ClaimRow(String claimKey, String bagTag, String passengerVerificationSummary,
                            String reason, String holdAgentId, String holdAgentRole,
                            String status, String statusBeforeHold, String frozenLegId,
                            String reviewerId, String reviewerRole,
                            Instant holdAt, Instant reviewedAt, Instant releasedAt) {
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
}
