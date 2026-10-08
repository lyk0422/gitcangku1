package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.example.starter.baggage.BaggageDtos.ClaimGroupRequest;
import com.example.starter.baggage.BaggageDtos.ClaimGroupResponse;
import com.example.starter.baggage.BaggageDtos.ClaimMember;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.FormGroupRequest;
import com.example.starter.baggage.BaggageDtos.FormGroupResponse;
import com.example.starter.baggage.BaggageDtos.GroupMemberStatus;
import com.example.starter.baggage.BaggageDtos.GroupResponse;
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
    private static final String BAG_READY_FOR_CLAIM = "READY_FOR_CLAIM";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String GROUP_FORMED = "FORMED";
    private static final String GROUP_CLAIMED = "CLAIMED";

    private static final String EVT_REGISTERED = "REGISTERED";
    private static final String EVT_LOADED = "LOADED";
    private static final String EVT_UNLOADED = "UNLOADED";
    private static final String EVT_SHORT = "SHORT_UNLOADED";
    private static final String EVT_RECOVERED = "RECOVERED";
    private static final String EVT_READY_FOR_CLAIM = "READY_FOR_CLAIM";
    private static final String EVT_CLAIMED = "CLAIMED";
    private static final String EVT_DELIVERED = "DELIVERED";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getString("group_key"), rs.getString("short_leg_id"), rs.getString("short_destination"),
            getInstant(rs, "short_registered_at"), rs.getInt("version"));

    private static final RowMapper<GroupRow> GROUP_MAPPER = (rs, rowNum) -> new GroupRow(
            rs.getString("group_key"), rs.getString("status"), rs.getString("final_leg_id"),
            rs.getString("final_station"), rs.getString("created_by"), rs.getString("claimed_by"),
            getInstant(rs, "claimed_at"));

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

    /**
     * 建组：2~5 件同行程、同当前站且从未装载（next_leg_index=0、无在装航段、无事件外的装载记录）
     * 的行李固定为一组，成员集合与各自行程之后不可更改；组成员后续独立按原航段装载及到达。
     */
    public FormGroupResponse formGroup(FormGroupRequest request) {
        FormGroupPayload payload = new FormGroupPayload(request.groupKey(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "FORM_GROUP", request.groupKey(),
                request.operator(), 201, payload, FormGroupResponse.class,
                () -> doFormGroup(request, payload));
    }

    /** 组查询：返回逐成员状态与整组/各成员未齐原因；只读，不触发认领。 */
    public GroupResponse getGroup(String groupKey) {
        GroupRow group = findGroup(groupKey);
        if (group == null) {
            throw ApiException.notFound("组不存在: " + groupKey);
        }
        List<String> memberTags = listMembers(groupKey);
        List<GroupMemberStatus> members = new ArrayList<>();
        boolean allReady = true;
        for (String bagTag : memberTags) {
            BagRow bag = findBag(bagTag);
            String reason = bag == null ? "行李不存在" : memberNotReadyReason(bag, group);
            if (reason != null) {
                allReady = false;
            }
            members.add(new GroupMemberStatus(
                    bagTag, bag == null ? null : bag.status(), bag == null ? -1 : bag.version(),
                    bag == null ? null : bag.currentLocation(), bag == null ? -1 : bag.nextLegIndex(),
                    bag == null ? null : bag.shortLegId(), bag == null ? null : bag.shortDestination(),
                    reason));
        }
        String groupReason = null;
        if (GROUP_CLAIMED.equals(group.status())) {
            groupReason = null;
        } else if (!allReady) {
            groupReason = "尚有成员未进入待认领状态";
        }
        return new GroupResponse(group.groupKey(), group.status(), group.finalLegId(),
                group.finalStation(), GROUP_CLAIMED.equals(group.status()) || allReady,
                groupReason, members);
    }

    /**
     * 整组显式认领：提交 groupKey（路径）、全部成员及各成员当前轨迹版本。
     * 缺成员 400、版本旧 409、仍短卸或有后续段（未全部 READY_FOR_CLAIM）422；
     * 失败整次回滚，没有任何一件被交付。成功后全组一次转 DELIVERED，
     * 冻结每件完整行程（行程表本就只读）与末段证据（READY_FOR_CLAIM 事件 + CLAIMED 事件）。
     */
    public ClaimGroupResponse claimGroup(String groupKey, ClaimGroupRequest request) {
        ClaimGroupPayload payload = new ClaimGroupPayload(groupKey, normalizeMembers(request.members()));
        return idempotencyService.execute(request.requestId(), "CLAIM_GROUP", groupKey,
                request.operator(), 200, payload, ClaimGroupResponse.class,
                () -> doClaimGroup(groupKey, request, payload));
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
            jdbcTemplate.update(
                    "UPDATE bag SET loaded_leg_id = ?, version = version + 1 WHERE bag_tag = ?",
                    legId, bag.bagTag());
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
                                + " short_destination = ?, short_registered_at = ?, version = version + 1"
                                + " WHERE bag_tag = ?",
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
        boolean finalLeg = nextIndex >= total;
        // 组内成员补到走完末段同样只进入待认领，补到入口不自动交付组成员
        String newStatus = !finalLeg ? BAG_RECOVERED
                : (bag.groupKey() == null ? BAG_DELIVERED : BAG_READY_FOR_CLAIM);
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                        + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL,"
                        + " version = version + 1 WHERE bag_tag = ?",
                request.actualStation(), nextIndex, newStatus, bag.bagTag());
        insertEvent(bag.bagTag(), EVT_RECOVERED, request.missingLegId(), request.actualStation());
        if (BAG_DELIVERED.equals(newStatus)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, request.missingLegId(), request.actualStation());
        } else if (BAG_READY_FOR_CLAIM.equals(newStatus)) {
            insertEvent(bag.bagTag(), EVT_READY_FOR_CLAIM, request.missingLegId(), request.actualStation());
        }
        return new RecoverResponse(bag.bagTag(), newStatus, request.actualStation(),
                nextIndex, request.missingLegId());
    }

    /** 实际到达行李的统一推进：移动到到达站、推进待乘索引，完成行程者交付。 */
    private void advanceArrivedBag(BagRow bag, String destination, String legId) {
        int nextIndex = bag.nextLegIndex() + 1;
        int total = itineraryCount(bag.bagTag());
        boolean finalLeg = nextIndex >= total;
        // 组成员走完末段只进入待认领，原到达入口不得把一件到达冒充整组交付；非组行李仍自动交付
        String status = !finalLeg ? BAG_IN_TRANSIT
                : (bag.groupKey() == null ? BAG_DELIVERED : BAG_READY_FOR_CLAIM);
        jdbcTemplate.update(
                "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL,"
                        + " version = version + 1 WHERE bag_tag = ?",
                destination, nextIndex, status, bag.bagTag());
        insertEvent(bag.bagTag(), EVT_UNLOADED, legId, destination);
        if (BAG_DELIVERED.equals(status)) {
            insertEvent(bag.bagTag(), EVT_DELIVERED, legId, destination);
        } else if (BAG_READY_FOR_CLAIM.equals(status)) {
            insertEvent(bag.bagTag(), EVT_READY_FOR_CLAIM, legId, destination);
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
                + " group_key, short_leg_id, short_destination, short_registered_at, version"
                + " FROM bag WHERE bag_tag = ?"
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
                bag.status(), bag.loadedLegId(), bag.groupKey(), toItinerary(bag.bagTag()),
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

    private FormGroupResponse doFormGroup(FormGroupRequest request, FormGroupPayload payload) {
        if (findGroup(request.groupKey()) != null) {
            throw ApiException.conflict("组已存在: " + request.groupKey());
        }
        List<String> tags = payload.bagTags();
        if (new HashSet<>(tags).size() != tags.size()) {
            throw ApiException.unprocessable("组成员袋号不得重复");
        }
        List<BagRow> bags = new ArrayList<>();
        List<List<ItineraryItem>> itineraries = new ArrayList<>();
        String sharedStation = null;
        List<ItineraryItem> sharedItinerary = null;
        // 按袋号排序加锁，避免多事务交叉加锁导致死锁
        for (String bagTag : tags) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            if (bag.groupKey() != null) {
                throw ApiException.unprocessable("行李 " + bagTag + " 已属于组 " + bag.groupKey());
            }
            if (bag.loadedLegId() != null || bag.nextLegIndex() != 0
                    || !BAG_IN_TRANSIT.equals(bag.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 已开始运输，必须在任何装载前建组");
            }
            List<ItineraryItem> itinerary = toItinerary(bagTag);
            if (sharedStation == null) {
                sharedStation = bag.currentLocation();
                sharedItinerary = itinerary;
            } else {
                if (!bag.currentLocation().equals(sharedStation)) {
                    throw ApiException.unprocessable(
                            "组成员当前站不一致: " + sharedStation + " / " + bag.currentLocation());
                }
                if (!sameLegSequence(sharedItinerary, itinerary)) {
                    throw ApiException.unprocessable("组成员行程不一致: " + bagTag);
                }
            }
            bags.add(bag);
            itineraries.add(itinerary);
        }
        String finalLegId = sharedItinerary.get(sharedItinerary.size() - 1).legId();
        String finalStation = sharedItinerary.get(sharedItinerary.size() - 1).destination();
        jdbcTemplate.update(
                "INSERT INTO bag_group (group_key, status, final_leg_id, final_station, created_by)"
                        + " VALUES (?, ?, ?, ?, ?)",
                request.groupKey(), GROUP_FORMED, finalLegId, finalStation, request.operator());
        List<String> rawTags = request.bagTags();
        for (int i = 0; i < rawTags.size(); i++) {
            jdbcTemplate.update(
                    "INSERT INTO bag_group_member (group_key, bag_tag, member_seq) VALUES (?, ?, ?)",
                    request.groupKey(), rawTags.get(i), i);
            jdbcTemplate.update("UPDATE bag SET group_key = ? WHERE bag_tag = ?",
                    request.groupKey(), rawTags.get(i));
        }
        return new FormGroupResponse(request.groupKey(), GROUP_FORMED, finalLegId, finalStation,
                tags, sharedItinerary);
    }

    private ClaimGroupResponse doClaimGroup(String groupKey, ClaimGroupRequest request,
                                            ClaimGroupPayload payload) {
        GroupRow group = lockGroup(groupKey);
        List<String> expectedMembers = listMembers(groupKey);
        List<ClaimMemberPayload> submitted = payload.members();
        Set<String> submittedTags = new HashSet<>();
        for (ClaimMemberPayload member : submitted) {
            if (!submittedTags.add(member.bagTag())) {
                throw ApiException.badRequest("认领成员重复: " + member.bagTag());
            }
        }
        // 缺成员或出现组外成员：400，整次认领不交付任何一件
        if (!submittedTags.equals(new HashSet<>(expectedMembers))) {
            throw ApiException.badRequest("认领成员集合与固定成员不一致，必须提交全部成员且不得夹带");
        }
        // 先逐一加锁并校验版本：版本旧一律 409，不进入状态判定
        Map<String, BagRow> lockedBags = new java.util.LinkedHashMap<>();
        for (String bagTag : expectedMembers.stream().sorted().toList()) {
            BagRow bag = lockBag(bagTag);
            lockedBags.put(bagTag, bag);
        }
        if (GROUP_CLAIMED.equals(group.status())) {
            // 组已整组交付：持有的成员行锁为最新数据，重复认领（含并发落败者）一律 422，不产生第二次交付
            throw ApiException.unprocessable("组 " + groupKey + " 已整组交付");
        }
        for (ClaimMemberPayload member : submitted) {
            BagRow bag = lockedBags.get(member.bagTag());
            if (bag.version() != member.expectedVersion()) {
                throw ApiException.conflict(
                        "行李 " + member.bagTag() + " 轨迹版本冲突: 期望 "
                                + member.expectedVersion() + "，当前 " + bag.version());
            }
        }
        // 版本全部为当前值后再判状态：仍短卸或有后续段 -> 422，无一件交付
        for (String bagTag : expectedMembers) {
            String reason = memberNotReadyReason(lockedBags.get(bagTag), group);
            if (reason != null) {
                throw ApiException.unprocessable("成员 " + bagTag + " 未就绪: " + reason);
            }
        }
        OffsetDateTime claimedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        for (String bagTag : expectedMembers) {
            jdbcTemplate.update(
                    "UPDATE bag SET status = ?, version = version + 1 WHERE bag_tag = ?",
                    BAG_DELIVERED, bagTag);
            // 末段证据：UNLOADED/READY_FOR_CLAIM 事件已在末段到达或补到时固定，此处追加认领交付事件
            insertEvent(bagTag, EVT_CLAIMED, group.finalLegId(), group.finalStation());
        }
        jdbcTemplate.update(
                "UPDATE bag_group SET status = ?, claimed_by = ?, claimed_at = ? WHERE group_key = ?",
                GROUP_CLAIMED, request.operator(), claimedAt, groupKey);
        return new ClaimGroupResponse(groupKey, GROUP_CLAIMED,
                expectedMembers.stream().sorted().toList(),
                request.operator(), claimedAt.toInstant().toString());
    }

    /** 成员未进入可认领状态的原因；已就绪返回 null。 */
    private String memberNotReadyReason(BagRow bag, GroupRow group) {
        if (bag.groupKey() == null || !bag.groupKey().equals(group.groupKey())) {
            return "不属于该组";
        }
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return "仍短卸，缺失航段 " + bag.shortLegId() + " 应到站 " + bag.shortDestination();
        }
        if (BAG_READY_FOR_CLAIM.equals(bag.status())) {
            if (!group.finalStation().equals(bag.currentLocation())) {
                return "尚未到达末段应到站 " + group.finalStation();
            }
            return null;
        }
        if (BAG_DELIVERED.equals(bag.status())) {
            return "已交付";
        }
        int total = itineraryCount(bag.bagTag());
        if (bag.nextLegIndex() < total) {
            return "尚有后续航段未完成";
        }
        return "当前状态 " + bag.status() + " 不可认领";
    }

    private static boolean sameLegSequence(List<ItineraryItem> expected, List<ItineraryItem> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).legId().equals(actual.get(i).legId())) {
                return false;
            }
        }
        return true;
    }

    private GroupRow findGroup(String groupKey) {
        List<GroupRow> rows = jdbcTemplate.query(groupSelect(false), GROUP_MAPPER, groupKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private GroupRow lockGroup(String groupKey) {
        List<GroupRow> rows = jdbcTemplate.query(groupSelect(true), GROUP_MAPPER, groupKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("组不存在: " + groupKey);
        }
        return rows.get(0);
    }

    private static String groupSelect(boolean forUpdate) {
        return "SELECT group_key, status, final_leg_id, final_station, created_by, claimed_by, claimed_at"
                + " FROM bag_group WHERE group_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private List<String> listMembers(String groupKey) {
        return jdbcTemplate.queryForList(
                "SELECT bag_tag FROM bag_group_member WHERE group_key = ? ORDER BY member_seq",
                String.class, groupKey);
    }

    private static List<ClaimMemberPayload> normalizeMembers(List<ClaimMember> members) {
        return members.stream()
                .map(member -> new ClaimMemberPayload(member.bagTag(), member.expectedVersion()))
                .sorted(java.util.Comparator.comparing(ClaimMemberPayload::bagTag))
                .toList();
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String groupKey, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt, int version) {
    }

    private record GroupRow(String groupKey, String status, String finalLegId,
                            String finalStation, String createdBy, String claimedBy,
                            Instant claimedAt) {
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

    /** 建组幂等摘要参数：bagTags 已排序，提交顺序差异不视为异参；operator 不计入摘要。 */
    private record FormGroupPayload(String groupKey, List<String> bagTags) {
    }

    /** 认领成员摘要：十进制版本按数值参与摘要，bagTag 文本原样。 */
    private record ClaimMemberPayload(String bagTag, int expectedVersion) {
    }

    /** 认领幂等摘要参数：成员按袋号排序，含各成员期望版本；operator 不计入摘要。 */
    private record ClaimGroupPayload(String groupKey, List<ClaimMemberPayload> members) {
    }
}
