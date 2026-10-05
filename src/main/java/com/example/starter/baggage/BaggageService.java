package com.example.starter.baggage;

import java.math.BigDecimal;
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
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.HoldAssignment;
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
import com.example.starter.baggage.BaggageDtos.ReholdRequest;
import com.example.starter.baggage.BaggageDtos.ReholdResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.SealedBagDetail;
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

    /** 航段类型：旧航段（无舱位概念）。 */
    private static final String KIND_CLASSIC = "CLASSIC";
    /** 航段类型：新类型固定前后两舱航段。 */
    private static final String KIND_HOLD = "HOLD";
    /** 前舱代码。 */
    private static final String HOLD_FWD = "FWD";
    /** 后舱代码。 */
    private static final String HOLD_AFT = "AFT";
    /** 前舱纵向位置（米）。 */
    private static final BigDecimal FWD_POSITION_M = new BigDecimal("-2");
    /** 后舱纵向位置（米）。 */
    private static final BigDecimal AFT_POSITION_M = new BigDecimal("3");
    /** 单舱限重（千克）。 */
    private static final BigDecimal HOLD_LIMIT_KG = new BigDecimal("1000");
    /** 重心闭区间下限（米）。 */
    private static final BigDecimal CG_MIN_M = new BigDecimal("-0.5");
    /** 重心闭区间上限（米）。 */
    private static final BigDecimal CG_MAX_M = new BigDecimal("0.5");

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"),
            rs.getString("leg_kind"), rs.getBigDecimal("sealed_total_mass"),
            rs.getBigDecimal("sealed_moment"), rs.getString("sealed_detail"));

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

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动；HOLD 航段须同时给出舱位。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.operator(), request.expectedVersion(),
                sortedCopy(request.bagTags()), sortedAssignments(request.holds()));
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /** 封舱：校验版本，保存只读装载清单并转 SEALED；HOLD 航段须总质量为正且重心在闭区间内。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.operator(), request.expectedVersion());
        return idempotencyService.execute(request.requestId(), "SEAL", 200,
                payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /** 批量换舱：仅 OPEN 的 HOLD 航段，按最终全体位置一次性校验舱重，整批原子。 */
    public ReholdResponse rehold(String legId, ReholdRequest request) {
        ReholdPayload payload = new ReholdPayload(legId, request.operator(), request.expectedVersion(),
                sortedAssignments(request.assignments()));
        return idempotencyService.execute(request.requestId(), "REHOLD", 200,
                payload, ReholdResponse.class, () -> doRehold(legId, request));
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

    /** 封舱清单查询：未封舱时返回空清单；HOLD 航段封舱后附冻结的总质量、力矩与单件明细。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null ? List.of() : readJsonList(leg.sealedManifest());
        List<SealedBagDetail> detail = leg.sealedDetail() == null
                ? null : readJson(leg.sealedDetail(), SealedBagDetail.class);
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest,
                leg.sealedTotalMass(), leg.sealedMoment(), detail);
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
        String kind = request.kind() == null ? KIND_CLASSIC : request.kind();
        if (!KIND_CLASSIC.equals(kind) && !KIND_HOLD.equals(kind)) {
            throw ApiException.unprocessable("未知航段类型: " + kind + "（仅支持 CLASSIC/HOLD）");
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, leg_kind)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN, kind);
        return new LegResponse(request.legId(), request.origin(), request.destination(), LEG_OPEN, 1, kind);
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
        List<String> bagTags = request.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw ApiException.unprocessable("批量装载的 bagTag 不得重复");
        }
        boolean holdLeg = KIND_HOLD.equals(leg.legKind());
        Map<String, String> assignments = validateHoldAssignments(leg, request.holds(), bagTags, holdLeg);
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateLoadable(bag, leg);
            if (holdLeg && bag.massKg() == null) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 未登记质量，不能装载到固定舱位航段");
            }
            bags.add(bag);
        }
        if (holdLeg) {
            Map<String, BigDecimal> holdTotals = currentHoldTotals(legId);
            for (BagRow bag : bags) {
                String holdCode = assignments.get(bag.bagTag());
                holdTotals.merge(holdCode, bag.massKg(), BigDecimal::add);
            }
            checkHoldLimits(holdTotals);
        }
        for (BagRow bag : bags) {
            String holdCode = holdLeg ? assignments.get(bag.bagTag()) : null;
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id, hold_code) VALUES (?, ?, ?)",
                    bag.bagTag(), legId, holdCode);
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
        if (!KIND_HOLD.equals(leg.legKind())) {
            jdbcTemplate.update("UPDATE leg SET status = ?, version = ?, sealed_manifest = ? WHERE leg_id = ?",
                    LEG_SEALED, newVersion, writeJson(manifest), legId);
            return new SealResponse(legId, LEG_SEALED, newVersion, manifest);
        }
        List<SealedBagDetail> detail = loadHoldDetail(legId);
        BigDecimal totalMass = BigDecimal.ZERO;
        BigDecimal moment = BigDecimal.ZERO;
        for (SealedBagDetail item : detail) {
            totalMass = totalMass.add(item.massKg());
            moment = moment.add(item.massKg().multiply(BigDecimal.valueOf(item.positionM())));
        }
        if (totalMass.signum() <= 0) {
            throw ApiException.unprocessable("封舱要求舱内行李总质量为正值");
        }
        // 交叉相乘比较，不先舍入重心：CG_MIN <= moment/total <= CG_MAX
        if (moment.compareTo(CG_MIN_M.multiply(totalMass)) < 0
                || moment.compareTo(CG_MAX_M.multiply(totalMass)) > 0) {
            throw ApiException.unprocessable(
                    "重心超限：总质量 " + totalMass + " 千克，力矩 " + moment
                            + " 千克·米，重心须位于闭区间 [" + CG_MIN_M + ", " + CG_MAX_M + "] 米");
        }
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, sealed_manifest = ?, sealed_total_mass = ?,"
                        + " sealed_moment = ?, sealed_detail = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(manifest), totalMass, moment,
                writeJson(detail), legId);
        return new SealResponse(legId, LEG_SEALED, newVersion, manifest, totalMass, moment);
    }

    private ReholdResponse doRehold(String legId, ReholdRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!KIND_HOLD.equals(leg.legKind())) {
            throw ApiException.unprocessable("旧航段无舱位概念，禁止换舱");
        }
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，封舱后禁止换舱");
        }
        List<HoldAssignment> assignments = request.assignments();
        Set<String> tags = new HashSet<>();
        for (HoldAssignment assignment : assignments) {
            if (!tags.add(assignment.bagTag())) {
                throw ApiException.unprocessable("批量换舱的 bagTag 不得重复: " + assignment.bagTag());
            }
            holdPosition(assignment.holdCode());
        }
        Map<String, String> currentHolds = currentHoldMap(legId);
        Map<String, BigDecimal> masses = currentLoadMasses(legId);
        for (HoldAssignment assignment : assignments) {
            if (!currentHolds.containsKey(assignment.bagTag())) {
                throw ApiException.unprocessable(
                        "行李 " + assignment.bagTag() + " 未装载到航段 " + legId + "，不能换舱");
            }
        }
        // 按最终全体位置一次性核算舱重：先移除全部换舱行李的旧舱质量，再计入新舱
        Map<String, BigDecimal> finalTotals = currentHoldTotals(legId);
        for (HoldAssignment assignment : assignments) {
            BigDecimal mass = masses.get(assignment.bagTag());
            finalTotals.merge(currentHolds.get(assignment.bagTag()), mass, BigDecimal::subtract);
        }
        List<String> changed = new ArrayList<>();
        for (HoldAssignment assignment : assignments) {
            finalTotals.merge(assignment.holdCode(), masses.get(assignment.bagTag()), BigDecimal::add);
            if (!assignment.holdCode().equals(currentHolds.get(assignment.bagTag()))) {
                changed.add(assignment.bagTag());
            }
        }
        checkHoldLimits(finalTotals);
        for (HoldAssignment assignment : assignments) {
            jdbcTemplate.update("UPDATE load_record SET hold_code = ? WHERE bag_tag = ? AND leg_id = ?",
                    assignment.holdCode(), assignment.bagTag(), legId);
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new ReholdResponse(legId, LEG_OPEN, newVersion, sortedCopy(changed));
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
                + " arrival_type, arrival_actual, leg_kind, sealed_total_mass, sealed_moment,"
                + " sealed_detail FROM leg WHERE leg_id = ?"
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
                + " short_leg_id, short_destination, short_registered_at, mass_kg FROM bag"
                + " WHERE bag_tag = ?" + (forUpdate ? " FOR UPDATE" : "");
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
                bag.massKg(), toEvents(bag.bagTag()));
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    private String writeJson(Object values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("清单序列化失败", ex);
        }
    }

    private List<String> readJsonList(String json) {
        return readJson(json, String.class);
    }

    private <T> List<T> readJson(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception ex) {
            throw new IllegalStateException("清单反序列化失败", ex);
        }
    }

    /**
     * 校验装载请求的舱位指派：HOLD 航段必须覆盖全部 bagTag 且无多余指派；
     * 旧航段不得携带舱位指派。返回 bagTag -> holdCode 映射（旧航段为空 Map）。
     */
    private Map<String, String> validateHoldAssignments(LegRow leg, List<HoldAssignment> holds,
                                                        List<String> bagTags, boolean holdLeg) {
        if (!holdLeg) {
            if (holds != null && !holds.isEmpty()) {
                throw ApiException.unprocessable("旧航段 " + leg.legId() + " 无舱位概念，不得携带舱位指派");
            }
            return Map.of();
        }
        if (holds == null || holds.isEmpty()) {
            throw ApiException.unprocessable("固定舱位航段 " + leg.legId() + " 装载必须同时给出舱位");
        }
        Map<String, String> assignments = new HashMap<>();
        for (HoldAssignment hold : holds) {
            holdPosition(hold.holdCode());
            if (assignments.putIfAbsent(hold.bagTag(), hold.holdCode()) != null) {
                throw ApiException.unprocessable("舱位指派的 bagTag 不得重复: " + hold.bagTag());
            }
        }
        for (String bagTag : bagTags) {
            if (!assignments.containsKey(bagTag)) {
                throw ApiException.unprocessable("行李 " + bagTag + " 缺少舱位指派");
            }
        }
        for (String tag : assignments.keySet()) {
            if (!bagTags.contains(tag)) {
                throw ApiException.unprocessable("舱位指派包含本批之外的行李: " + tag);
            }
        }
        return assignments;
    }

    /** 舱位纵向位置（米）：FWD -2 / AFT 3，其余代码 422。 */
    private BigDecimal holdPosition(String holdCode) {
        return switch (holdCode) {
            case HOLD_FWD -> FWD_POSITION_M;
            case HOLD_AFT -> AFT_POSITION_M;
            default -> throw ApiException.unprocessable(
                    "未知舱位: " + holdCode + "（仅支持 FWD/AFT）");
        };
    }

    /** 当前航段各舱已装总质量（千克）。 */
    private Map<String, BigDecimal> currentHoldTotals(String legId) {
        Map<String, BigDecimal> totals = new HashMap<>();
        totals.put(HOLD_FWD, BigDecimal.ZERO);
        totals.put(HOLD_AFT, BigDecimal.ZERO);
        jdbcTemplate.query(
                "SELECT lr.hold_code, COALESCE(SUM(b.mass_kg), 0) AS total FROM load_record lr"
                        + " JOIN bag b ON b.bag_tag = lr.bag_tag WHERE lr.leg_id = ?"
                        + " GROUP BY lr.hold_code",
                rs -> {
                    totals.put(rs.getString("hold_code"), rs.getBigDecimal("total"));
                }, legId);
        return totals;
    }

    /** 当前航段每件已装行李的舱位。 */
    private Map<String, String> currentHoldMap(String legId) {
        Map<String, String> holds = new HashMap<>();
        jdbcTemplate.query(
                "SELECT bag_tag, hold_code FROM load_record WHERE leg_id = ?",
                rs -> {
                    holds.put(rs.getString("bag_tag"), rs.getString("hold_code"));
                }, legId);
        return holds;
    }

    /** 当前航段每件已装行李的质量（千克）。 */
    private Map<String, BigDecimal> currentLoadMasses(String legId) {
        Map<String, BigDecimal> masses = new HashMap<>();
        jdbcTemplate.query(
                "SELECT lr.bag_tag, b.mass_kg FROM load_record lr"
                        + " JOIN bag b ON b.bag_tag = lr.bag_tag WHERE lr.leg_id = ?",
                rs -> {
                    masses.put(rs.getString("bag_tag"), rs.getBigDecimal("mass_kg"));
                }, legId);
        return masses;
    }

    /** 封舱时读取每件已装行李的质量、舱位与纵向位置，用于冻结快照与重心核算。 */
    private List<SealedBagDetail> loadHoldDetail(String legId) {
        return jdbcTemplate.query(
                "SELECT lr.bag_tag, b.mass_kg, lr.hold_code FROM load_record lr"
                        + " JOIN bag b ON b.bag_tag = lr.bag_tag WHERE lr.leg_id = ?"
                        + " ORDER BY lr.bag_tag",
                (rs, rowNum) -> {
                    String holdCode = rs.getString("hold_code");
                    return new SealedBagDetail(rs.getString("bag_tag"), rs.getBigDecimal("mass_kg"),
                            holdCode, holdPosition(holdCode).intValue());
                }, legId);
    }

    /** 单舱限重校验：任一舱超过 1000 千克则 422（调用方整批回滚）。 */
    private void checkHoldLimits(Map<String, BigDecimal> holdTotals) {
        for (Map.Entry<String, BigDecimal> entry : holdTotals.entrySet()) {
            if (entry.getValue().compareTo(HOLD_LIMIT_KG) > 0) {
                throw ApiException.unprocessable(
                        "舱位 " + entry.getKey() + " 超重：合计 " + entry.getValue()
                                + " 千克，限重 " + HOLD_LIMIT_KG + " 千克");
            }
        }
    }

    /** 舱位指派按袋号排序，保证幂等摘要对顺序差异不敏感。 */
    private static List<HoldAssignment> sortedAssignments(List<HoldAssignment> assignments) {
        if (assignments == null) {
            return null;
        }
        return assignments.stream()
                .sorted(java.util.Comparator.comparing(HoldAssignment::bagTag))
                .toList();
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual, String legKind,
                          BigDecimal sealedTotalMass, BigDecimal sealedMoment, String sealedDetail) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt, BigDecimal massKg) {
    }

    /** 装载幂等摘要参数：bagTags/holds 已排序，顺序差异不视为异参；含操作者与期望版本。 */
    private record LoadPayload(String legId, String operator, int expectedVersion,
                               List<String> bagTags, List<HoldAssignment> holds) {
    }

    /** 封舱幂等摘要参数：含操作者与期望版本。 */
    private record SealPayload(String legId, String operator, int expectedVersion) {
    }

    /** 换舱幂等摘要参数：assignments 已按袋号排序，含操作者与期望版本。 */
    private record ReholdPayload(String legId, String operator, int expectedVersion,
                                 List<HoldAssignment> assignments) {
    }

    /** 到达确认幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record ArrivePayload(String legId, List<String> bagTags) {
    }

    /** 差异到达幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record DifferenceArrivePayload(String legId, int expectedVersion, List<String> bagTags) {
    }
}
