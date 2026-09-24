package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.OccupancyResponse;
import com.example.starter.baggage.BaggageDtos.OffloadBagItem;
import com.example.starter.baggage.BaggageDtos.OffloadItem;
import com.example.starter.baggage.BaggageDtos.OffloadListResponse;
import com.example.starter.baggage.BaggageDtos.OffloadRequest;
import com.example.starter.baggage.BaggageDtos.OffloadResponse;
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
 * 联程行李装载交接核心业务：航段/行李登记、批量装载（含载量上限）、封舱、精确/差异到达、
 * 补到/改派、超载优先级卸载与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载、卸载、封舱并发按提交顺序裁决，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 卸载仅允许 OPEN 航段：按 BASIC -> STANDARD -> PREMIUM、同级重量降序、同重 bagTag 升序选取，
 * 被卸行李转 OFFLOADED、移出装载清单、冻结待乘索引；改派前不得再装载，改派沿用补到入口。
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

    private static final String CLASS_PREMIUM = "PREMIUM";
    private static final String CLASS_STANDARD = "STANDARD";
    private static final String CLASS_BASIC = "BASIC";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_OFFLOADED = "OFFLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"),
            rs.getInt("max_pieces"), rs.getInt("max_weight_kg"),
            rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"),
            rs.getInt("weight_kg"), rs.getString("cabin_class"),
            rs.getString("loaded_leg_id"), rs.getString("short_leg_id"),
            rs.getString("short_destination"), getInstant(rs, "short_registered_at"),
            rs.getString("offload_leg_id"),
            (Integer) rs.getObject("offload_next_index"),
            getInstant(rs, "offloaded_at"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<OffloadItem> OFFLOAD_MAPPER = (rs, rowNum) -> new OffloadItem(
            rs.getString("offload_key"), rs.getString("leg_id"), rs.getString("bag_tag"),
            rs.getInt("origin_index"), rs.getInt("weight_kg"), rs.getString("cabin_class"),
            rs.getInt("target_max_pieces"), rs.getInt("target_max_weight_kg"),
            getInstant(rs, "offloaded_at").toString());

    /** 卸载候选排序：BASIC -> STANDARD -> PREMIUM，同级重量降序，重量相同 bagTag 升序。 */
    private static final Comparator<BagRow> OFFLOAD_ORDER = Comparator
            .comparingInt((BagRow bag) -> cabinRank(bag.cabinClass()))
            .thenComparing(Comparator.comparingInt(BagRow::weightKg).reversed())
            .thenComparing(BagRow::bagTag);

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

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1，携带件数与总重上限。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                request, LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /**
     * 批量装载：整批原子。装载后件数或总重超过航段登记上限时整批 422 并返回超出维度与数值；
     * 任一行李不满足装载条件同样整批 422，无一件移动。
     */
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

    /**
     * 超载卸载决策与执行（同一事务）：仅 OPEN 航段可卸载，SEALED/ARRIVED 返回 409。
     * 目标上限高于登记上限返回 422；目标已满足时返回空卸载清单（成功，占 offloadKey）。
     * 同 offloadKey 同参重放首次结果，异参 409，失败不占键。
     */
    public OffloadResponse offload(String legId, OffloadRequest request) {
        OffloadPayload payload = new OffloadPayload(legId, request.expectedVersion(),
                request.targetMaxPieces(), request.targetMaxWeightKg());
        return idempotencyService.execute(request.offloadKey(), "OFFLOAD", 200,
                payload, OffloadResponse.class, () -> doOffload(legId, request));
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
     * 补到/改派：SHORT_UNLOADED 行李按缺失航段应到站补到并推进待乘索引；
     * OFFLOADED 行李沿用本入口改派，actualStation 为卸载航段始发站（行李仍滞留该站），
     * 待乘索引不推进，改派后恢复 IN_TRANSIT 并可重新装载剩余行程。
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

    /** 航段载量占用查询：占用只统计当前仍在装载清单内的行李。 */
    public OccupancyResponse getOccupancy(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        Occupancy occupancy = loadOccupancy(legId);
        return new OccupancyResponse(leg.legId(), leg.status(), leg.version(),
                leg.maxPieces(), leg.maxWeightKg(),
                occupancy.pieces(), occupancy.weightKg(),
                leg.maxPieces() - occupancy.pieces(), leg.maxWeightKg() - occupancy.weightKg());
    }

    /** 航段卸载明细查询：按卸载时刻与写入顺序返回全部被卸行李记录。 */
    public OffloadListResponse listOffloads(String legId) {
        if (findLeg(legId) == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<OffloadItem> items = jdbcTemplate.query(
                "SELECT offload_key, leg_id, bag_tag, origin_index, weight_kg, cabin_class,"
                        + " target_max_pieces, target_max_weight_kg, offloaded_at"
                        + " FROM offload_record WHERE leg_id = ? ORDER BY offloaded_at, id",
                OFFLOAD_MAPPER, legId);
        return new OffloadListResponse(legId, items);
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
                "INSERT INTO leg (leg_id, origin, destination, status, version, max_pieces, max_weight_kg)"
                        + " VALUES (?, ?, ?, ?, 1, ?, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                request.maxPieces(), request.maxWeightKg());
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, request.maxPieces(), request.maxWeightKg());
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
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, weight_kg, cabin_class)"
                        + " VALUES (?, ?, 0, ?, ?, ?)",
                request.bagTag(), startStation, BAG_IN_TRANSIT, request.weightKg(), request.cabinClass());
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
        Occupancy current = loadOccupancy(legId);
        int batchWeight = bags.stream().mapToInt(BagRow::weightKg).sum();
        int prospectivePieces = current.pieces() + bags.size();
        int prospectiveWeight = current.weightKg() + batchWeight;
        List<String> exceeded = new ArrayList<>();
        if (prospectivePieces > leg.maxPieces()) {
            exceeded.add("件数: 装载后 " + prospectivePieces + " 超过上限 " + leg.maxPieces());
        }
        if (prospectiveWeight > leg.maxWeightKg()) {
            exceeded.add("总重(千克): 装载后 " + prospectiveWeight + " 超过上限 " + leg.maxWeightKg());
        }
        if (!exceeded.isEmpty()) {
            throw ApiException.unprocessable(
                    "装载后航段载量超限，整批拒绝: " + String.join("；", exceeded));
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

    private OffloadResponse doOffload(String legId, OffloadRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            // 题面契约：SEALED/ARRIVED 航段卸载一律 409
            throw ApiException.conflict("航段状态为 " + leg.status() + "，禁止卸载");
        }
        if (request.targetMaxPieces() > leg.maxPieces()
                || request.targetMaxWeightKg() > leg.maxWeightKg()) {
            throw ApiException.unprocessable(
                    "卸载目标上限高于航段登记上限: 登记件数 " + leg.maxPieces()
                            + "/总重 " + leg.maxWeightKg() + " 千克，目标件数 "
                            + request.targetMaxPieces() + "/总重 " + request.targetMaxWeightKg() + " 千克");
        }
        List<BagRow> onboard = loadOnboardBags(legId);
        int pieces = onboard.size();
        int weight = onboard.stream().mapToInt(BagRow::weightKg).sum();

        // 确定性选取：逐件卸载直到件数与总重都不超目标
        List<BagRow> ordered = new ArrayList<>(onboard);
        ordered.sort(OFFLOAD_ORDER);
        List<BagRow> removed = new ArrayList<>();
        int cursor = 0;
        while ((pieces > request.targetMaxPieces() || weight > request.targetMaxWeightKg())
                && cursor < ordered.size()) {
            BagRow victim = ordered.get(cursor++);
            removed.add(victim);
            pieces--;
            weight -= victim.weightKg();
        }

        OffsetDateTime offloadedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        Set<String> removedTags = new HashSet<>();
        for (BagRow victim : removed) {
            removedTags.add(victim.bagTag());
            jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ? AND leg_id = ?",
                    victim.bagTag(), legId);
            jdbcTemplate.update(
                    "UPDATE bag SET status = ?, loaded_leg_id = NULL, offload_leg_id = ?,"
                            + " offload_next_index = ?, offloaded_at = ? WHERE bag_tag = ?",
                    BAG_OFFLOADED, legId, victim.nextLegIndex(), offloadedAt, victim.bagTag());
            jdbcTemplate.update(
                    "INSERT INTO offload_record (offload_key, leg_id, bag_tag, origin_index, weight_kg,"
                            + " cabin_class, target_max_pieces, target_max_weight_kg, offloaded_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    request.offloadKey(), legId, victim.bagTag(), victim.nextLegIndex(),
                    victim.weightKg(), victim.cabinClass(),
                    request.targetMaxPieces(), request.targetMaxWeightKg(), offloadedAt);
            insertEvent(victim.bagTag(), EVT_OFFLOADED, legId, victim.currentLocation());
        }
        List<OffloadBagItem> offloadedItems = removed.stream()
                .map(bag -> new OffloadBagItem(bag.bagTag(), bag.weightKg(), bag.cabinClass()))
                .toList();
        List<OffloadBagItem> retainedItems = onboard.stream()
                .filter(bag -> !removedTags.contains(bag.bagTag()))
                .sorted(Comparator.comparing(BagRow::bagTag))
                .map(bag -> new OffloadBagItem(bag.bagTag(), bag.weightKg(), bag.cabinClass()))
                .toList();
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new OffloadResponse(request.offloadKey(), legId, LEG_OPEN, newVersion,
                request.targetMaxPieces(), request.targetMaxWeightKg(),
                pieces, weight, offloadedItems, retainedItems);
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
        if (BAG_OFFLOADED.equals(bag.status())) {
            return rerouteOffloadedBag(bag, request);
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

    /**
     * OFFLOADED 行李改派：仍滞留卸载航段始发站，待乘索引不推进；
     * actualStation 必须等于卸载航段始发站，改派后恢复 IN_TRANSIT，可重新装载剩余行程。
     */
    private RecoverResponse rerouteOffloadedBag(BagRow bag, RecoverRequest request) {
        if (!bag.offloadLegId().equals(request.missingLegId())) {
            throw ApiException.conflict(
                    "卸载航段不匹配: 登记为 " + bag.offloadLegId() + "，提交为 " + request.missingLegId());
        }
        LegRow offloadLeg = findLeg(request.missingLegId());
        if (offloadLeg == null) {
            throw ApiException.unprocessable("卸载航段不存在: " + request.missingLegId());
        }
        if (!offloadLeg.origin().equals(request.actualStation())) {
            throw ApiException.unprocessable(
                    "改派站点 " + request.actualStation() + " 与卸载航段始发站 "
                            + offloadLeg.origin() + " 不一致，被卸行李仍滞留始发站");
        }
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, status = ?, loaded_leg_id = NULL,"
                        + " offload_leg_id = NULL, offload_next_index = NULL, offloaded_at = NULL"
                        + " WHERE bag_tag = ?",
                request.actualStation(), BAG_IN_TRANSIT, bag.bagTag());
        insertEvent(bag.bagTag(), EVT_RECOVERED, request.missingLegId(), request.actualStation());
        return new RecoverResponse(bag.bagTag(), BAG_IN_TRANSIT, request.actualStation(),
                bag.nextLegIndex(), request.missingLegId());
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
        if (BAG_OFFLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于卸载待改派状态，须先改派才能重新装载");
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
        return "SELECT leg_id, origin, destination, status, version, max_pieces, max_weight_kg,"
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
        return "SELECT bag_tag, current_location, next_leg_index, status, weight_kg, cabin_class,"
                + " loaded_leg_id, short_leg_id, short_destination, short_registered_at,"
                + " offload_leg_id, offload_next_index, offloaded_at FROM bag WHERE bag_tag = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    /** 读取航段当前装载清单内的全部行李（航段行锁已持有，读取顺序不影响确定性决策）。 */
    private List<BagRow> loadOnboardBags(String legId) {
        return jdbcTemplate.query(
                "SELECT b.bag_tag, b.current_location, b.next_leg_index, b.status, b.weight_kg,"
                        + " b.cabin_class, b.loaded_leg_id, b.short_leg_id, b.short_destination,"
                        + " b.short_registered_at, b.offload_leg_id, b.offload_next_index, b.offloaded_at"
                        + " FROM bag b WHERE b.bag_tag IN (SELECT bag_tag FROM load_record WHERE leg_id = ?)",
                BAG_MAPPER, legId);
    }

    /** 当前装载清单的件数与总重占用。 */
    private Occupancy loadOccupancy(String legId) {
        List<Occupancy> rows = jdbcTemplate.query(
                "SELECT COUNT(*) AS pieces, COALESCE(SUM(b.weight_kg), 0) AS weight"
                        + " FROM load_record lr JOIN bag b ON b.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ?",
                (rs, rowNum) -> new Occupancy(rs.getInt("pieces"), rs.getInt("weight")),
                legId);
        return rows.isEmpty() ? new Occupancy(0, 0) : rows.get(0);
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
                bag.status(), bag.weightKg(), bag.cabinClass(), bag.loadedLegId(),
                toItinerary(bag.bagTag()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                bag.offloadLegId(), bag.offloadNextIndex(),
                bag.offloadedAt() == null ? null : bag.offloadedAt().toString(),
                toEvents(bag.bagTag()));
    }

    private static int cabinRank(String cabinClass) {
        return switch (cabinClass) {
            case CLASS_BASIC -> 0;
            case CLASS_STANDARD -> 1;
            case CLASS_PREMIUM -> 2;
            default -> throw new IllegalStateException("未知舱位等级: " + cabinClass);
        };
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
                          String status, int version, int maxPieces, int maxWeightKg,
                          String sealedManifest, String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, int weightKg, String cabinClass,
                          String loadedLegId, String shortLegId, String shortDestination,
                          Instant shortRegisteredAt, String offloadLegId,
                          Integer offloadNextIndex, Instant offloadedAt) {
    }

    /** 载量占用快照：件数与总重（千克）。 */
    private record Occupancy(int pieces, int weightKg) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, List<String> bagTags) {
    }

    /** 封舱幂等摘要参数。 */
    private record SealPayload(String legId, int expectedVersion) {
    }

    /** 卸载决策幂等摘要参数：offloadKey 本身为幂等键，不参与摘要。 */
    private record OffloadPayload(String legId, int expectedVersion,
                                  int targetMaxPieces, int targetMaxWeightKg) {
    }

    /** 到达确认幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record ArrivePayload(String legId, List<String> bagTags) {
    }

    /** 差异到达幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DifferenceArrivePayload(String legId, int expectedVersion, List<String> bagTags) {
    }
}
