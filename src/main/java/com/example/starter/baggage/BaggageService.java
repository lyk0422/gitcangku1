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
import com.example.starter.baggage.BaggageDtos.ClaimHoldFreezeRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldListResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldReviewRequest;
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
 * 认领冻结：未到达最终目的地的行李可登记冻结转 CLAIM_HOLD，禁止装载、补到与交付；
 * 位于 OPEN 清单的行李在同事务先移出清单并写不可变链记录；解除须不同客服复核核验摘要，
 * 第二次确认原子恢复冻结前状态，冻结/复核/解除/清单移出均写入 claim_hold_event 不可变链。
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

    private static final String HOLD_ACTIVE = "ACTIVE";
    private static final String HOLD_REVIEWED = "RELEASE_REVIEWED";
    private static final String HOLD_RELEASED = "RELEASED";

    private static final String HOLD_EVT_FROZEN = "FROZEN";
    private static final String HOLD_EVT_MANIFEST_REMOVED = "MANIFEST_REMOVED";
    private static final String HOLD_EVT_REVIEWED = "REVIEWED";
    private static final String HOLD_EVT_RELEASED = "RELEASED";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

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
            rs.getString("claim_key"), rs.getString("bag_tag"), rs.getString("status"),
            rs.getString("reason"), rs.getString("passenger_digest"),
            rs.getString("freeze_agent"), rs.getString("review_agent"),
            rs.getString("prev_bag_status"), rs.getString("removed_leg_id"),
            getInstant(rs, "frozen_at"), getInstant(rs, "reviewed_at"), getInstant(rs, "released_at"));

    private static final RowMapper<ClaimHoldEventItem> HOLD_EVENT_MAPPER = (rs, rowNum) -> new ClaimHoldEventItem(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("claim_key"),
            rs.getString("bag_tag"), rs.getString("leg_id"), rs.getString("reason"),
            rs.getString("operator_id"), rs.getString("counterpart_id"),
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

    /**
     * 登记认领冻结：仅未到达最终目的地的行李可冻结；已 SEALED 航段清单内、已交付行李返回 409。
     * 同一行李同时只允许一个生效冻结；若行李位于 OPEN 航段清单，同事务先移出清单
     * （航段版本递增）再写入冻结与不可变链记录，行李转 CLAIM_HOLD。
     */
    public ClaimHoldResponse freezeClaimHold(String bagTag, ClaimHoldFreezeRequest request) {
        ClaimHoldFreezePayload payload = new ClaimHoldFreezePayload(
                bagTag, request.claimKey(), request.agentId(), request.passengerDigest(), request.reason());
        return idempotencyService.execute(request.requestId(), "CLAIM_HOLD_FREEZE", 201,
                payload, ClaimHoldResponse.class, () -> doFreezeClaimHold(bagTag, request));
    }

    /**
     * 复核乘客核验摘要：须由不同于冻结人的客服提交；摘要不匹配返回 422 且不改变状态；
     * 成功后冻结转 RELEASE_REVIEWED，等待第二次确认。
     */
    public ClaimHoldResponse reviewClaimHold(String bagTag, ClaimHoldReviewRequest request) {
        ClaimHoldReviewPayload payload = new ClaimHoldReviewPayload(
                bagTag, request.agentId(), request.passengerDigest());
        return idempotencyService.execute(request.requestId(), "CLAIM_HOLD_REVIEW", 200,
                payload, ClaimHoldResponse.class, () -> doReviewClaimHold(bagTag, request));
    }

    /**
     * 解除确认：仅已复核冻结可由复核人本人第二次确认，原子恢复冻结前可交接状态；
     * 冻结期间的旧装载请求不自动恢复，须重新提交装载。
     */
    public ClaimHoldResponse releaseClaimHold(String bagTag, ClaimHoldReleaseRequest request) {
        ClaimHoldReleasePayload payload = new ClaimHoldReleasePayload(bagTag, request.agentId());
        return idempotencyService.execute(request.requestId(), "CLAIM_HOLD_RELEASE", 200,
                payload, ClaimHoldResponse.class, () -> doReleaseClaimHold(bagTag, request));
    }

    /** 认领冻结明细查询：优先返回生效中冻结，否则返回最近一次冻结；只读不改状态。 */
    public ClaimHoldResponse getClaimHold(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ClaimHoldRow hold = findLatestHold(bagTag);
        if (hold == null) {
            throw ApiException.notFound("行李 " + bagTag + " 无认领冻结记录");
        }
        return toHoldResponse(hold);
    }

    /** 认领冻结历史链查询：返回该行李全部冻结的不可变链记录，按写入顺序稳定升序。 */
    public ClaimHoldHistoryResponse getClaimHoldHistory(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ClaimHoldEventItem> events = jdbcTemplate.query(
                "SELECT seq, event_type, claim_key, bag_tag, leg_id, reason, operator_id,"
                        + " counterpart_id, event_time FROM claim_hold_event"
                        + " WHERE bag_tag = ? ORDER BY id",
                HOLD_EVENT_MAPPER, bagTag);
        return new ClaimHoldHistoryResponse(bagTag, events);
    }

    /** 认领冻结诊断清单查询：可按状态过滤（缺省返回全部），按冻结时刻与认领键稳定排序。 */
    public ClaimHoldListResponse listClaimHolds(String status) {
        List<ClaimHoldRow> holds;
        if (status == null || status.isBlank()) {
            holds = jdbcTemplate.query(holdSelect()
                    + " ORDER BY frozen_at, claim_key", HOLD_MAPPER);
        } else {
            holds = jdbcTemplate.query(holdSelect()
                    + " WHERE status = ? ORDER BY frozen_at, claim_key", HOLD_MAPPER, status);
        }
        return new ClaimHoldListResponse(holds.stream().map(this::toHoldResponse).toList());
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
        if (!BAG_SHORT_UNLOADED.equals(bag.status())) {
            if (BAG_CLAIM_HOLD.equals(bag.status())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 处于认领冻结状态，禁止补到确认");
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

    private ClaimHoldResponse doFreezeClaimHold(String bagTag, ClaimHoldFreezeRequest request) {
        BagRow bag = lockBagWithLoadedLeg(bagTag);
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已最终交付，不可认领冻结");
        }
        if (BAG_CLAIM_HOLD.equals(bag.status()) || findActiveHold(bagTag, false) != null) {
            throw ApiException.conflict("行李 " + bagTag + " 已存在生效的认领冻结");
        }
        if (findHoldByKey(request.claimKey()) != null) {
            throw ApiException.conflict("认领冻结键已存在: " + request.claimKey());
        }
        String removedLegId = null;
        if (bag.loadedLegId() != null) {
            LegRow loadedLeg = lockLeg(bag.loadedLegId());
            if (LEG_SEALED.equals(loadedLeg.status())) {
                throw ApiException.conflict(
                        "行李 " + bagTag + " 所在航段 " + loadedLeg.legId() + " 已 SEALED，不可认领冻结");
            }
            if (LEG_OPEN.equals(loadedLeg.status())) {
                // 同一事务内先移出 OPEN 清单：删除装载明细、清单版本递增，再写冻结
                jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bagTag);
                jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?",
                        loadedLeg.version() + 1, loadedLeg.legId());
                removedLegId = loadedLeg.legId();
            }
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO claim_hold (claim_key, bag_tag, status, reason, passenger_digest,"
                        + " freeze_agent, prev_bag_status, removed_leg_id, frozen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                request.claimKey(), bagTag, HOLD_ACTIVE, request.reason(), request.passengerDigest(),
                request.agentId(), bag.status(), removedLegId, now);
        jdbcTemplate.update(
                "UPDATE bag SET status = ?, loaded_leg_id = NULL WHERE bag_tag = ?",
                BAG_CLAIM_HOLD, bagTag);
        insertHoldEvent(request.claimKey(), bagTag, HOLD_EVT_FROZEN, removedLegId,
                request.reason(), request.agentId(), null);
        if (removedLegId != null) {
            insertHoldEvent(request.claimKey(), bagTag, HOLD_EVT_MANIFEST_REMOVED, removedLegId,
                    null, request.agentId(), null);
        }
        return toHoldResponse(findHoldByKey(request.claimKey()));
    }

    private ClaimHoldResponse doReviewClaimHold(String bagTag, ClaimHoldReviewRequest request) {
        lockBagForClaim(bagTag);
        ClaimHoldRow hold = requireActiveHold(bagTag);
        if (!hold.passengerDigest().equals(request.passengerDigest())) {
            throw ApiException.unprocessable("乘客核验摘要不匹配，复核拒绝");
        }
        if (hold.freezeAgent().equals(request.agentId())) {
            throw ApiException.conflict("复核人须与冻结人不同: " + request.agentId());
        }
        if (!HOLD_ACTIVE.equals(hold.status())) {
            throw ApiException.conflict(
                    "认领冻结 " + hold.claimKey() + " 状态为 " + hold.status() + "，不可重复复核");
        }
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, review_agent = ?, reviewed_at = ? WHERE claim_key = ?",
                HOLD_REVIEWED, request.agentId(),
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC), hold.claimKey());
        insertHoldEvent(hold.claimKey(), bagTag, HOLD_EVT_REVIEWED, null,
                null, request.agentId(), hold.freezeAgent());
        return toHoldResponse(findHoldByKey(hold.claimKey()));
    }

    private ClaimHoldResponse doReleaseClaimHold(String bagTag, ClaimHoldReleaseRequest request) {
        BagRow bag = lockBagForClaim(bagTag);
        ClaimHoldRow hold = requireActiveHold(bagTag);
        if (!HOLD_REVIEWED.equals(hold.status())) {
            throw ApiException.conflict(
                    "认领冻结 " + hold.claimKey() + " 状态为 " + hold.status() + "，须先复核再解除");
        }
        if (!hold.reviewAgent().equals(request.agentId())) {
            throw ApiException.conflict(
                    "解除确认须由复核人 " + hold.reviewAgent() + " 提交，实际为 " + request.agentId());
        }
        jdbcTemplate.update(
                "UPDATE claim_hold SET status = ?, released_at = ? WHERE claim_key = ?",
                HOLD_RELEASED, OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC), hold.claimKey());
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?",
                hold.prevBagStatus(), bagTag);
        insertHoldEvent(hold.claimKey(), bagTag, HOLD_EVT_RELEASED, null,
                null, request.agentId(), hold.freezeAgent());
        return toHoldResponse(findHoldByKey(hold.claimKey()));
    }

    /**
     * 按「先航段后行李」的固定顺序加锁读取行李，避免与装载（先锁航段再锁行李）死锁。
     * 行李行锁持有后 loaded_leg_id 不会再被他人修改；若加锁间隙状态变化则回滚，
     * 以 409 提示调用方按当前状态重提（失败不占幂等键）。
     */
    private BagRow lockBagWithLoadedLeg(String bagTag) {
        BagRow snapshot = findBag(bagTag);
        if (snapshot == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (snapshot.loadedLegId() != null) {
            lockLeg(snapshot.loadedLegId());
        }
        BagRow locked = lockBag(bagTag);
        if (!Objects.equals(locked.loadedLegId(), snapshot.loadedLegId())) {
            throw ApiException.conflict(
                    "行李 " + bagTag + " 装载状态已变化，请按当前状态重提认领冻结");
        }
        return locked;
    }

    /** 锁定行李行用于认领冻结复核/解除，行李不存在返回 404。 */
    private BagRow lockBagForClaim(String bagTag) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        return bag;
    }

    /** 当前生效（ACTIVE/RELEASE_REVIEWED）的冻结，须在持有行李行锁后调用。 */
    private ClaimHoldRow requireActiveHold(String bagTag) {
        ClaimHoldRow hold = findActiveHold(bagTag, true);
        if (hold == null) {
            throw ApiException.conflict("行李 " + bagTag + " 当前无生效的认领冻结");
        }
        return hold;
    }

    private ClaimHoldRow findActiveHold(String bagTag, boolean forUpdate) {
        List<ClaimHoldRow> rows = jdbcTemplate.query(
                holdSelect() + " WHERE bag_tag = ? AND status IN (?, ?)" + (forUpdate ? " FOR UPDATE" : ""),
                HOLD_MAPPER, bagTag, HOLD_ACTIVE, HOLD_REVIEWED);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimHoldRow findHoldByKey(String claimKey) {
        List<ClaimHoldRow> rows = jdbcTemplate.query(
                holdSelect() + " WHERE claim_key = ?", HOLD_MAPPER, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近一次冻结明细：生效中优先，其次按冻结时刻与认领键倒序。 */
    private ClaimHoldRow findLatestHold(String bagTag) {
        List<ClaimHoldRow> rows = jdbcTemplate.query(
                holdSelect() + " WHERE bag_tag = ?"
                        + " ORDER BY CASE WHEN status = '" + HOLD_RELEASED + "' THEN 1 ELSE 0 END,"
                        + " frozen_at DESC, claim_key DESC LIMIT 1",
                HOLD_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String holdSelect() {
        return "SELECT claim_key, bag_tag, status, reason, passenger_digest, freeze_agent,"
                + " review_agent, prev_bag_status, removed_leg_id, frozen_at, reviewed_at, released_at"
                + " FROM claim_hold";
    }

    /** 追加认领冻结不可变链记录，seq 按该冻结已有链记录数递增；调用方须持有行李行锁。 */
    private void insertHoldEvent(String claimKey, String bagTag, String eventType, String legId,
                                 String reason, String operatorId, String counterpartId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM claim_hold_event WHERE claim_key = ?", Integer.class, claimKey);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO claim_hold_event (claim_key, seq, event_type, bag_tag, leg_id, reason,"
                        + " operator_id, counterpart_id, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                claimKey, nextSeq, eventType, bagTag, legId, reason, operatorId, counterpartId,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private ClaimHoldResponse toHoldResponse(ClaimHoldRow hold) {
        return new ClaimHoldResponse(hold.claimKey(), hold.bagTag(), hold.status(), hold.reason(),
                hold.freezeAgent(), hold.reviewAgent(), hold.prevBagStatus(), hold.removedLegId(),
                hold.frozenAt().toString(),
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

    private record ClaimHoldRow(String claimKey, String bagTag, String status, String reason,
                                String passengerDigest, String freezeAgent, String reviewAgent,
                                String prevBagStatus, String removedLegId, Instant frozenAt,
                                Instant reviewedAt, Instant releasedAt) {
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

    /** 认领冻结登记幂等摘要参数。 */
    private record ClaimHoldFreezePayload(String bagTag, String claimKey, String agentId,
                                          String passengerDigest, String reason) {
    }

    /** 认领冻结复核幂等摘要参数。 */
    private record ClaimHoldReviewPayload(String bagTag, String agentId, String passengerDigest) {
    }

    /** 认领冻结解除确认幂等摘要参数。 */
    private record ClaimHoldReleasePayload(String bagTag, String agentId) {
    }
}
