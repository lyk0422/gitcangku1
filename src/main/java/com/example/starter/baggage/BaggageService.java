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
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.DwellCheckItem;
import com.example.starter.baggage.BaggageDtos.DwellCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellCheckResponse;
import com.example.starter.baggage.BaggageDtos.DwellConfigRequest;
import com.example.starter.baggage.BaggageDtos.DwellConfigResponse;
import com.example.starter.baggage.BaggageDtos.DwellExceededItem;
import com.example.starter.baggage.BaggageDtos.DwellExceededListResponse;
import com.example.starter.baggage.BaggageDtos.DwellRecordItem;
import com.example.starter.baggage.BaggageDtos.DwellRecordListResponse;
import com.example.starter.baggage.BaggageDtos.DwellReleaseRequest;
import com.example.starter.baggage.BaggageDtos.DwellReleaseResponse;
import com.example.starter.baggage.BaggageDtos.DwellStatusResponse;
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
    private static final String BAG_DWELL_EXCEEDED = "DWELL_EXCEEDED";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";
    private static final String EVT_DWELL_EXCEEDED = "DWELL_EXCEEDED";
    private static final String EVT_DWELL_RELEASED = "DWELL_RELEASED";

    private static final String REC_ARRIVAL = "ARRIVAL";
    private static final String REC_EXCEEDED = "EXCEEDED";
    private static final String REC_RELEASED = "RELEASED";

    /** 航段装载容量上限（件），解除滞留阻断时重新校验。 */
    private static final int MAX_LEG_CAPACITY = 20;

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"),
            rs.getString("dwell_station"), getInstant(rs, "dwell_arrived_at"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<DwellRecordItem> DWELL_RECORD_MAPPER = (rs, rowNum) -> {
        Instant arrivedAt = getInstant(rs, "arrived_at");
        return new DwellRecordItem(rs.getLong("id"), rs.getString("record_type"),
                rs.getString("station"), arrivedAt == null ? null : arrivedAt.toString(),
                getInstant(rs, "recorded_at").toString(),
                (Long) rs.getObject("dwell_minutes"), (Integer) rs.getObject("max_dwell_minutes"),
                rs.getString("next_leg_id"), rs.getString("reviewer"), rs.getString("review_note"));
    };

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

    /** 配置中转点最大滞留分钟数：同中转点重复配置覆盖上限，历史证据保留判定时上限。 */
    public DwellConfigResponse configureDwell(DwellConfigRequest request) {
        return idempotencyService.execute(request.requestId(), "CONFIG_DWELL", 201,
                request, DwellConfigResponse.class, () -> doConfigureDwell(request));
    }

    /**
     * 批量到达核对（超时判定）：中转点缺配置、到达时刻缺失或任一行李状态非法均 422 整次回滚；
     * 滞留时长达到或超过上限（区间左闭右开 [0, max)）的行李转 DWELL_EXCEEDED 并追加不可变超时证据。
     */
    public DwellCheckResponse checkDwell(String station, DwellCheckRequest request) {
        DwellCheckPayload payload = new DwellCheckPayload(station, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "DWELL_CHECK", 200,
                payload, DwellCheckResponse.class, () -> doCheckDwell(station, request));
    }

    /**
     * 解除滞留阻断：登记复核人与复核结论，必须指定行程内下一航段，
     * 并重新校验该航段装载截止（仍为 OPEN）与容量（未达上限）。
     */
    public DwellReleaseResponse releaseDwell(String bagTag, DwellReleaseRequest request) {
        DwellReleasePayload payload = new DwellReleasePayload(
                bagTag, request.reviewer(), request.reviewNote(), request.nextLegId());
        return idempotencyService.execute(request.requestId(), "DWELL_RELEASE", 200,
                payload, DwellReleaseResponse.class, () -> doReleaseDwell(bagTag, request));
    }

    /** 行李当前滞留诊断：只读，不改状态。 */
    public DwellStatusResponse getDwellStatus(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        Long dwellMinutes = null;
        Integer maxDwellMinutes = null;
        if (bag.dwellArrivedAt() != null) {
            dwellMinutes = Duration.between(bag.dwellArrivedAt(), clock.get()).toMinutes();
            maxDwellMinutes = findDwellConfig(bag.dwellStation());
        }
        return new DwellStatusResponse(bag.bagTag(), bag.status(), bag.dwellStation(),
                bag.dwellArrivedAt() == null ? null : bag.dwellArrivedAt().toString(),
                dwellMinutes, maxDwellMinutes);
    }

    /** 行李滞留证据历史：按 id 升序（提交顺序），只读，不改状态。 */
    public DwellRecordListResponse listDwellRecords(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<DwellRecordItem> records = jdbcTemplate.query(
                "SELECT id, record_type, station, arrived_at, recorded_at, dwell_minutes,"
                        + " max_dwell_minutes, next_leg_id, reviewer, review_note"
                        + " FROM dwell_record WHERE bag_tag = ? ORDER BY id",
                DWELL_RECORD_MAPPER, bagTag);
        return new DwellRecordListResponse(bagTag, records);
    }

    /** 中转点当前滞留超时行李清单：按到达时刻、袋号稳定排序，只读。 */
    public DwellExceededListResponse listDwellExceeded(String station) {
        List<DwellExceededItem> items = jdbcTemplate.query(
                "SELECT bag_tag, dwell_station, dwell_arrived_at FROM bag"
                        + " WHERE status = ? AND dwell_station = ?"
                        + " ORDER BY dwell_arrived_at, bag_tag",
                (rs, rowNum) -> new DwellExceededItem(rs.getString("bag_tag"),
                        rs.getString("dwell_station"),
                        getInstant(rs, "dwell_arrived_at").toString()),
                BAG_DWELL_EXCEEDED, station);
        return new DwellExceededListResponse(station, items);
    }

    private DwellConfigResponse doConfigureDwell(DwellConfigRequest request) {
        Integer existing = findDwellConfig(request.station());
        if (existing == null) {
            jdbcTemplate.update(
                    "INSERT INTO dwell_config (station, max_dwell_minutes) VALUES (?, ?)",
                    request.station(), request.maxDwellMinutes());
        } else {
            jdbcTemplate.update(
                    "UPDATE dwell_config SET max_dwell_minutes = ? WHERE station = ?",
                    request.maxDwellMinutes(), request.station());
        }
        return new DwellConfigResponse(request.station(), request.maxDwellMinutes());
    }

    private DwellCheckResponse doCheckDwell(String station, DwellCheckRequest request) {
        Integer maxDwellMinutes = findDwellConfig(station);
        if (maxDwellMinutes == null) {
            throw ApiException.unprocessable("中转点 " + station + " 缺少滞留配置，无法核对");
        }
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("批量核对的 bagTag 不得重复");
        }
        // 先对全部行李完成最终状态校验，任一非法则整次回滚，不产生半成品
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sortedCopy(bagTags)) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateDwellCheckable(bag, station);
            bags.add(bag);
        }
        Instant now = clock.get();
        List<DwellCheckItem> results = new ArrayList<>();
        for (BagRow bag : bags) {
            long dwellMinutes = Duration.between(bag.dwellArrivedAt(), now).toMinutes();
            boolean exceeded = dwellMinutes >= maxDwellMinutes;
            if (exceeded) {
                jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?",
                        BAG_DWELL_EXCEEDED, bag.bagTag());
                insertDwellRecord(new DwellEvidence(bag.bagTag(), station, REC_EXCEEDED,
                        bag.dwellArrivedAt(), dwellMinutes, maxDwellMinutes, null, null, null));
                insertEvent(bag.bagTag(), EVT_DWELL_EXCEEDED, null, station);
            }
            results.add(new DwellCheckItem(bag.bagTag(), dwellMinutes, maxDwellMinutes, exceeded));
        }
        return new DwellCheckResponse(station, maxDwellMinutes, now.toString(), results);
    }

    /** 核对前状态校验：仅允许在中转点等待（未装载、行程未完）的行李参与。 */
    private void validateDwellCheckable(BagRow bag, String station) {
        if (BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已处于滞留超时阻断状态，须先复核解除");
        }
        if (!BAG_IN_TRANSIT.equals(bag.status()) && !BAG_RECOVERED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，不可参与到达核对");
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已装载到航段 " + bag.loadedLegId() + "，不在中转点等待");
        }
        if (!bag.currentLocation().equals(station)) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 当前位于 " + bag.currentLocation()
                            + "，不在中转点 " + station);
        }
        if (bag.dwellArrivedAt() == null) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 到达中转点 " + station + " 的时刻缺失，无法核对");
        }
    }

    private DwellReleaseResponse doReleaseDwell(String bagTag, DwellReleaseRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (!BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 状态为 " + bag.status() + "，不在滞留阻断中");
        }
        ItineraryItem next = nextItinerary(bagTag, bag.nextLegIndex());
        if (next == null || !next.legId().equals(request.nextLegId())) {
            throw ApiException.unprocessable(
                    "下一航段 " + request.nextLegId() + " 不属于行李 " + bagTag + " 的行程待乘航段");
        }
        LegRow leg = findLeg(request.nextLegId());
        if (leg == null) {
            throw ApiException.unprocessable("下一航段不存在: " + request.nextLegId());
        }
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable(
                    "下一航段 " + leg.legId() + " 已过装载截止: 当前状态 " + leg.status() + "，要求 OPEN");
        }
        Integer loaded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, leg.legId());
        int loadedCount = loaded == null ? 0 : loaded;
        if (loadedCount >= MAX_LEG_CAPACITY) {
            throw ApiException.unprocessable(
                    "下一航段 " + leg.legId() + " 容量不足: 已装载 " + loadedCount
                            + " 件，上限 " + MAX_LEG_CAPACITY + " 件");
        }
        Instant now = clock.get();
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?", BAG_IN_TRANSIT, bagTag);
        insertDwellRecord(new DwellEvidence(bagTag, bag.dwellStation(), REC_RELEASED,
                bag.dwellArrivedAt(), null, null, request.nextLegId(),
                request.reviewer(), request.reviewNote()));
        insertEvent(bagTag, EVT_DWELL_RELEASED, request.nextLegId(), bag.currentLocation());
        return new DwellReleaseResponse(bagTag, BAG_IN_TRANSIT, request.nextLegId(),
                request.reviewer(), now.toString());
    }

    /** 追加不可变滞留证据，recorded_at 取当前时钟（UTC）。 */
    private void insertDwellRecord(DwellEvidence evidence) {
        jdbcTemplate.update(
                "INSERT INTO dwell_record (bag_tag, station, record_type, arrived_at, recorded_at,"
                        + " dwell_minutes, max_dwell_minutes, next_leg_id, reviewer, review_note)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                evidence.bagTag(), evidence.station(), evidence.recordType(),
                evidence.arrivedAt() == null ? null
                        : OffsetDateTime.ofInstant(evidence.arrivedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC),
                evidence.dwellMinutes(), evidence.maxDwellMinutes(),
                evidence.nextLegId(), evidence.reviewer(), evidence.reviewNote());
    }

    private Integer findDwellConfig(String station) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT max_dwell_minutes FROM dwell_config WHERE station = ?",
                (rs, rowNum) -> rs.getInt("max_dwell_minutes"), station);
        return rows.isEmpty() ? null : rows.get(0);
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
            jdbcTemplate.update(
                    "UPDATE bag SET loaded_leg_id = ?, dwell_station = NULL, dwell_arrived_at = NULL"
                            + " WHERE bag_tag = ?",
                    legId, bag.bagTag());
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
        if (BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 滞留超时已阻断，须复核解除后才能改派");
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
        if (BAG_DELIVERED.equals(newStatus)) {
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                            + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL,"
                            + " dwell_station = NULL, dwell_arrived_at = NULL WHERE bag_tag = ?",
                    request.actualStation(), nextIndex, newStatus, bag.bagTag());
        } else {
            // 补到后行程未完：实际到站即中转点，记录滞留起点并追加到达证据
            OffsetDateTime arrivedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                            + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL,"
                            + " dwell_station = ?, dwell_arrived_at = ? WHERE bag_tag = ?",
                    request.actualStation(), nextIndex, newStatus,
                    request.actualStation(), arrivedAt, bag.bagTag());
            insertDwellRecord(new DwellEvidence(bag.bagTag(), request.actualStation(), REC_ARRIVAL,
                    arrivedAt.toInstant(), null, null, null, null, null));
        }
        insertEvent(bag.bagTag(), EVT_RECOVERED, request.missingLegId(), request.actualStation());
        if (BAG_DELIVERED.equals(newStatus)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, request.missingLegId(), request.actualStation());
        }
        return new RecoverResponse(bag.bagTag(), newStatus, request.actualStation(),
                nextIndex, request.missingLegId());
    }

    /** 实际到达行李的统一推进：移动到到达站、推进待乘索引，完成行程者交付；
     *  行程未完者到达站即中转点，记录滞留起点并追加到达证据。 */
    private void advanceArrivedBag(BagRow bag, String destination, String legId) {
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bag.bagTag());
        String status = nextIndex >= total ? BAG_DELIVERED : BAG_IN_TRANSIT;
        if (BAG_DELIVERED.equals(status)) {
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                            + " dwell_station = NULL, dwell_arrived_at = NULL WHERE bag_tag = ?",
                    destination, nextIndex, status, bag.bagTag());
        } else {
            OffsetDateTime arrivedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                            + " dwell_station = ?, dwell_arrived_at = ? WHERE bag_tag = ?",
                    destination, nextIndex, status, destination, arrivedAt, bag.bagTag());
            insertDwellRecord(new DwellEvidence(bag.bagTag(), destination, REC_ARRIVAL,
                    arrivedAt.toInstant(), null, null, null, null, null));
        }
        insertEvent(bag.bagTag(), EVT_UNLOADED, legId, destination);
        if (BAG_DELIVERED.equals(status)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, legId, destination);
        }
    }

    private void validateLoadable(BagRow bag, LegRow leg) {
        if (BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 滞留超时已阻断，须复核解除后才能装载");
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
                + " short_leg_id, short_destination, short_registered_at,"
                + " dwell_station, dwell_arrived_at FROM bag WHERE bag_tag = ?"
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
                bag.dwellStation(),
                bag.dwellArrivedAt() == null ? null : bag.dwellArrivedAt().toString(),
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
                          String shortDestination, Instant shortRegisteredAt,
                          String dwellStation, Instant dwellArrivedAt) {
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

    /** 批量到达核对幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DwellCheckPayload(String station, List<String> bagTags) {
    }

    /** 滞留解除幂等摘要参数：覆盖复核人、复核结论与下一航段。 */
    private record DwellReleasePayload(String bagTag, String reviewer, String reviewNote,
                                       String nextLegId) {
    }

    /** 滞留证据写入参数：recordedAt 由写入时统一取时钟，null 含义见 dwell_record 表注释。 */
    private record DwellEvidence(String bagTag, String station, String recordType, Instant arrivedAt,
                                 Long dwellMinutes, Integer maxDwellMinutes, String nextLegId,
                                 String reviewer, String reviewNote) {
    }
}
