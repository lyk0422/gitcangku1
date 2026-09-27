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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagDiagnosticsResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ClaimEventItem;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest;
import com.example.starter.baggage.BaggageDtos.DeliveryDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.HoldClaimRequest;
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
 * 联程行李装载交接与认领冻结核心业务。
 * 航段/行李登记、批量装载（容量校验）、封舱、精确/差异到达、补到与查询；
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交。
 * 行级锁（SELECT ... FOR UPDATE）统一按“航段行 -> 行李行”顺序加锁，
 * 冻结、装载、封舱按提交顺序串行裁决：封舱先提交则在舱行李冻结 409，冻结先提交则装载 409。
 * 冻结生效后行李转 CLAIM_HOLD：在 OPEN 清单中的同事务移出并写不可变链记录，
 * 禁止后续装载、补到与到达交付；解除由不同客服复核摘要 + 第二次确认原子恢复，
 * 旧装载请求不自动恢复，须重提并按当前容量校验。
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

    /** 允许冻结的可交接/待处理状态。 */
    private static final Set<String> HOLDABLE_PRIOR_STATUSES =
            Set.of(BAG_IN_TRANSIT, BAG_SHORT_UNLOADED, BAG_RECOVERED);

    private static final String CLAIM_ACTIVE = "ACTIVE";
    private static final String CLAIM_REVIEWED = "REVIEWED";
    private static final String CLAIM_RELEASED = "RELEASED";
    private static final String ROLE_AGENT = "CS_AGENT";
    private static final String ROLE_REVIEWER = "CS_REVIEWER";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_CLAIM_HELD = "CLAIM_HELD";
    private static final String EVT_CLAIM_RELEASED = "CLAIM_RELEASED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final String CHAIN_HELD = "HELD";
    private static final String CHAIN_MANIFEST_REMOVED = "MANIFEST_REMOVED";
    private static final String CHAIN_REVIEWED = "REVIEWED";
    private static final String CHAIN_RELEASED = "RELEASED";
    private static final String CHAIN_DELIVERY_DIFFERENCE = "DELIVERY_DIFFERENCE";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> {
        Integer capacity = (Integer) rs.getObject("capacity");
        return new LegRow(
                rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
                rs.getString("status"), rs.getInt("version"), capacity,
                rs.getString("sealed_manifest"), rs.getString("arrival_type"),
                rs.getString("arrival_actual"));
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

    private static final RowMapper<ClaimRow> CLAIM_MAPPER = (rs, rowNum) -> new ClaimRow(
            rs.getString("claim_key"), rs.getString("bag_tag"), rs.getString("prior_status"),
            rs.getString("leg_id"), rs.getString("passenger_digest"), rs.getString("reason"),
            rs.getString("held_by"), rs.getString("held_by_role"), getInstant(rs, "held_at"),
            rs.getString("status"), rs.getString("reviewed_by"), rs.getString("reviewed_by_role"),
            getInstant(rs, "reviewed_at"), rs.getString("released_by"),
            getInstant(rs, "released_at"), rs.getInt("discrepancy_count"));

    private static final RowMapper<ClaimEventItem> CLAIM_EVENT_MAPPER = (rs, rowNum) -> new ClaimEventItem(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("reason"), rs.getString("operator_id"), rs.getString("operator_role"),
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

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1；capacity 为 null 表示不限容量。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                request, LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则失败且无一件移动；按航段容量校验实际件数。 */
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
     * 认领冻结中的行李不可补到（409）。
     */
    public RecoverResponse recover(RecoverRequest request) {
        return idempotencyService.execute(request.requestId(), "RECOVER", 200,
                request, RecoverResponse.class, () -> doRecover(request));
    }

    /**
     * 认领冻结登记：授权客服对未到达最终目的地的行李登记 claimKey、乘客核验摘要与原因。
     * 行李进入 CLAIM_HOLD；若位于某 OPEN 航段装载清单，同事务移出清单（旧装载请求不自动恢复）、
     * 推进航段版本并写 MANIFEST_REMOVED 链记录。已在 SEALED 舱单、已交付、已丢失或已有生效冻结返回 409。
     */
    public ClaimHoldResponse holdClaim(HoldClaimRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_HOLD", 200,
                request, ClaimHoldResponse.class, () -> doHoldClaim(request));
    }

    /**
     * 冻结复核：必须由与登记客服不同的复核客服（CS_REVIEWER）提交相同乘客核验摘要。
     * 摘要不匹配返回 422；复核成功冻结转 REVIEWED，等待同一复核人第二次确认。
     */
    public ClaimHoldResponse reviewClaim(ClaimReviewRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_REVIEW", 200,
                request, ClaimHoldResponse.class, () -> doReviewClaim(request));
    }

    /** 解除冻结：复核人的第二次确认，原子把行李恢复为冻结前可交接状态；未复核或非复核人返回 422。 */
    public ClaimHoldResponse releaseClaim(ClaimReleaseRequest request) {
        return idempotencyService.execute(request.requestId(), "CLAIM_RELEASE", 200,
                request, ClaimHoldResponse.class, () -> doReleaseClaim(request));
    }

    /**
     * 误交付差异登记：生效冻结（ACTIVE/REVIEWED）的行李被误交付时只登记差异，
     * 不改行李状态、不删除认领记录，差异计数累加并追加不可变链记录。
     */
    public DeliveryDifferenceResponse registerDeliveryDifference(DeliveryDifferenceRequest request) {
        return idempotencyService.execute(request.requestId(), "DELIVERY_DIFFERENCE", 200,
                request, DeliveryDifferenceResponse.class, () -> doRegisterDeliveryDifference(request));
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

    /** 认领冻结明细与不可变链查询：返回固化航段、原因、两位操作人、UTC 时刻与左闭右开时长。 */
    public ClaimHoldResponse getClaimHold(String claimKey) {
        ClaimRow claim = findClaim(claimKey);
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + claimKey);
        }
        return toClaimResponse(claim);
    }

    /** 行李诊断只读视图：待乘航段、容量差额、生效冻结与可交接状态；读取不改变任何状态。 */
    public BagDiagnosticsResponse getBagDiagnostics(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        Integer capacity = null;
        Integer loadedCount = null;
        Integer remaining = null;
        if (next != null) {
            LegRow leg = findLeg(next.legId());
            if (leg != null) {
                capacity = leg.capacity();
                loadedCount = countLoaded(leg.legId());
                remaining = capacity == null ? null : capacity - loadedCount;
            }
        }
        ClaimRow activeClaim = findActiveClaimByBag(bag.bagTag());
        boolean handlable = BAG_IN_TRANSIT.equals(bag.status()) || BAG_RECOVERED.equals(bag.status());
        return new BagDiagnosticsResponse(bag.bagTag(), bag.status(), bag.currentLocation(),
                bag.nextLegIndex(), next == null ? null : next.legId(), bag.loadedLegId(),
                capacity, loadedCount, remaining,
                activeClaim == null ? null : activeClaim.claimKey(), handlable);
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
                LEG_OPEN, 1, request.capacity());
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
        int currentCount = countLoaded(legId);
        if (leg.capacity() != null && currentCount + bags.size() > leg.capacity()) {
            int required = currentCount + bags.size();
            throw ApiException.unprocessable(
                    "航段 " + legId + " 容量不足：容量上限 " + leg.capacity() + " 件（要求值），清单实际 "
                            + currentCount + " 件，本次申请 " + bags.size() + " 件，合计要求 " + required
                            + " 件，超出差额 " + (required - leg.capacity()) + " 件");
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        int loadedCount = currentCount + bags.size();
        Integer remaining = leg.capacity() == null ? null : leg.capacity() - loadedCount;
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted,
                leg.capacity(), loadedCount, remaining);
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
            rejectIfClaimHeld(bag);
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
            rejectIfClaimHeld(bag);
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
        rejectIfClaimHeld(bag);
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

    private ClaimHoldResponse doHoldClaim(HoldClaimRequest request) {
        if (!ROLE_AGENT.equals(request.operatorRole())) {
            throw ApiException.unprocessable("仅授权客服（CS_AGENT）可登记认领冻结，当前角色: "
                    + request.operatorRole());
        }
        if (findClaim(request.claimKey()) != null) {
            throw ApiException.conflict("claimKey 已存在: " + request.claimKey());
        }
        BagRow unlockedBag = findBag(request.bagTag());
        if (unlockedBag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        // 统一按“航段行 -> 行李行”加锁：冻结与装载/封舱在同一航段行上串行，按提交顺序裁决。
        ItineraryItem nextItem = nextItinerary(request.bagTag(), unlockedBag.nextLegIndex());
        LegRow lockedLeg = null;
        if (nextItem != null) {
            lockedLeg = lockLegIfExists(nextItem.legId());
        }
        BagRow bag = lockBag(request.bagTag());
        if (findActiveClaimByBag(bag.bagTag()) != null) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已存在生效冻结，同一行李同时只能一个生效冻结");
        }
        if (!HOLDABLE_PRIOR_STATUSES.contains(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 状态为 " + bag.status()
                    + "，不可冻结（仅在途/短卸/已补到且未最终交付的行李可冻结）");
        }
        String removedLegId = null;
        if (bag.loadedLegId() != null) {
            // 行李已在某航段清单：必须是该航段 OPEN 才能同事务移出；SEALED 舱单不可冻结。
            LegRow aboard = lockedLeg != null && lockedLeg.legId().equals(bag.loadedLegId())
                    ? lockedLeg : lockLeg(bag.loadedLegId());
            if (!LEG_OPEN.equals(aboard.status())) {
                throw ApiException.conflict("行李 " + bag.bagTag() + " 所在航段 " + aboard.legId()
                        + " 已 " + aboard.status() + "，不可冻结");
            }
            removedLegId = aboard.legId();
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bag.bagTag());
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = NULL WHERE bag_tag = ?", bag.bagTag());
            int newVersion = aboard.version() + 1;
            jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, removedLegId);
        }
        Instant heldAt = clock.get();
        try {
            jdbcTemplate.update(
                    "INSERT INTO claim_hold (claim_key, bag_tag, prior_status, leg_id, passenger_digest,"
                            + " reason, held_by, held_by_role, held_at, status, discrepancy_count)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    request.claimKey(), bag.bagTag(), bag.status(), removedLegId,
                    request.passengerDigest(), request.reason(),
                    request.operatorId(), ROLE_AGENT, OffsetDateTime.ofInstant(heldAt, ZoneOffset.UTC),
                    CLAIM_ACTIVE);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("claimKey 已存在或行李 " + bag.bagTag() + " 已有生效冻结");
        }
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?", BAG_CLAIM_HOLD, bag.bagTag());
        // 不可变链：HELD 固化航段（移出清单时为该 OPEN 航段，否则为 null）、原因与登记操作人。
        insertClaimEvent(request.claimKey(), bag.bagTag(), CHAIN_HELD, removedLegId,
                request.reason(), request.operatorId(), ROLE_AGENT, heldAt);
        if (removedLegId != null) {
            insertClaimEvent(request.claimKey(), bag.bagTag(), CHAIN_MANIFEST_REMOVED, removedLegId,
                    null, request.operatorId(), ROLE_AGENT, heldAt);
        }
        insertEvent(bag.bagTag(), EVT_CLAIM_HELD, removedLegId, bag.currentLocation());
        return toClaimResponse(lockClaim(request.claimKey()));
    }

    private ClaimHoldResponse doReviewClaim(ClaimReviewRequest request) {
        if (!ROLE_REVIEWER.equals(request.operatorRole())) {
            throw ApiException.unprocessable("仅复核客服（CS_REVIEWER）可复核认领冻结，当前角色: "
                    + request.operatorRole());
        }
        ClaimRow claim = lockClaim(request.claimKey());
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + request.claimKey());
        }
        if (CLAIM_RELEASED.equals(claim.status())) {
            throw ApiException.conflict("认领冻结 " + claim.claimKey() + " 已解除，不可复核");
        }
        if (CLAIM_REVIEWED.equals(claim.status())) {
            throw ApiException.conflict("认领冻结 " + claim.claimKey() + " 已复核，等待第二次确认解除");
        }
        if (request.operatorId().equals(claim.heldBy())) {
            throw ApiException.unprocessable(
                    "复核客服必须与登记客服不同：登记人 " + claim.heldBy());
        }
        if (!request.passengerDigest().equals(claim.passengerDigest())) {
            throw ApiException.unprocessable("乘客核验摘要不匹配，复核拒绝");
        }
        Instant reviewedAt = clock.get();
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, reviewed_by = ?, reviewed_by_role = ?, reviewed_at = ?"
                        + " WHERE claim_key = ?",
                CLAIM_REVIEWED, request.operatorId(), ROLE_REVIEWER,
                OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC), claim.claimKey());
        insertClaimEvent(claim.claimKey(), claim.bagTag(), CHAIN_REVIEWED, claim.legId(),
                null, request.operatorId(), ROLE_REVIEWER, reviewedAt);
        return toClaimResponse(lockClaim(request.claimKey()));
    }

    private ClaimHoldResponse doReleaseClaim(ClaimReleaseRequest request) {
        if (!ROLE_REVIEWER.equals(request.operatorRole())) {
            throw ApiException.unprocessable("仅复核客服（CS_REVIEWER）可确认解除，当前角色: "
                    + request.operatorRole());
        }
        ClaimRow claim = lockClaim(request.claimKey());
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + request.claimKey());
        }
        if (CLAIM_RELEASED.equals(claim.status())) {
            throw ApiException.conflict("认领冻结 " + claim.claimKey() + " 已解除");
        }
        if (CLAIM_ACTIVE.equals(claim.status())) {
            throw ApiException.unprocessable("认领冻结 " + claim.claimKey() + " 尚未经不同客服复核，不能解除");
        }
        if (!request.operatorId().equals(claim.reviewedBy())) {
            throw ApiException.unprocessable("第二次确认解除须由复核人 " + claim.reviewedBy() + " 本人提交");
        }
        // 原子恢复为冻结前状态；短卸字段在冻结期间原样保留，旧装载记录不恢复，须重提并按当前容量校验。
        Instant releasedAt = clock.get();
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?", claim.priorStatus(), claim.bagTag());
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, released_by = ?, released_at = ? WHERE claim_key = ?",
                CLAIM_RELEASED, request.operatorId(),
                OffsetDateTime.ofInstant(releasedAt, ZoneOffset.UTC), claim.claimKey());
        insertClaimEvent(claim.claimKey(), claim.bagTag(), CHAIN_RELEASED, claim.legId(),
                null, request.operatorId(), ROLE_REVIEWER, releasedAt);
        insertEvent(claim.bagTag(), EVT_CLAIM_RELEASED, claim.legId(), null);
        return toClaimResponse(lockClaim(request.claimKey()));
    }

    private DeliveryDifferenceResponse doRegisterDeliveryDifference(DeliveryDifferenceRequest request) {
        ClaimRow claim = lockClaim(request.claimKey());
        if (claim == null) {
            throw ApiException.notFound("认领冻结不存在: " + request.claimKey());
        }
        if (CLAIM_RELEASED.equals(claim.status())) {
            throw ApiException.unprocessable("认领冻结 " + claim.claimKey()
                    + " 已解除，行李恢复可交接，误交付按正常交付差异流程处理");
        }
        if (findLeg(request.legId()) == null) {
            throw ApiException.unprocessable("差异登记的航段不存在: " + request.legId());
        }
        BagRow bag = lockBag(claim.bagTag());
        if (bag == null || !BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + claim.bagTag() + " 当前不在认领冻结中，不能登记误交付差异");
        }
        Instant registeredAt = clock.get();
        int newCount = claim.discrepancyCount() + 1;
        jdbcTemplate.update(
                "UPDATE claim_hold SET discrepancy_count = ? WHERE claim_key = ?",
                newCount, claim.claimKey());
        insertClaimEvent(claim.claimKey(), claim.bagTag(), CHAIN_DELIVERY_DIFFERENCE, request.legId(),
                request.note(), request.operatorId(), request.operatorRole(), registeredAt);
        List<ClaimEventItem> events = listClaimEvents(claim.claimKey());
        return new DeliveryDifferenceResponse(claim.claimKey(), claim.bagTag(),
                newCount, registeredAt.toString(), events);
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
            // 冻结先于装载提交：状态冲突 409，整批拒绝。
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 处于认领冻结（CLAIM_HOLD），禁止装载后续航段");
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

    private void rejectIfClaimHeld(BagRow bag) {
        if (bag != null && BAG_CLAIM_HOLD.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 处于认领冻结（CLAIM_HOLD），禁止补到确认或最终交付");
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

    private LegRow lockLegIfExists(String legId) {
        List<LegRow> rows = jdbcTemplate.query(legSelect(true), LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
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

    private ClaimRow findClaim(String claimKey) {
        List<ClaimRow> rows = jdbcTemplate.query(claimSelect(false), CLAIM_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimRow lockClaim(String claimKey) {
        List<ClaimRow> rows = jdbcTemplate.query(claimSelect(true), CLAIM_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String claimSelect(boolean forUpdate) {
        return "SELECT claim_key, bag_tag, prior_status, leg_id, passenger_digest, reason,"
                + " held_by, held_by_role, held_at, status, reviewed_by, reviewed_by_role,"
                + " reviewed_at, released_by, released_at, discrepancy_count"
                + " FROM claim_hold WHERE claim_key = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private ClaimRow findActiveClaimByBag(String bagTag) {
        List<ClaimRow> rows = jdbcTemplate.query(
                "SELECT claim_key, bag_tag, prior_status, leg_id, passenger_digest, reason,"
                        + " held_by, held_by_role, held_at, status, reviewed_by, reviewed_by_role,"
                        + " reviewed_at, released_by, released_at, discrepancy_count"
                        + " FROM claim_hold WHERE bag_tag = ? AND status IN (?, ?)",
                CLAIM_MAPPER, bagTag, CLAIM_ACTIVE, CLAIM_REVIEWED);
        return rows.isEmpty() ? null : rows.get(0);
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

    private int countLoaded(String legId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
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

    private List<ClaimEventItem> listClaimEvents(String claimKey) {
        return jdbcTemplate.query(
                "SELECT seq, event_type, leg_id, reason, operator_id, operator_role, event_time"
                        + " FROM claim_event WHERE claim_key = ? ORDER BY seq",
                CLAIM_EVENT_MAPPER, claimKey);
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

    /** 追加不可变冻结链事件，seq 按该 claim 已有链事件数递增，时刻由调用方在同一裁决点固化。 */
    private void insertClaimEvent(String claimKey, String bagTag, String eventType, String legId,
                                  String reason, String operatorId, String operatorRole, Instant at) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM claim_event WHERE claim_key = ?", Integer.class, claimKey);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO claim_event (claim_key, bag_tag, seq, event_type, leg_id, reason,"
                        + " operator_id, operator_role, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                claimKey, bagTag, nextSeq, eventType, legId, reason, operatorId, operatorRole,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toEvents(bag.bagTag()));
    }

    private ClaimHoldResponse toClaimResponse(ClaimRow claim) {
        Instant intervalEnd = claim.releasedAt() != null ? claim.releasedAt() : clock.get();
        long durationSeconds = Duration.between(claim.heldAt(), intervalEnd).getSeconds();
        return new ClaimHoldResponse(claim.claimKey(), claim.bagTag(), claim.status(),
                claim.priorStatus(), CLAIM_RELEASED.equals(claim.status()) ? claim.priorStatus() : null,
                claim.legId(), claim.reason(),
                claim.heldBy(), claim.heldByRole(), claim.heldAt().toString(),
                claim.reviewedBy(), claim.reviewedByRole(),
                claim.reviewedAt() == null ? null : claim.reviewedAt().toString(),
                claim.releasedBy(), claim.releasedAt() == null ? null : claim.releasedAt().toString(),
                durationSeconds, claim.discrepancyCount(), listClaimEvents(claim.claimKey()));
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
                          String status, int version, Integer capacity, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    private record ClaimRow(String claimKey, String bagTag, String priorStatus, String legId,
                            String passengerDigest, String reason,
                            String heldBy, String heldByRole, Instant heldAt,
                            String status, String reviewedBy, String reviewedByRole,
                            Instant reviewedAt, String releasedBy, Instant releasedAt,
                            int discrepancyCount) {
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
