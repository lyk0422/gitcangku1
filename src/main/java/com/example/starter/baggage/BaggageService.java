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
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.CapacityResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.OffloadDetailResponse;
import com.example.starter.baggage.BaggageDtos.OffloadItemResponse;
import com.example.starter.baggage.BaggageDtos.OffloadListResponse;
import com.example.starter.baggage.BaggageDtos.OffloadRequest;
import com.example.starter.baggage.BaggageDtos.OffloadResponse;
import com.example.starter.baggage.BaggageDtos.OffloadedItem;
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
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、精确/差异到达、补到、容量卸载与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载、封舱、卸载并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 航段登记件数/总重上限：装载后任一维度超限则整批 422 且无一件移动。
 * 容量卸载仅允许 OPEN 航段：按 BASIC→STANDARD→PREMIUM、同级重量降序/bagTag 升序选择被卸行李，
 * 被卸行李置 OFFLOADED、记录卸载航段/原待乘索引/UTC 时刻且不推进待乘索引，可经既有装载入口改派。
 * 差异到达仅允许 SEALED 航段提交封舱清单子集；缺失行李转 SHORT_UNLOADED 并冻结待乘索引。
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
    private static final String BAG_OFFLOADED = "OFFLOADED";
    private static final String BAG_RECOVERED = "RECOVERED";
    private static final String BAG_DELIVERED = "DELIVERED";

    private static final String CABIN_PREMIUM = "PREMIUM";
    private static final String CABIN_STANDARD = "STANDARD";
    private static final String CABIN_BASIC = "BASIC";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_CAPACITY_OFFLOADED = "CAPACITY_OFFLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getInt("max_bags"), rs.getInt("max_weight"),
            rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getInt("weight"),
            rs.getString("cabin"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"));

    private static final RowMapper<LoadedBag> LOADED_MAPPER = (rs, rowNum) -> new LoadedBag(
            rs.getString("bag_tag"), rs.getInt("weight"), rs.getString("cabin"),
            rs.getInt("next_leg_index"));

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

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1，件数上限 1~500、总重上限 1~50000 千克。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                request, LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，重量 1~50 千克、舱位 PREMIUM/STANDARD/BASIC。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /** 批量装载：整批原子，装载后件数或总重超限、任一行李不满足则 422 且无一件移动。 */
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
     * 容量卸载决策与执行：仅 OPEN 航段可提交，expectedVersion 做并发版本校验。
     * 按 BASIC→STANDARD→PREMIUM、同级重量降序、重量相同按 bagTag 升序选择被卸行李，
     * 直至保留清单件数与总重都不超过目标上限；目标高于登记上限返回 422，
     * 目标本已满足时返回空卸载清单（成功，版本不变）。同 offloadKey 同参重放、异参 409、失败不占键。
     */
    public OffloadResponse offload(String legId, OffloadRequest request) {
        OffloadPayload payload = new OffloadPayload(legId, request.expectedVersion(),
                request.targetMaxBags(), request.targetMaxWeight());
        return idempotencyService.execute(request.offloadKey(), "OFFLOAD", 200,
                payload, OffloadResponse.class, () -> doOffload(legId, request));
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

    /** 航段载量占用查询：当前清单件数/总重占用与剩余额度。 */
    public CapacityResponse getCapacity(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        Occupancy occupancy = currentOccupancy(legId);
        return new CapacityResponse(leg.legId(), leg.status(), leg.version(),
                leg.maxBags(), leg.maxWeight(), occupancy.bags(), occupancy.weight(),
                leg.maxBags() - occupancy.bags(), leg.maxWeight() - occupancy.weight());
    }

    /** 航段卸载明细查询：按卸载时刻倒序返回每次卸载决策与逐件明细。 */
    public OffloadListResponse listOffloads(String legId) {
        if (findLeg(legId) == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT offload_key FROM offload_record WHERE leg_id = ? ORDER BY offloaded_at DESC, offload_key",
                String.class, legId);
        List<OffloadDetailResponse> details = new ArrayList<>();
        for (String key : keys) {
            details.add(loadOffloadDetail(key));
        }
        return new OffloadListResponse(legId, details);
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
                "INSERT INTO leg (leg_id, origin, destination, status, version, max_bags, max_weight)"
                        + " VALUES (?, ?, ?, ?, 1, ?, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                request.maxBags(), request.maxWeight());
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, request.maxBags(), request.maxWeight());
    }

    private BagResponse doRegisterBag(RegisterBagRequest request) {
        List<String> legIds = request.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw ApiException.unprocessable("行程航段不得重复");
        }
        if (!Set.of(CABIN_PREMIUM, CABIN_STANDARD, CABIN_BASIC).contains(request.cabin())) {
            throw ApiException.unprocessable("舱位等级必须为 PREMIUM、STANDARD 或 BASIC");
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
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, weight, cabin)"
                        + " VALUES (?, ?, 0, ?, ?, ?)",
                request.bagTag(), startStation, BAG_IN_TRANSIT, request.weight(), request.cabin());
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
        // 全部行李校验通过后统一核算载量：装载后件数与总重都不得超限，超限整批拒绝且无一件移动。
        Occupancy current = currentOccupancy(legId);
        int postBags = current.bags() + bags.size();
        int postWeight = current.weight() + bags.stream().mapToInt(BagRow::weight).sum();
        if (postBags > leg.maxBags() || postWeight > leg.maxWeight()) {
            throw ApiException.unprocessable(buildCapacityExceededMessage(
                    leg, current.bags(), current.weight(), bags.size(), postBags, postWeight));
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            // OFFLOADED 行李经改派重新装载时恢复在途；RECOVERED 等其余可装载状态保持原样，不回退状态。
            jdbcTemplate.update(
                    "UPDATE bag SET loaded_leg_id = ?,"
                            + " status = CASE WHEN status = ? THEN ? ELSE status END WHERE bag_tag = ?",
                    legId, BAG_OFFLOADED, BAG_IN_TRANSIT, bag.bagTag());
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

    private OffloadResponse doOffload(String legId, OffloadRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        // 封舱/到达航段清单冻结，卸载返回 409。
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.conflict("航段状态为 " + leg.status() + "，禁止卸载");
        }
        if (request.targetMaxBags() > leg.maxBags() || request.targetMaxWeight() > leg.maxWeight()) {
            throw ApiException.unprocessable(
                    "目标上限高于航段登记上限: 登记件数 " + leg.maxBags() + "/总重 " + leg.maxWeight()
                            + " 千克，目标件数 " + request.targetMaxBags() + "/总重 "
                            + request.targetMaxWeight() + " 千克");
        }
        // 锁定当前清单内全部行李行，与装载/补到并发按行锁与提交顺序裁决。
        List<String> loadedTags = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag", String.class, legId);
        List<BagRow> loadedBags = new ArrayList<>();
        for (String bagTag : loadedTags) {
            loadedBags.add(lockBag(bagTag));
        }
        List<LoadedBag> candidates = loadedBags.stream()
                .map(bag -> new LoadedBag(bag.bagTag(), bag.weight(), bag.cabin(), bag.nextLegIndex()))
                .sorted(BaggageService::offloadOrder)
                .toList();
        int totalBags = candidates.size();
        int totalWeight = candidates.stream().mapToInt(LoadedBag::weight).sum();

        List<LoadedBag> offloaded = new ArrayList<>();
        int retainedBags = totalBags;
        int retainedWeight = totalWeight;
        // 按确定顺序逐件卸出，直到保留清单两项都不超目标上限。
        for (LoadedBag candidate : candidates) {
            if (retainedBags <= request.targetMaxBags() && retainedWeight <= request.targetMaxWeight()) {
                break;
            }
            offloaded.add(candidate);
            retainedBags--;
            retainedWeight -= candidate.weight();
        }

        if (offloaded.isEmpty()) {
            // 目标本已满足：空卸载清单，不算失败，不推进版本。
            List<String> retained = candidates.stream().map(LoadedBag::bagTag).sorted().toList();
            return new OffloadResponse(request.offloadKey(), legId, LEG_OPEN, leg.version(),
                    request.targetMaxBags(), request.targetMaxWeight(),
                    retainedBags, retainedWeight, List.of(), retained);
        }

        Set<String> offloadedTags = new HashSet<>();
        OffsetDateTime offloadedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (LoadedBag bag : offloaded) {
            offloadedTags.add(bag.bagTag());
            // 移出航段清单并置 OFFLOADED：位置不变、待乘索引不推进。
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ? AND leg_id = ?",
                    bag.bagTag(), legId);
            jdbcTemplate.update(
                    "UPDATE bag SET status = ?, loaded_leg_id = NULL WHERE bag_tag = ?",
                    BAG_OFFLOADED, bag.bagTag());
            jdbcTemplate.update(
                    "INSERT INTO offload_item (offload_key, leg_id, bag_tag, weight, cabin,"
                            + " origin_next_leg_index, offloaded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    request.offloadKey(), legId, bag.bagTag(), bag.weight(), bag.cabin(),
                    bag.nextLegIndex(), offloadedAt);
            insertEvent(bag.bagTag(), EVT_CAPACITY_OFFLOADED, legId, leg.origin());
        }
        jdbcTemplate.update(
                "INSERT INTO offload_record (offload_key, leg_id, target_max_bags, target_max_weight,"
                        + " retained_count, retained_weight, offloaded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                request.offloadKey(), legId, request.targetMaxBags(), request.targetMaxWeight(),
                retainedBags, retainedWeight, offloadedAt);

        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);

        List<OffloadedItem> offloadedItems = offloaded.stream()
                .map(bag -> new OffloadedItem(bag.bagTag(), bag.weight(), bag.cabin(), bag.nextLegIndex()))
                .toList();
        List<String> retained = candidates.stream()
                .map(LoadedBag::bagTag)
                .filter(tag -> !offloadedTags.contains(tag))
                .sorted()
                .toList();
        return new OffloadResponse(request.offloadKey(), legId, LEG_OPEN, newVersion,
                request.targetMaxBags(), request.targetMaxWeight(),
                retainedBags, retainedWeight, offloadedItems, retained);
    }

    /** 卸载选择顺序：BASIC 先于 STANDARD 先于 PREMIUM，同级重量降序，重量相同 bagTag 升序。 */
    private static int offloadOrder(LoadedBag a, LoadedBag b) {
        int byCabin = Integer.compare(cabinRank(a.cabin()), cabinRank(b.cabin()));
        if (byCabin != 0) {
            return byCabin;
        }
        int byWeight = Integer.compare(b.weight(), a.weight());
        if (byWeight != 0) {
            return byWeight;
        }
        return a.bagTag().compareTo(b.bagTag());
    }

    private static int cabinRank(String cabin) {
        return switch (cabin) {
            case CABIN_BASIC -> 0;
            case CABIN_STANDARD -> 1;
            case CABIN_PREMIUM -> 2;
            default -> 3;
        };
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

    /** 当前航段清单的件数与总重占用。 */
    private Occupancy currentOccupancy(String legId) {
        List<Occupancy> rows = jdbcTemplate.query(
                "SELECT COUNT(*) AS bags, COALESCE(SUM(b.weight), 0) AS weight"
                        + " FROM load_record lr JOIN bag b ON b.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ?",
                (rs, rowNum) -> new Occupancy(rs.getInt("bags"), rs.getInt("weight")), legId);
        return rows.isEmpty() ? new Occupancy(0, 0) : rows.get(0);
    }

    private String buildCapacityExceededMessage(LegRow leg, int currentBags, int currentWeight,
                                                int addedBags, int postBags, int postWeight) {
        List<String> exceeded = new ArrayList<>();
        if (postBags > leg.maxBags()) {
            exceeded.add("BAGS: 装载后件数 " + currentBags + "+" + addedBags + "=" + postBags
                    + " 超过上限 " + leg.maxBags());
        }
        if (postWeight > leg.maxWeight()) {
            exceeded.add("WEIGHT: 装载后总重 " + currentWeight + "+"
                    + (postWeight - currentWeight) + "=" + postWeight + " 千克超过上限 " + leg.maxWeight());
        }
        return "装载后超出航段 " + leg.legId() + " 容量 [" + String.join("; ", exceeded) + "]，整批拒绝";
    }

    private OffloadDetailResponse loadOffloadDetail(String offloadKey) {
        List<OffloadRecordRow> heads = jdbcTemplate.query(
                "SELECT offload_key, leg_id, target_max_bags, target_max_weight, retained_count,"
                        + " retained_weight, offloaded_at FROM offload_record WHERE offload_key = ?",
                (rs, rowNum) -> new OffloadRecordRow(rs.getString("offload_key"), rs.getString("leg_id"),
                        rs.getInt("target_max_bags"), rs.getInt("target_max_weight"),
                        rs.getInt("retained_count"), rs.getInt("retained_weight"),
                        getInstant(rs, "offloaded_at")),
                offloadKey);
        OffloadRecordRow head = heads.get(0);
        List<OffloadItemResponse> items = jdbcTemplate.query(
                "SELECT bag_tag, weight, cabin, origin_next_leg_index, offloaded_at FROM offload_item"
                        + " WHERE offload_key = ? ORDER BY id",
                (rs, rowNum) -> new OffloadItemResponse(rs.getString("bag_tag"), rs.getInt("weight"),
                        rs.getString("cabin"), rs.getInt("origin_next_leg_index"),
                        getInstant(rs, "offloaded_at").toString()),
                offloadKey);
        int offloadedWeight = items.stream().mapToInt(OffloadItemResponse::weight).sum();
        return new OffloadDetailResponse(head.offloadKey(), head.legId(),
                head.targetMaxBags(), head.targetMaxWeight(),
                items.size(), offloadedWeight, head.retainedCount(), head.retainedWeight(),
                head.offloadedAt().toString(), items);
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
        return "SELECT leg_id, origin, destination, status, version, max_bags, max_weight,"
                + " sealed_manifest, arrival_type, arrival_actual FROM leg WHERE leg_id = ?"
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
        return "SELECT bag_tag, current_location, next_leg_index, status, weight, cabin,"
                + " loaded_leg_id, short_leg_id, short_destination, short_registered_at"
                + " FROM bag WHERE bag_tag = ?" + (forUpdate ? " FOR UPDATE" : "");
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
                bag.status(), bag.weight(), bag.cabin(), bag.loadedLegId(), toItinerary(bag.bagTag()),
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
                          String status, int version, int maxBags, int maxWeight,
                          String sealedManifest, String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, int weight, String cabin, String loadedLegId,
                          String shortLegId, String shortDestination, Instant shortRegisteredAt) {
    }

    /** 航段清单内行李的卸载决策视图。 */
    private record LoadedBag(String bagTag, int weight, String cabin, int nextLegIndex) {
    }

    /** 航段当前载量占用。 */
    private record Occupancy(int bags, int weight) {
    }

    /** 卸载决策主记录行。 */
    private record OffloadRecordRow(String offloadKey, String legId, int targetMaxBags, int targetMaxWeight,
                                    int retainedCount, int retainedWeight, Instant offloadedAt) {
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

    /** 容量卸载幂等摘要参数：以 offloadKey 为去重键。 */
    private record OffloadPayload(String legId, int expectedVersion,
                                  int targetMaxBags, int targetMaxWeight) {
    }
}
