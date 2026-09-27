package com.example.starter.baggage;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ConfigureDwellLimitRequest;
import com.example.starter.baggage.BaggageDtos.ConfigureLegOpsRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.DwellCheckItem;
import com.example.starter.baggage.BaggageDtos.DwellConfigResponse;
import com.example.starter.baggage.BaggageDtos.DwellEventItem;
import com.example.starter.baggage.BaggageDtos.DwellHistoryResponse;
import com.example.starter.baggage.BaggageDtos.DwellRecordResponse;
import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegOpsConfigResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReleaseDwellRequest;
import com.example.starter.baggage.BaggageDtos.ReleaseDwellResponse;
import com.example.starter.baggage.BaggageDtos.RerouteRequest;
import com.example.starter.baggage.BaggageDtos.RerouteResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TraceEvent;
import com.example.starter.baggage.BaggageDtos.TransitArriveItem;
import com.example.starter.baggage.BaggageDtos.TransitArriveRequest;
import com.example.starter.baggage.BaggageDtos.TransitArriveResponse;
import com.example.starter.baggage.BaggageDtos.StationDwellDiagnostics;

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
    private static final String EVT_REROUTED = "REROUTED";

    private static final String DWELL_OPEN = "OPEN";
    private static final String DWELL_EXCEEDED_REC = "EXCEEDED";
    private static final String DWELL_RELEASED = "RELEASED";
    private static final String DWELL_CLOSED = "CLOSED";

    private static final String DVT_ARRIVED = "ARRIVED";
    private static final String DVT_EXCEEDED = "EXCEEDED";
    private static final String DVT_RELEASED = "RELEASED";
    private static final String DVT_CLOSED = "CLOSED";

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

    private static final RowMapper<DwellRecordRow> DWELL_RECORD_MAPPER = (rs, rowNum) -> new DwellRecordRow(
            rs.getLong("id"), rs.getString("bag_tag"), rs.getString("station"),
            getInstant(rs, "arrived_at"), rs.getString("status"),
            getInstant(rs, "exceeded_at"), getInstant(rs, "released_at"),
            rs.getString("reviewer"), rs.getString("review_conclusion"),
            rs.getString("next_leg_id"), getInstant(rs, "closed_at"));

    private static final RowMapper<DwellEventItem> DWELL_EVENT_MAPPER = (rs, rowNum) -> new DwellEventItem(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("station"),
            rs.getString("operator"), rs.getString("next_leg_id"), rs.getString("detail"),
            getInstant(rs, "event_time").toString());

    private static final RowMapper<LegOpsRow> LEG_OPS_MAPPER = (rs, rowNum) -> new LegOpsRow(
            rs.getString("leg_id"), getInstant(rs, "load_deadline"), rs.getInt("max_capacity"));

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

    /** 配置中转点最大滞留分钟数：station 已存在则更新，可重复提交。 */
    public DwellConfigResponse configureDwellLimit(String station, ConfigureDwellLimitRequest request) {
        ConfigureDwellLimitPayload payload = new ConfigureDwellLimitPayload(
                station, request.operator(), request.maxDwellMinutes());
        return idempotencyService.execute(request.requestId(), "CONFIGURE_DWELL_LIMIT", 200,
                payload, DwellConfigResponse.class, () -> doConfigureDwellLimit(station, request));
    }

    /** 配置航段装载截止时刻与最大容量：供复核解除时重新校验。 */
    public LegOpsConfigResponse configureLegOps(String legId, ConfigureLegOpsRequest request) {
        ConfigureLegOpsPayload payload = new ConfigureLegOpsPayload(
                legId, request.operator(), request.loadDeadline(), request.maxCapacity());
        return idempotencyService.execute(request.requestId(), "CONFIGURE_LEG_OPS", 200,
                payload, LegOpsConfigResponse.class, () -> doConfigureLegOps(legId, request));
    }

    /**
     * 中转到达核对：批量登记行李到达中转点并开启滞留计时（UTC）。
     * 中转点缺配置、行李状态非法、不在该站或无后续航段均 422，任一行李非法整次回滚。
     */
    public TransitArriveResponse transitArrive(String station, TransitArriveRequest request) {
        TransitArrivePayload payload = new TransitArrivePayload(
                station, request.operator(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "TRANSIT_ARRIVE", 200,
                payload, TransitArriveResponse.class, () -> doTransitArrive(station, request));
    }

    /**
     * 滞留超时判定：按可注入时钟计算滞留时长，区间左闭右开 [0, max)，
     * 达到上限的行李转 DWELL_EXCEEDED 并阻断后续装载与改派；已超时重复判定返回 409。
     */
    public DwellTimeoutCheckResponse checkDwellTimeout(String station, DwellTimeoutCheckRequest request) {
        DwellTimeoutPayload payload = new DwellTimeoutPayload(
                station, request.operator(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "DWELL_TIMEOUT_CHECK", 200,
                payload, DwellTimeoutCheckResponse.class, () -> doDwellTimeoutCheck(station, request));
    }

    /**
     * 复核解除：处理人登记复核结论后解除阻断，必须指定下一航段，
     * 解除时重新校验该航段属于行程、装载截止（左闭右开）与剩余容量。
     */
    public ReleaseDwellResponse releaseDwell(String bagTag, ReleaseDwellRequest request) {
        ReleaseDwellPayload payload = new ReleaseDwellPayload(
                bagTag, request.reviewer(), request.conclusion(), request.nextLegId());
        return idempotencyService.execute(request.requestId(), "RELEASE_DWELL", 200,
                payload, ReleaseDwellResponse.class, () -> doReleaseDwell(bagTag, request));
    }

    /**
     * 改派：以 newLegIds 整体替换待乘行程（自 nextLegIndex 起），首段须从当前站点出发。
     * DWELL_EXCEEDED 阻断改派；历史到达/超时/解除证据不改写。
     */
    public RerouteResponse reroute(String bagTag, RerouteRequest request) {
        ReroutePayload payload = new ReroutePayload(bagTag, request.operator(), request.newLegIds());
        return idempotencyService.execute(request.requestId(), "REROUTE", 200,
                payload, RerouteResponse.class, () -> doReroute(bagTag, request));
    }

    /** 行李最新滞留明细查询：只读，不改变任何状态。 */
    public DwellRecordResponse getDwellDetail(String bagTag) {
        DwellRecordRow record = findLatestDwellRecord(bagTag);
        if (record == null) {
            throw ApiException.notFound("行李无滞留记录: " + bagTag);
        }
        return toDwellRecordResponse(record);
    }

    /** 行李滞留历史查询：追加只写事件流，按 seq 稳定升序，只读。 */
    public DwellHistoryResponse getDwellHistory(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<DwellEventItem> events = jdbcTemplate.query(
                "SELECT seq, event_type, station, operator, next_leg_id, detail, event_time"
                        + " FROM dwell_event WHERE bag_tag = ? ORDER BY seq",
                DWELL_EVENT_MAPPER, bagTag);
        return new DwellHistoryResponse(bagTag, events);
    }

    /** 中转点滞留诊断查询：返回上限配置与各状态滞留记录数，只读。 */
    public StationDwellDiagnostics getStationDwellDiagnostics(String station) {
        Long maxDwell = findDwellLimit(station);
        Map<String, Integer> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM dwell_record WHERE station = ? GROUP BY status",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        counts.put(rs.getString("status"), rs.getInt("cnt"));
                    }
                    return null;
                }, station);
        return new StationDwellDiagnostics(station, maxDwell,
                counts.getOrDefault(DWELL_OPEN, 0), counts.getOrDefault(DWELL_EXCEEDED_REC, 0),
                counts.getOrDefault(DWELL_RELEASED, 0), counts.getOrDefault(DWELL_CLOSED, 0));
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
            closeActiveDwellRecord(bag.bagTag());
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
        if (BAG_DWELL_EXCEEDED.equals(bag.status())) {
            // 超时判定先提交（或已存在）则装载冲突，须先复核解除
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 滞留超时阻断（DWELL_EXCEEDED），须复核解除后才能装载");
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

    private DwellConfigResponse doConfigureDwellLimit(String station, ConfigureDwellLimitRequest request) {
        int updated = jdbcTemplate.update(
                "UPDATE dwell_config SET max_dwell_minutes = ? WHERE station = ?",
                request.maxDwellMinutes(), station);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO dwell_config (station, max_dwell_minutes) VALUES (?, ?)",
                    station, request.maxDwellMinutes());
        }
        return new DwellConfigResponse(station, request.maxDwellMinutes());
    }

    private LegOpsConfigResponse doConfigureLegOps(String legId, ConfigureLegOpsRequest request) {
        if (findLeg(legId) == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        Instant deadline = parseUtcInstant(request.loadDeadline(), "loadDeadline");
        int updated = jdbcTemplate.update(
                "UPDATE leg_ops_config SET load_deadline = ?, max_capacity = ? WHERE leg_id = ?",
                OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC), request.maxCapacity(), legId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO leg_ops_config (leg_id, load_deadline, max_capacity) VALUES (?, ?, ?)",
                    legId, OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC), request.maxCapacity());
        }
        return new LegOpsConfigResponse(legId, deadline.toString(), request.maxCapacity());
    }

    private TransitArriveResponse doTransitArrive(String station, TransitArriveRequest request) {
        if (findDwellLimit(station) == null) {
            throw ApiException.unprocessable("中转点 " + station + " 缺少滞留上限配置");
        }
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("批量到达核对的 bagTag 不得重复");
        }
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateTransitArrivable(bag, station);
            bags.add(bag);
        }
        Instant now = clock.get();
        List<TransitArriveItem> items = new ArrayList<>();
        for (BagRow bag : bags) {
            jdbcTemplate.update(
                    "INSERT INTO dwell_record (bag_tag, station, arrived_at, status) VALUES (?, ?, ?, ?)",
                    bag.bagTag(), station, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), DWELL_OPEN);
            insertDwellEvent(bag.bagTag(), DVT_ARRIVED, station, request.operator(), null, null);
            items.add(new TransitArriveItem(bag.bagTag(), now.toString()));
        }
        return new TransitArriveResponse(station, items);
    }

    private void validateTransitArrivable(BagRow bag, String station) {
        if (BAG_DWELL_EXCEEDED.equals(bag.status()) || BAG_SHORT_UNLOADED.equals(bag.status())
                || BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，不可登记中转到达");
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已装载到航段 " + bag.loadedLegId() + "，不可登记中转到达");
        }
        if (!bag.currentLocation().equals(station)) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 当前位于 " + bag.currentLocation() + "，不在中转点 " + station);
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null || !next.origin().equals(station)) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 在中转点 " + station + " 无后续航段，下一航段不属于行程");
        }
        DwellRecordRow latest = findLatestDwellRecord(bag.bagTag());
        if (latest != null && latest.closedAt() == null) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 在中转点 " + latest.station() + " 已有未关闭的滞留记录");
        }
    }

    private DwellTimeoutCheckResponse doDwellTimeoutCheck(String station, DwellTimeoutCheckRequest request) {
        Long maxDwell = findDwellLimit(station);
        if (maxDwell == null) {
            throw ApiException.unprocessable("中转点 " + station + " 缺少滞留上限配置");
        }
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("批量超时判定的 bagTag 不得重复");
        }
        Instant now = clock.get();
        // 第一阶段：锁定并校验全部对象，任一非法则整次回滚，不产生半成品
        List<PendingCheck> pending = new ArrayList<>();
        for (String bagTag : sortedCopy(bagTags)) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            DwellRecordRow record = findLatestDwellRecord(bagTag);
            if (record == null || !record.station().equals(station) || record.arrivedAt() == null) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 在中转点 " + station + " 的到达时刻缺失");
            }
            if (DWELL_EXCEEDED_REC.equals(record.status())) {
                throw ApiException.conflict("行李 " + bagTag + " 已判定超时，不得重复判定");
            }
            if (!DWELL_OPEN.equals(record.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 滞留记录状态为 " + record.status() + "，不可判定超时");
            }
            long dwellMinutes = Duration.between(record.arrivedAt(), now).toMinutes();
            pending.add(new PendingCheck(bagTag, record, dwellMinutes));
        }
        // 第二阶段：全部校验通过后统一落库
        List<DwellCheckItem> items = new ArrayList<>();
        for (PendingCheck check : pending) {
            boolean exceeded = check.dwellMinutes() >= maxDwell;
            if (exceeded) {
                jdbcTemplate.update(
                        "UPDATE dwell_record SET status = ?, exceeded_at = ? WHERE id = ?",
                        DWELL_EXCEEDED_REC, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), check.record().id());
                jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?",
                        BAG_DWELL_EXCEEDED, check.bagTag());
                insertDwellEvent(check.bagTag(), DVT_EXCEEDED, station, request.operator(), null, null);
            }
            items.add(new DwellCheckItem(check.bagTag(), station,
                    exceeded ? DWELL_EXCEEDED_REC : DWELL_OPEN,
                    check.record().arrivedAt().toString(), check.dwellMinutes(), maxDwell,
                    exceeded ? check.dwellMinutes() - maxDwell : null));
        }
        return new DwellTimeoutCheckResponse(station, items);
    }

    private ReleaseDwellResponse doReleaseDwell(String bagTag, ReleaseDwellRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        DwellRecordRow record = findLatestDwellRecord(bagTag);
        if (record == null) {
            throw ApiException.unprocessable("行李 " + bagTag + " 无滞留记录，不可解除");
        }
        if (DWELL_RELEASED.equals(record.status())) {
            throw ApiException.conflict("行李 " + bagTag + " 已解除阻断，不得重复解除");
        }
        if (!DWELL_EXCEEDED_REC.equals(record.status()) || !BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 未处于滞留超时阻断状态，不可解除");
        }
        ItineraryItem next = nextItinerary(bagTag, bag.nextLegIndex());
        if (next == null || !next.legId().equals(request.nextLegId())) {
            throw ApiException.unprocessable(
                    "下一航段 " + request.nextLegId() + " 不属于行李 " + bagTag + " 的行程");
        }
        LegRow leg = findLeg(request.nextLegId());
        if (leg == null) {
            throw ApiException.unprocessable("下一航段不存在: " + request.nextLegId());
        }
        if (!leg.origin().equals(record.station())) {
            throw ApiException.unprocessable(
                    "下一航段 " + request.nextLegId() + " 始发站 " + leg.origin()
                            + " 与滞留站点 " + record.station() + " 不一致");
        }
        LegOpsRow ops = findLegOpsConfig(request.nextLegId());
        if (ops == null) {
            throw ApiException.unprocessable(
                    "下一航段 " + request.nextLegId() + " 缺少装载截止与容量配置");
        }
        Instant now = clock.get();
        if (!now.isBefore(ops.loadDeadline())) {
            throw ApiException.unprocessable(
                    "已超过装载截止时刻: 实际 " + now + "，要求早于 " + ops.loadDeadline());
        }
        int loadCount = countLoads(request.nextLegId());
        if (loadCount >= ops.maxCapacity()) {
            throw ApiException.unprocessable(
                    "下一航段容量不足: 实际已装 " + loadCount + "，要求小于 " + ops.maxCapacity());
        }
        jdbcTemplate.update(
                "UPDATE dwell_record SET status = ?, released_at = ?, reviewer = ?,"
                        + " review_conclusion = ?, next_leg_id = ? WHERE id = ?",
                DWELL_RELEASED, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), request.reviewer(),
                request.conclusion(), request.nextLegId(), record.id());
        jdbcTemplate.update("UPDATE bag SET status = ? WHERE bag_tag = ?", BAG_IN_TRANSIT, bagTag);
        insertDwellEvent(bagTag, DVT_RELEASED, record.station(), request.reviewer(),
                request.nextLegId(), request.conclusion());
        return new ReleaseDwellResponse(bagTag, BAG_IN_TRANSIT, request.nextLegId(),
                now.toString(), request.reviewer());
    }

    private RerouteResponse doReroute(String bagTag, RerouteRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (BAG_DWELL_EXCEEDED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 滞留超时阻断（DWELL_EXCEEDED），须复核解除后才能改派");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status()) || BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 状态为 " + bag.status() + "，不可改派");
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 已装载到航段 " + bag.loadedLegId() + "，不可改派");
        }
        List<String> newLegIds = request.newLegIds();
        if (new HashSet<>(newLegIds).size() != newLegIds.size()) {
            throw ApiException.unprocessable("改派航段不得重复");
        }
        int total = itineraryCount(bagTag);
        int from = bag.nextLegIndex();
        if (from >= total) {
            throw ApiException.unprocessable("行李 " + bagTag + " 已完成全部行程，不可改派");
        }
        if (from + newLegIds.size() > 5) {
            throw ApiException.unprocessable(
                    "改派后行程航段数超限: 实际 " + (from + newLegIds.size()) + "，要求不超过 5");
        }
        List<LegRow> legs = new ArrayList<>();
        for (String legId : newLegIds) {
            LegRow leg = findLeg(legId);
            if (leg == null) {
                throw ApiException.unprocessable("改派引用的航段不存在: " + legId);
            }
            legs.add(leg);
        }
        if (!legs.get(0).origin().equals(bag.currentLocation())) {
            throw ApiException.unprocessable(
                    "改派首段始发站 " + legs.get(0).origin() + " 与行李当前位置 "
                            + bag.currentLocation() + " 不一致");
        }
        for (int i = 0; i + 1 < legs.size(); i++) {
            if (!legs.get(i).destination().equals(legs.get(i + 1).origin())) {
                throw ApiException.unprocessable(
                        "相邻航段首尾站必须衔接: " + legs.get(i).legId() + " -> " + legs.get(i + 1).legId());
            }
        }
        closeActiveDwellRecord(bagTag);
        jdbcTemplate.update("DELETE FROM bag_itinerary WHERE bag_tag = ? AND seq >= ?", bagTag, from);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    bagTag, from + i, leg.legId(), leg.origin(), leg.destination());
        }
        insertEvent(bagTag, EVT_REROUTED, null, bag.currentLocation());
        return new RerouteResponse(bagTag, from, toItinerary(bagTag));
    }

    /** 关闭行李当前未关闭的滞留记录（装载或改派离开中转点），证据字段不改写。 */
    private void closeActiveDwellRecord(String bagTag) {
        DwellRecordRow record = findLatestDwellRecord(bagTag);
        if (record == null || record.closedAt() != null
                || !(DWELL_OPEN.equals(record.status()) || DWELL_RELEASED.equals(record.status()))) {
            return;
        }
        Instant now = clock.get();
        String newStatus = DWELL_OPEN.equals(record.status()) ? DWELL_CLOSED : record.status();
        jdbcTemplate.update("UPDATE dwell_record SET status = ?, closed_at = ? WHERE id = ?",
                newStatus, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), record.id());
        insertDwellEvent(bagTag, DVT_CLOSED, record.station(), null, null, null);
    }

    /** 在持有行李行锁后追加滞留事件，seq 按该行李已有事件数递增。 */
    private void insertDwellEvent(String bagTag, String eventType, String station,
                                  String operator, String nextLegId, String detail) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM dwell_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO dwell_event (bag_tag, seq, event_type, station, operator, next_leg_id,"
                        + " detail, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, station, operator, nextLegId, detail,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private Long findDwellLimit(String station) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT max_dwell_minutes FROM dwell_config WHERE station = ?",
                (rs, rowNum) -> rs.getLong("max_dwell_minutes"), station);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private DwellRecordRow findLatestDwellRecord(String bagTag) {
        List<DwellRecordRow> rows = jdbcTemplate.query(
                "SELECT id, bag_tag, station, arrived_at, status, exceeded_at, released_at,"
                        + " reviewer, review_conclusion, next_leg_id, closed_at"
                        + " FROM dwell_record WHERE bag_tag = ? ORDER BY id DESC LIMIT 1",
                DWELL_RECORD_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegOpsRow findLegOpsConfig(String legId) {
        List<LegOpsRow> rows = jdbcTemplate.query(
                "SELECT leg_id, load_deadline, max_capacity FROM leg_ops_config WHERE leg_id = ?",
                LEG_OPS_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countLoads(String legId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
        return count == null ? 0 : count;
    }

    private DwellRecordResponse toDwellRecordResponse(DwellRecordRow record) {
        return new DwellRecordResponse(record.bagTag(), record.station(), record.status(),
                record.arrivedAt() == null ? null : record.arrivedAt().toString(),
                record.exceededAt() == null ? null : record.exceededAt().toString(),
                record.releasedAt() == null ? null : record.releasedAt().toString(),
                record.reviewer(), record.reviewConclusion(), record.nextLegId(),
                record.closedAt() == null ? null : record.closedAt().toString());
    }

    private static Instant parseUtcInstant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    field + " 须为 ISO-8601 UTC 时刻: " + text);
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

    /** 滞留记录行。 */
    private record DwellRecordRow(long id, String bagTag, String station, Instant arrivedAt,
                                  String status, Instant exceededAt, Instant releasedAt,
                                  String reviewer, String reviewConclusion, String nextLegId,
                                  Instant closedAt) {
    }

    /** 航段运行配置行。 */
    private record LegOpsRow(String legId, Instant loadDeadline, int maxCapacity) {
    }

    /** 超时判定第一阶段暂存：已锁定校验的行李及其滞留时长。 */
    private record PendingCheck(String bagTag, DwellRecordRow record, long dwellMinutes) {
    }

    /** 中转点滞留上限配置幂等摘要参数。 */
    private record ConfigureDwellLimitPayload(String station, String operator, long maxDwellMinutes) {
    }

    /** 航段运行配置幂等摘要参数。 */
    private record ConfigureLegOpsPayload(String legId, String operator,
                                          String loadDeadline, int maxCapacity) {
    }

    /** 中转到达幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record TransitArrivePayload(String station, String operator, List<String> bagTags) {
    }

    /** 超时判定幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DwellTimeoutPayload(String station, String operator, List<String> bagTags) {
    }

    /** 复核解除幂等摘要参数。 */
    private record ReleaseDwellPayload(String bagTag, String reviewer,
                                       String conclusion, String nextLegId) {
    }

    /** 改派幂等摘要参数：newLegIds 为有序行程，顺序差异视为异参。 */
    private record ReroutePayload(String bagTag, String operator, List<String> newLegIds) {
    }
}
