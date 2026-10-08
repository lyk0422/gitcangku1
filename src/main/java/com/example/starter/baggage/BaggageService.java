package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.example.starter.baggage.BaggageDtos.LayoutResponse;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.PlacementResponse;
import com.example.starter.baggage.BaggageDtos.PositionRequest;
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

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    /** 整批重排数量上限。 */
    private static final int MAX_REARRANGE = 10;
    /** 尺寸/坐标允许的合成正整数/非负整数上限，配合 long 乘加防溢出。 */
    private static final int MAX_DIMENSION = 1_000_000_000;

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("sealed_layout"), rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"));

    /** floor_layout 行映射：舱底固定长宽（毫米）。 */
    private static final RowMapper<FloorRow> FLOOR_MAPPER = (rs, rowNum) -> new FloorRow(
            rs.getString("leg_id"), rs.getInt("width_mm"), rs.getInt("height_mm"));

    /** bag_size 行映射：行李登记长宽（毫米）。 */
    private static final RowMapper<SizeRow> SIZE_MAPPER = (rs, rowNum) -> new SizeRow(
            rs.getString("bag_tag"), rs.getInt("width_mm"), rs.getInt("height_mm"));

    /** load_record 落位行映射：登记尺寸、旋转、左下角坐标。 */
    private static final RowMapper<PlacementRow> PLACEMENT_MAPPER = (rs, rowNum) -> new PlacementRow(
            rs.getString("bag_tag"), rs.getInt("width_mm"), rs.getInt("height_mm"),
            rs.getBoolean("rotated"), rs.getInt("pos_x"), rs.getInt("pos_y"));

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

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1；可附固定矩形舱底长宽。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        RegisterLegPayload payload = new RegisterLegPayload(request.legId(), request.origin(),
                request.destination(), request.floorWidthMm(), request.floorHeightMm());
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", request.operator(), 201,
                payload, LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站；可附登记尺寸。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        RegisterBagPayload payload = new RegisterBagPayload(request.bagTag(), request.widthMm(),
                request.heightMm(), request.legIds());
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", request.operator(), 201,
                payload, BagResponse.class, () -> doRegisterBag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动；平面舱位航段须提交整批落位。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(), sortedCopy(request.bagTags()),
                positionSummary(request.positions()));
        return idempotencyService.execute(request.requestId(), "LOAD", request.operator(), 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /**
     * 整批重排：仅 OPEN 航段，统一移动 1~10 件已装行李。
     * 先从最终集合移除这些旧位置，再统一检查新位置与未移动行李；任一违规 422，位置与航段版本均不变。
     */
    public RearrangeResponse rearrange(String legId, RearrangeRequest request) {
        RearrangePayload payload = new RearrangePayload(legId, request.expectedVersion(),
                positionSummary(request.moves()));
        return idempotencyService.execute(request.requestId(), "REARRANGE", request.operator(), 200,
                payload, RearrangeResponse.class, () -> doRearrange(legId, request));
    }

    /** 封舱：校验版本，冻结完整袋号、尺寸、旋转与坐标并转 SEALED；空清单也可封舱。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.expectedVersion());
        return idempotencyService.execute(request.requestId(), "SEAL", request.operator(), 200,
                payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /** 到达确认：实际袋号集合须与封舱清单完全一致，匹配则原子转 ARRIVED 并推进各行李。 */
    public ArriveResponse arrive(String legId, ArriveRequest request) {
        ArrivePayload payload = new ArrivePayload(legId, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE", request.operator(), 200,
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
        return idempotencyService.execute(request.requestId(), "ARRIVE_DIFFERENCE", request.operator(), 200,
                payload, DifferenceArriveResponse.class, () -> doArriveDifference(legId, request));
    }

    /**
     * 补到：仅 SHORT_UNLOADED 且缺失航段匹配的行李可补到，实际站必须等于该航段到达站（否则 422）。
     * 成功后移动行李、推进待乘索引并标记 RECOVERED（完成行程者 DELIVERED）；
     * 相同补到同参由 requestId 重放原结果，已补到不得再次推进。
     */
    public RecoverResponse recover(RecoverRequest request) {
        RecoverPayload payload = new RecoverPayload(request.bagTag(), request.missingLegId(),
                request.actualStation());
        return idempotencyService.execute(request.requestId(), "RECOVER", request.operator(), 200,
                payload, RecoverResponse.class, () -> doRecover(request));
    }

    /** 行李完整轨迹查询。 */
    public BagResponse getBagTrace(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        return toBagResponse(bag);
    }

    /** 封舱清单查询：未封舱时返回空清单；平面舱位航段随附冻结/当前二维落位与舱底尺寸。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        FloorRow floor = findFloor(legId);
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        List<PlacementResponse> placements;
        if (floor == null) {
            placements = null;
        } else if (LEG_SEALED.equals(leg.status()) || LEG_ARRIVED.equals(leg.status())) {
            placements = leg.sealedLayout() == null ? List.of() : readPlacements(leg.sealedLayout());
        } else {
            placements = currentPlacements(legId);
        }
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest, placements,
                floor == null ? null : floor.widthMm(), floor == null ? null : floor.heightMm());
    }

    /** 当前平面布局查询：未封舱返回当前落位，已封舱返回冻结快照；无二维配置旧航段返回 422。 */
    public LayoutResponse getLayout(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        FloorRow floor = findFloor(legId);
        if (floor == null) {
            throw ApiException.unprocessable("航段 " + legId + " 未配置平面舱位布局");
        }
        boolean frozen = LEG_SEALED.equals(leg.status()) || LEG_ARRIVED.equals(leg.status());
        List<PlacementResponse> placements = frozen ? readPlacements(leg.sealedLayout()) : currentPlacements(legId);
        return new LayoutResponse(leg.legId(), leg.status(), leg.version(),
                floor.widthMm(), floor.heightMm(), placements);
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
        boolean hasWidth = request.floorWidthMm() != null;
        boolean hasHeight = request.floorHeightMm() != null;
        if (hasWidth != hasHeight) {
            throw ApiException.unprocessable("平面舱底长宽必须同时提供");
        }
        if (hasWidth && (!positive(request.floorWidthMm()) || !positive(request.floorHeightMm()))) {
            throw ApiException.unprocessable("平面舱底长宽必须为不超过 " + MAX_DIMENSION + " 的正整数（毫米）");
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version) VALUES (?, ?, ?, ?, 1)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN);
        if (hasWidth) {
            jdbcTemplate.update(
                    "INSERT INTO floor_layout (leg_id, width_mm, height_mm) VALUES (?, ?, ?)",
                    request.legId(), request.floorWidthMm(), request.floorHeightMm());
        }
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
        boolean hasWidth = request.widthMm() != null;
        boolean hasHeight = request.heightMm() != null;
        if (hasWidth != hasHeight) {
            throw ApiException.unprocessable("行李长宽必须同时提供");
        }
        if (hasWidth && (!positive(request.widthMm()) || !positive(request.heightMm()))) {
            throw ApiException.unprocessable("行李长宽必须为不超过 " + MAX_DIMENSION + " 的正整数（毫米）");
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
        if (hasWidth) {
            jdbcTemplate.update(
                    "INSERT INTO bag_size (bag_tag, width_mm, height_mm) VALUES (?, ?, ?)",
                    request.bagTag(), request.widthMm(), request.heightMm());
        }
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
        FloorRow floor = findFloor(legId);
        List<PositionRequest> positions = request.positions();
        if (floor != null) {
            if (positions == null || positions.size() != bagTags.size()) {
                throw ApiException.unprocessable("平面舱位航段装载必须为每件行李提交一个落位");
            }
            Set<String> positionTags = new HashSet<>();
            for (PositionRequest position : positions) {
                if (!positionTags.add(position.bagTag())) {
                    throw ApiException.unprocessable("落位列表的 bagTag 不得重复");
                }
            }
            if (!positionTags.equals(new HashSet<>(bagTags))) {
                throw ApiException.unprocessable("落位列表必须与批量装载袋号一一对应");
            }
            if (positions.stream().anyMatch(p -> p.x() < 0 || p.y() < 0)) {
                throw ApiException.unprocessable("落位左下角坐标必须为非负整数（毫米）");
            }
        } else if (positions != null && !positions.isEmpty()) {
            throw ApiException.unprocessable("航段 " + legId + " 未配置平面舱位，不接受落位");
        }
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        Map<String, SizeRow> sizes = new LinkedHashMap<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateLoadable(bag, leg);
            bags.add(bag);
            if (floor != null) {
                SizeRow size = findSize(bagTag);
                if (size == null) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 未登记二维尺寸，不能装载到平面舱位航段");
                }
                sizes.put(bagTag, size);
            }
        }
        List<PlacementRow> newPlacements = new ArrayList<>();
        if (floor != null) {
            Map<String, PositionRequest> byTag = new LinkedHashMap<>();
            for (PositionRequest position : positions) {
                byTag.put(position.bagTag(), position);
            }
            List<PlacementRow> existing = currentPlacementRows(legId);
            for (String bagTag : sorted) {
                SizeRow size = sizes.get(bagTag);
                PositionRequest position = byTag.get(bagTag);
                Geometry.Rect rect = Geometry.rect(size.widthMm(), size.heightMm(),
                        position.rotated(), position.x(), position.y());
                newPlacements.add(new PlacementRow(bagTag, size.widthMm(), size.heightMm(),
                        position.rotated(), position.x(), position.y()));
                if (!rect.inside(floor.widthMm(), floor.heightMm())) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 落位矩形越界，舱底 "
                                    + floor.widthMm() + "x" + floor.heightMm() + " 毫米");
                }
            }
            // 新位置之间及与已在位但未移动行李两两检查，边界接触合法
            ensureNoOverlap(existing, newPlacements, floor);
        }
        for (int i = 0; i < bags.size(); i++) {
            BagRow bag = bags.get(i);
            if (floor == null) {
                jdbcTemplate.update(
                        "INSERT INTO load_record (bag_tag, leg_id, rotated) VALUES (?, ?, FALSE)",
                        bag.bagTag(), legId);
            } else {
                PlacementRow placement = newPlacements.get(i);
                jdbcTemplate.update(
                        "INSERT INTO load_record (bag_tag, leg_id, width_mm, height_mm, rotated, pos_x, pos_y)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                        placement.bagTag(), legId, placement.widthMm(), placement.heightMm(),
                        placement.rotated(), placement.posX(), placement.posY());
            }
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        List<PlacementResponse> placementResponses = floor == null
                ? null
                : newPlacements.stream().map(BaggageService::toPlacementResponse)
                        .sorted(java.util.Comparator.comparing(PlacementResponse::bagTag)).toList();
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted, placementResponses);
    }

    /**
     * 整批重排：仅平面舱位 OPEN 航段。先把被移动行李的旧位置从最终集合移除，
     * 再统一校验新位置之间及与未移动行李不越界、不重叠，任何一件违规整批 422 且版本不变。
     */
    private RearrangeResponse doRearrange(String legId, RearrangeRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止重排");
        }
        FloorRow floor = findFloor(legId);
        if (floor == null) {
            throw ApiException.unprocessable("航段 " + legId + " 未配置平面舱位，无位置可重排");
        }
        List<PositionRequest> moves = request.moves();
        if (moves.size() > MAX_REARRANGE) {
            throw ApiException.unprocessable("整批重排数量必须为 1~" + MAX_REARRANGE);
        }
        Set<String> moveTags = new HashSet<>();
        for (PositionRequest move : moves) {
            if (!moveTags.add(move.bagTag())) {
                throw ApiException.unprocessable("重排袋号不得重复: " + move.bagTag());
            }
            if (move.x() < 0 || move.y() < 0) {
                throw ApiException.unprocessable("落位左下角坐标必须为非负整数（毫米）");
            }
        }
        List<PlacementRow> current = currentPlacementRows(legId);
        Map<String, PlacementRow> currentByTag = new LinkedHashMap<>();
        for (PlacementRow row : current) {
            currentByTag.put(row.bagTag(), row);
        }
        List<PlacementRow> staying = new ArrayList<>();
        List<PlacementRow> moved = new ArrayList<>();
        for (PlacementRow row : current) {
            if (moveTags.contains(row.bagTag())) {
                moved.add(row);
            } else {
                staying.add(row);
            }
        }
        for (String bagTag : moveTags) {
            if (!currentByTag.containsKey(bagTag)) {
                throw ApiException.unprocessable("行李 " + bagTag + " 未装载在航段 " + legId + "，不能重排");
            }
            BagRow bag = lockBag(bagTag);
            if (bag == null || !legId.equals(bag.loadedLegId())) {
                throw ApiException.unprocessable("行李 " + bagTag + " 当前不在航段 " + legId + " 舱内");
            }
        }
        // 先整体移除旧位置，再用“未移动行李 + 全部新位置”组成最终集合统一检查，支持左右互换
        List<PlacementRow> newPlacements = new ArrayList<>();
        for (PlacementRow old : moved) {
            PositionRequest move = moves.stream()
                    .filter(m -> m.bagTag().equals(old.bagTag())).findFirst().orElseThrow();
            Geometry.Rect rect = Geometry.rect(old.widthMm(), old.heightMm(), move.rotated(), move.x(), move.y());
            if (!rect.inside(floor.widthMm(), floor.heightMm())) {
                throw ApiException.unprocessable(
                        "行李 " + old.bagTag() + " 新位置越界，舱底 "
                                + floor.widthMm() + "x" + floor.heightMm() + " 毫米");
            }
            newPlacements.add(new PlacementRow(old.bagTag(), old.widthMm(), old.heightMm(),
                    move.rotated(), move.x(), move.y()));
        }
        ensureNoOverlap(staying, newPlacements, floor);

        for (PlacementRow placement : newPlacements) {
            jdbcTemplate.update(
                    "UPDATE load_record SET rotated = ?, pos_x = ?, pos_y = ? WHERE bag_tag = ? AND leg_id = ?",
                    placement.rotated(), placement.posX(), placement.posY(),
                    placement.bagTag(), legId);
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        List<PlacementResponse> all = currentPlacements(legId, newPlacements);
        return new RearrangeResponse(legId, LEG_OPEN, newVersion, all);
    }

    /**
     * 统一重叠检查：未移动行李保持原矩形，新放置行李使用新矩形；
     * 新矩形彼此也要互不重叠。任一内部相交即 422，边界接触合法。
     */
    private void ensureNoOverlap(List<PlacementRow> staying, List<PlacementRow> incoming, FloorRow floor) {
        List<PlacementRow> all = new ArrayList<>(staying.size() + incoming.size());
        all.addAll(staying);
        all.addAll(incoming);
        for (int i = 0; i < all.size(); i++) {
            PlacementRow a = all.get(i);
            Geometry.Rect ra = toRect(a);
            if (!ra.inside(floor.widthMm(), floor.heightMm())) {
                throw ApiException.unprocessable("行李 " + a.bagTag() + " 落位矩形越界");
            }
            for (int j = i + 1; j < all.size(); j++) {
                PlacementRow b = all.get(j);
                if (a.bagTag().equals(b.bagTag())) {
                    continue;
                }
                if (ra.overlaps(toRect(b))) {
                    throw ApiException.unprocessable(
                            "行李 " + a.bagTag() + " 与 " + b.bagTag() + " 落位矩形内部重叠");
                }
            }
        }
    }

    private static Geometry.Rect toRect(PlacementRow row) {
        return Geometry.rect(row.widthMm(), row.heightMm(), row.rotated(), row.posX(), row.posY());
    }

    private SealResponse doSeal(String legId, SealRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止封舱");
        }
        List<String> manifest = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag", String.class, legId);
        // 冻结完整袋号、登记尺寸、旋转与左下角坐标；无二维配置旧航段不产生布局快照
        FloorRow floor = findFloor(legId);
        List<PlacementResponse> placements = floor == null ? null : currentPlacements(legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, sealed_manifest = ?, sealed_layout = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(manifest),
                        placements == null ? null : writeJson(placements), legId);
        return new SealResponse(legId, LEG_SEALED, newVersion, manifest, placements);
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
        return "SELECT leg_id, origin, destination, status, version, sealed_manifest, sealed_layout,"
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

    private List<PlacementResponse> readPlacements(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlacementResponse.class));
        } catch (Exception ex) {
            throw new IllegalStateException("布局快照反序列化失败", ex);
        }
    }

    private FloorRow findFloor(String legId) {
        List<FloorRow> rows = jdbcTemplate.query(
                "SELECT leg_id, width_mm, height_mm FROM floor_layout WHERE leg_id = ?",
                FLOOR_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SizeRow findSize(String bagTag) {
        List<SizeRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, width_mm, height_mm FROM bag_size WHERE bag_tag = ?",
                SIZE_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取航段舱内当前落位行（仅二维配置航段有坐标行），按袋号排序。 */
    private List<PlacementRow> currentPlacementRows(String legId) {
        return jdbcTemplate.query(
                "SELECT bag_tag, width_mm, height_mm, rotated, pos_x, pos_y FROM load_record"
                        + " WHERE leg_id = ? AND pos_x IS NOT NULL ORDER BY bag_tag",
                PLACEMENT_MAPPER, legId);
    }

    private List<PlacementResponse> currentPlacements(String legId) {
        return currentPlacementRows(legId).stream()
                .map(BaggageService::toPlacementResponse).toList();
    }

    /** 重排提交后的舱内全集：未移动行李取数据库旧位置，移动行李取新位置，按袋号排序。 */
    private List<PlacementResponse> currentPlacements(String legId, List<PlacementRow> moved) {
        Set<String> movedTags = new HashSet<>();
        for (PlacementRow row : moved) {
            movedTags.add(row.bagTag());
        }
        List<PlacementResponse> all = new ArrayList<>();
        for (PlacementRow row : currentPlacementRows(legId)) {
            if (!movedTags.contains(row.bagTag())) {
                all.add(toPlacementResponse(row));
            }
        }
        for (PlacementRow row : moved) {
            all.add(toPlacementResponse(row));
        }
        return all.stream().sorted(java.util.Comparator.comparing(PlacementResponse::bagTag)).toList();
    }

    private static PlacementResponse toPlacementResponse(PlacementRow row) {
        return new PlacementResponse(row.bagTag(), row.widthMm(), row.heightMm(),
                row.rotated(), row.posX(), row.posY());
    }

    /** 落位参数规范化：按袋号排序后参与摘要，保证提交顺序差异不视为异参。 */
    private List<PositionSummary> positionSummary(List<PositionRequest> positions) {
        if (positions == null) {
            return List.of();
        }
        return positions.stream()
                .sorted(java.util.Comparator.comparing(PositionRequest::bagTag))
                .map(p -> new PositionSummary(p.bagTag(), p.rotated(), p.x(), p.y()))
                .toList();
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0 && value <= MAX_DIMENSION;
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest, String sealedLayout,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 舱底固定尺寸行（毫米）。 */
    private record FloorRow(String legId, int widthMm, int heightMm) {
    }

    /** 行李登记尺寸行（毫米）。 */
    private record SizeRow(String bagTag, int widthMm, int heightMm) {
    }

    /** 装载记录中的完整落位行：登记尺寸、旋转、左下角坐标。 */
    private record PlacementRow(String bagTag, int widthMm, int heightMm,
                                boolean rotated, int posX, int posY) {
    }

    /** 登记航段幂等摘要参数：舱底尺寸为资源配置的一部分。 */
    private record RegisterLegPayload(String legId, String origin, String destination,
                                      Integer floorWidthMm, Integer floorHeightMm) {
    }

    /** 登记行李幂等摘要参数。 */
    private record RegisterBagPayload(String bagTag, Integer widthMm, Integer heightMm,
                                      List<String> legIds) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，positions 按袋号排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, List<String> bagTags,
                               List<PositionSummary> positions) {
    }

    /** 重排幂等摘要参数：moves 按袋号排序。 */
    private record RearrangePayload(String legId, int expectedVersion, List<PositionSummary> moves) {
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

    /** 补到幂等摘要参数。 */
    private record RecoverPayload(String bagTag, String missingLegId, String actualStation) {
    }

    /** 单件落位的规范化摘要：十进制坐标按 JSON 数值比较，旋转与袋号原样。 */
    private record PositionSummary(String bagTag, boolean rotated, int x, int y) {
    }
}
