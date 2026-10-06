package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.example.starter.baggage.BaggageDtos.ArrangeItem;
import com.example.starter.baggage.BaggageDtos.ArrangeRequest;
import com.example.starter.baggage.BaggageDtos.ArrangeResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
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
import com.example.starter.baggage.BaggageDtos.StackViewResponse;
import com.example.starter.baggage.BaggageDtos.TraceEvent;
import com.example.starter.baggage.BaggageDtos.WeightProfileCreateRequest;
import com.example.starter.baggage.BaggageDtos.WeightProfileResponse;
import com.example.starter.baggage.BaggageDtos.WeightProfileUpdateRequest;

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

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("sealed_stack"), rs.getString("arrival_type"), rs.getString("arrival_actual"));

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

    /** 创建重量档案：自重正整数克、承压非负整数克，版本从 1 起。 */
    public WeightProfileResponse createWeightProfile(String bagTag, WeightProfileCreateRequest request) {
        WeightCreatePayload payload = new WeightCreatePayload(
                bagTag, request.weightGrams(), request.maxLoadGrams());
        return idempotencyService.execute(request.requestId(), "CREATE_WEIGHT", 201,
                payload, WeightProfileResponse.class, () -> doCreateWeightProfile(bagTag, request));
    }

    /**
     * 修改重量档案：expectedVersion 乐观锁，冲突 409；已装载行李修改后若令当前堆栈超压则 409；
     * 航段已封舱时重量档案冻结，修改已装行李档案 409。
     */
    public WeightProfileResponse updateWeightProfile(String bagTag, WeightProfileUpdateRequest request) {
        WeightUpdatePayload payload = new WeightUpdatePayload(
                bagTag, request.weightGrams(), request.maxLoadGrams(), request.expectedVersion());
        return idempotencyService.execute(request.requestId(), "UPDATE_WEIGHT", 200,
                payload, WeightProfileResponse.class, () -> doUpdateWeightProfile(bagTag, request));
    }

    /** 重量档案查询。 */
    public WeightProfileResponse getWeightProfile(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        WeightRow profile = findWeight(bagTag);
        if (profile == null) {
            throw ApiException.notFound("重量档案不存在: " + bagTag);
        }
        return toWeightResponse(profile);
    }

    /**
     * 堆叠排舱：声明舱内完整后态（装载、卸载、换层一次生效），核验通过才落库；
     * 层号空洞、重复或承压超限 422，原清单不变；已封舱禁止新排堆栈。
     */
    public ArrangeResponse arrange(String legId, ArrangeRequest request) {
        ArrangePayload payload = new ArrangePayload(
                legId, request.expectedVersion(), normalizeItems(request.items()));
        return idempotencyService.execute(request.requestId(), "ARRANGE", 200,
                payload, ArrangeResponse.class, () -> doArrange(legId, request));
    }

    /** 舱内堆叠视图：未封舱返回当前层序，封舱后返回冻结的封舱快照。 */
    public StackViewResponse getStacks(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<ArrangeItem> items = leg.sealedStack() == null
                ? currentStackItems(legId) : readStackItems(leg.sealedStack());
        return new StackViewResponse(leg.legId(), leg.status(), leg.version(), items);
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
        // 封舱冻结当前堆叠层序与各层载荷：快照只读保存，历史不漂移
        List<ArrangeItem> stackSnapshot = currentStackItems(legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, sealed_manifest = ?, sealed_stack = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(manifest), writeJson(stackSnapshot), legId);
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

    private WeightProfileResponse doCreateWeightProfile(String bagTag, WeightProfileCreateRequest request) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (findWeight(bagTag) != null) {
            throw ApiException.conflict("重量档案已存在: " + bagTag);
        }
        jdbcTemplate.update(
                "INSERT INTO bag_weight (bag_tag, weight_grams, max_load_grams, version) VALUES (?, ?, ?, 1)",
                bagTag, request.weightGrams(), request.maxLoadGrams());
        return new WeightProfileResponse(bagTag, request.weightGrams(), request.maxLoadGrams(), 1);
    }

    private WeightProfileResponse doUpdateWeightProfile(String bagTag, WeightProfileUpdateRequest request) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        // 与封舱/排舱串行：先持航段行锁再持行李行锁，与装载链路锁序一致
        LegRow loadedLeg = bag.loadedLegId() == null ? null : lockLeg(bag.loadedLegId());
        bag = lockBag(bagTag);
        WeightRow profile = findWeight(bagTag);
        if (profile == null) {
            throw ApiException.notFound("重量档案不存在: " + bagTag);
        }
        if (profile.version() != request.expectedVersion()) {
            throw ApiException.conflict(
                    "重量档案版本冲突: 期望 " + request.expectedVersion() + "，当前 " + profile.version());
        }
        if (bag.loadedLegId() != null) {
            if (loadedLeg == null || !loadedLeg.legId().equals(bag.loadedLegId())) {
                throw ApiException.conflict("行李 " + bagTag + " 装载状态发生变化，请重试");
            }
            if (!LEG_OPEN.equals(loadedLeg.status())) {
                throw ApiException.conflict(
                        "航段 " + loadedLeg.legId() + " 已封舱，已装行李重量档案冻结，禁止修改");
            }
            ensureStackPressureAfterWeightChange(loadedLeg.legId(), bagTag,
                    request.weightGrams(), request.maxLoadGrams());
        }
        int newVersion = profile.version() + 1;
        jdbcTemplate.update(
                "UPDATE bag_weight SET weight_grams = ?, max_load_grams = ?, version = ? WHERE bag_tag = ?",
                request.weightGrams(), request.maxLoadGrams(), newVersion, bagTag);
        return new WeightProfileResponse(bagTag, request.weightGrams(), request.maxLoadGrams(), newVersion);
    }

    private ArrangeResponse doArrange(String legId, ArrangeRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable(
                    "航段状态为 " + leg.status() + "，已封舱禁止新排堆栈");
        }
        List<ArrangeItem> items = request.items();
        Set<String> seen = new HashSet<>();
        for (ArrangeItem item : items) {
            if (!seen.add(item.bagTag())) {
                throw ApiException.unprocessable("排舱行李不得重复: " + item.bagTag());
            }
        }
        Map<Integer, List<ArrangeItem>> byStack = new HashMap<>();
        for (ArrangeItem item : items) {
            byStack.computeIfAbsent(item.stackNo(), key -> new ArrayList<>()).add(item);
        }
        // 层号核验：同堆栈从底部 1 起连续，空洞或重复整次拒绝
        for (Map.Entry<Integer, List<ArrangeItem>> entry : byStack.entrySet()) {
            List<ArrangeItem> stack = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ArrangeItem::layer)).toList();
            for (int i = 0; i < stack.size(); i++) {
                if (stack.get(i).layer() != i + 1) {
                    throw ApiException.unprocessable(
                            "堆栈 " + entry.getKey() + " 层号须从底部 1 起连续，存在空洞或重复");
                }
            }
        }
        // 行李资格与重量档案核验：全部通过才允许落库
        Map<String, WeightRow> weights = new HashMap<>();
        List<String> sortedTags = items.stream().map(ArrangeItem::bagTag).sorted().toList();
        for (String bagTag : sortedTags) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateArrangeable(bag, leg);
            WeightRow weight = findWeight(bagTag);
            if (weight == null) {
                throw ApiException.unprocessable("行李 " + bagTag + " 缺少重量档案，无法排舱");
            }
            weights.put(bagTag, weight);
        }
        // 承压核验：每件行李上方全部行李重量之和不得超过其承压，零承压只能在顶层
        for (List<ArrangeItem> stack : byStack.values()) {
            List<StackEntry> entries = stack.stream()
                    .map(item -> {
                        WeightRow weight = weights.get(item.bagTag());
                        return new StackEntry(item.bagTag(), item.layer(),
                                weight.weightGrams(), weight.maxLoadGrams());
                    })
                    .sorted(Comparator.comparingInt(StackEntry::layer))
                    .toList();
            String violation = stackPressureViolation(entries);
            if (violation != null) {
                throw ApiException.unprocessable(violation);
            }
        }
        // 完整后态落库：清单外原在舱行李卸载，保留者改层，新增者装载
        List<String> current = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ?", String.class, legId);
        for (String bagTag : current) {
            if (!seen.contains(bagTag)) {
                jdbcTemplate.update("DELETE FROM load_record WHERE bag_tag = ?", bagTag);
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = NULL WHERE bag_tag = ?", bagTag);
            }
        }
        for (ArrangeItem item : items) {
            if (current.contains(item.bagTag())) {
                jdbcTemplate.update(
                        "UPDATE load_record SET stack_no = ?, layer_no = ? WHERE bag_tag = ?",
                        item.stackNo(), item.layer(), item.bagTag());
            } else {
                jdbcTemplate.update(
                        "INSERT INTO load_record (bag_tag, leg_id, stack_no, layer_no) VALUES (?, ?, ?, ?)",
                        item.bagTag(), legId, item.stackNo(), item.layer());
                jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?",
                        legId, item.bagTag());
                insertEvent(item.bagTag(), EVT_LOADED, legId, leg.origin());
            }
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new ArrangeResponse(legId, LEG_OPEN, newVersion, normalizeItems(items));
    }

    /** 排舱资格：短卸须先补到；已装载到他段、待乘航段不符、不在始发站均 422。 */
    private void validateArrangeable(BagRow bag, LegRow leg) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能装载后续航段");
        }
        if (bag.loadedLegId() != null && !bag.loadedLegId().equals(leg.legId())) {
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

    /** 已装载行李重量修改后的堆栈复验：超压则 409，原档案不变。 */
    private void ensureStackPressureAfterWeightChange(String legId, String bagTag,
                                                      int newWeightGrams, int newMaxLoadGrams) {
        List<Integer> stacks = jdbcTemplate.queryForList(
                "SELECT stack_no FROM load_record WHERE bag_tag = ? AND stack_no IS NOT NULL",
                Integer.class, bagTag);
        if (stacks.isEmpty()) {
            return;
        }
        List<StackEntry> entries = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT lr.bag_tag, lr.layer_no, bw.weight_grams, bw.max_load_grams"
                        + " FROM load_record lr JOIN bag_weight bw ON bw.bag_tag = lr.bag_tag"
                        + " WHERE lr.leg_id = ? AND lr.stack_no = ?",
                rs -> {
                    String tag = rs.getString("bag_tag");
                    int weight = tag.equals(bagTag) ? newWeightGrams : rs.getInt("weight_grams");
                    int maxLoad = tag.equals(bagTag) ? newMaxLoadGrams : rs.getInt("max_load_grams");
                    entries.add(new StackEntry(tag, rs.getInt("layer_no"), weight, maxLoad));
                }, legId, stacks.get(0));
        entries.sort(Comparator.comparingInt(StackEntry::layer));
        String violation = stackPressureViolation(entries);
        if (violation != null) {
            throw ApiException.conflict("修改后" + violation);
        }
    }

    /** 单堆栈承压核验：entries 按层号升序，返回超限描述，未超限返回 null。 */
    private static String stackPressureViolation(List<StackEntry> stack) {
        long above = 0;
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackEntry entry = stack.get(i);
            if (above > entry.maxLoadGrams()) {
                return "行李 " + entry.bagTag() + " 上方重量 " + above
                        + " 克超过其承压 " + entry.maxLoadGrams() + " 克";
            }
            above += entry.weightGrams();
        }
        return null;
    }

    /** 当前舱内已排层序（仅含已指定堆栈位置的装载记录），按堆栈号、层号升序。 */
    private List<ArrangeItem> currentStackItems(String legId) {
        return jdbcTemplate.query(
                "SELECT bag_tag, stack_no, layer_no FROM load_record"
                        + " WHERE leg_id = ? AND stack_no IS NOT NULL ORDER BY stack_no, layer_no",
                (rs, rowNum) -> new ArrangeItem(rs.getString("bag_tag"),
                        rs.getInt("stack_no"), rs.getInt("layer_no")), legId);
    }

    private WeightRow findWeight(String bagTag) {
        List<WeightRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, weight_grams, max_load_grams, version FROM bag_weight WHERE bag_tag = ?",
                (rs, rowNum) -> new WeightRow(rs.getString("bag_tag"), rs.getInt("weight_grams"),
                        rs.getInt("max_load_grams"), rs.getInt("version")), bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static WeightProfileResponse toWeightResponse(WeightRow profile) {
        return new WeightProfileResponse(profile.bagTag(), profile.weightGrams(),
                profile.maxLoadGrams(), profile.version());
    }

    /** 排舱明细规范化：按堆栈号、层号升序，顺序差异不视为异参。 */
    private static List<ArrangeItem> normalizeItems(List<ArrangeItem> items) {
        return items.stream()
                .sorted(Comparator.comparingInt(ArrangeItem::stackNo)
                        .thenComparingInt(ArrangeItem::layer))
                .toList();
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
        return "SELECT leg_id, origin, destination, status, version, sealed_manifest, sealed_stack,"
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

    private List<ArrangeItem> readStackItems(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ArrangeItem.class));
        } catch (Exception ex) {
            throw new IllegalStateException("层序快照反序列化失败", ex);
        }
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest, String sealedStack,
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

    /** 重量档案行。 */
    private record WeightRow(String bagTag, int weightGrams, int maxLoadGrams, int version) {
    }

    /** 堆栈承压核验条目。 */
    private record StackEntry(String bagTag, int layer, int weightGrams, int maxLoadGrams) {
    }

    /** 创建重量档案幂等摘要参数。 */
    private record WeightCreatePayload(String bagTag, int weightGrams, int maxLoadGrams) {
    }

    /** 修改重量档案幂等摘要参数。 */
    private record WeightUpdatePayload(String bagTag, int weightGrams, int maxLoadGrams,
                                       int expectedVersion) {
    }

    /** 排舱幂等摘要参数：items 已按堆栈号、层号排序，顺序差异不视为异参。 */
    private record ArrangePayload(String legId, int expectedVersion, List<ArrangeItem> items) {
    }
}
