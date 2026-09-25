package com.example.starter.baggage;

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
import com.example.starter.baggage.BaggageDtos.BagBlockingResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.CustomsHoldConfirmRequest;
import com.example.starter.baggage.BaggageDtos.CustomsHoldConfirmResponse;
import com.example.starter.baggage.BaggageDtos.CustomsHoldRequest;
import com.example.starter.baggage.BaggageDtos.CustomsHoldResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.HoldHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.PendingHoldListResponse;
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
    private static final String BAG_CUSTOMS_HOLD = "CUSTOMS_HOLD";

    private static final String HOLD_ACTIVE = "ACTIVE";
    private static final String HOLD_RELEASED = "RELEASED";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";
    private static final String EVT_CUSTOMS_HELD = "CUSTOMS_HELD";
    private static final String EVT_CUSTOMS_HOLD_RELEASED = "CUSTOMS_HOLD_RELEASED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("active_hold_key"),
            rs.getString("loaded_leg_id"),
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

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, rowNum) -> new HoldRow(
            rs.getString("hold_key"), rs.getString("bag_tag"), rs.getString("hold_location"),
            rs.getString("reason"), rs.getString("previous_status"), getInstant(rs, "held_at"));

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, rowNum) ->
            new ConfirmationRow(rs.getString("operator_id"), getInstant(rs, "confirmed_at"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, rowNum) -> new ReleaseRow(
            rs.getString("first_operator_id"), getInstant(rs, "first_confirmed_at"),
            rs.getString("second_operator_id"), getInstant(rs, "second_confirmed_at"),
            getInstant(rs, "released_at"));

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
     * 海关暂扣：行李须未到达最终目的地且不处于 SEALED 航段；
     * 已在 OPEN 航段装载清单中的行李先从清单原子移除（移除失败整次回滚），
     * 随后行李转 CUSTOMS_HOLD 并写入不可变暂扣记录。
     */
    public CustomsHoldResponse customsHold(CustomsHoldRequest request) {
        return idempotencyService.execute(request.requestId(), "CUSTOMS_HOLD", 200,
                request, CustomsHoldResponse.class, () -> doCustomsHold(request));
    }

    /**
     * 解除暂扣确认：同一 holdKey 须两名不同操作人分别确认。
     * 首次确认仅固化操作人；同一操作人重复确认 422；
     * 第二名不同操作人确认时原子把行李转回可交接状态并写入解除记录。
     */
    public CustomsHoldConfirmResponse customsHoldConfirm(CustomsHoldConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "CUSTOMS_HOLD_CONFIRM", 200,
                request, CustomsHoldConfirmResponse.class, () -> doCustomsHoldConfirm(request));
    }

    /** 行李暂扣历史查询：含每条暂扣的确认人与解除记录，按暂扣时刻升序。 */
    public HoldHistoryResponse getHoldHistory(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<HoldRow> holds = jdbcTemplate.query(
                "SELECT hold_key, bag_tag, hold_location, reason, previous_status, held_at"
                        + " FROM customs_hold WHERE bag_tag = ? ORDER BY held_at, hold_key",
                HOLD_MAPPER, bagTag);
        List<BaggageDtos.HoldHistoryItem> items = holds.stream().map(hold -> {
            List<ConfirmationRow> confirmations = listConfirmations(hold.holdKey());
            ReleaseRow release = findRelease(hold.holdKey());
            return new BaggageDtos.HoldHistoryItem(hold.holdKey(), hold.bagTag(),
                    hold.holdLocation(), hold.reason(),
                    release != null ? HOLD_RELEASED : HOLD_ACTIVE, hold.heldAt().toString(),
                    toConfirmationViews(confirmations), toReleaseView(release));
        }).toList();
        return new HoldHistoryResponse(bagTag, items);
    }

    /** 待第二人确认清单：当前仅有一名操作人确认且尚无解除记录，按暂扣时刻升序。 */
    public PendingHoldListResponse listPendingSecondConfirmation() {
        List<HoldRow> holds = jdbcTemplate.query(
                "SELECT h.hold_key, h.bag_tag, h.hold_location, h.reason, h.previous_status,"
                        + " h.held_at FROM customs_hold h"
                        + " WHERE (SELECT COUNT(*) FROM customs_hold_confirmation c"
                        + " WHERE c.hold_key = h.hold_key) = 1"
                        + " AND NOT EXISTS (SELECT 1 FROM customs_hold_release r"
                        + " WHERE r.hold_key = h.hold_key)"
                        + " ORDER BY h.held_at, h.hold_key",
                HOLD_MAPPER);
        List<BaggageDtos.PendingHoldItem> items = holds.stream().map(hold -> {
            ConfirmationRow first = listConfirmations(hold.holdKey()).get(0);
            return new BaggageDtos.PendingHoldItem(hold.holdKey(), hold.bagTag(),
                    hold.holdLocation(), hold.reason(), hold.heldAt().toString(),
                    first.operatorId(), first.confirmedAt().toString());
        }).toList();
        return new PendingHoldListResponse(items);
    }

    /** 行李当前交接阻断原因：海关暂扣返回暂扣地点，另列出短卸/在舱/已交付等阻断状态。 */
    public BagBlockingResponse getBlockingReasons(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<BaggageDtos.BlockingReason> reasons = new ArrayList<>();
        if (BAG_CUSTOMS_HOLD.equals(bag.status())) {
            HoldRow hold = bag.activeHoldKey() == null ? null : findHold(bag.activeHoldKey());
            String location = hold == null ? "未知" : hold.holdLocation();
            reasons.add(new BaggageDtos.BlockingReason("CUSTOMS_HOLD",
                    "行李 " + bagTag + " 处于海关暂扣状态，暂扣地点: " + location,
                    bag.activeHoldKey(), location));
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            reasons.add(new BaggageDtos.BlockingReason("SHORT_UNLOADED",
                    "行李 " + bagTag + " 短卸于航段 " + bag.shortLegId()
                            + "，须先补到才能交接", null, null));
        }
        if (bag.loadedLegId() != null && !BAG_CUSTOMS_HOLD.equals(bag.status())) {
            reasons.add(new BaggageDtos.BlockingReason("LOADED",
                    "行李 " + bagTag + " 已在航段 " + bag.loadedLegId() + " 舱单中，不能再装入其他航段",
                    null, null));
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            reasons.add(new BaggageDtos.BlockingReason("DELIVERED",
                    "行李 " + bagTag + " 已到达最终目的地，无后续交接", null, null));
        }
        return new BagBlockingResponse(bagTag, bag.status(), !reasons.isEmpty(), reasons);
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
            if (BAG_CUSTOMS_HOLD.equals(bag.status())) {
                throw holdConflict(bag);
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

    private CustomsHoldResponse doCustomsHold(CustomsHoldRequest request) {
        // 先无锁读出行李引用的已装载航段，再按“航段锁 -> 行李锁”统一顺序加锁，
        // 与装载/封舱/到达保持一致的加锁顺序，避免跨操作锁倒置死锁。
        BagRow preview = findBag(request.bagTag());
        if (preview == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        LegRow loadedLeg = null;
        if (preview.loadedLegId() != null) {
            loadedLeg = lockLeg(preview.loadedLegId());
        }
        BagRow bag = lockBag(request.bagTag());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        if (findHold(request.holdKey()) != null) {
            throw ApiException.conflict("holdKey 已存在: " + request.holdKey());
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已到达最终目的地，不得暂扣");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.conflict("行李 " + bag.bagTag() + " 已短卸丢失，不得暂扣");
        }
        if (BAG_CUSTOMS_HOLD.equals(bag.status())) {
            throw holdConflict(bag);
        }
        String previousStatus = bag.status();
        if (bag.loadedLegId() != null) {
            // 加锁后行李装载航段必须仍与无锁预览一致：否则说明预览后发生过到达/改乘，
            // 为避免“持行李锁再锁航段”与装载/封舱（航段->行李）形成锁倒置，按并发修改 409 拒绝。
            String previewLegId = preview.loadedLegId();
            if (previewLegId == null || !previewLegId.equals(bag.loadedLegId())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 的装载航段在处理期间发生变化，请重试");
            }
            LegRow currentLeg = loadedLeg;
            if (LEG_SEALED.equals(currentLeg.status())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 处于已封舱航段 " + currentLeg.legId() + "，不得暂扣");
            }
            if (!LEG_OPEN.equals(currentLeg.status())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 所在航段 " + currentLeg.legId()
                                + " 状态为 " + currentLeg.status() + "，不得暂扣");
            }
            // OPEN 清单：先原子移除，移除影响行数非 1 则抛错整次回滚
            int removed = jdbcTemplate.update(
                    "DELETE FROM load_record WHERE bag_tag = ? AND leg_id = ?",
                    bag.bagTag(), currentLeg.legId());
            if (removed != 1) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 不在航段 " + currentLeg.legId() + " 的 OPEN 清单中，移除失败，暂扣回滚");
            }
            int newVersion = currentLeg.version() + 1;
            jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?",
                    newVersion, currentLeg.legId());
        }
        OffsetDateTime heldAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        try {
            jdbcTemplate.update(
                    "INSERT INTO customs_hold (hold_key, bag_tag, hold_location, reason,"
                            + " previous_status, held_at) VALUES (?, ?, ?, ?, ?, ?)",
                    request.holdKey(), bag.bagTag(), request.holdLocation(),
                    request.reason(), previousStatus, heldAt);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 不同 requestId 并发使用同一 holdKey：唯一约束兜底，转 409 且不占 requestId 键
            throw ApiException.conflict("holdKey 已存在: " + request.holdKey());
        }
        jdbcTemplate.update(
                "UPDATE bag SET status = ?, active_hold_key = ?, loaded_leg_id = NULL,"
                        + " current_location = ? WHERE bag_tag = ?",
                BAG_CUSTOMS_HOLD, request.holdKey(), request.holdLocation(), bag.bagTag());
        insertEvent(bag.bagTag(), EVT_CUSTOMS_HELD, bag.loadedLegId(), request.holdLocation());
        return new CustomsHoldResponse(request.holdKey(), bag.bagTag(), request.holdLocation(),
                request.reason(), HOLD_ACTIVE, 0, previousStatus, heldAt.toInstant().toString());
    }

    private CustomsHoldConfirmResponse doCustomsHoldConfirm(CustomsHoldConfirmRequest request) {
        HoldRow hold = lockHold(request.holdKey());
        if (hold == null) {
            throw ApiException.notFound("暂扣记录不存在: " + request.holdKey());
        }
        // 暂扣记录本身不可变，是否已解除以解除记录是否存在为准
        if (findRelease(hold.holdKey()) != null) {
            throw ApiException.conflict("暂扣 " + hold.holdKey() + " 已解除，不能再确认");
        }
        List<ConfirmationRow> confirmations = listConfirmations(hold.holdKey());
        boolean sameOperator = confirmations.stream()
                .anyMatch(row -> row.operatorId().equals(request.operatorId()));
        if (sameOperator) {
            throw ApiException.unprocessable(
                    "操作人 " + request.operatorId() + " 已确认过暂扣 " + hold.holdKey()
                            + "，同一操作人不得重复确认");
        }
        OffsetDateTime confirmedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        try {
            jdbcTemplate.update(
                    "INSERT INTO customs_hold_confirmation (hold_key, operator_id, confirmed_at)"
                            + " VALUES (?, ?, ?)",
                    hold.holdKey(), request.operatorId(), confirmedAt);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 持锁后仍由唯一约束兜底并发：同操作人重复确认固定为 422
            throw ApiException.unprocessable(
                    "操作人 " + request.operatorId() + " 已确认过暂扣 " + hold.holdKey()
                            + "，同一操作人不得重复确认");
        }
        confirmations = listConfirmations(hold.holdKey());
        if (confirmations.size() == 1) {
            return buildConfirmResponse(hold, confirmations, null);
        }
        // 第二名不同操作人确认：原子解除并固化两名操作人及时刻
        ConfirmationRow first = confirmations.get(0);
        ConfirmationRow second = confirmations.get(1);
        BagRow bag = lockBag(hold.bagTag());
        if (bag == null || !BAG_CUSTOMS_HOLD.equals(bag.status())
                || !hold.holdKey().equals(bag.activeHoldKey())) {
            throw ApiException.conflict(
                    "行李 " + hold.bagTag() + " 当前不处于暂扣 " + hold.holdKey() + "，解除失败");
        }
        OffsetDateTime secondAt = OffsetDateTime.ofInstant(second.confirmedAt(), ZoneOffset.UTC);
        ReleaseRow release = new ReleaseRow(first.operatorId(), first.confirmedAt(),
                second.operatorId(), second.confirmedAt(), second.confirmedAt());
        try {
            jdbcTemplate.update(
                    "INSERT INTO customs_hold_release (hold_key, bag_tag, first_operator_id,"
                            + " first_confirmed_at, second_operator_id, second_confirmed_at, released_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    hold.holdKey(), hold.bagTag(), first.operatorId(),
                    OffsetDateTime.ofInstant(first.confirmedAt(), ZoneOffset.UTC),
                    second.operatorId(), secondAt, secondAt);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw ApiException.conflict("暂扣 " + hold.holdKey() + " 已解除，不能重复解除");
        }
        jdbcTemplate.update(
                "UPDATE bag SET status = ?, active_hold_key = NULL WHERE bag_tag = ?",
                hold.previousStatus(), hold.bagTag());
        insertEvent(hold.bagTag(), EVT_CUSTOMS_HOLD_RELEASED, null, bag.currentLocation());
        return buildConfirmResponse(hold, confirmations, release);
    }

    private List<ConfirmationRow> listConfirmations(String holdKey) {
        return jdbcTemplate.query(
                "SELECT operator_id, confirmed_at FROM customs_hold_confirmation"
                        + " WHERE hold_key = ? ORDER BY id",
                CONFIRMATION_MAPPER, holdKey);
    }

    private ReleaseRow findRelease(String holdKey) {
        List<ReleaseRow> rows = jdbcTemplate.query(
                "SELECT first_operator_id, first_confirmed_at, second_operator_id,"
                        + " second_confirmed_at, released_at FROM customs_hold_release WHERE hold_key = ?",
                RELEASE_MAPPER, holdKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BaggageDtos.HoldReleaseView toReleaseView(ReleaseRow release) {
        if (release == null) {
            return null;
        }
        return new BaggageDtos.HoldReleaseView(
                release.firstOperatorId(), release.firstConfirmedAt().toString(),
                release.secondOperatorId(), release.secondConfirmedAt().toString(),
                release.releasedAt().toString());
    }

    private static List<BaggageDtos.HoldOperatorConfirmation> toConfirmationViews(
            List<ConfirmationRow> rows) {
        return rows.stream()
                .map(row -> new BaggageDtos.HoldOperatorConfirmation(
                        row.operatorId(), row.confirmedAt().toString()))
                .toList();
    }

    private CustomsHoldConfirmResponse buildConfirmResponse(HoldRow hold,
                                                            List<ConfirmationRow> confirmations,
                                                            ReleaseRow release) {
        ReleaseRow effectiveRelease = release != null ? release : findRelease(hold.holdKey());
        String status = effectiveRelease != null ? HOLD_RELEASED : HOLD_ACTIVE;
        return new CustomsHoldConfirmResponse(hold.holdKey(), hold.bagTag(), status,
                confirmations.size(), toConfirmationViews(confirmations), toReleaseView(effectiveRelease));
    }

    /** 实际到达行李的统一推进：移动到到达站、推进待乘索引，完成行程者交付。 */
    private void advanceArrivedBag(BagRow bag, String destination, String legId) {        int nextIndex = bag.nextLegIndex() + 1;
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
        if (BAG_CUSTOMS_HOLD.equals(bag.status())) {
            throw holdConflict(bag);
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

    /** 无锁读取暂扣记录（不存在返回 null），调用方须已自行加锁保证一致性。 */
    private HoldRow findHold(String holdKey) {
        List<HoldRow> rows = jdbcTemplate.query(
                "SELECT hold_key, bag_tag, hold_location, reason, previous_status, held_at"
                        + " FROM customs_hold WHERE hold_key = ?",
                HOLD_MAPPER, holdKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 持暂扣记录锁读取，用于确认解除流程串行化。 */
    private HoldRow lockHold(String holdKey) {
        List<HoldRow> rows = jdbcTemplate.query(
                "SELECT hold_key, bag_tag, hold_location, reason, previous_status, held_at"
                        + " FROM customs_hold WHERE hold_key = ? FOR UPDATE",
                HOLD_MAPPER, holdKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 暂扣阻断类错误统一为 409，并在消息中带暂扣地点。 */
    private ApiException holdConflict(BagRow bag) {
        HoldRow hold = bag.activeHoldKey() == null ? null : findHold(bag.activeHoldKey());
        String location = hold == null ? "未知" : hold.holdLocation();
        return ApiException.conflict(
                "行李 " + bag.bagTag() + " 处于海关暂扣状态，暂扣地点: " + location);
    }

    private static String bagSelect(boolean forUpdate) {
        return "SELECT bag_tag, current_location, next_leg_index, status, active_hold_key,"
                + " loaded_leg_id, short_leg_id, short_destination, short_registered_at"
                + " FROM bag WHERE bag_tag = ?"
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
                bag.status(), bag.activeHoldKey(), bag.loadedLegId(), toItinerary(bag.bagTag()),
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
                          String status, String activeHoldKey, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    private record HoldRow(String holdKey, String bagTag, String holdLocation,
                           String reason, String previousStatus, Instant heldAt) {
    }

    private record ConfirmationRow(String operatorId, Instant confirmedAt) {
    }

    private record ReleaseRow(String firstOperatorId, Instant firstConfirmedAt,
                              String secondOperatorId, Instant secondConfirmedAt, Instant releasedAt) {
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
