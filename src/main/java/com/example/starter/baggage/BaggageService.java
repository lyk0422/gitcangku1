package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.example.starter.baggage.BaggageDtos.ArrivalItem;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.DwellCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellCheckResponse;
import com.example.starter.baggage.BaggageDtos.DwellHistoryItem;
import com.example.starter.baggage.BaggageDtos.DwellHistoryResponse;
import com.example.starter.baggage.BaggageDtos.DwellItem;
import com.example.starter.baggage.BaggageDtos.DwellListResponse;
import com.example.starter.baggage.BaggageDtos.DwellReleaseRequest;
import com.example.starter.baggage.BaggageDtos.DwellReleaseResponse;
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
import com.example.starter.baggage.BaggageDtos.RegisterTransitPointRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TransitArrivalRequest;
import com.example.starter.baggage.BaggageDtos.TransitArrivalResponse;
import com.example.starter.baggage.BaggageDtos.TransitPointResponse;
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
    private static final String BAG_DWELL_EXCEEDED = "DWELL_EXCEEDED";
    private static final String BAG_DELIVERED = "DELIVERED";

    private static final String DWELL_ACTIVE = "ACTIVE";
    private static final String DWELL_EXCEEDED = "EXCEEDED";
    private static final String DWELL_RELEASED = "RELEASED";

    private static final String DWELL_EVT_ARRIVED = "ARRIVED";
    private static final String DWELL_EVT_TIMEOUT = "TIMEOUT";
    private static final String DWELL_EVT_RELEASED = "RELEASED";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DWELL_ARRIVED = "DWELL_ARRIVED";
    private static final String EVT_DWELL_EXCEEDED = "DWELL_EXCEEDED";
    private static final String EVT_DWELL_RELEASED = "DWELL_RELEASED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"),
            (Integer) rs.getObject("capacity"), getInstant(rs, "load_cutoff"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"),
            rs.getString("dwell_state"), rs.getString("dwell_station"),
            getInstant(rs, "dwell_started_at"), (Integer) rs.getObject("dwell_limit_minutes"),
            getInstant(rs, "dwell_exceeded_at"), rs.getString("dwell_next_leg_id"),
            rs.getString("dwell_prev_status"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<DwellItem> DWELL_MAPPER = (rs, rowNum) -> {
        Instant now = getInstant(rs, "now_instant");
        Instant startedAt = getInstant(rs, "dwell_started_at");
        int limit = rs.getInt("dwell_limit_minutes");
        Instant deadline = startedAt.plusSeconds(60L * limit);
        String state = rs.getString("dwell_state");
        Instant exceededAt = getInstant(rs, "dwell_exceeded_at");
        Long elapsed = null;
        Long remaining = null;
        Long excess = null;
        if (DWELL_ACTIVE.equals(state)) {
            elapsed = Math.max(0, java.time.Duration.between(startedAt, now).toMinutes());
            remaining = Math.max(0, java.time.Duration.between(now, deadline).toMinutes());
        } else if (DWELL_EXCEEDED.equals(state)) {
            elapsed = Math.max(0, java.time.Duration.between(startedAt, now).toMinutes());
            excess = Math.max(0, elapsed - limit);
        }
        return new DwellItem(rs.getString("bag_tag"), rs.getString("dwell_station"), state,
                startedAt.toString(), limit, deadline.toString(),
                exceededAt == null ? null : exceededAt.toString(),
                elapsed, remaining, excess);
    };

    private static final RowMapper<DwellHistoryItem> DWELL_HISTORY_MAPPER = (rs, rowNum) -> {
        Instant arrivedAt = getInstant(rs, "arrived_at");
        Instant deadline = getInstant(rs, "deadline");
        Instant eventTime = getInstant(rs, "event_time");
        return new DwellHistoryItem(
                rs.getInt("seq"), rs.getString("event_type"), rs.getString("station"),
                arrivedAt == null ? null : arrivedAt.toString(),
                (Integer) rs.getObject("limit_minutes"),
                deadline == null ? null : deadline.toString(),
                eventTime.toString(),
                (Long) rs.getObject("elapsed_minutes"),
                (Long) rs.getObject("excess_minutes"),
                rs.getString("next_leg_id"), rs.getString("reviewer"),
                rs.getString("review_conclusion"));
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

    /** 登记中转点最大滞留分钟数（正整数）；station 唯一。 */
    public TransitPointResponse registerTransitPoint(RegisterTransitPointRequest request) {
        RegisterTransitPointPayload payload = new RegisterTransitPointPayload(
                request.operator(), request.station(), request.maxDwellMinutes());
        return idempotencyService.execute(request.requestId(), "REGISTER_TRANSIT_POINT", 201,
                payload, TransitPointResponse.class, () -> doRegisterTransitPoint(request));
    }

    /**
     * 批量中转到达核对：先完整校验所有到达项（站点缺配置、行李状态非法、到达点不一致、
     * 重复登记），再在同一事务写入；任一项非法整次回滚，无半成品。
     */
    public TransitArrivalResponse transitArrival(TransitArrivalRequest request) {
        List<ArrivalItem> normalized = request.arrivals().stream()
                .sorted(Comparator.comparing(ArrivalItem::bagTag)).toList();
        TransitArrivalPayload payload = new TransitArrivalPayload(request.operator(), normalized);
        return idempotencyService.execute(request.requestId(), "TRANSIT_ARRIVAL", 200,
                payload, TransitArrivalResponse.class,
                () -> doTransitArrival(normalized));
    }

    /**
     * 批量超时判定：按注入时钟对 ACTIVE 行李判定，now &gt;= arrivedAt+limit（左闭右开）即转
     * DWELL_EXCEEDED 并追加不可变超时证据。显式 bagTags 中任一状态非法整次回滚；
     * bagTags 为空时判定指定 station 下全部 ACTIVE 行李。
     */
    public DwellCheckResponse dwellCheck(DwellCheckRequest request) {
        List<String> normalized = request.bagTags() == null
                ? null
                : request.bagTags().stream().sorted().toList();
        DwellCheckPayload payload = new DwellCheckPayload(
                request.operator(), request.station(), normalized);
        return idempotencyService.execute(request.requestId(), "DWELL_CHECK", 200,
                payload, DwellCheckResponse.class,
                () -> doDwellCheck(request.station(), normalized));
    }

    /**
     * 处理人复核解除阻断：仅 EXCEEDED 行李可解除，必须指定属于本行程的当前待乘下一航段，
     * 解除时重新校验航段状态、截载时刻与容量；解除后状态恢复为进入滞留前状态，
     * 超时证据保留不改写，之后只能装载所指定的下一航段。
     */
    public DwellReleaseResponse dwellRelease(DwellReleaseRequest request) {
        DwellReleasePayload payload = new DwellReleasePayload(
                request.operator(), request.bagTag(), request.nextLegId(), request.reviewConclusion());
        return idempotencyService.execute(request.requestId(), "DWELL_RELEASE", 200,
                payload, DwellReleaseResponse.class, () -> doDwellRelease(request));
    }

    /** 滞留明细/诊断查询：station 为空返回全部当前滞留访问，按 station、bagTag 稳定排序。 */
    public DwellListResponse listDwell(String station) {
        List<DwellItem> rows;
        OffsetDateTime nowParam = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        if (station == null || station.isBlank()) {
            rows = jdbcTemplate.query(dwellSelect(false), DWELL_MAPPER, nowParam);
        } else {
            rows = jdbcTemplate.query(dwellSelect(true), DWELL_MAPPER, nowParam, station);
        }
        return new DwellListResponse(station, rows);
    }

    /** 单行李滞留历史证据查询：只追加记录按 seq、id 升序，字段与 null 语义稳定。 */
    public DwellHistoryResponse getDwellHistory(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<DwellHistoryItem> events = jdbcTemplate.query(
                "SELECT seq, event_type, station, arrived_at, limit_minutes, deadline,"
                        + " event_time, elapsed_minutes, excess_minutes, next_leg_id,"
                        + " reviewer, review_conclusion FROM dwell_event"
                        + " WHERE bag_tag = ? ORDER BY seq, id",
                DWELL_HISTORY_MAPPER, bagTag);
        return new DwellHistoryResponse(bagTag, events);
    }

    private TransitPointResponse doRegisterTransitPoint(RegisterTransitPointRequest request) {
        if (findTransitPoint(request.station()) != null) {
            throw ApiException.conflict("中转点配置已存在: " + request.station());
        }
        jdbcTemplate.update(
                "INSERT INTO transit_point (station, max_dwell_minutes) VALUES (?, ?)",
                request.station(), request.maxDwellMinutes());
        return new TransitPointResponse(request.station(), request.maxDwellMinutes());
    }

    private TransitArrivalResponse doTransitArrival(List<ArrivalItem> items) {
        Set<String> seen = new HashSet<>();
        for (ArrivalItem item : items) {
            if (!seen.add(item.bagTag())) {
                throw ApiException.unprocessable("批量到达核对中行李重复: " + item.bagTag());
            }
        }
        Instant now = clock.get();
        List<BagRow> bags = new ArrayList<>();
        // 先锁定并完整校验全部行李，再统一写入
        for (ArrivalItem item : items) {
            BagRow bag = lockBag(item.bagTag());
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + item.bagTag());
            }
            if (bag.loadedLegId() != null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 已在航段 " + bag.loadedLegId() + " 上，未到达中转站");
            }
            if (BAG_SHORT_UNLOADED.equals(bag.status()) || BAG_DWELL_EXCEEDED.equals(bag.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，不能登记中转到达");
            }
            if (bag.dwellState() != null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 已在中转点 " + bag.dwellStation() + " 登记到达，不得重复登记");
            }
            if (!bag.currentLocation().equals(item.station())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 当前位于 " + bag.currentLocation()
                                + "，与到达中转站 " + item.station() + " 不一致");
            }
            if (bag.nextLegIndex() < 1) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 尚未完成任何航段，" + item.station() + " 不是中转到达站");
            }
            ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
            if (next == null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 已完成全部行程，不存在待乘中转航段");
            }
            Integer maxDwell = findTransitPoint(item.station());
            if (maxDwell == null) {
                throw ApiException.unprocessable("中转点缺少滞留配置: " + item.station());
            }
            bags.add(bag);
        }
        List<String> arrivedTags = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ArrivalItem item = items.get(i);
            BagRow bag = bags.get(i);
            int limit = findTransitPoint(item.station());
            Instant deadline = now.plusSeconds(60L * limit);
            jdbcTemplate.update(
                    "UPDATE bag SET dwell_state = ?, dwell_station = ?, dwell_started_at = ?,"
                            + " dwell_limit_minutes = ?, dwell_exceeded_at = NULL,"
                            + " dwell_next_leg_id = NULL, dwell_prev_status = ? WHERE bag_tag = ?",
                    DWELL_ACTIVE, item.station(), utc(now), limit, bag.status(), bag.bagTag());
            insertDwellEvent(bag.bagTag(), DWELL_EVT_ARRIVED, item.station(), now,
                    limit, deadline, now, null, null, null, null, null);
            insertEvent(bag.bagTag(), EVT_DWELL_ARRIVED, null, item.station());
            arrivedTags.add(bag.bagTag());
        }
        return new TransitArrivalResponse(arrivedTags, arrivedTags.size());
    }

    private DwellCheckResponse doDwellCheck(String station, List<String> bagTags) {
        Instant now = clock.get();
        List<BagRow> candidates = new ArrayList<>();
        if (bagTags != null) {
            Set<String> seen = new HashSet<>();
            for (String bagTag : bagTags) {
                if (!seen.add(bagTag)) {
                    throw ApiException.unprocessable("超时判定中行李重复: " + bagTag);
                }
                BagRow bag = lockBag(bagTag);
                if (bag == null) {
                    throw ApiException.unprocessable("行李不存在: " + bagTag);
                }
                if (!DWELL_ACTIVE.equals(bag.dwellState())) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 滞留状态为 " + bag.dwellState()
                                    + "，不在等待中，不能判定超时");
                }
                if (station != null && !station.isBlank() && !station.equals(bag.dwellStation())) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 所在中转点为 " + bag.dwellStation()
                                    + "，与请求站点 " + station + " 不一致");
                }
                candidates.add(bag);
            }
        } else {
            if (station == null || station.isBlank()) {
                throw ApiException.unprocessable("超时判定必须指定 station 或显式 bagTags");
            }
            List<String> activeTags = jdbcTemplate.queryForList(
                    "SELECT bag_tag FROM bag WHERE dwell_state = ? AND dwell_station = ?"
                            + " ORDER BY bag_tag",
                    String.class, DWELL_ACTIVE, station);
            for (String bagTag : activeTags) {
                candidates.add(lockBag(bagTag));
            }
        }
        List<String> exceeded = new ArrayList<>();
        for (BagRow bag : candidates) {
            Instant deadline = bag.dwellStartedAt().plusSeconds(60L * bag.dwellLimitMinutes());
            // 左闭右开：now >= deadline 即超时
            if (!now.isBefore(deadline)) {
                long elapsed = durationMinutes(bag.dwellStartedAt(), now);
                long excess = Math.max(0, elapsed - bag.dwellLimitMinutes());
                jdbcTemplate.update(
                        "UPDATE bag SET status = ?, dwell_state = ?, dwell_exceeded_at = ?"
                                + " WHERE bag_tag = ?",
                        BAG_DWELL_EXCEEDED, DWELL_EXCEEDED, utc(now), bag.bagTag());
                insertDwellEvent(bag.bagTag(), DWELL_EVT_TIMEOUT, bag.dwellStation(),
                        bag.dwellStartedAt(), bag.dwellLimitMinutes(), deadline, now,
                        elapsed, excess, null, null, null);
                insertEvent(bag.bagTag(), EVT_DWELL_EXCEEDED, null, bag.dwellStation());
                exceeded.add(bag.bagTag());
            }
        }
        String resultStation = station == null || station.isBlank()
                ? (candidates.isEmpty() ? null : candidates.get(0).dwellStation())
                : station;
        return new DwellCheckResponse(resultStation, candidates.size(), exceeded, exceeded.size());
    }

    private DwellReleaseResponse doDwellRelease(DwellReleaseRequest request) {
        Instant now = clock.get();
        // 锁顺序与装载一致：先航段后行李，避免与并发装载/超时判定形成环；
        // 不存在的下一航段归入 422（不属于行程），行李不存在在持锁后返回 404。
        if (findLeg(request.nextLegId()) == null) {
            throw ApiException.unprocessable("下一航段不存在: " + request.nextLegId());
        }
        LegRow leg = lockLeg(request.nextLegId());
        BagRow bag = lockBag(request.bagTag());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        if (!DWELL_EXCEEDED.equals(bag.dwellState())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 滞留状态为 " + bag.dwellState()
                            + "，未处于超时阻断，不能解除");
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null || !next.legId().equals(request.nextLegId())) {
            throw ApiException.unprocessable(
                    "航段 " + request.nextLegId() + " 不属于行李 " + bag.bagTag()
                            + " 的当前待乘行程，不能作为下一航段");
        }
        if (!leg.origin().equals(bag.dwellStation())) {
            throw ApiException.unprocessable(
                    "下一航段始发站 " + leg.origin() + " 与滞留中转点 "
                            + bag.dwellStation() + " 不一致");
        }
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable(
                    "下一航段状态为 " + leg.status() + "，无法承接解除阻断后的装载");
        }
        if (leg.loadCutoff() != null && !now.isBefore(leg.loadCutoff())) {
            throw ApiException.unprocessable(
                    "已过下一航段 " + leg.legId() + " 截载时刻 " + leg.loadCutoff()
                            + "（UTC），当前 " + now + "，差额 "
                            + durationMinutes(leg.loadCutoff(), now) + " 分钟，不能解除阻断");
        }
        if (leg.capacity() != null) {
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, leg.legId());
            int loadedCount = current == null ? 0 : current;
            if (loadedCount + 1 > leg.capacity()) {
                throw ApiException.unprocessable(
                        "下一航段 " + leg.legId() + " 容量不足：要求 " + leg.capacity()
                                + " 件，已装 " + loadedCount + " 件，再装 1 件将超出 "
                                + (loadedCount + 1 - leg.capacity()) + " 件，不能解除阻断");
            }
        }
        Instant deadline = bag.dwellStartedAt().plusSeconds(60L * bag.dwellLimitMinutes());
        long elapsed = durationMinutes(bag.dwellStartedAt(), now);
        long excess = Math.max(0, elapsed - bag.dwellLimitMinutes());
        String restoredStatus = bag.dwellPrevStatus() == null ? BAG_IN_TRANSIT : bag.dwellPrevStatus();
        jdbcTemplate.update(
                "UPDATE bag SET status = ?, dwell_state = ?, dwell_exceeded_at = NULL,"
                        + " dwell_next_leg_id = ? WHERE bag_tag = ?",
                restoredStatus, DWELL_RELEASED, leg.legId(), bag.bagTag());
        insertDwellEvent(bag.bagTag(), DWELL_EVT_RELEASED, bag.dwellStation(),
                bag.dwellStartedAt(), bag.dwellLimitMinutes(), deadline, now,
                elapsed, excess, leg.legId(), request.operator(), request.reviewConclusion());
        insertEvent(bag.bagTag(), EVT_DWELL_RELEASED, leg.legId(), bag.dwellStation());
        return new DwellReleaseResponse(bag.bagTag(), restoredStatus, bag.dwellStation(),
                leg.legId(), request.operator(), now.toString(),
                elapsed, bag.dwellLimitMinutes(), excess);
    }

    private Integer findTransitPoint(String station) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT max_dwell_minutes FROM transit_point WHERE station = ?",
                (rs, rowNum) -> rs.getInt("max_dwell_minutes"), station);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String dwellSelect(boolean withStation) {
        // DWELL_* 诊断字段由时钟参数 ? 与库内到达时刻实时计算
        return "SELECT bag_tag, dwell_station, dwell_state, dwell_started_at, dwell_limit_minutes,"
                + " dwell_exceeded_at, ? AS now_instant FROM bag WHERE dwell_state IS NOT NULL"
                + (withStation ? " AND dwell_station = ?" : "")
                + " ORDER BY dwell_station, bag_tag";
    }

    private void insertDwellEvent(String bagTag, String eventType, String station,
                                  Instant arrivedAt, Integer limitMinutes, Instant deadline,
                                  Instant eventTime, Long elapsedMinutes, Long excessMinutes,
                                  String nextLegId, String reviewer, String reviewConclusion) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM dwell_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO dwell_event (bag_tag, seq, event_type, station, arrived_at,"
                        + " limit_minutes, deadline, event_time, elapsed_minutes, excess_minutes,"
                        + " next_leg_id, reviewer, review_conclusion)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, station, utc(arrivedAt), limitMinutes, utc(deadline),
                utc(eventTime), elapsedMinutes, excessMinutes, nextLegId, reviewer, reviewConclusion);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long durationMinutes(Instant from, Instant to) {
        return java.time.Duration.between(from, to).toMinutes();
    }

    private Instant parseCutoff(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ex) {
                throw ApiException.unprocessable("loadCutoff 必须为 ISO-8601 UTC 时刻: " + value);
            }
        }
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        Instant cutoff = parseCutoff(request.loadCutoff());
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, capacity, load_cutoff)"
                        + " VALUES (?, ?, ?, ?, 1, ?, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                request.capacity(), cutoff == null ? null : OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        return new LegResponse(request.legId(), request.origin(), request.destination(), LEG_OPEN, 1,
                request.capacity(), cutoff == null ? null : cutoff.toString());
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
        Instant now = clock.get();
        // 截载区间左闭右开：now >= cutoff 即禁止装载
        if (leg.loadCutoff() != null && !now.isBefore(leg.loadCutoff())) {
            throw ApiException.unprocessable(
                    "已过航段 " + legId + " 截载时刻 " + leg.loadCutoff() + "（UTC），当前 " + now
                            + "，差额 " + durationMinutes(leg.loadCutoff(), now) + " 分钟");
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
        // 容量：装载后总数不得超过上限（实际值、要求值、差额）
        if (leg.capacity() != null) {
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
            int loadedCount = current == null ? 0 : current;
            int afterLoad = loadedCount + bags.size();
            if (afterLoad > leg.capacity()) {
                throw ApiException.unprocessable(
                        "航段 " + legId + " 容量不足：要求 " + leg.capacity() + " 件，已装 "
                                + loadedCount + " 件，本批 " + bags.size() + " 件，装载后 "
                                + afterLoad + " 件，超出 " + (afterLoad - leg.capacity()) + " 件");
            }
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            // 解除阻断后离站：清空当前滞留访问状态，历史证据保留在 dwell_event
            if (bag.dwellState() != null) {
                jdbcTemplate.update(
                        "UPDATE bag SET loaded_leg_id = ?, dwell_state = NULL, dwell_station = NULL,"
                                + " dwell_started_at = NULL, dwell_limit_minutes = NULL,"
                                + " dwell_exceeded_at = NULL, dwell_next_leg_id = NULL,"
                                + " dwell_prev_status = NULL WHERE bag_tag = ?",
                        legId, bag.bagTag());
            } else {
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            }
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
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能装载后续航段");
        }
        if (DWELL_EXCEEDED.equals(bag.dwellState())) {
            // 超时判定先提交：后续装载按冲突阻断（409），须处理人复核解除
            throw ApiException.conflict(
                    "行李 " + bag.bagTag() + " 在中转点 " + bag.dwellStation()
                            + " 滞留超时已阻断，须登记复核解除后才能装载到 " + leg.legId());
        }
        if (DWELL_RELEASED.equals(bag.dwellState())
                && !leg.legId().equals(bag.dwellNextLegId())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已解除阻断并指定下一航段 "
                            + bag.dwellNextLegId() + "，不能装载到 " + leg.legId());
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
                + " arrival_type, arrival_actual, capacity, load_cutoff FROM leg WHERE leg_id = ?"
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
                + " dwell_state, dwell_station, dwell_started_at, dwell_limit_minutes,"
                + " dwell_exceeded_at, dwell_next_leg_id, dwell_prev_status FROM bag WHERE bag_tag = ?"
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
                          String arrivalType, String arrivalActual,
                          Integer capacity, Instant loadCutoff) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt,
                          String dwellState, String dwellStation, Instant dwellStartedAt,
                          Integer dwellLimitMinutes, Instant dwellExceededAt,
                          String dwellNextLegId, String dwellPrevStatus) {
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

    /** 中转点配置幂等摘要参数。 */
    private record RegisterTransitPointPayload(String operator, String station, int maxDwellMinutes) {
    }

    /** 批量到达核对幂等摘要参数：到达项已按 bagTag 排序，集合为 JSON 数组不拼接分隔符。 */
    private record TransitArrivalPayload(String operator, List<ArrivalItem> arrivals) {
    }

    /** 超时判定幂等摘要参数：bagTags 已排序；为空（按站点全量判定）时为 null。 */
    private record DwellCheckPayload(String operator, String station, List<String> bagTags) {
    }

    /** 解除阻断幂等摘要参数：含操作者、行李、指定下一航段与复核结论。 */
    private record DwellReleasePayload(String operator, String bagTag, String nextLegId,
                                      String reviewConclusion) {
    }
}
