package com.example.starter.baggage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DiscrepancyArriveRequest;
import com.example.starter.baggage.BaggageDtos.DiscrepancyArriveResponse;
import com.example.starter.baggage.BaggageDtos.DiscrepancySnapshotResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
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
import com.example.starter.baggage.BaggageDtos.ShortUnloadedItem;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、到达确认与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 */
@Service
public class BaggageService {

    private static final String LEG_OPEN = "OPEN";
    private static final String LEG_SEALED = "SEALED";
    private static final String LEG_ARRIVED = "ARRIVED";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_SHORT_UNLOADED = "SHORT_UNLOADED";
    private static final String BAG_RECOVERED = "RECOVERED";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String MODE_EXACT = "EXACT";
    private static final String MODE_DISCREPANCY = "DISCREPANCY";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_mode"), rs.getString("actual_arrivals"),
            rs.getString("short_manifest"), rs.getObject("arrived_at", LocalDateTime.class));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            rs.getObject("short_registered_at", LocalDateTime.class));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
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
     * 差异到达：仅 SEALED 航段可提交；实际袋号不得重复且为封舱清单子集，
     * 实际到达者推进，缺失者转 SHORT_UNLOADED 并登记缺失航段/应到站/UTC 时刻。
     */
    public DiscrepancyArriveResponse arriveDiscrepancy(String legId, DiscrepancyArriveRequest request) {
        DiscrepancyPayload payload = new DiscrepancyPayload(
                legId, request.expectedVersion(), sortedCopy(request.actualBagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE_DISCREPANCY", 200,
                payload, DiscrepancyArriveResponse.class, () -> doArriveDiscrepancy(legId, request));
    }

    /**
     * 补到：仅 SHORT_UNLOADED 且缺失航段匹配的行李可补到；实际站须等于该航段到达站。
     * 成功后移动行李并推进待乘索引，标记 RECOVERED；完成行程者转 DELIVERED。
     */
    public RecoverResponse recover(String bagTag, RecoverRequest request) {
        RecoverPayload payload = new RecoverPayload(
                bagTag, request.missingLegId(), request.actualStation());
        return idempotencyService.execute(request.requestId(), "RECOVER", 200,
                payload, RecoverResponse.class, () -> doRecover(bagTag, request));
    }

    /** 航段差异快照查询。 */
    public DiscrepancySnapshotResponse getDiscrepancySnapshot(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        if (leg.arrivalMode() == null) {
            return new DiscrepancySnapshotResponse(leg.legId(), leg.status(), leg.version(),
                    null, manifest, null, null, null);
        }
        return new DiscrepancySnapshotResponse(leg.legId(), leg.status(), leg.version(),
                leg.arrivalMode(), manifest, readJsonList(leg.actualArrivals()),
                readJsonList(leg.shortManifest()), toUtcInstant(leg.arrivedAt()));
    }

    /** 未补到清单查询（全局，按短卸登记时刻、袋号排序）。 */
    public List<ShortUnloadedItem> listShortUnloaded() {
        return jdbcTemplate.query(
                "SELECT bag_tag, short_leg_id, short_destination, short_registered_at FROM bag"
                        + " WHERE status = ? ORDER BY short_registered_at, bag_tag",
                (rs, rowNum) -> new ShortUnloadedItem(rs.getString("bag_tag"),
                        rs.getString("short_leg_id"), rs.getString("short_destination"),
                        toUtcInstant(rs.getObject("short_registered_at", LocalDateTime.class))),
                BAG_SHORT_UNLOADED);
    }

    /** 行李轨迹查询。 */
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
        jdbcTemplate.update(
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status) VALUES (?, ?, 0, ?)",
                request.bagTag(), legs.get(0).origin(), BAG_IN_TRANSIT);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    request.bagTag(), i, leg.legId(), leg.origin(), leg.destination());
        }
        insertTrace(request.bagTag(), "REGISTERED", null, legs.get(0).origin());
        return new BagResponse(request.bagTag(), legs.get(0).origin(), 0, BAG_IN_TRANSIT, null,
                toItinerary(request.bagTag()), toTrace(request.bagTag()));
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
            // 补后续运：装载后恢复在途状态；其余行李状态（IN_TRANSIT）不变
            jdbcTemplate.update(
                    "UPDATE bag SET loaded_leg_id = ?, status = CASE WHEN status = ? THEN ? ELSE status END"
                            + " WHERE bag_tag = ?",
                    legId, BAG_RECOVERED, BAG_IN_TRANSIT, bag.bagTag());
            insertTrace(bag.bagTag(), "LOADED", legId, leg.origin());
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
        List<String> sortedActual = sortedCopy(actual);
        LocalDateTime arrivedAt = nowUtc();
        for (String bagTag : manifest) {
            BagRow bag = lockBag(bagTag);
            int nextIndex = bag.nextLegIndex() + 1;
            int total = itineraryCount(bagTag);
            boolean finished = nextIndex >= total;
            String status = finished ? BAG_DELIVERED : BAG_IN_TRANSIT;
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                            + " short_leg_id = NULL, short_destination = NULL"
                            + " WHERE bag_tag = ?",
                    leg.destination(), nextIndex, status, bagTag);
            insertTrace(bagTag, "ARRIVED", legId, leg.destination());
            if (finished) {
                insertTrace(bagTag, "DELIVERED", legId, leg.destination());
            }
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_mode = ?, actual_arrivals = ?,"
                        + " short_manifest = ?, arrived_at = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, MODE_EXACT, writeJson(sortedActual),
                writeJson(List.of()), arrivedAt, legId);
        return new ArriveResponse(legId, LEG_ARRIVED, newVersion, manifest);
    }

    private DiscrepancyArriveResponse doArriveDiscrepancy(String legId, DiscrepancyArriveRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止差异到达");
        }
        List<String> manifest = readJsonList(leg.sealedManifest());
        List<String> actual = request.actualBagTags();
        Set<String> actualSet = new HashSet<>(actual);
        if (actualSet.size() != actual.size()) {
            throw ApiException.unprocessable("实际到达袋号不得重复");
        }
        if (!new HashSet<>(manifest).containsAll(actualSet)) {
            throw ApiException.unprocessable("实际到达袋号必须是封舱清单的子集，存在清单外袋号");
        }
        List<String> sortedActual = sortedCopy(actual);
        List<String> missing = manifest.stream().filter(tag -> !actualSet.contains(tag)).toList();

        LocalDateTime registeredAt = nowUtc();
        for (String bagTag : manifest) {
            BagRow bag = lockBag(bagTag);
            if (actualSet.contains(bagTag)) {
                // 实际到达：按原规则移动到到达站并推进待乘索引
                int nextIndex = bag.nextLegIndex() + 1;
                int total = itineraryCount(bagTag);
                boolean finished = nextIndex >= total;
                String status = finished ? BAG_DELIVERED : BAG_IN_TRANSIT;
                jdbcTemplate.update(
                        "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL"
                                + " WHERE bag_tag = ?",
                        leg.destination(), nextIndex, status, bagTag);
                insertTrace(bagTag, "ARRIVED", legId, leg.destination());
                if (finished) {
                    insertTrace(bagTag, "DELIVERED", legId, leg.destination());
                }
            } else {
                // 短卸：不移动、不推进待乘索引，登记缺失航段/应到站/UTC 时刻
                jdbcTemplate.update(
                        "UPDATE bag SET status = ?, loaded_leg_id = NULL, short_leg_id = ?,"
                                + " short_destination = ?, short_registered_at = ? WHERE bag_tag = ?",
                        BAG_SHORT_UNLOADED, legId, leg.destination(), registeredAt, bagTag);
                insertTrace(bagTag, "SHORT_UNLOADED", legId, bag.currentLocation());
            }
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_mode = ?, actual_arrivals = ?,"
                        + " short_manifest = ?, arrived_at = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, MODE_DISCREPANCY, writeJson(sortedActual),
                writeJson(missing), registeredAt, legId);
        return new DiscrepancyArriveResponse(legId, LEG_ARRIVED, newVersion, MODE_DISCREPANCY,
                sortedActual, missing, toUtcInstant(registeredAt));
    }

    private RecoverResponse doRecover(String bagTag, RecoverRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (!BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 状态为 " + bag.status() + "，仅 SHORT_UNLOADED 行李可补到");
        }
        if (!request.missingLegId().equals(bag.shortLegId())) {
            throw ApiException.conflict(
                    "补到缺失航段不匹配: 登记缺失航段为 " + bag.shortLegId()
                            + "，提交为 " + request.missingLegId());
        }
        LegRow missingLeg = findLeg(request.missingLegId());
        if (missingLeg == null || !request.actualStation().equals(missingLeg.destination())) {
            throw ApiException.unprocessable(
                    "实际到达站必须等于缺失航段 " + request.missingLegId() + " 的到达站 "
                            + (missingLeg == null ? "(航段不存在)" : missingLeg.destination()));
        }
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bagTag);
        boolean finished = nextIndex >= total;
        String status = finished ? BAG_DELIVERED : BAG_RECOVERED;
        LocalDateTime recoveredAt = nowUtc();
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                        + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL"
                        + " WHERE bag_tag = ?",
                missingLeg.destination(), nextIndex, status, bagTag);
        insertTrace(bagTag, "RECOVERED", missingLeg.legId(), missingLeg.destination());
        if (finished) {
            insertTrace(bagTag, "DELIVERED", missingLeg.legId(), missingLeg.destination());
        }
        return new RecoverResponse(bagTag, status, missingLeg.legId(),
                missingLeg.destination(), nextIndex, toUtcInstant(recoveredAt));
    }

    private void validateLoadable(BagRow bag, LegRow leg) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于短卸未补到状态，补到前不得装载后续航段");
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
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest,"
                        + " arrival_mode, actual_arrivals, short_manifest, arrived_at"
                        + " FROM leg WHERE leg_id = ?",
                LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest,"
                        + " arrival_mode, actual_arrivals, short_manifest, arrived_at"
                        + " FROM leg WHERE leg_id = ? FOR UPDATE",
                LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
                        + " short_leg_id, short_destination, short_registered_at"
                        + " FROM bag WHERE bag_tag = ?",
                BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
                        + " short_leg_id, short_destination, short_registered_at"
                        + " FROM bag WHERE bag_tag = ? FOR UPDATE",
                BAG_MAPPER, bagTag);
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

    private List<ItineraryItem> toItinerary(String bagTag) {
        return jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_tag = ? ORDER BY seq",
                ITINERARY_MAPPER, bagTag);
    }

    private List<BaggageDtos.TraceEvent> toTrace(String bagTag) {
        return jdbcTemplate.query(
                "SELECT seq, event_type, leg_id, station, event_time FROM bag_trace_event"
                        + " WHERE bag_tag = ? ORDER BY seq",
                (rs, rowNum) -> new BaggageDtos.TraceEvent(rs.getInt("seq"),
                        rs.getString("event_type"), rs.getString("leg_id"),
                        rs.getString("station"),
                        toUtcInstant(rs.getObject("event_time", LocalDateTime.class))),
                bagTag);
    }

    /** 追加轨迹事件，事件号按该行李现有事件数顺延（调用方持 bag 行锁或处于登记事务中）。 */
    private void insertTrace(String bagTag, String eventType, String legId, String station) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_trace_event WHERE bag_tag = ?", Integer.class, bagTag);
        int seq = count == null ? 0 : count;
        jdbcTemplate.update(
                "INSERT INTO bag_trace_event (bag_tag, seq, event_type, leg_id, station, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, seq, eventType, legId, station, nowUtc());
    }

    /** 当前时刻的 UTC 墙钟值（列按 UTC 解释，不做 JVM 时区换算）。 */
    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static String toUtcInstant(LocalDateTime utcWallTime) {
        return utcWallTime.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()), toTrace(bag.bagTag()));
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
                          String arrivalMode, String actualArrivals,
                          String shortManifest, LocalDateTime arrivedAt) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, LocalDateTime shortRegisteredAt) {
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

    /** 差异到达幂等摘要参数：actualBagTags 已排序，顺序差异不视为异参。 */
    private record DiscrepancyPayload(String legId, int expectedVersion, List<String> actualBagTags) {
    }

    /** 补到幂等摘要参数。 */
    private record RecoverPayload(String bagTag, String missingLegId, String actualStation) {
    }
}
