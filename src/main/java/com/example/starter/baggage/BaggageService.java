package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.example.starter.baggage.BaggageDtos.TraceEvent;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、精确/差异到达、补到、换标与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_id 为主键保证同件行李不会进入两个清单。
 * 行李以稳定 bag_id 标识，bag_tag 为当前标签、tag_generation 为当前代次（初始 1）；
 * 换标仅在未装舱、非短卸、未交付时允许，旧标签永久保留别名且不复用；
 * 装载/补到等写入口强制当前标签（及可选代次）门禁，历史查询接受任一旧标签；
 * 封舱清单冻结当时 {bagId, bagTag, tagGeneration}，到达按清单存储的稳定 ID 处理。
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
    private static final String EVT_RETAGGED = "RETAGGED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_id"), rs.getString("bag_tag"), rs.getInt("tag_generation"),
            rs.getString("current_location"), rs.getInt("next_leg_index"), rs.getString("status"),
            rs.getString("loaded_leg_id"), rs.getString("short_leg_id"),
            rs.getString("short_destination"), getInstant(rs, "short_registered_at"));

    private static final RowMapper<AliasRow> ALIAS_MAPPER = (rs, rowNum) -> new AliasRow(
            rs.getString("bag_tag"), rs.getString("bag_id"), rs.getInt("tag_generation"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<TraceEvent> EVENT_MAPPER = (rs, rowNum) -> new TraceEvent(
            rs.getInt("seq"), rs.getString("event_type"), rs.getString("leg_id"),
            rs.getString("location"), getInstant(rs, "event_time").toString(), rs.getString("bag_tag"));

    private static final RowMapper<ShortItem> SHORT_MAPPER = (rs, rowNum) -> new ShortItem(
            rs.getString("bag_tag"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at").toString(),
            rs.getInt("next_leg_index"), rs.getString("current_location"));

    private static final RowMapper<ManifestEntry> MANIFEST_ENTRY_MAPPER = (rs, rowNum) -> new ManifestEntry(
            rs.getString("bag_id"), rs.getString("bag_tag"), rs.getInt("tag_generation"));

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

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站；分配稳定 bagId 与代次 1。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动；袋号必须为当前标签。 */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(),
                sortedCopy(request.bagTags()), request.expectedTagGeneration());
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoad(legId, request));
    }

    /** 封舱：校验版本，冻结当时 {bagId, bagTag, tagGeneration} 只读清单并转 SEALED；空清单也可封舱。 */
    public SealResponse seal(String legId, SealRequest request) {
        SealPayload payload = new SealPayload(legId, request.expectedVersion());
        return idempotencyService.execute(request.requestId(), "SEAL", 200,
                payload, SealResponse.class, () -> doSeal(legId, request));
    }

    /** 到达确认：实际袋号按稳定 ID 解析后须与封舱清单完全一致，匹配则原子转 ARRIVED 并推进各行李。 */
    public ArriveResponse arrive(String legId, ArriveRequest request) {
        ArrivePayload payload = new ArrivePayload(legId, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE", 200,
                payload, ArriveResponse.class, () -> doArrive(legId, request));
    }

    /**
     * 差异到达：仅 SEALED 航段可提交，actual 按稳定 ID 解析后须为封舱清单子集（允许空集）且不得重复。
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
     * bagTag 必须为当前标签（旧标签 409）；成功后移动行李、推进待乘索引并标记 RECOVERED
     * （完成行程者 DELIVERED）；相同补到同参由 requestId 重放原结果，已补到不得再次推进。
     */
    public RecoverResponse recover(RecoverRequest request) {
        return idempotencyService.execute(request.requestId(), "RECOVER", 200,
                request, RecoverResponse.class, () -> doRecover(request));
    }

    /**
     * 换标：指定稳定 bagId、期望代次与全局未用的新标签，成功后代次加一。
     * 仅在未装舱、无 SHORT_UNLOADED 且未 DELIVERED 时允许；旧标签永久保留别名，不分配给另一实物。
     */
    public RetagResponse retag(RetagRequest request) {
        RetagPayload payload = new RetagPayload(request.operator(), request.bagId(),
                request.expectedTagGeneration(), request.newBagTag());
        return idempotencyService.execute(request.requestId(), "RETAG", 200,
                payload, RetagResponse.class, () -> doRetag(request));
    }

    /** 行李完整轨迹查询：接受任一历史标签，返回稳定 ID、当前标签与代次。 */
    public BagResponse getBagTrace(String bagTag) {
        AliasRow alias = findAlias(bagTag);
        if (alias == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        return toBagResponse(findBagById(alias.bagId()));
    }

    /** 封舱清单查询：未封舱时返回空清单；已封舱返回冻结的标签号。 */
    public ManifestResponse getManifest(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null
                ? List.of() : manifestTags(readManifest(leg.sealedManifest()));
        return new ManifestResponse(leg.legId(), leg.status(), leg.version(), manifest);
    }

    /** 航段差异快照查询：返回只读封舱清单、到达类型及差异到达实际集合。 */
    public LegDifferenceResponse getDifference(String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<String> manifest = leg.sealedManifest() == null
                ? List.of() : manifestTags(readManifest(leg.sealedManifest()));
        List<String> actual = switch (leg.arrivalType() == null ? "" : leg.arrivalType()) {
            case ARRIVAL_DIFF -> leg.arrivalActual() == null ? List.of() : readJsonList(leg.arrivalActual());
            case ARRIVAL_EXACT -> manifest;
            default -> null;
        };
        return new LegDifferenceResponse(leg.legId(), leg.status(), leg.version(),
                manifest, leg.arrivalType(), actual);
    }

    /** 未补到短卸行李清单查询：仅含当前仍为 SHORT_UNLOADED 的行李，bagTag 为当前标签。 */
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
        if (findAlias(request.bagTag()) != null) {
            throw ApiException.conflict("行李牌号已被使用（含历史别名，永久不复用）: " + request.bagTag());
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
        String bagId = "BAG-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bag (bag_id, bag_tag, tag_generation, current_location, next_leg_index, status)"
                        + " VALUES (?, ?, 1, ?, 0, ?)",
                bagId, request.bagTag(), startStation, BAG_IN_TRANSIT);
        insertAlias(request.bagTag(), bagId, 1);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_id, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    bagId, i, leg.legId(), leg.origin(), leg.destination());
        }
        insertEvent(bagId, EVT_REGISTERED, null, startStation, request.bagTag());
        return toBagResponse(findBagById(bagId));
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
        Map<String, String> tagByBagId = new HashMap<>();
        for (String bagTag : bagTags) {
            AliasRow alias = findAlias(bagTag);
            if (alias == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            tagByBagId.put(alias.bagId(), bagTag);
        }
        if (tagByBagId.size() != bagTags.size()) {
            throw ApiException.unprocessable("批量装载含同一实物的多个标签，不得重复装载");
        }
        List<BagRow> bags = new ArrayList<>();
        for (String bagId : tagByBagId.keySet().stream().sorted().toList()) {
            BagRow bag = lockBagById(bagId);
            checkCurrentTag(bag, tagByBagId.get(bagId), request.expectedTagGeneration());
            validateLoadable(bag, leg);
            bags.add(bag);
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_id, leg_id, bag_tag) VALUES (?, ?, ?)",
                    bag.bagId(), legId, bag.bagTag());
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_id = ?", legId, bag.bagId());
            insertEvent(bag.bagId(), EVT_LOADED, legId, leg.origin(), bag.bagTag());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sortedCopy(bagTags));
    }

    private SealResponse doSeal(String legId, SealRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止封舱");
        }
        List<ManifestEntry> entries = jdbcTemplate.query(
                "SELECT lr.bag_id, lr.bag_tag, b.tag_generation FROM load_record lr"
                        + " JOIN bag b ON b.bag_id = lr.bag_id WHERE lr.leg_id = ? ORDER BY lr.bag_tag",
                MANIFEST_ENTRY_MAPPER, legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET status = ?, version = ?, sealed_manifest = ? WHERE leg_id = ?",
                LEG_SEALED, newVersion, writeJson(entries), legId);
        return new SealResponse(legId, LEG_SEALED, newVersion, manifestTags(entries));
    }

    private RetagResponse doRetag(RetagRequest request) {
        BagRow bag = lockBagById(request.bagId());
        if (bag == null) {
            throw ApiException.notFound("bagId 不存在: " + request.bagId());
        }
        if (bag.tagGeneration() != request.expectedTagGeneration()) {
            throw ApiException.conflict("标签代次冲突: 期望 " + request.expectedTagGeneration()
                    + "，当前 " + bag.tagGeneration());
        }
        if (bag.loadedLegId() != null) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 已装舱，禁止换标");
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 处于短卸状态，禁止换标");
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            throw ApiException.unprocessable("行李 " + bag.bagTag() + " 已交付，禁止换标");
        }
        if (findAlias(request.newBagTag()) != null) {
            throw ApiException.conflict("新标签已被使用: " + request.newBagTag());
        }
        int newGeneration = bag.tagGeneration() + 1;
        jdbcTemplate.update("UPDATE bag SET bag_tag = ?, tag_generation = ? WHERE bag_id = ?",
                request.newBagTag(), newGeneration, bag.bagId());
        try {
            insertAlias(request.newBagTag(), bag.bagId(), newGeneration);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("新标签已被使用: " + request.newBagTag());
        }
        insertEvent(bag.bagId(), EVT_RETAGGED, null, bag.currentLocation(), request.newBagTag());
        return new RetagResponse(bag.bagId(), request.newBagTag(), bag.bagTag(), newGeneration);
    }

    private ArriveResponse doArrive(String legId, ArriveRequest request) {
        LegRow leg = lockLeg(legId);
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止到达确认");
        }
        List<ManifestEntry> manifest = readManifest(leg.sealedManifest());
        Set<String> actualIds = resolveBagIds(request.bagTags());
        if (actualIds.size() != request.bagTags().size()
                || !actualIds.equals(manifestBagIds(manifest))) {
            throw ApiException.unprocessable("实际袋号集合与封舱清单不一致");
        }
        for (ManifestEntry entry : manifestSortedByBagId(manifest)) {
            BagRow bag = lockBagById(entry.bagId());
            advanceArrivedBag(bag, leg.destination(), legId);
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = NULL WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_EXACT, legId);
        return new ArriveResponse(legId, LEG_ARRIVED, newVersion, manifestTags(manifest));
    }

    private DifferenceArriveResponse doArriveDifference(String legId, DifferenceArriveRequest request) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_SEALED.equals(leg.status())) {
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止差异到达");
        }
        List<ManifestEntry> manifest = readManifest(leg.sealedManifest());
        Set<String> actualIds = resolveBagIds(request.bagTags());
        if (actualIds.size() != request.bagTags().size()) {
            throw ApiException.unprocessable("实际袋号集合不得重复");
        }
        if (!manifestBagIds(manifest).containsAll(actualIds)) {
            throw ApiException.unprocessable("实际袋号存在封舱清单外袋号，整次差异到达拒绝");
        }
        List<ManifestEntry> arrived = manifest.stream()
                .filter(entry -> actualIds.contains(entry.bagId()))
                .toList();
        List<ManifestEntry> shortList = manifest.stream()
                .filter(entry -> !actualIds.contains(entry.bagId()))
                .toList();
        OffsetDateTime registeredAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (ManifestEntry entry : manifestSortedByBagId(manifest)) {
            BagRow bag = lockBagById(entry.bagId());
            if (actualIds.contains(entry.bagId())) {
                advanceArrivedBag(bag, leg.destination(), legId);
            } else {
                jdbcTemplate.update(
                        "UPDATE bag SET status = ?, loaded_leg_id = NULL, short_leg_id = ?,"
                                + " short_destination = ?, short_registered_at = ? WHERE bag_id = ?",
                        BAG_SHORT_UNLOADED, legId, leg.destination(), registeredAt, entry.bagId());
                insertEvent(entry.bagId(), EVT_SHORT, legId, bag.currentLocation(), entry.bagTag());
            }
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        List<String> arrivedTags = manifestTags(arrived);
        jdbcTemplate.update(
                "UPDATE leg SET status = ?, version = ?, arrival_type = ?, arrival_actual = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, ARRIVAL_DIFF, writeJson(arrivedTags), legId);
        return new DifferenceArriveResponse(legId, LEG_ARRIVED, newVersion,
                arrivedTags, manifestTags(shortList));
    }

    private RecoverResponse doRecover(RecoverRequest request) {
        AliasRow alias = findAlias(request.bagTag());
        if (alias == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        BagRow bag = lockBagById(alias.bagId());
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
        insertEvent(bag.bagId(), EVT_RECOVERED, request.missingLegId(), request.actualStation(), bag.bagTag());
        if (BAG_DELIVERED.equals(newStatus)) {
            insertEvent(bag.bagId(), EVT_DELIVERED, request.missingLegId(), request.actualStation(), bag.bagTag());
        }
        return new RecoverResponse(bag.bagTag(), newStatus, request.actualStation(),
                nextIndex, request.missingLegId());
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
        insertEvent(bag.bagId(), EVT_UNLOADED, legId, destination, bag.bagTag());
        if (BAG_DELIVERED.equals(status)) {
            insertEvent(bag.bagId(), EVT_DELIVERED, legId, destination, bag.bagTag());
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

    /** 当前号门禁：提交的标签必须是该行锁下行李的当前标签，代次（如提供）必须一致。 */
    private void checkCurrentTag(BagRow bag, String submittedTag, Integer expectedTagGeneration) {
        if (!bag.bagTag().equals(submittedTag)) {
            throw ApiException.conflict("标签 " + submittedTag + " 已更换，当前标签为 " + bag.bagTag());
        }
        if (expectedTagGeneration != null && bag.tagGeneration() != expectedTagGeneration) {
            throw ApiException.conflict("标签代次冲突: 期望 " + expectedTagGeneration
                    + "，当前 " + bag.tagGeneration());
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

    private AliasRow findAlias(String bagTag) {
        List<AliasRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, bag_id, tag_generation FROM bag_tag_alias WHERE bag_tag = ?",
                ALIAS_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertAlias(String bagTag, String bagId, int tagGeneration) {
        jdbcTemplate.update(
                "INSERT INTO bag_tag_alias (bag_tag, bag_id, tag_generation) VALUES (?, ?, ?)",
                bagTag, bagId, tagGeneration);
    }

    private BagRow findBagById(String bagId) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(false), BAG_MAPPER, bagId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBagById(String bagId) {
        List<BagRow> rows = jdbcTemplate.query(bagSelect(true), BAG_MAPPER, bagId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String bagSelect(boolean forUpdate) {
        return "SELECT bag_id, bag_tag, tag_generation, current_location, next_leg_index, status,"
                + " loaded_leg_id, short_leg_id, short_destination, short_registered_at"
                + " FROM bag WHERE bag_id = ?" + (forUpdate ? " FOR UPDATE" : "");
    }

    /** 将提交的袋号解析为稳定实物标识集合；未知标签解析为 null（由调用方按业务判定拒绝）。 */
    private Set<String> resolveBagIds(List<String> bagTags) {
        Set<String> bagIds = new HashSet<>();
        for (String bagTag : bagTags) {
            AliasRow alias = findAlias(bagTag);
            bagIds.add(alias == null ? null : alias.bagId());
        }
        return bagIds;
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
                "SELECT seq, event_type, leg_id, location, event_time, bag_tag FROM bag_event"
                        + " WHERE bag_id = ? ORDER BY seq",
                EVENT_MAPPER, bagId);
    }

    /** 在持有行李行锁后追加事件，seq 按该行李已有事件数递增，bagTag 冻结事件发生时刻的当前标签。 */
    private void insertEvent(String bagId, String eventType, String legId, String location, String bagTag) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_id = ?", Integer.class, bagId);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_id, seq, event_type, leg_id, location, bag_tag, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                bagId, nextSeq, eventType, legId, location, bagTag,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.bagId(), bag.tagGeneration(),
                bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagId()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toEvents(bag.bagId()));
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static Set<String> manifestBagIds(List<ManifestEntry> manifest) {
        Set<String> bagIds = new HashSet<>();
        for (ManifestEntry entry : manifest) {
            bagIds.add(entry.bagId());
        }
        return bagIds;
    }

    private static List<ManifestEntry> manifestSortedByBagId(List<ManifestEntry> manifest) {
        return manifest.stream()
                .sorted(java.util.Comparator.comparing(ManifestEntry::bagId))
                .toList();
    }

    /** 清单冻结标签（按标签排序），响应与快照统一使用冻结号，不随换标倒改。 */
    private static List<String> manifestTags(List<ManifestEntry> manifest) {
        return manifest.stream().map(ManifestEntry::bagTag).sorted().toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("清单序列化失败", ex);
        }
    }

    private List<ManifestEntry> readManifest(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ManifestEntry.class));
        } catch (Exception ex) {
            throw new IllegalStateException("清单反序列化失败", ex);
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

    private record BagRow(String bagId, String bagTag, int tagGeneration,
                          String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    /** 标签别名：任一历史或当前标签到稳定实物标识的永久映射。 */
    private record AliasRow(String bagTag, String bagId, int tagGeneration) {
    }

    /** 封舱清单冻结项：封舱时刻的稳定标识、当时标签与代次。 */
    private record ManifestEntry(String bagId, String bagTag, int tagGeneration) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, List<String> bagTags,
                               Integer expectedTagGeneration) {
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

    /** 换标幂等摘要参数：含非空操作者、稳定实物标识、期望代次与新标签。 */
    private record RetagPayload(String operator, String bagId, int expectedTagGeneration, String newBagTag) {
    }
}
