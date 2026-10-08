package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.PlacementRequest;
import com.example.starter.baggage.BaggageDtos.PlacementView;
import com.example.starter.baggage.BaggageDtos.RearrangeRequest;
import com.example.starter.baggage.BaggageDtos.RearrangeResponse;
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
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、整批重排、封舱、精确/差异到达、补到与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载、重排与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 启用舱位布局的航段：装载必须提交左下角坐标与 0/90 度旋转，落位矩形不得越界或内部重叠；
 * 未封舱可整批重排 1~10 件已装行李，先移除旧位置再统一检查；封舱冻结完整落位身份。
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

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"),
            (Integer) rs.getObject("floor_length_mm"), (Integer) rs.getObject("floor_width_mm"),
            rs.getString("sealed_layout"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"),
            (Integer) rs.getObject("length_mm"), (Integer) rs.getObject("width_mm"));

    private static final RowMapper<LayoutRow> LAYOUT_MAPPER = (rs, rowNum) -> new LayoutRow(
            rs.getString("bag_tag"),
            (Integer) rs.getObject("x_mm"), (Integer) rs.getObject("y_mm"),
            (Integer) rs.getObject("rotation"),
            (Integer) rs.getObject("length_mm"), (Integer) rs.getObject("width_mm"));

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

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动；布局航段必须同时提交合法落位。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.operator(), request.expectedVersion(),
                sortedCopy(request.bagTags()), sortedPlacements(request.placements()));
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /**
     * 整批重排：仅未封舱且启用布局的航段，对 1~10 件已装行李统一下发新位置。
     * 先从最终集合移除这些旧位置，再统一检查新位置与未移动行李；
     * 任一违规 422，所有位置与航段版本保持不变。
     */
    public RearrangeResponse rearrange(String legId, RearrangeRequest request) {
        RearrangePayload payload = new RearrangePayload(legId, request.operator(),
                request.expectedVersion(), sortedPlacements(request.placements()));
        return idempotencyService.execute(request.requestId(), "REARRANGE", 200,
                payload, RearrangeResponse.class, () -> doRearrange(legId, request));
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

    /** 封舱清单查询：未封舱时返回空清单；placements 为封舱冻结布局。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest,
                readLayout(leg.sealedLayout()));
    }

    /** 航段差异快照查询：返回只读封舱清单、冻结布局、到达类型及差异到达实际集合。 */
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
                manifest, leg.arrivalType(), actual, readLayout(leg.sealedLayout()));
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
        validateDimensionPair(request.floorLengthMm(), request.floorWidthMm(), "舱底长宽");
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version,"
                        + " floor_length_mm, floor_width_mm) VALUES (?, ?, ?, ?, 1, ?, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                request.floorLengthMm(), request.floorWidthMm());
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, request.floorLengthMm(), request.floorWidthMm());
    }

    private BagResponse doRegisterBag(RegisterBagRequest request) {
        List<String> legIds = request.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw ApiException.unprocessable("行程航段不得重复");
        }
        if (findBag(request.bagTag()) != null) {
            throw ApiException.conflict("行李已存在: " + request.bagTag());
        }
        validateDimensionPair(request.lengthMm(), request.widthMm(), "行李长宽");
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
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, length_mm, width_mm)"
                        + " VALUES (?, ?, 0, ?, ?, ?)",
                request.bagTag(), startStation, BAG_IN_TRANSIT, request.lengthMm(), request.widthMm());
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
        boolean hasFloor = leg.floorLengthMm() != null;
        Map<String, PlacementRequest> placementByTag = checkLoadPlacements(request, hasFloor, bagTags);
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateLoadable(bag, leg);
            if (hasFloor && (bag.lengthMm() == null || bag.widthMm() == null)) {
                throw ApiException.unprocessable("行李 " + bagTag + " 未登记尺寸，无法装入布局航段");
            }
            bags.add(bag);
        }
        List<PlacementView> applied = new ArrayList<>();
        if (hasFloor) {
            // 布局航段：新落位与舱内已装行李统一做边界与重叠检查，全部合法才落库
            List<PlacementCell> finalCells = new ArrayList<>(currentCells(legId, Set.of()));
            for (BagRow bag : bags) {
                finalCells.add(toCell(bag.bagTag(), bag.lengthMm(), bag.widthMm(),
                        placementByTag.get(bag.bagTag()), leg));
            }
            checkNoOverlap(finalCells);
            for (BagRow bag : bags) {
                PlacementRequest p = placementByTag.get(bag.bagTag());
                jdbcTemplate.update(
                        "INSERT INTO load_record (bag_tag, leg_id, x_mm, y_mm, rotation)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        bag.bagTag(), legId, p.xMm(), p.yMm(), p.rotation());
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
                insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
                applied.add(new PlacementView(bag.bagTag(), bag.lengthMm(), bag.widthMm(),
                        p.rotation(), p.xMm(), p.yMm()));
            }
        } else {
            for (BagRow bag : bags) {
                jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)",
                        bag.bagTag(), legId);
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
                insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
            }
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted, List.copyOf(applied));
    }

    private SealResponse doSeal(String legId, SealRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止封舱");
        }
        List<String> manifest = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag", String.class, legId);
        // 布局航段封舱时冻结完整落位身份（袋号、尺寸、旋转、坐标），后续到达/短卸/补到不改写
        List<PlacementView> frozenLayout = List.of();
        String sealedLayout = null;
        if (leg.floorLengthMm() != null) {
            frozenLayout = layoutRows(legId).stream()
                    .map(row -> new PlacementView(row.bagTag(), row.lengthMm(), row.widthMm(),
                            row.rotation(), row.xMm(), row.yMm()))
                    .sorted(Comparator.comparing(PlacementView::bagTag))
                    .toList();
            sealedLayout = writeJson(frozenLayout);
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, sealed_manifest = ?, sealed_layout = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(manifest), sealedLayout, legId);
        return new SealResponse(legId, LEG_SEALED, newVersion, manifest, frozenLayout);
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
                + " arrival_type, arrival_actual, floor_length_mm, floor_width_mm, sealed_layout"
                + " FROM leg WHERE leg_id = ?"
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
                + " short_leg_id, short_destination, short_registered_at, length_mm, width_mm"
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
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toEvents(bag.bagTag()), bag.lengthMm(), bag.widthMm());
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    /** 落位列表按袋号排序（null 保持 null），用于幂等摘要规范化。 */
    private static List<PlacementRequest> sortedPlacements(List<PlacementRequest> placements) {
        if (placements == null) {
            return null;
        }
        return placements.stream().sorted(Comparator.comparing(PlacementRequest::bagTag)).toList();
    }

    /** 尺寸成对校验：同时缺省表示未配置；同时提供时必须为正整数。 */
    private static void validateDimensionPair(Integer lengthMm, Integer widthMm, String label) {
        if (lengthMm == null && widthMm == null) {
            return;
        }
        if (lengthMm == null || widthMm == null) {
            throw ApiException.unprocessable(label + "必须同时提供");
        }
        if (lengthMm <= 0 || widthMm <= 0) {
            throw ApiException.unprocessable(label + "必须为正整数");
        }
    }

    /** 装载落位结构校验：布局航段必须逐件提供位置，旧航段不得提交位置。 */
    private static Map<String, PlacementRequest> checkLoadPlacements(LoadRequest request,
                                                                     boolean hasFloor,
                                                                     List<String> bagTags) {
        List<PlacementRequest> placements = request.placements();
        if (!hasFloor) {
            if (placements != null && !placements.isEmpty()) {
                throw ApiException.unprocessable("航段未配置舱位布局，不得提交位置");
            }
            return Map.of();
        }
        if (placements == null || placements.isEmpty()) {
            throw ApiException.unprocessable("航段已配置舱位布局，装载必须提供位置");
        }
        if (placements.size() != bagTags.size()) {
            throw ApiException.unprocessable("位置数量与袋号数量不一致");
        }
        Map<String, PlacementRequest> byTag = new LinkedHashMap<>();
        for (PlacementRequest p : placements) {
            if (byTag.put(p.bagTag(), p) != null) {
                throw ApiException.unprocessable("位置提交中 bagTag 重复: " + p.bagTag());
            }
        }
        if (!byTag.keySet().equals(new HashSet<>(bagTags))) {
            throw ApiException.unprocessable("位置与袋号集合不一致");
        }
        return byTag;
    }

    /** 查询航段当前装载布局（关联行李固定尺寸）。 */
    private List<LayoutRow> layoutRows(String legId) {
        return jdbcTemplate.query(
                "SELECT lr.bag_tag, lr.x_mm, lr.y_mm, lr.rotation, b.length_mm, b.width_mm"
                        + " FROM load_record lr JOIN bag b ON b.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ?",
                LAYOUT_MAPPER, legId);
    }

    /** 航段内除 excludeTags 外的既有落位矩形（布局航段上位置必然齐全）。 */
    private List<PlacementCell> currentCells(String legId, Set<String> excludeTags) {
        List<PlacementCell> cells = new ArrayList<>();
        for (LayoutRow row : layoutRows(legId)) {
            if (!excludeTags.contains(row.bagTag())) {
                cells.add(toCell(row.bagTag(), row.lengthMm(), row.widthMm(),
                        new PlacementRequest(row.bagTag(), row.xMm(), row.yMm(), row.rotation()), null));
            }
        }
        return cells;
    }

    /**
     * 构造落位矩形并做合法性检查：旋转仅 0/90 度，坐标非负，长整数运算防溢出，
     * 落位矩形不得越出舱底（leg 为 null 时跳过越界检查，用于既有合法落位）。
     */
    private static PlacementCell toCell(String bagTag, int lengthMm, int widthMm,
                                        PlacementRequest p, LegRow leg) {
        int rotation = p.rotation();
        if (rotation != 0 && rotation != 90) {
            throw ApiException.unprocessable("行李 " + bagTag + " 旋转角度仅支持 0 或 90 度");
        }
        if (p.xMm() < 0 || p.yMm() < 0) {
            throw ApiException.unprocessable("行李 " + bagTag + " 落位坐标不得为负");
        }
        long effLen = rotation == 0 ? lengthMm : widthMm;
        long effWid = rotation == 0 ? widthMm : lengthMm;
        if (leg != null && ((long) p.xMm() + effLen > leg.floorLengthMm()
                || (long) p.yMm() + effWid > leg.floorWidthMm())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 落位越出舱底边界");
        }
        return new PlacementCell(bagTag, p.xMm(), p.yMm(), effLen, effWid);
    }

    /** 两两检查落位矩形不得内部重叠，边界接触合法。 */
    private static void checkNoOverlap(List<PlacementCell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                PlacementCell a = cells.get(i);
                PlacementCell b = cells.get(j);
                if (a.x() < b.x() + b.len() && b.x() < a.x() + a.len()
                        && a.y() < b.y() + b.wid() && b.y() < a.y() + a.wid()) {
                    throw ApiException.unprocessable(
                            "落位重叠: " + a.bagTag() + " 与 " + b.bagTag());
                }
            }
        }
    }

    private RearrangeResponse doRearrange(String legId, RearrangeRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止重排");
        }
        if (leg.floorLengthMm() == null) {
            throw ApiException.unprocessable("航段未配置舱位布局，禁止重排");
        }
        Map<String, PlacementRequest> byTag = new LinkedHashMap<>();
        for (PlacementRequest p : request.placements()) {
            if (byTag.put(p.bagTag(), p) != null) {
                throw ApiException.unprocessable("重排提交中 bagTag 重复: " + p.bagTag());
            }
        }
        Map<String, LayoutRow> current = new HashMap<>();
        for (LayoutRow row : layoutRows(legId)) {
            current.put(row.bagTag(), row);
        }
        for (String bagTag : byTag.keySet()) {
            if (!current.containsKey(bagTag)) {
                throw ApiException.unprocessable("行李 " + bagTag + " 未装载到航段 " + legId);
            }
        }
        // 最终集合：被重排行李先整体移除旧位置，再与未移动行李一起统一检查新位置
        List<PlacementCell> finalCells = new ArrayList<>(currentCells(legId, byTag.keySet()));
        List<PlacementView> applied = new ArrayList<>();
        for (PlacementRequest p : request.placements()) {
            LayoutRow old = current.get(p.bagTag());
            finalCells.add(toCell(p.bagTag(), old.lengthMm(), old.widthMm(), p, leg));
            applied.add(new PlacementView(p.bagTag(), old.lengthMm(), old.widthMm(),
                    p.rotation(), p.xMm(), p.yMm()));
        }
        checkNoOverlap(finalCells);
        for (PlacementRequest p : request.placements()) {
            jdbcTemplate.update(
                    "UPDATE load_record SET x_mm = ?, y_mm = ?, rotation = ? WHERE bag_tag = ?",
                    p.xMm(), p.yMm(), p.rotation(), p.bagTag());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        List<PlacementView> appliedSorted = applied.stream()
                .sorted(Comparator.comparing(PlacementView::bagTag))
                .toList();
        return new RearrangeResponse(legId, LEG_OPEN, newVersion,
                appliedSorted.stream().map(PlacementView::bagTag).toList(), appliedSorted);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
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

    /** 读取封舱冻结布局；未封舱或无布局时返回空列表。 */
    private List<PlacementView> readLayout(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlacementView.class));
        } catch (Exception ex) {
            throw new IllegalStateException("布局反序列化失败", ex);
        }
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual,
                          Integer floorLengthMm, Integer floorWidthMm, String sealedLayout) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt,
                          Integer lengthMm, Integer widthMm) {
    }

    /** 航段当前装载布局行：load_record 位置关联行李固定尺寸。 */
    private record LayoutRow(String bagTag, Integer xMm, Integer yMm, Integer rotation,
                             Integer lengthMm, Integer widthMm) {
    }

    /** 落位矩形（长整数运算防溢出）：左下角坐标与占位长宽，单位毫米。 */
    private record PlacementCell(String bagTag, long x, long y, long len, long wid) {
    }

    /** 装载幂等摘要参数：bagTags 与 placements 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, String operator, int expectedVersion,
                               List<String> bagTags, List<PlacementRequest> placements) {
    }

    /** 整批重排幂等摘要参数：placements 已按袋号排序。 */
    private record RearrangePayload(String legId, String operator, int expectedVersion,
                                    List<PlacementRequest> placements) {
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
