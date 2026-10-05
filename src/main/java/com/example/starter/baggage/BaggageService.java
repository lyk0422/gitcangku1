package com.example.starter.baggage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import com.example.starter.baggage.BaggageDtos.BagCompartment;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.BalanceItem;
import com.example.starter.baggage.BaggageDtos.BalanceResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.MoveCompartmentRequest;
import com.example.starter.baggage.BaggageDtos.MoveCompartmentResponse;
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
 * 双舱航段（COMPARTMENT）固定前舱 -2 米、后舱 3 米、每舱限重 1000 千克：装载须同时指定舱位，
 * 开放时可批量换舱（按最终全体位置校验舱重），封舱要求总质量为正且质量加权重心落在
 * 闭区间 [-0.5, 0.5] 米（交叉相乘精确判断）；封舱成功冻结每件质量、位置、总重与精确力矩，
 * 到达与短卸不改变历史重心。只核算行李质量，不引入机体与旅客质量。
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

    /** 航段类型：普通航段（无舱位与重心约束）。 */
    private static final String LEG_TYPE_STANDARD = "STANDARD";
    /** 航段类型：双舱航段（固定前后两舱，封舱需重心合格）。 */
    private static final String LEG_TYPE_COMPARTMENT = "COMPARTMENT";
    /** 前舱。 */
    private static final String COMPARTMENT_FWD = "FWD";
    /** 后舱。 */
    private static final String COMPARTMENT_AFT = "AFT";
    /** 前舱纵向位置（米，机身为原点，前负后正）。 */
    private static final BigDecimal FWD_POSITION_M = new BigDecimal("-2");
    /** 后舱纵向位置（米）。 */
    private static final BigDecimal AFT_POSITION_M = new BigDecimal("3");
    /** 每舱限重（千克）。 */
    private static final BigDecimal COMPARTMENT_LIMIT_KG = new BigDecimal("1000");
    /** 封舱重心闭区间下限（米）。 */
    private static final BigDecimal CG_MIN_M = new BigDecimal("-0.5");
    /** 封舱重心闭区间上限（米）。 */
    private static final BigDecimal CG_MAX_M = new BigDecimal("0.5");

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getString("leg_type"), rs.getInt("version"),
            rs.getString("sealed_manifest"), rs.getString("arrival_type"), rs.getString("arrival_actual"),
            rs.getBigDecimal("sealed_total_mass"), rs.getBigDecimal("sealed_total_moment"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"), rs.getBigDecimal("mass_kg"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<BalanceItem> BALANCE_ITEM_MAPPER = (rs, rowNum) -> new BalanceItem(
            rs.getString("bag_tag"), rs.getBigDecimal("mass_kg"), rs.getString("compartment"),
            rs.getBigDecimal("position_m"), rs.getBigDecimal("moment_kgm"));

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

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动；双舱航段须同时指定舱位并校验舱重。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(), request.operator(),
                sortedCopy(request.bagTags()), sortedCompartments(request.compartments()));
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /** 封舱：校验版本，保存只读装载清单并转 SEALED；双舱航段要求总质量为正且重心在闭区间 [-0.5, 0.5] 米。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.expectedVersion(), request.operator());
        return idempotencyService.execute(request.requestId(), "SEAL", 200,
                payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /**
     * 批量换舱：仅开放的双舱航段可用，与航段版本竞争（失败 409 且不移动任何行李）；
     * 按最终全体位置一次性校验每舱限重，不做逐件中途校验，任一项不满足则整批 422 回滚。
     */
    public MoveCompartmentResponse moveCompartment(String legId, MoveCompartmentRequest request) {
        MovePayload payload = new MovePayload(legId, request.expectedVersion(), request.operator(),
                sortedCompartments(request.moves()));
        return idempotencyService.execute(request.requestId(), "MOVE_COMPARTMENT", 200,
                payload, MoveCompartmentResponse.class, () -> doMoveCompartment(legId, request));
    }

    /**
     * 航段重心平衡查询：开放航段返回实时计算；封舱后返回冻结快照（每件质量、位置、总重与精确力矩），
     * 到达与短卸不改变历史值。仅双舱航段支持。
     */
    public BalanceResponse getBalance(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        if (!LEG_TYPE_COMPARTMENT.equals(leg.legType())) {
            throw ApiException.unprocessable("航段 " + legId + " 为普通航段，无舱位与重心数据");
        }
        if (leg.sealedTotalMass() != null) {
            List<BalanceItem> items = jdbcTemplate.query(
                    "SELECT bag_tag, mass_kg, compartment, position_m, moment_kgm FROM seal_item"
                            + " WHERE leg_id = ? ORDER BY bag_tag",
                    BALANCE_ITEM_MAPPER, legId);
            return new BalanceResponse(leg.legId(), leg.status(), leg.version(),
                    leg.sealedTotalMass(), leg.sealedTotalMoment(),
                    displayCg(leg.sealedTotalMoment(), leg.sealedTotalMass()), items);
        }
        List<BalanceItem> items = liveBalanceItems(legId);
        BigDecimal totalMass = totalMass(items);
        BigDecimal totalMoment = totalMoment(items);
        return new BalanceResponse(leg.legId(), leg.status(), leg.version(),
                totalMass, totalMoment, displayCg(totalMoment, totalMass), items);
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

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        String legType = request.legType() == null ? LEG_TYPE_STANDARD : request.legType();
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, leg_type, version)"
                        + " VALUES (?, ?, ?, ?, ?, 1)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN, legType);
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, legType);
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
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, mass_kg)"
                        + " VALUES (?, ?, 0, ?, ?)",
                request.bagTag(), startStation, BAG_IN_TRANSIT, request.massKg());
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
        boolean compartmentLeg = LEG_TYPE_COMPARTMENT.equals(leg.legType());
        Map<String, String> assignments = validateCompartments(leg, request, compartmentLeg);
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
            if (compartmentLeg && bag.massKg() == null) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 未登记质量，不能装入双舱航段 " + legId);
            }
            bags.add(bag);
        }
        if (compartmentLeg) {
            checkCompartmentCapacity(legId, bags, assignments);
        }
        List<BagCompartment> assignmentList = null;
        if (compartmentLeg) {
            assignmentList = new ArrayList<>();
            for (BagRow bag : bags) {
                String compartment = assignments.get(bag.bagTag());
                jdbcTemplate.update(
                        "INSERT INTO load_record (bag_tag, leg_id, compartment) VALUES (?, ?, ?)",
                        bag.bagTag(), legId, compartment);
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?",
                        legId, bag.bagTag());
                insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
                assignmentList.add(new BagCompartment(bag.bagTag(), compartment));
            }
        } else {
            for (BagRow bag : bags) {
                jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)",
                        bag.bagTag(), legId);
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?",
                        legId, bag.bagTag());
                insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
            }
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted, assignmentList);
    }

    /** 校验装载请求的舱位指定：双舱航段必须逐件指定，普通航段不得指定；返回 bagTag -> 舱位。 */
    private Map<String, String> validateCompartments(LegRow leg, LoadRequest request,
                                                     boolean compartmentLeg) {
        List<BagCompartment> compartments = request.compartments();
        if (!compartmentLeg) {
            if (compartments != null && !compartments.isEmpty()) {
                throw ApiException.unprocessable("航段 " + leg.legId() + " 为普通航段，不支持舱位指定");
            }
            return Map.of();
        }
        if (compartments == null || compartments.isEmpty()) {
            throw ApiException.unprocessable("双舱航段 " + leg.legId() + " 装载必须同时指定舱位");
        }
        Set<String> bagTagSet = new HashSet<>(request.bagTags());
        Map<String, String> assignments = new HashMap<>();
        for (BagCompartment entry : compartments) {
            if (!bagTagSet.contains(entry.bagTag())) {
                throw ApiException.unprocessable("舱位指定的 bagTag 不在本批装载中: " + entry.bagTag());
            }
            if (assignments.put(entry.bagTag(), entry.compartment()) != null) {
                throw ApiException.unprocessable("舱位指定的 bagTag 重复: " + entry.bagTag());
            }
        }
        if (assignments.size() != bagTagSet.size()) {
            throw ApiException.unprocessable("双舱航段装载须为每件行李指定舱位");
        }
        return assignments;
    }

    /** 校验本批装载后每舱总重不超过限重：基于航段已在舱行李与本批指定的最终位置一次计算。 */
    private void checkCompartmentCapacity(String legId, List<BagRow> bags,
                                          Map<String, String> assignments) {
        Map<String, BigDecimal> totals = compartmentTotals(legId);
        for (BagRow bag : bags) {
            String compartment = assignments.get(bag.bagTag());
            totals.merge(compartment, bag.massKg(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            if (entry.getValue().compareTo(COMPARTMENT_LIMIT_KG) > 0) {
                throw ApiException.unprocessable(
                        "舱位 " + entry.getKey() + " 装载后总重 " + entry.getValue()
                                + " 千克超过限重 " + COMPARTMENT_LIMIT_KG + " 千克，整批回滚");
            }
        }
    }

    /** 航段当前在舱行李的每舱总重（仅双舱航段有数据）。 */
    private Map<String, BigDecimal> compartmentTotals(String legId) {
        Map<String, BigDecimal> totals = new HashMap<>();
        totals.put(COMPARTMENT_FWD, BigDecimal.ZERO);
        totals.put(COMPARTMENT_AFT, BigDecimal.ZERO);
        for (BalanceItem item : liveBalanceItems(legId)) {
            totals.merge(item.compartment(), item.massKg(), BigDecimal::add);
        }
        return totals;
    }

    /** 航段当前在舱行李的实时平衡明细（装载清单关联行李质量）。 */
    private List<BalanceItem> liveBalanceItems(String legId) {
        return jdbcTemplate.query(
                "SELECT r.bag_tag, b.mass_kg, r.compartment FROM load_record r"
                        + " JOIN bag b ON b.bag_tag = r.bag_tag WHERE r.leg_id = ? ORDER BY r.bag_tag",
                (rs, rowNum) -> {
                    String compartment = rs.getString("compartment");
                    BigDecimal mass = rs.getBigDecimal("mass_kg");
                    BigDecimal position = positionOf(compartment);
                    return new BalanceItem(rs.getString("bag_tag"), mass, compartment, position,
                            mass == null ? null : mass.multiply(position));
                },
                legId);
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
        if (LEG_TYPE_COMPARTMENT.equals(leg.legType())) {
            List<BalanceItem> items = liveBalanceItems(legId);
            BigDecimal totalMass = totalMass(items);
            BigDecimal totalMoment = totalMoment(items);
            checkCenterOfGravity(totalMass, totalMoment);
            for (BalanceItem item : items) {
                jdbcTemplate.update(
                        "INSERT INTO seal_item (leg_id, bag_tag, mass_kg, compartment, position_m, moment_kgm)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        legId, item.bagTag(), item.massKg(), item.compartment(),
                        item.positionM(), item.momentKgm());
            }
            jdbcTemplate.update(
                    "UPDATE leg SET status = ?, version = ?, sealed_manifest = ?,"
                            + " sealed_total_mass = ?, sealed_total_moment = ? WHERE leg_id = ?",
                    LEG_SEALED, newVersion, writeJson(manifest), totalMass, totalMoment, legId);
        } else {
            jdbcTemplate.update("UPDATE leg SET status = ?, version = ?, sealed_manifest = ? WHERE leg_id = ?",
                    LEG_SEALED, newVersion, writeJson(manifest), legId);
        }
        return new SealResponse(legId, LEG_SEALED, newVersion, manifest);
    }

    /**
     * 封舱重心校验：总质量必须为正值；重心 = 总力矩 / 总质量 须落在闭区间 [-0.5, 0.5] 米。
     * 采用交叉相乘（下界×总质量 ≤ 总力矩 ≤ 上界×总质量）做精确比较，不先舍入。
     */
    private void checkCenterOfGravity(BigDecimal totalMass, BigDecimal totalMoment) {
        if (totalMass.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.unprocessable("封舱要求行李总质量为正值，当前为 " + totalMass + " 千克");
        }
        BigDecimal lower = CG_MIN_M.multiply(totalMass);
        BigDecimal upper = CG_MAX_M.multiply(totalMass);
        if (totalMoment.compareTo(lower) < 0 || totalMoment.compareTo(upper) > 0) {
            throw ApiException.unprocessable(
                    "重心超限：总力矩 " + totalMoment + " 千克·米，总质量 " + totalMass
                            + " 千克，重心超出闭区间 [" + CG_MIN_M + ", " + CG_MAX_M + "] 米，航段保持开放");
        }
    }

    private MoveCompartmentResponse doMoveCompartment(String legId, MoveCompartmentRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_TYPE_COMPARTMENT.equals(leg.legType())) {
            throw ApiException.unprocessable("航段 " + legId + " 为普通航段，不支持换舱");
        }
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，封舱后禁止换舱");
        }
        List<BagCompartment> moves = request.moves();
        Map<String, String> moveMap = new HashMap<>();
        for (BagCompartment move : moves) {
            if (moveMap.put(move.bagTag(), move.compartment()) != null) {
                throw ApiException.unprocessable("批量换舱的 bagTag 不得重复: " + move.bagTag());
            }
        }
        List<BalanceItem> current = liveBalanceItems(legId);
        Map<String, BalanceItem> byBag = new HashMap<>();
        for (BalanceItem item : current) {
            byBag.put(item.bagTag(), item);
        }
        for (String bagTag : moveMap.keySet()) {
            if (!byBag.containsKey(bagTag)) {
                throw ApiException.unprocessable("行李 " + bagTag + " 未装载在航段 " + legId + "，不能换舱");
            }
        }
        // 按最终全体位置一次性核算每舱总重，不逐件先搬
        Map<String, BigDecimal> totals = new HashMap<>();
        totals.put(COMPARTMENT_FWD, BigDecimal.ZERO);
        totals.put(COMPARTMENT_AFT, BigDecimal.ZERO);
        for (BalanceItem item : current) {
            String finalCompartment = moveMap.getOrDefault(item.bagTag(), item.compartment());
            totals.merge(finalCompartment, item.massKg(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            if (entry.getValue().compareTo(COMPARTMENT_LIMIT_KG) > 0) {
                throw ApiException.unprocessable(
                        "换舱后舱位 " + entry.getKey() + " 总重 " + entry.getValue()
                                + " 千克超过限重 " + COMPARTMENT_LIMIT_KG + " 千克，整批回滚");
            }
        }
        List<String> moved = new ArrayList<>();
        for (BagCompartment move : moves) {
            if (!move.compartment().equals(byBag.get(move.bagTag()).compartment())) {
                jdbcTemplate.update(
                        "UPDATE load_record SET compartment = ? WHERE bag_tag = ? AND leg_id = ?",
                        move.compartment(), move.bagTag(), legId);
                moved.add(move.bagTag());
            }
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new MoveCompartmentResponse(legId, LEG_OPEN, newVersion,
                moved.stream().sorted().toList());
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
        return "SELECT leg_id, origin, destination, status, leg_type, version, sealed_manifest,"
                + " arrival_type, arrival_actual, sealed_total_mass, sealed_total_moment"
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
                + " short_leg_id, short_destination, short_registered_at, mass_kg FROM bag WHERE bag_tag = ?"
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
                toEvents(bag.bagTag()), bag.massKg());
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    /** 舱位指定规范化：null 视为未指定，否则按 bagTag 排序，顺序差异不视为异参。 */
    private static List<BagCompartment> sortedCompartments(List<BagCompartment> entries) {
        if (entries == null) {
            return null;
        }
        return entries.stream()
                .sorted(java.util.Comparator.comparing(BagCompartment::bagTag))
                .toList();
    }

    /** 舱位纵向位置（米）：FWD 前舱 -2 米，AFT 后舱 3 米。 */
    private static BigDecimal positionOf(String compartment) {
        return switch (compartment == null ? "" : compartment) {
            case COMPARTMENT_FWD -> FWD_POSITION_M;
            case COMPARTMENT_AFT -> AFT_POSITION_M;
            default -> null;
        };
    }

    /** 平衡明细的总质量（千克），忽略无质量项。 */
    private static BigDecimal totalMass(List<BalanceItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (BalanceItem item : items) {
            if (item.massKg() != null) {
                total = total.add(item.massKg());
            }
        }
        return total;
    }

    /** 平衡明细的总纵向力矩（千克·米），忽略无力矩项。 */
    private static BigDecimal totalMoment(List<BalanceItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (BalanceItem item : items) {
            if (item.momentKgm() != null) {
                total = total.add(item.momentKgm());
            }
        }
        return total;
    }

    /** 展示用重心（米，6 位小数四舍五入）；总质量非正时无重心，返回 null。封舱判定不使用该值。 */
    private static BigDecimal displayCg(BigDecimal totalMoment, BigDecimal totalMass) {
        if (totalMass == null || totalMass.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return totalMoment.divide(totalMass, 6, RoundingMode.HALF_UP).stripTrailingZeros();
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
                          String status, String legType, int version, String sealedManifest,
                          String arrivalType, String arrivalActual,
                          BigDecimal sealedTotalMass, BigDecimal sealedTotalMoment) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt, BigDecimal massKg) {
    }

    /** 装载幂等摘要参数：bagTags 与舱位指定已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, String operator,
                               List<String> bagTags, List<BagCompartment> compartments) {
    }

    /** 封舱幂等摘要参数。 */
    private record SealPayload(String legId, int expectedVersion, String operator) {
    }

    /** 批量换舱幂等摘要参数：moves 已按 bagTag 排序。 */
    private record MovePayload(String legId, int expectedVersion, String operator,
                               List<BagCompartment> moves) {
    }

    /** 到达确认幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record ArrivePayload(String legId, List<String> bagTags) {
    }

    /** 差异到达幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DifferenceArrivePayload(String legId, int expectedVersion, List<String> bagTags) {
    }
}
