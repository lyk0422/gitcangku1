package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegBagStatusResponse;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RejectionItem;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortItem;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.TraceEvent;
import com.example.starter.baggage.BaggageDtos.UpdateCutoffRequest;

/**
 * 联程行李装载交接核心业务：航段/行李登记、UTC 装载截止控制、批量装载、封舱、
 * 精确/差异到达、补到与状态查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * 行李行级锁 + loaded_leg_id 保证同件行李不会同时进入两个清单，
 * load_record 以 (bag_tag, leg_id) 唯一约束兜底并在到达后保留（各航段独立留痕）。
 * 装载只在当前时刻严格早于截止时刻（now == cutoff 即关闭）且沿用原有交接状态判断时放行；
 * 被拒装载在独立事务写 load_rejection 留痕（不占装载名额、不占幂等键），主业务事务整批回滚。
 * 成功装载的截止时刻快照固化在 load_record，后续修改截止时刻不覆盖已完成航段的记录。
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

    /** 装载状态：未装载 / 已在本航段装载 / 航段已封舱 / 航段已到达。 */
    private static final String LOAD_NOT_LOADED = "NOT_LOADED";
    private static final String LOAD_LOADED = "LOADED";
    private static final String LOAD_SEALED = "SEALED";
    private static final String LOAD_ARRIVED = "ARRIVED";

    // 拒绝原因代码（与 load_rejection.reason_code 列注释保持同步）
    private static final String REASON_CUTOFF = "CUTOFF_REACHED";
    private static final String REASON_LEG_NOT_OPEN = "LEG_NOT_OPEN";
    private static final String REASON_BAG_SHORT = "BAG_SHORT_UNLOADED";
    private static final String REASON_BAG_LOADED = "BAG_ALREADY_LOADED";
    private static final String REASON_ITINERARY_DONE = "ITINERARY_COMPLETED";
    private static final String REASON_WRONG_LEG = "WRONG_WAITING_LEG";
    private static final String REASON_NOT_AT_ORIGIN = "NOT_AT_ORIGIN";
    private static final String REASON_BAG_NOT_FOUND = "BAG_NOT_FOUND";
    private static final String REASON_COMPANION = "BATCH_COMPANION_REJECTED";
    private static final String REASON_DUPLICATE = "BAG_DUPLICATE_IN_BATCH";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            getInstant(rs, "cutoff_time"), rs.getString("status"), rs.getInt("version"),
            rs.getString("sealed_manifest"), rs.getString("arrival_type"), rs.getString("arrival_actual"));

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
            rs.getString("bag_tag"), rs.getString("leg_id"),
            getInstant(rs, "cutoff_snapshot"), getInstant(rs, "loaded_at"));

    private static final RowMapper<RejectionItem> REJECTION_MAPPER = (rs, rowNum) -> new RejectionItem(
            rs.getString("bag_tag"), rs.getString("leg_id"), rs.getString("request_id"),
            rs.getString("reason_code"), rs.getString("reason_detail"),
            getInstant(rs, "rejected_at").toString());

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          TransactionTemplate transactionTemplate,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 登记航段：legId 唯一，携带 UTC 装载截止时刻，初始状态 OPEN、版本 1。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        Instant cutoff = parseCutoff(request.cutoffTime());
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                new RegisterLegPayload(request.legId(), request.origin(), request.destination(),
                        cutoff.toString()),
                LegResponse.class, () -> doRegisterLeg(request, cutoff));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /**
     * 批量装载：整批原子，任一行李不满足（截止已到或交接状态不满足）则 422 且无一件移动，
     * 每个被拒袋号与同批连带袋号均独立留痕；成功时固化各袋号在本航段的截止时刻快照。
     * 拒绝留痕在外层业务事务回滚结束后用独立事务提交，每线程任一刻只占用一个连接；
     * 留痕不写 load_record（不占名额）也不写 request_log（不占幂等键）。
     */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(), sortedCopy(request.bagTags()));
        rejectionTrail.set(new ArrayList<>());
        try {
            return idempotencyService.execute(request.requestId(), "LOAD", 200,
                    payload, LoadResponse.class, () -> doLoad(legId, request));
        } catch (ApiException ex) {
            persistRejectionTrail();
            throw ex;
        } catch (RuntimeException ex) {
            rejectionTrail.remove();
            throw ex;
        } finally {
            rejectionTrail.remove();
        }
    }

    /**
     * 修改航段 UTC 截止时刻：仅 OPEN（未封舱、未到达）航段可改，否则 409；
     * expectedVersion 与其他航段写操作一致做乐观并发控制，版本不匹配返回 409。
     * 已成功装载记录中的截止快照不受影响，被拒留痕也不会被改写，无法借改时刻追认历史拒绝。
     */
    public LegResponse updateCutoff(String legId, UpdateCutoffRequest request) {
        Instant cutoff = parseCutoff(request.cutoffTime());
        UpdateCutoffPayload payload = new UpdateCutoffPayload(
                legId, request.expectedVersion(), cutoff.toString());
        return idempotencyService.execute(request.requestId(), "UPDATE_CUTOFF", 200,
                payload, LegResponse.class, () -> doUpdateCutoff(legId, request.expectedVersion(), cutoff));
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
     * 按行李和航段查询交接、截止、装载状态及拒绝原因。
     * 航段或行李不存在返回 404；航段不在该行李行程中返回 422。
     */
    public LegBagStatusResponse getLegBagStatus(String bagTag, String legId) {
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        ItineraryItem itinerary = findItinerary(bagTag, legId);
        if (itinerary == null) {
            throw ApiException.unprocessable("航段 " + legId + " 不在行李 " + bagTag + " 的行程中");
        }
        LoadRecordRow loadRecord = findLoadRecord(bagTag, legId);
        boolean handedOver = loadRecord != null || hasHandoverEvent(bagTag, legId)
                || isWaitingAtOrigin(bag, leg);
        Instant now = clock.get();
        String loadStatus = loadRecord != null ? LOAD_LOADED : switch (leg.status()) {
            case LEG_SEALED -> LOAD_SEALED;
            case LEG_ARRIVED -> LOAD_ARRIVED;
            default -> LOAD_NOT_LOADED;
        };
        List<RejectionItem> rejections = jdbcTemplate.query(
                "SELECT bag_tag, leg_id, request_id, reason_code, reason_detail, rejected_at"
                        + " FROM load_rejection WHERE bag_tag = ? AND leg_id = ?"
                        + " ORDER BY rejected_at, id",
                REJECTION_MAPPER, bagTag, legId);
        return new LegBagStatusResponse(bagTag, legId, leg.status(), leg.version(),
                leg.cutoff().toString(), now.isBefore(leg.cutoff()), handedOver, loadStatus,
                loadRecord == null ? null : loadRecord.cutoffSnapshot().toString(),
                loadRecord == null ? null : loadRecord.loadedAt().toString(),
                rejections);
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request, Instant cutoff) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, cutoff_time, status, version)"
                        + " VALUES (?, ?, ?, ?, ?, 1)",
                request.legId(), request.origin(), request.destination(), utc(cutoff), LEG_OPEN);
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                cutoff.toString(), LEG_OPEN, 1);
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
        Instant now = clock.get();
        List<String> requested = request.bagTags();
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            // 封舱/到达后的装载一律拒绝并留痕
            List<String> distinct = requested.stream().distinct().sorted().toList();
            for (String bagTag : distinct) {
                collectRejection(bagTag, legId, request.requestId(), REASON_LEG_NOT_OPEN,
                        "航段状态为 " + leg.status() + "，禁止装载", now);
            }
            throw ApiException.unprocessable("航段状态为 " + leg.status() + "，禁止装载");
        }
        if (new HashSet<>(requested).size() != requested.size()) {
            List<String> distinct = requested.stream().distinct().sorted().toList();
            for (String bagTag : distinct) {
                collectRejection(bagTag, legId, request.requestId(), REASON_DUPLICATE,
                        "批量装载的 bagTag 不得重复", now);
            }
            throw ApiException.unprocessable("批量装载的 bagTag 不得重复");
        }
        List<String> sorted = sortedCopy(requested);
        List<BagRow> bags = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                violations.add(new Violation(bagTag, REASON_BAG_NOT_FOUND, "行李不存在: " + bagTag));
                continue;
            }
            // 截止判断：时刻恰好到达（now == cutoff）即关闭装载
            if (!now.isBefore(leg.cutoff())) {
                violations.add(new Violation(bagTag, REASON_CUTOFF,
                        "行李 " + bagTag + " 申请时刻 " + now + " 已到截止时刻 " + leg.cutoff()));
                continue;
            }
            Violation violation = checkLoadable(bag, leg);
            if (violation != null) {
                violations.add(violation);
            } else {
                bags.add(bag);
            }
        }
        if (!violations.isEmpty()) {
            Set<String> violatedTags = new HashSet<>();
            for (Violation violation : violations) {
                violatedTags.add(violation.bagTag());
                collectRejection(violation.bagTag(), legId, request.requestId(),
                        violation.reasonCode(), violation.detail(), now);
            }
            // 同批合法袋号因整批原子被连带拒绝，同样留痕但不归属具体请求的参数责任
            for (BagRow companion : bags) {
                if (!violatedTags.contains(companion.bagTag())) {
                    collectRejection(companion.bagTag(), legId, null, REASON_COMPANION,
                            "同批其他行李不满足装载条件，整批回滚", now);
                }
            }
            throw ApiException.unprocessable(violations.get(0).detail());
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update(
                    "INSERT INTO load_record (bag_tag, leg_id, cutoff_snapshot, loaded_at)"
                            + " VALUES (?, ?, ?, ?)",
                    bag.bagTag(), legId, utc(leg.cutoff()), utc(now));
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, leg.cutoff().toString(), LEG_OPEN, newVersion, sorted);
    }

    private LegResponse doUpdateCutoff(String legId, int expectedVersion, Instant cutoff) {
        LegRow leg = lockLeg(legId);
        checkVersion(leg, expectedVersion);
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.conflict(
                    "航段状态为 " + leg.status() + "，截止时刻已锁定，不得修改");
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET cutoff_time = ?, version = ? WHERE leg_id = ?",
                utc(cutoff), newVersion, legId);
        return new LegResponse(leg.legId(), leg.origin(), leg.destination(),
                cutoff.toString(), LEG_OPEN, newVersion);
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
        // load_record 各航段独立留痕，到达后保留；行李已由 advanceArrivedBag 解除在舱状态
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
        OffsetDateTime registeredAt = utc(clock.get());
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
        // load_record 各航段独立留痕，差异到达后同样保留
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

    /** 沿用原有交接状态判断：返回违规原因，null 表示本航段交接已完成、允许装载。 */
    private Violation checkLoadable(BagRow bag, LegRow leg) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return new Violation(bag.bagTag(), REASON_BAG_SHORT,
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能装载后续航段");
        }
        if (bag.loadedLegId() != null) {
            return new Violation(bag.bagTag(), REASON_BAG_LOADED,
                    "行李 " + bag.bagTag() + " 已装载到航段 " + bag.loadedLegId());
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null) {
            return new Violation(bag.bagTag(), REASON_ITINERARY_DONE,
                    "行李 " + bag.bagTag() + " 已完成全部行程");
        }
        if (!next.legId().equals(leg.legId())) {
            return new Violation(bag.bagTag(), REASON_WRONG_LEG,
                    "行李 " + bag.bagTag() + " 当前待乘航段为 " + next.legId()
                            + "，不能装载到 " + leg.legId());
        }
        if (!bag.currentLocation().equals(leg.origin())) {
            return new Violation(bag.bagTag(), REASON_NOT_AT_ORIGIN,
                    "行李 " + bag.bagTag() + " 不在航段始发站 " + leg.origin());
        }
        return null;
    }

    /**
     * 拒绝留痕暂存：业务事务内只收集不写库，事务回滚结束后由 {@link #load} 统一用独立事务提交，
     * 避免在业务事务进行中再申请第二个连接造成连接池饥饿。
     */
    private static final ThreadLocal<List<RejectionTrail>> rejectionTrail =
            ThreadLocal.withInitial(ArrayList::new);

    private void collectRejection(String bagTag, String legId, String requestId,
                                  String reasonCode, String detail, Instant at) {
        rejectionTrail.get().add(new RejectionTrail(bagTag, legId, requestId, reasonCode, detail, at));
    }

    /** 外层装载事务回滚后，用一个独立事务批量写入拒绝留痕；失败不影响已确定的 422 结果。 */
    private void persistRejectionTrail() {
        List<RejectionTrail> trails = rejectionTrail.get();
        if (trails.isEmpty()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (RejectionTrail trail : trails) {
                    jdbcTemplate.update(
                            "INSERT INTO load_rejection (bag_tag, leg_id, request_id, reason_code,"
                                    + " reason_detail, rejected_at) VALUES (?, ?, ?, ?, ?, ?)",
                            trail.bagTag(), trail.legId(), trail.requestId(),
                            trail.reasonCode(), trail.detail(), utc(trail.rejectedAt()));
                }
            });
        } catch (RuntimeException ignored) {
            // 留痕写入失败不应改变装载请求已被拒绝（422）的事实，仅放弃本次留痕
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
        return "SELECT leg_id, origin, destination, cutoff_time, status, version, sealed_manifest,"
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

    private ItineraryItem findItinerary(String bagTag, String legId) {
        List<ItineraryItem> rows = jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary WHERE bag_tag = ? AND leg_id = ?",
                ITINERARY_MAPPER, bagTag, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LoadRecordRow findLoadRecord(String bagTag, String legId) {
        List<LoadRecordRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, leg_id, cutoff_snapshot, loaded_at FROM load_record"
                        + " WHERE bag_tag = ? AND leg_id = ?",
                LOAD_RECORD_MAPPER, bagTag, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该行李在本航段是否已有装载/卸下/短卸/补到事件，作为“已完成交接”的留痕判据。 */
    private boolean hasHandoverEvent(String bagTag, String legId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = ? AND leg_id = ?"
                        + " AND event_type IN ('LOADED', 'UNLOADED', 'SHORT_UNLOADED', 'RECOVERED')",
                Integer.class, bagTag, legId);
        return count != null && count > 0;
    }

    /** 行李当前待乘索引正指向本航段且位于始发站（在途），视为已完成交接、待装载。 */
    private boolean isWaitingAtOrigin(BagRow bag, LegRow leg) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return false;
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        return next != null && next.legId().equals(leg.legId())
                && bag.currentLocation().equals(leg.origin());
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
                utc(clock.get()));
    }

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()),
                bag.shortLegId(), bag.shortDestination(),
                bag.shortRegisteredAt() == null ? null : bag.shortRegisteredAt().toString(),
                toEvents(bag.bagTag()));
    }

    /** 解析 UTC ISO-8601 时刻；非法格式返回 400。 */
    private static Instant parseCutoff(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                throw ApiException.badRequest("cutoffTime 必须是合法的 UTC ISO-8601 时刻: " + text);
            }
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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

    /** 装载校验违规：bagTag + 原因代码 + 明细。 */
    private record Violation(String bagTag, String reasonCode, String detail) {
    }

    /** 待持久化的拒绝留痕。 */
    private record RejectionTrail(String bagTag, String legId, String requestId,
                                  String reasonCode, String detail, Instant rejectedAt) {
    }

    private record LegRow(String legId, String origin, String destination, Instant cutoff,
                          String status, int version, String sealedManifest,
                          String arrivalType, String arrivalActual) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, String shortLegId,
                          String shortDestination, Instant shortRegisteredAt) {
    }

    private record LoadRecordRow(String bagTag, String legId, Instant cutoffSnapshot, Instant loadedAt) {
    }

    /** 登记航段幂等摘要参数：cutoff 已规范化为 UTC 字符串。 */
    private record RegisterLegPayload(String legId, String origin, String destination, String cutoffTime) {
    }

    /** 装载幂等摘要参数：bagTags 已排序，顺序差异不视为异参。 */
    private record LoadPayload(String legId, int expectedVersion, List<String> bagTags) {
    }

    /** 修改截止时刻幂等摘要参数：cutoff 已规范化为 UTC 字符串。 */
    private record UpdateCutoffPayload(String legId, int expectedVersion, String cutoffTime) {
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
