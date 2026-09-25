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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.CutoffExceptionItem;
import com.example.starter.baggage.BaggageDtos.CutoffExceptionListResponse;
import com.example.starter.baggage.BaggageDtos.CutoffResponse;
import com.example.starter.baggage.BaggageDtos.CutoffUpdateRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.LoadStatusResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RerouteRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TraceEvent;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、精确/差异到达、补到、
 * 剩余行程改派、航段截载控制与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载、封舱与截载修改并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 差异到达仅允许 SEALED 航段提交封舱清单子集；缺失行李转 SHORT_UNLOADED 并冻结待乘索引，
 * 补到前无法装载任何后续航段，补到提交后才恢复参与装载。
 * 截载控制：航段可配置 UTC 截载时刻（必须早于起飞时刻），装载/补到时以可注入时钟判定，
 * 当前时刻大于等于截载时刻即 422；截载修改早于既有装载时刻时写入不可变超截载例外清单，
 * 不追溯改变既有装载记录。容器（ULD）同一时间只允许被一个未到达航段占用，航段到达后释放。
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
    private static final String EVT_REROUTED = "REROUTED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"),
            getInstant(rs, "departure_at"), getInstant(rs, "cutoff_at"));

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

    private static final RowMapper<LoadRecordRow> LOAD_RECORD_MAPPER = (rs, rowNum) -> new LoadRecordRow(
            rs.getString("bag_tag"), rs.getString("leg_id"), rs.getString("container_id"),
            rs.getString("operator"), getInstant(rs, "loaded_at"));

    private static final RowMapper<CutoffExceptionItem> CUTOFF_EXCEPTION_MAPPER = (rs, rowNum) ->
            new CutoffExceptionItem(rs.getString("bag_tag"),
                    getInstant(rs, "loaded_at").toString(),
                    getInstant(rs, "cutoff_at").toString(),
                    getInstant(rs, "created_at").toString());

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
        LoadPayload payload = new LoadPayload(legId, request.operator(), request.expectedVersion(),
                sortedCopy(request.bagTags()), request.containerId());
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
     * 配置航段 UTC 截载时刻：必须早于起飞时刻；版本校验后保存并递增版本。
     * 若新截载时刻早于已有装载记录的装载时刻，允许保存但写入不可变超截载例外清单，
     * 不追溯改变既有装载记录。
     */
    public CutoffResponse updateCutoff(String legId, CutoffUpdateRequest request) {
        CutoffPayload payload = new CutoffPayload(legId, request.expectedVersion(),
                request.cutoffAt().toString());
        return idempotencyService.execute(request.requestId(), "UPDATE_CUTOFF", 200,
                payload, CutoffResponse.class, () -> doUpdateCutoff(legId, request));
    }

    /**
     * 剩余行程改派：仅未装载、非短卸、未交付的行李可改派；新剩余行程首段始发站
     * 须等于行李当前所在站且相邻航段衔接。改派后后续装载使用新航段的截载时刻。
     */
    public BagResponse reroute(String bagTag, RerouteRequest request) {
        ReroutePayload payload = new ReroutePayload(bagTag, List.copyOf(request.legIds()));
        return idempotencyService.execute(request.requestId(), "REROUTE", 200,
                payload, BagResponse.class, () -> doReroute(bagTag, request));
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

    /** 航段截载配置查询：返回起飞时刻与当前截载时刻（未配置为 null）。 */
    public CutoffResponse getCutoff(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return toCutoffResponse(leg);
    }

    /** 超截载例外清单查询：按登记顺序返回，清单不可变。 */
    public CutoffExceptionListResponse listCutoffExceptions(String legId) {
        if (findLeg(legId) == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<CutoffExceptionItem> items = jdbcTemplate.query(
                "SELECT bag_tag, loaded_at, cutoff_at, created_at FROM cutoff_exception"
                        + " WHERE leg_id = ? ORDER BY id",
                CUTOFF_EXCEPTION_MAPPER, legId);
        return new CutoffExceptionListResponse(legId, items);
    }

    /** 行李装载状态查询：含交接记录中的容器、操作者、装载时刻及所装航段截载时刻。 */
    public LoadStatusResponse getLoadStatus(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (bag.loadedLegId() == null) {
            return new LoadStatusResponse(bag.bagTag(), bag.status(), bag.currentLocation(),
                    bag.nextLegIndex(), null, null, null, null, null);
        }
        LoadRecordRow record = findLoadRecord(bagTag);
        LegRow loadedLeg = findLeg(bag.loadedLegId());
        return new LoadStatusResponse(bag.bagTag(), bag.status(), bag.currentLocation(),
                bag.nextLegIndex(), bag.loadedLegId(),
                record == null ? null : record.containerId(),
                record == null ? null : record.operator(),
                record == null ? null : record.loadedAt().toString(),
                loadedLeg != null && loadedLeg.cutoffAt() != null ? loadedLeg.cutoffAt().toString() : null);
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, departure_at)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                toUtc(request.departureAt()));
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, request.departureAt().toString(), null);
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
        checkCutoff(leg, "装载");
        occupyContainer(request.containerId(), legId);
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
        OffsetDateTime loadedAt = toUtc(clock.get());
        for (BagRow bag : bags) {
            jdbcTemplate.update(
                    "INSERT INTO load_record (bag_tag, leg_id, container_id, operator, loaded_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    bag.bagTag(), legId, request.containerId(), request.operator(), loadedAt);
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
        jdbcTemplate.update("DELETE FROM container_occupancy WHERE leg_id = ?", legId);
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
        jdbcTemplate.update("DELETE FROM container_occupancy WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_DIFF, writeJson(arrivedSorted), legId);
        return new DifferenceArriveResponse(legId, LEG_ARRIVED, newVersion, arrivedSorted, shortList);
    }

    private RecoverResponse doRecover(RecoverRequest request) {
        BagRow snapshot = findBag(request.bagTag());
        if (snapshot == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        // 与批量装载保持“先航段后行李”的加锁顺序，避免并发死锁；
        // 短卸行李锁定其缺失航段，其余行李按提交的缺失航段锁定
        String legIdToLock = snapshot.shortLegId() != null ? snapshot.shortLegId() : request.missingLegId();
        LegRow lockedLeg = lockLeg(legIdToLock);
        BagRow bag = lockBag(request.bagTag());
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
        LegRow missingLeg = lockedLeg;
        if (!missingLeg.destination().equals(request.actualStation())) {
            throw ApiException.unprocessable(
                    "实际到站 " + request.actualStation() + " 与缺失航段应到站 "
                            + missingLeg.destination() + " 不一致");
        }
        checkCutoff(missingLeg, "补到");
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

    private CutoffResponse doUpdateCutoff(String legId, CutoffUpdateRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        Instant cutoff = request.cutoffAt();
        if (!cutoff.isBefore(leg.departureAt())) {
            throw ApiException.unprocessable(
                    "截载时刻 " + cutoff + " 必须早于起飞时刻 " + leg.departureAt());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET cutoff_at = ?, version = ? WHERE leg_id = ?",
                toUtc(cutoff), newVersion, legId);
        // 新截载时刻早于已有装载记录的装载时刻：允许保存，但逐件写入不可变超截载例外清单
        List<LoadRecordRow> records = jdbcTemplate.query(
                "SELECT bag_tag, leg_id, container_id, operator, loaded_at FROM load_record"
                        + " WHERE leg_id = ? ORDER BY bag_tag",
                LOAD_RECORD_MAPPER, legId);
        OffsetDateTime now = toUtc(clock.get());
        for (LoadRecordRow record : records) {
            if (record.loadedAt().isAfter(cutoff)) {
                Integer existing = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM cutoff_exception"
                                + " WHERE leg_id = ? AND bag_tag = ? AND cutoff_at = ?",
                        Integer.class, legId, record.bagTag(), toUtc(cutoff));
                if (existing == null || existing == 0) {
                    jdbcTemplate.update(
                            "INSERT INTO cutoff_exception (leg_id, bag_tag, loaded_at, cutoff_at, created_at)"
                                    + " VALUES (?, ?, ?, ?, ?)",
                            legId, record.bagTag(), toUtc(record.loadedAt()), toUtc(cutoff), now);
                }
            }
        }
        return toCutoffResponse(lockLeg(legId));
    }

    private BagResponse doReroute(String bagTag, RerouteRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 处于短卸状态，须先补到才能改派");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 已完成全部行程，不得改派");
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 已装载到航段 " + bag.loadedLegId() + "，不得改派");
        }
        List<String> legIds = request.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw ApiException.unprocessable("改派航段不得重复");
        }
        List<LegRow> legs = new ArrayList<>();
        for (String legId : legIds) {
            LegRow leg = findLeg(legId);
            if (leg == null) {
                throw ApiException.unprocessable("改派航段不存在: " + legId);
            }
            legs.add(leg);
        }
        if (!legs.get(0).origin().equals(bag.currentLocation())) {
            throw ApiException.unprocessable(
                    "改派首段始发站 " + legs.get(0).origin() + " 必须等于行李当前所在站 " + bag.currentLocation());
        }
        for (int i = 0; i + 1 < legs.size(); i++) {
            if (!legs.get(i).destination().equals(legs.get(i + 1).origin())) {
                throw ApiException.unprocessable(
                        "相邻航段首尾站必须衔接: " + legs.get(i).legId() + " -> " + legs.get(i + 1).legId());
            }
        }
        jdbcTemplate.update("DELETE FROM bag_itinerary WHERE bag_tag = ? AND seq >= ?",
                bagTag, bag.nextLegIndex());
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    bagTag, bag.nextLegIndex() + i, leg.legId(), leg.origin(), leg.destination());
        }
        insertEvent(bagTag, EVT_REROUTED, null, bag.currentLocation());
        return toBagResponse(findBag(bagTag));
    }

    /** 截载判定：当前时刻大于等于截载时刻即 422，并给出截载时刻。 */
    private void checkCutoff(LegRow leg, String action) {
        if (leg.cutoffAt() != null && !clock.get().isBefore(leg.cutoffAt())) {
            throw ApiException.unprocessable(
                    "航段 " + leg.legId() + " 已过截载时刻 " + leg.cutoffAt() + "，禁止" + action);
        }
    }

    /** 容器占用：同一容器同一时间只允许被一个未到达航段占用；整批回滚时占用记录随事务回滚。 */
    private void occupyContainer(String containerId, String legId) {
        List<String> occupants = jdbcTemplate.queryForList(
                "SELECT leg_id FROM container_occupancy WHERE container_id = ?", String.class, containerId);
        if (!occupants.isEmpty()) {
            if (!occupants.get(0).equals(legId)) {
                throw ApiException.unprocessable(
                        "容器 " + containerId + " 已被航段 " + occupants.get(0) + " 占用");
            }
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO container_occupancy (container_id, leg_id, occupied_at) VALUES (?, ?, ?)",
                    containerId, legId, toUtc(clock.get()));
        } catch (DuplicateKeyException duplicate) {
            // 并发占用同一容器：以先提交者为准复核
            List<String> winners = jdbcTemplate.queryForList(
                    "SELECT leg_id FROM container_occupancy WHERE container_id = ?", String.class, containerId);
            if (winners.isEmpty() || !winners.get(0).equals(legId)) {
                throw ApiException.unprocessable(
                        "容器 " + containerId + " 已被航段 " + (winners.isEmpty() ? "其他" : winners.get(0)) + " 占用");
            }
        }
    }

    private LoadRecordRow findLoadRecord(String bagTag) {
        List<LoadRecordRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, leg_id, container_id, operator, loaded_at FROM load_record WHERE bag_tag = ?",
                LOAD_RECORD_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CutoffResponse toCutoffResponse(LegRow leg) {
        return new CutoffResponse(leg.legId(), leg.status(), leg.version(),
                leg.departureAt().toString(),
                leg.cutoffAt() == null ? null : leg.cutoffAt().toString());
    }

    private static OffsetDateTime toUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
                + " arrival_type, arrival_actual, departure_at, cutoff_at FROM leg WHERE leg_id = ?"
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
                          String arrivalType, String arrivalActual,
                          Instant departureAt, Instant cutoffAt) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 交接记录行：loaded_at 为不可变原装载时刻（UTC）。 */
    private record LoadRecordRow(String bagTag, String legId, String containerId,
                                 String operator, Instant loadedAt) {
    }

    /** 装载幂等摘要参数：含操作者、航段版本、规范化（排序）行李集合和容器；bagTags 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, String operator, int expectedVersion,
                               List<String> bagTags, String containerId) {
    }

    /** 截载配置幂等摘要参数。 */
    private record CutoffPayload(String legId, int expectedVersion, String cutoffAt) {
    }

    /** 剩余行程改派幂等摘要参数：legIds 为有序行程，顺序差异视为异参。 */
    private record ReroutePayload(String bagTag, List<String> legIds) {
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
