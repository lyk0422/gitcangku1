package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
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
import com.example.starter.baggage.BaggageDtos.ManifestItem;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RetagRequest;
import com.example.starter.baggage.BaggageDtos.RetagResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TagAliasItem;
import com.example.starter.baggage.BaggageDtos.TraceEvent;

/**
 * 联程行李装载交接核心业务：航段/行李登记、换标、批量装载、封舱、精确/差异到达、补到与查询。
 * 每件行李有跨换标稳定的 bagId 与单调递增的标签代次（首号为 1）；bagTag 仅代表当前标签，
 * 历次标签永久登记于 bag_tag_alias，不再分配给另一实物。装载、补到等一切写入都必须同时
 * 出示当前标签号与期望代次，旧标签或过期代次一律 409，未知标签 404。
 * 封舱冻结当时的稳定ID/标签/代次，到达按冻结清单的稳定ID处理；所有写操作经
 * {@link IdempotencyService} 去重，业务变更与去重记录原子提交。
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
    private static final String EVT_RETAG = "TAG_REPLACED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_id"), rs.getString("bag_tag"), rs.getInt("tag_generation"),
            rs.getString("current_location"), rs.getInt("next_leg_index"), rs.getString("status"),
            rs.getString("loaded_leg_id"), rs.getString("short_leg_id"),
            rs.getString("short_destination"), getInstant(rs, "short_registered_at"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString());

    private static final RowMapper<TagAliasItem> ALIAS_MAPPER = (rs, rowNum) -> new TagAliasItem(
            rs.getString("bag_tag"), rs.getInt("generation"));

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_id"), rs.getString("bag_tag"), rs.getInt("tag_generation"),
            rs.getString("short_leg_id"), rs.getString("short_destination"),
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
        return idempotencyService.execute(request.requestId(), request.operator(), "REGISTER_LEG",
                request.legId(), 201, new RegisterLegPayload(request.origin(), request.destination()),
                LegResponse.class, () -> doRegisterLeg(request));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站；代次为 1。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        RegisterBagPayload payload = new RegisterBagPayload(
                request.bagId(), request.bagTag(), request.legIds());
        return idempotencyService.execute(request.requestId(), request.operator(), "REGISTER_BAG",
                request.bagId(), 201, payload, BagResponse.class, () -> doRegisterBag(request));
    }

    /**
     * 换标：指定稳定 bagId、期望当前代次与全局未用新标签。
     * 仅未装舱、非 SHORT_UNLOADED、未 DELIVERED 允许；成功后当前标签替换、代次加一，
     * 旧标签永久保留为别名。已用标签（含任何行李的旧标签）一律 409。
     */
    public RetagResponse retag(RetagRequest request) {
        RetagPayload payload = new RetagPayload(
                request.bagId(), request.expectedTagGeneration(), request.newBagTag());
        return idempotencyService.execute(request.requestId(), request.operator(), "RETAG",
                request.bagId(), 200, payload, RetagResponse.class, () -> doRetag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则 422/409 且无一件移动；必须当前号与代次同时匹配。 */
    public LoadResponse load(String legId, LoadRequest request) {
        TreeMap<String, Integer> generations = new TreeMap<>(request.expectedTagGenerations());
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(),
                sortedCopy(request.bagTags()), generations);
        return idempotencyService.execute(request.requestId(), request.operator(), "LOAD",
                legId, 200, payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /** 封舱：校验版本，冻结当时稳定ID/标签/代次的只读清单并转 SEALED；空清单也可封舱。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.expectedVersion());
        return idempotencyService.execute(request.requestId(), request.operator(), "SEAL",
                legId, 200, payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /** 到达确认：实际袋号集合须与封舱冻结清单完全一致，按清单存储的稳定ID推进各行李。 */
    public ArriveResponse arrive(String legId, ArriveRequest request) {
        ArrivePayload payload = new ArrivePayload(legId, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), request.operator(), "ARRIVE",
                legId, 200, payload, ArriveResponse.class, () -> doArrive(legId, request));
    }

    /** 差异到达：仅 SEALED 航段可提交，actual 标签须为冻结清单子集（允许空集）且不得重复。 */
    public DifferenceArriveResponse arriveDifference(String legId, DifferenceArriveRequest request) {
        DifferenceArrivePayload payload = new DifferenceArrivePayload(
                legId, request.expectedVersion(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), request.operator(), "ARRIVE_DIFFERENCE",
                legId, 200, payload, DifferenceArriveResponse.class,
                () -> doArriveDifference(legId, request));
    }

    /** 补到：短卸行李在缺失航段应到站实际到达，必须当前标签号与代次同时匹配；同参重放原结果。 */
    public RecoverResponse recover(RecoverRequest request) {
        RecoverPayload payload = new RecoverPayload(request.bagTag(), request.expectedTagGeneration(),
                request.missingLegId(), request.actualStation());
        return idempotencyService.execute(request.requestId(), request.operator(), "RECOVER",
                request.bagTag(), 200, payload, RecoverResponse.class, () -> doRecover(request));
    }
    /** 行李完整轨迹查询：接受当前标签或任一历史旧标签，返回稳定ID、当前号、代次与别名。 */
    public BagResponse getBagTrace(String bagTagOrAlias) {
        BagRow bag = findBagByAnyTag(bagTagOrAlias);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTagOrAlias);
        }
        return toBagResponse(bag);
    }

    /** 封舱清单查询：未封舱时返回空清单。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<ManifestItem> manifest = leg.sealedManifest() == null
                ? List.of() : readManifest(leg.sealedManifest());
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest);
    }

    /** 航段差异快照查询：返回只读冻结清单、到达类型及差异到达实际集合快照。 */
    public LegDifferenceResponse getDifference(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<ManifestItem> manifest = leg.sealedManifest() == null
                ? List.of() : readManifest(leg.sealedManifest());
        List<ManifestItem> actual = switch (leg.arrivalType() == null ? "" : leg.arrivalType()) {
            case ARRIVAL_DIFF -> leg.arrivalActual() == null ? List.of() : readManifest(leg.arrivalActual());
            case ARRIVAL_EXACT -> manifest;
            default -> null;
        };
        return new LegDifferenceResponse(leg.legId(), leg.status(), leg.version(),
                manifest, leg.arrivalType(), actual);
    }

    /** 未补到短卸行李清单查询：仅含当前仍为 SHORT_UNLOADED 的行李。 */
    public ShortListResponse listShortUnloaded() {
        List<ShortItem> items = jdbcTemplate.query(
                "SELECT bag_id, bag_tag, tag_generation, short_leg_id, short_destination,"
                        + " short_registered_at, next_leg_index, current_location FROM bag"
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
        if (findBagById(request.bagId()) != null) {
            throw ApiException.conflict("行李稳定ID已存在: " + request.bagId());
        }
        if (tagExists(request.bagTag())) {
            throw ApiException.conflict("行李牌号已被占用（含历史标签）: " + request.bagTag());
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
                "INSERT INTO bag (bag_id, bag_tag, tag_generation, current_location, next_leg_index, status)"
                        + " VALUES (?, ?, 1, ?, 0, ?)",
                request.bagId(), request.bagTag(), startStation, BAG_IN_TRANSIT);
        insertAlias(request.bagId(), request.bagTag(), 1);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_id, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    request.bagId(), i, leg.legId(), leg.origin(), leg.destination());
        }
        insertEvent(request.bagId(), EVT_REGISTERED, null, startStation);
        return toBagResponse(findBagById(request.bagId()));
    }

    private RetagResponse doRetag(RetagRequest request) {
        BagRow bag = lockBagById(request.bagId());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagId());
        }
        if (bag.tagGeneration() != request.expectedTagGeneration()) {
            throw ApiException.conflict(
                    "标签代次冲突: 期望 " + request.expectedTagGeneration()
                            + "，当前 " + bag.tagGeneration());
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已装舱到航段 " + bag.loadedLegId() + "，禁止换标");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能换标");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 已交付，禁止换标");
        }
        if (request.newBagTag().equals(bag.bagTag())) {
            throw ApiException.conflict("新标签与当前标签相同: " + bag.bagTag());
        }
        int newGeneration = bag.tagGeneration() + 1;
        String oldTag = bag.bagTag();
        try {
            // 先占全局标签名：任何行李的当前/历史标签都不可再用
            insertAlias(request.bagId(), request.newBagTag(), newGeneration);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("新标签已被占用（含历史标签）: " + request.newBagTag());
        }
        jdbcTemplate.update(
                "UPDATE bag SET bag_tag = ?, tag_generation = ? WHERE bag_id = ?",
                request.newBagTag(), newGeneration, request.bagId());
        insertEvent(request.bagId(), EVT_RETAG, null, bag.currentLocation());
        return new RetagResponse(request.bagId(), oldTag, request.newBagTag(), newGeneration,
                bag.status(), bag.currentLocation());
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
        var expectedGenerations = request.expectedTagGenerations();
        for (String bagTag : bagTags) {
            if (!expectedGenerations.containsKey(bagTag)) {
                throw ApiException.conflict(
                        "缺少行李 " + bagTag + " 的期望标签代次 expectedTagGenerations");
            }
        }
        if (expectedGenerations.size() != bagTags.size()) {
            throw ApiException.unprocessable("期望代次条目必须与 bagTags 一一对应");
        }
        List<String> sorted = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBagByTag(bagTag);
            if (bag == null) {
                // 当前标签不存在：已被换走的历史旧标签按过期冲突 409，完全未知标签 404
                throw tagNotFound(bagTag);
            }
            checkCurrentTag(bag, bagTag, expectedGenerations.get(bagTag));
            validateLoadable(bag, leg);
            bags.add(bag);
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update(
                    "INSERT INTO load_record (bag_id, bag_tag, leg_id) VALUES (?, ?, ?)",
                    bag.bagId(), bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_id = ?", legId, bag.bagId());
            insertEvent(bag.bagId(), EVT_LOADED, legId, leg.origin());
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
        List<ManifestItem> manifest = jdbcTemplate.query(
                "SELECT b.bag_id AS bag_id, b.bag_tag AS bag_tag, b.tag_generation AS tag_generation"
                        + " FROM load_record lr JOIN bag b ON b.bag_id = lr.bag_id"
                        + " WHERE lr.leg_id = ? ORDER BY b.bag_tag",
                (rs, rowNum) -> new ManifestItem(
                        rs.getString("bag_id"), rs.getString("bag_tag"), rs.getInt("tag_generation")),
                legId);
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
        List<ManifestItem> manifest = readManifest(leg.sealedManifest());
        List<String> actual = request.bagTags();
        Set<String> actualSet = new HashSet<>(actual);
        if (actualSet.size() != actual.size()
                || !actualSet.equals(new HashSet<>(manifest.stream().map(ManifestItem::bagTag).toList()))) {
            throw ApiException.unprocessable("实际袋号集合与封舱清单不一致");
        }
        for (ManifestItem item : manifest) {
            BagRow bag = lockBagById(item.bagId());
            if (bag == null) {
                throw new IllegalStateException("封舱清单中的行李已不存在: " + item.bagId());
            }
            advanceArrivedBag(bag, leg.destination(), legId);
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = NULL WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_EXACT, legId);
        return new ArriveResponse(legId, LEG_ARRIVED, newVersion, sortedManifest(manifest));
    }

    private DifferenceArriveResponse doArriveDifference(String legId, DifferenceArriveRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止差异到达");
        }
        List<ManifestItem> manifest = readManifest(leg.sealedManifest());
        List<String> actual = request.bagTags();
        Set<String> actualSet = new HashSet<>(actual);
        if (actualSet.size() != actual.size()) {
            throw ApiException.unprocessable("实际袋号集合不得重复");
        }
        Set<String> manifestTags = new HashSet<>();
        java.util.Map<String, ManifestItem> byTag = new java.util.HashMap<>();
        for (ManifestItem item : manifest) {
            manifestTags.add(item.bagTag());
            byTag.put(item.bagTag(), item);
        }
        if (!manifestTags.containsAll(actualSet)) {
            throw ApiException.unprocessable("实际袋号存在封舱清单外袋号，整次差异到达拒绝");
        }
        List<ManifestItem> arrivedSorted = actualSet.stream()
                .map(byTag::get).sorted(java.util.Comparator.comparing(ManifestItem::bagTag)).toList();
        List<ManifestItem> shortList = manifest.stream()
                .filter(item -> !actualSet.contains(item.bagTag()))
                .sorted(java.util.Comparator.comparing(ManifestItem::bagTag))
                .toList();
        OffsetDateTime registeredAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (ManifestItem item : manifest) {
            BagRow bag = lockBagById(item.bagId());
            if (bag == null) {
                throw new IllegalStateException("封舱清单中的行李已不存在: " + item.bagId());
            }
            if (actualSet.contains(item.bagTag())) {
                advanceArrivedBag(bag, leg.destination(), legId);
            } else {
                jdbcTemplate.update(
                        "UPDATE bag SET status = ?, loaded_leg_id = NULL, short_leg_id = ?,"
                                + " short_destination = ?, short_registered_at = ? WHERE bag_id = ?",
                        BAG_SHORT_UNLOADED, legId, leg.destination(), registeredAt, bag.bagId());
                insertEvent(bag.bagId(), EVT_SHORT, legId, bag.currentLocation());
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
        BagRow bag = lockBagByTag(request.bagTag());
        if (bag == null) {
            throw tagNotFound(request.bagTag());
        }
        checkCurrentTag(bag, request.bagTag(), request.expectedTagGeneration());
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
        int total = itineraryCount(bag.bagId());
        String newStatus = nextIndex >= total ? BAG_DELIVERED : BAG_RECOVERED;
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                        + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL"
                        + " WHERE bag_id = ?",
                request.actualStation(), nextIndex, newStatus, bag.bagId());
        insertEvent(bag.bagId(), EVT_RECOVERED, request.missingLegId(), request.actualStation());
        if (BAG_DELIVERED.equals(newStatus)) {
            insertEvent(bag.bagId(), EVT_DELIVERED, request.missingLegId(), request.actualStation());
        }
        return new RecoverResponse(bag.bagId(), bag.bagTag(), bag.tagGeneration(), newStatus,
                request.actualStation(), nextIndex, request.missingLegId());
    }

    /** 实际到达行李的统一推进：移动到到达站、推进待乘索引，完成行程者交付。 */
    private void advanceArrivedBag(BagRow bag, String destination, String legId) {
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bag.bagId());
        String status = nextIndex >= total ? BAG_DELIVERED : BAG_IN_TRANSIT;
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL"
                        + " WHERE bag_id = ?",
                destination, nextIndex, status, bag.bagId());
        insertEvent(bag.bagId(), EVT_UNLOADED, legId, destination);
        if (BAG_DELIVERED.equals(status)) {
            insertEvent(bag.bagId(), EVT_DELIVERED, legId, destination);
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
        ItineraryItem next = nextItinerary(bag.bagId(), bag.nextLegIndex());
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

    /** 当前号与代次门禁：写入口一律校验，旧标签在此之前已按 409 拦截。 */
    private void checkCurrentTag(BagRow bag, String presentedTag, int expectedGeneration) {
        if (!bag.bagTag().equals(presentedTag) || bag.tagGeneration() != expectedGeneration) {
            throw ApiException.conflict(
                    "标签或代次冲突: " + presentedTag + "#" + expectedGeneration
                            + "，当前为 " + bag.bagTag() + "#" + bag.tagGeneration());
        }
    }

    /** 写入入口的标签解析：历史旧标签视为过期冲突 409，从未出现过的标签 404。 */
    private ApiException tagNotFound(String bagTag) {
        if (tagExists(bagTag)) {
            return ApiException.conflict("标签 " + bagTag + " 已被换标，不再是当前标签");
        }
        return ApiException.notFound("当前标签不存在: " + bagTag);
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

    private BagRow findBagById(String bagId) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect("bag_id", false), BAG_MAPPER, bagId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBagById(String bagId) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect("bag_id", true), BAG_MAPPER, bagId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按当前标签加锁；标签已被换走（仅存于别名表）时返回 null，由调用方判 404。 */
    private BagRow lockBagByTag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect("bag_tag", true), BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按当前标签或任一历史别名定位行李（查询使用，不加锁）。 */
    private BagRow findBagByAnyTag(String bagTag) {
        List<String> bagIds = jdbcTemplate.queryForList(
                "SELECT bag_id FROM bag_tag_alias WHERE bag_tag = ?", String.class, bagTag);
        if (bagIds.isEmpty()) {
            return null;
        }
        return findBagById(bagIds.get(0));
    }

    private boolean tagExists(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_tag = ?", Integer.class, bagTag);
        return count != null && count > 0;
    }

    private static String bagSelect(String keyColumn, boolean forUpdate) {
        return "SELECT bag_id, bag_tag, tag_generation, current_location, next_leg_index, status,"
                + " loaded_leg_id, short_leg_id, short_destination, short_registered_at"
                + " FROM bag WHERE " + keyColumn + " = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    private void insertAlias(String bagId, String bagTag, int generation) {
        jdbcTemplate.update(
                "INSERT INTO bag_tag_alias (bag_tag, bag_id, generation) VALUES (?, ?, ?)",
                bagTag, bagId, generation);
    }

    private ItineraryItem nextItinerary(String bagId, int nextLegIndex) {
        List<ItineraryItem> rows = jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_id = ? AND seq = ?",
                ITINERARY_MAPPER, bagId, nextLegIndex);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int itineraryCount(String bagId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE bag_id = ?", Integer.class, bagId);
        return count == null ? 0 : count;
    }

    private List<ItineraryItem> toItinerary(String bagId) {
        return jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_id = ? ORDER BY seq",
                ITINERARY_MAPPER, bagId);
    }

    private List<TraceEvent> toEvents(String bagId) {
        return jdbcTemplate.query(
                "SELECT seq, event_type, leg_id, location, event_time FROM bag_event"
                        + " WHERE bag_id = ? ORDER BY seq",
                EVENT_MAPPER, bagId);
    }

    private List<TagAliasItem> toAliases(String bagId) {
        return jdbcTemplate.query(
                "SELECT bag_tag, generation FROM bag_tag_alias WHERE bag_id = ? ORDER BY generation",
                ALIAS_MAPPER, bagId);
    }

    /** 在持有行李行锁后追加事件，seq 按该行李已有事件数递增。 */
    private void insertEvent(String bagId, String eventType, String legId, String location) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_id = ?", Integer.class, bagId);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_id, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagId, nextSeq, eventType, legId, location,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagId(), bag.bagTag(), bag.tagGeneration(),
                bag.currentLocation(), bag.nextLegIndex(), bag.status(), bag.loadedLegId(),
                toItinerary(bag.bagId()), bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toAliases(bag.bagId()), toEvents(bag.bagId()));
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static List<ManifestItem> sortedManifest(List<ManifestItem> items) {
        return items.stream().sorted(java.util.Comparator.comparing(ManifestItem::bagTag)).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("清单序列化失败", ex);
        }
    }

    private List<ManifestItem> readManifest(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ManifestItem.class));
        } catch (Exception ex) {
            throw new IllegalStateException("清单反序列化失败", ex);
        }
    }
    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagId, String bagTag, int tagGeneration,
                          String currentLocation, int nextLegIndex, String status,
                          String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 登记航段幂等摘要参数。 */
    private record RegisterLegPayload(String origin, String destination) {
    }

    /** 登记行李幂等摘要参数。 */
    private record RegisterBagPayload(String bagId, String bagTag, List<String> legIds) {
    }

    /** 换标幂等摘要参数。 */
    private record RetagPayload(String bagId, int expectedTagGeneration, String newBagTag) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，期望代次按标签名排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion,
                               List<String> bagTags, TreeMap<String, Integer> expectedTagGenerations) {
    }

    /** 封舱幂等摘要参数。 */
    private record SealPayload(String legId, int expectedVersion) {
    }

    /** 到达确认幂等摘要参数：bagTags 已排序。 */
    private record ArrivePayload(String legId, List<String> bagTags) {
    }

    /** 差异到达幂等摘要参数：bagTags 已排序。 */
    private record DifferenceArrivePayload(String legId, int expectedVersion, List<String> bagTags) {
    }

    /** 补到幂等摘要参数。 */
    private record RecoverPayload(String bagTag, int expectedTagGeneration,
                                  String missingLegId, String actualStation) {
    }
}
