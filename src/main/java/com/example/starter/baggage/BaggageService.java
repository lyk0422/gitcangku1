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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagLegStatusResponse;
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
import com.example.starter.baggage.BaggageDtos.TraceEvent;
import com.example.starter.baggage.BaggageDtos.UpdateCutoffRequest;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、精确/差异到达、补到与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 * 每个航段带 UTC 装载截止时刻：仅在当前时刻严格早于截止时刻、且行李已完成本航段交接
 * （待乘航段匹配且位于始发站、未装载、非短卸）时才能装载，当前时刻 >= 截止时刻即关闭装载并返回 422；
 * 成功装载在 bag_leg_load 中按 (bag_tag, leg_id) 独立留痕并保存截止快照，逾期拒绝在 load_rejection
 * 中留痕且不占装载名额；截止时刻仅 OPEN 航段可修改，封舱或最终到达后修改返回 409，不能借改时刻追认被拒装载。
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

    /** 装载状态：未装载（含已交接待装载）。 */
    private static final String LOAD_NOT_LOADED = "NOT_LOADED";
    /** 装载状态：已装载到当前航段、尚未到达卸下。 */
    private static final String LOAD_LOADED = "LOADED";
    /** 装载状态：已随航段到达卸下。 */
    private static final String LOAD_UNLOADED = "UNLOADED";
    /** 装载状态：在该航段短卸待补。 */
    private static final String LOAD_SHORT = "SHORT_UNLOADED";
    /** 装载状态：尚未交接或与该航段无关。 */
    private static final String LOAD_NOT_APPLICABLE = "NOT_APPLICABLE";

    /** 拒绝原因码：当前 UTC 时刻已到达截止时刻。 */
    private static final String REASON_CUTOFF_CLOSED = "CUTOFF_CLOSED";
    /** 拒绝原因码：行李处于短卸状态，须先补到。 */
    private static final String REASON_SHORT_UNLOADED = "SHORT_UNLOADED";
    /** 拒绝原因码：行李已装载在某航段清单中。 */
    private static final String REASON_ALREADY_LOADED = "ALREADY_LOADED";
    /** 拒绝原因码：行李当前待乘航段与目标航段不匹配。 */
    private static final String REASON_ITINERARY_MISMATCH = "ITINERARY_MISMATCH";
    /** 拒绝原因码：行李未在航段始发站完成交接。 */
    private static final String REASON_NOT_AT_ORIGIN = "NOT_AT_ORIGIN";
    /** 拒绝原因码：行李不存在。 */
    private static final String REASON_BAG_NOT_FOUND = "BAG_NOT_FOUND";
    /** 拒绝原因码：同次批量请求内 bagTag 重复。 */
    private static final String REASON_BAG_DUPLICATE = "BAG_DUPLICATE";
    /** 拒绝原因码：航段非 OPEN（已封舱或已到达）。 */
    private static final String REASON_LEG_NOT_OPEN = "LEG_NOT_OPEN";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"),
            getInstant(rs, "loading_cutoff"),
            rs.getString("sealed_manifest"),
            rs.getString("arrival_type"), rs.getString("arrival_actual"));

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
    /** 拒绝流水使用独立事务提交，与失败装载事务回滚互不影响。 */
    private final TransactionTemplate rejectionTransaction;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.rejectionTransaction = new TransactionTemplate(transactionManager);
        this.rejectionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 登记航段：legId 唯一，初始状态 OPEN、版本 1，并带 UTC 装载截止时刻。 */
    public LegResponse registerLeg(RegisterLegRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_LEG", 201,
                request, LegResponse.class, () -> doRegisterLeg(request));
    }

    /**
     * 修改航段 UTC 装载截止时刻：仅 OPEN 航段允许；
     * 航段已封舱（SEALED）或已最终到达（ARRIVED）返回 409，不能借改时刻追认被拒装载。
     */
    public LegResponse updateCutoff(String legId, UpdateCutoffRequest request) {
        Instant cutoff = parseCutoff(request.loadingCutoff());
        UpdateCutoffPayload payload = new UpdateCutoffPayload(legId, cutoff.toString());
        return idempotencyService.execute(request.requestId(), "UPDATE_CUTOFF", 200,
                payload, LegResponse.class, () -> doUpdateCutoff(legId, cutoff));
    }

    /** 登记行李：1~5 个无重复有序航段，相邻航段首尾站衔接，初始位于首段始发站。 */
    public BagResponse registerBag(RegisterBagRequest request) {
        return idempotencyService.execute(request.requestId(), "REGISTER_BAG", 201,
                request, BagResponse.class, () -> doRegisterBag(request));
    }

    /**
     * 批量装载：整批原子，任一行李不满足则 422 且无一件移动。
     * 装载条件：当前 UTC 时刻严格早于航段截止时刻（恰好到达即关闭）且每件行李均已完成本航段交接；
     * 成功装载保留航段与当时截止快照，逾期/交接不满足的 422 在 load_rejection 留痕且不占装载名额。
     */
    public LoadResponse load(String legId, LoadRequest request) {
        LoadPayload payload = new LoadPayload(legId, request.expectedVersion(), sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "LOAD", 200,
                payload, LoadResponse.class, () -> doLoadAudited(legId, request));
    }

    private LoadResponse doLoadAudited(String legId, LoadRequest request) {
        try {
            return doLoad(legId, request);
        } catch (LoadRejectedException ex) {
            // 业务装载被拒：外层事务回滚，拒绝流水以独立事务提交留痕
            recordRejections(ex.rows());
            throw ApiException.unprocessable(ex.getMessage());
        }
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
     * 按行李 + 航段查询交接、截止、装载状态及最近一次拒绝原因。
     * 各航段状态独立取数：已完成航段记录取自 bag_leg_load 留痕，不被后续航段时刻变化覆盖。
     */
    public BagLegStatusResponse getBagLegStatus(String bagTag, String legId) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        LegRow leg = findLeg(legId);
        if (leg == null) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<ItineraryItem> itinerary = toItinerary(bagTag);
        int seq = -1;
        for (ItineraryItem item : itinerary) {
            if (item.legId().equals(legId)) {
                seq = item.seq();
                break;
            }
        }
        Instant now = clock.get();
        boolean cutoffClosed = !now.isBefore(leg.loadingCutoff());

        LoadTrail trail = findLoadTrail(bagTag, legId);
        boolean onThisLeg = legId.equals(bag.loadedLegId());
        boolean waitingAtOrigin = seq >= 0 && bag.nextLegIndex() == seq
                && bag.currentLocation().equals(leg.origin())
                && bag.loadedLegId() == null && !BAG_SHORT_UNLOADED.equals(bag.status());
        // 已成功装载留痕（含在舱、到达卸下与短卸）或当前正在始发站待乘，均视为已完成本航段交接
        boolean handedOver = seq >= 0 && (trail != null || onThisLeg || waitingAtOrigin);

        String loadStatus;
        if (onThisLeg) {
            loadStatus = LOAD_LOADED;
        } else if (legId.equals(bag.shortLegId()) && BAG_SHORT_UNLOADED.equals(bag.status())) {
            loadStatus = LOAD_SHORT;
        } else if (trail != null) {
            // 留痕存在且当前不在舱内：已随航段到达卸下（或补到后完成），记录不因后续航段改时刻而变化
            loadStatus = LOAD_UNLOADED;
        } else if (handedOver) {
            loadStatus = LOAD_NOT_LOADED;
        } else {
            loadStatus = LOAD_NOT_APPLICABLE;
        }

        RejectionRow lastRejection = findLastRejection(bagTag, legId);
        return new BagLegStatusResponse(
                bagTag, legId, leg.status(), leg.version(), leg.loadingCutoff().toString(),
                cutoffClosed, handedOver, loadStatus,
                trail == null ? null : trail.cutoffSnapshot().toString(),
                trail == null ? null : trail.loadedAt().toString(),
                lastRejection == null ? null : lastRejection.reason(),
                lastRejection == null ? null : lastRejection.detail(),
                lastRejection == null ? null : lastRejection.attemptedAt().toString());
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        Instant cutoff = parseCutoff(request.loadingCutoff());
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, loading_cutoff)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN,
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, cutoff.toString());
    }

    private LegResponse doUpdateCutoff(String legId, Instant cutoff) {
        LegRow leg = lockLeg(legId);
        if (!LEG_OPEN.equals(leg.status())) {
            throw ApiException.conflict(
                    "航段状态为 " + leg.status() + "，已封舱或最终到达，禁止修改截止时刻");
        }
        jdbcTemplate.update("UPDATE leg SET loading_cutoff = ? WHERE leg_id = ?",
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), legId);
        return new LegResponse(leg.legId(), leg.origin(), leg.destination(),
                LEG_OPEN, leg.version(), cutoff.toString());
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
        Instant now = clock.get();
        LegRow leg = lockLeg(legId);
        checkVersion(leg, request.expectedVersion());
        if (!LEG_OPEN.equals(leg.status())) {
            // 航段封舱/到达：整批拒绝并按袋留痕，不产生任何装载
            throw loadRejected(request.requestId(), legId, leg.loadingCutoff(), now,
                    sortedCopy(request.bagTags()), REASON_LEG_NOT_OPEN,
                    "航段状态为 " + leg.status() + "，禁止装载");
        }
        // 截止时刻恰好到达即关闭装载：当前 UTC 时刻必须严格早于截止时刻
        if (!now.isBefore(leg.loadingCutoff())) {
            throw loadRejected(request.requestId(), legId, leg.loadingCutoff(), now,
                    sortedCopy(request.bagTags()), REASON_CUTOFF_CLOSED,
                    "已到 UTC 装载截止时刻 " + leg.loadingCutoff() + "（当前 " + now + "），装载关闭");
        }
        List<String> bagTags = request.bagTags();
        List<String> sorted = sortedCopy(bagTags);
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw loadRejected(request.requestId(), legId, leg.loadingCutoff(), now,
                    sorted, REASON_BAG_DUPLICATE, "批量装载的 bagTag 不得重复");
        }
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : sorted) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw loadRejected(request.requestId(), legId, leg.loadingCutoff(), now,
                        List.of(bagTag), REASON_BAG_NOT_FOUND, "行李不存在: " + bagTag);
            }
            String[] problem = loadableProblem(bag, leg);
            if (problem != null) {
                throw loadRejected(request.requestId(), legId, leg.loadingCutoff(), now,
                        List.of(bagTag), problem[0], problem[1]);
            }
            bags.add(bag);
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
            // 各航段独立留痕：保留成功装载时刻与当时的截止快照，后续改时刻不回写
            jdbcTemplate.update(
                    "INSERT INTO bag_leg_load (bag_tag, leg_id, cutoff_snapshot, loaded_at, request_id)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    bag.bagTag(), legId,
                    OffsetDateTime.ofInstant(leg.loadingCutoff(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC), request.requestId());
            insertEvent(bag.bagTag(), EVT_LOADED, legId, leg.origin());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted, leg.loadingCutoff().toString());
    }

    /** 构造按袋留痕的装载拒绝异常（同一次拒绝内所有袋共用原因与截止快照）。 */
    private LoadRejectedException loadRejected(String requestId, String legId, Instant cutoff,
                                               Instant attemptedAt, List<String> bagTags,
                                               String reason, String detail) {
        List<RejectionRow> rows = bagTags.stream()
                .map(bagTag -> new RejectionRow(requestId, bagTag, legId, cutoff, attemptedAt, reason, detail))
                .toList();
        return new LoadRejectedException(detail, rows);
    }

    /** 以独立事务写入拒绝流水，失败装载事务的回滚不影响留痕。 */
    private void recordRejections(List<RejectionRow> rows) {
        rejectionTransaction.executeWithoutResult(status -> {
            for (RejectionRow row : rows) {
                jdbcTemplate.update(
                        "INSERT INTO load_rejection (request_id, bag_tag, leg_id, cutoff_snapshot,"
                                + " attempted_at, reason, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        row.requestId(), row.bagTag(), row.legId(),
                        row.cutoff() == null ? null
                                : OffsetDateTime.ofInstant(row.cutoff(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(row.attemptedAt(), ZoneOffset.UTC),
                        row.reason(), row.detail());
            }
        });
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

    /**
     * 沿用原有交接状态判断：行李须非短卸、未装载、待乘航段即目标航段且位于航段始发站。
     * 返回 {原因码, 说明}；满足交接条件返回 null。
     */
    private String[] loadableProblem(BagRow bag, LegRow leg) {
        if (BAG_SHORT_UNLOADED.equals(bag.status())) {
            return new String[] {REASON_SHORT_UNLOADED,
                    "行李 " + bag.bagTag() + " 处于短卸状态，须先补到才能装载后续航段"};
        }
        if (bag.loadedLegId() != null) {
            return new String[] {REASON_ALREADY_LOADED,
                    "行李 " + bag.bagTag() + " 已装载到航段 " + bag.loadedLegId()};
        }
        ItineraryItem next = nextItinerary(bag.bagTag(), bag.nextLegIndex());
        if (next == null) {
            return new String[] {REASON_ITINERARY_MISMATCH,
                    "行李 " + bag.bagTag() + " 已完成全部行程"};
        }
        if (!next.legId().equals(leg.legId())) {
            return new String[] {REASON_ITINERARY_MISMATCH,
                    "行李 " + bag.bagTag() + " 当前待乘航段为 " + next.legId()
                            + "，不能装载到 " + leg.legId()};
        }
        if (!bag.currentLocation().equals(leg.origin())) {
            return new String[] {REASON_NOT_AT_ORIGIN,
                    "行李 " + bag.bagTag() + " 不在航段始发站 " + leg.origin()};
        }
        return null;
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
        return "SELECT leg_id, origin, destination, status, version, loading_cutoff, sealed_manifest,"
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

    private LoadTrail findLoadTrail(String bagTag, String legId) {
        List<LoadTrail> rows = jdbcTemplate.query(
                "SELECT cutoff_snapshot, loaded_at FROM bag_leg_load WHERE bag_tag = ? AND leg_id = ?",
                (rs, rowNum) -> new LoadTrail(getInstant(rs, "cutoff_snapshot"),
                        getInstant(rs, "loaded_at")),
                bagTag, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RejectionRow findLastRejection(String bagTag, String legId) {
        List<RejectionRow> rows = jdbcTemplate.query(
                "SELECT request_id, bag_tag, leg_id, cutoff_snapshot, attempted_at, reason, detail"
                        + " FROM load_rejection WHERE bag_tag = ? AND leg_id = ?"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new RejectionRow(
                        rs.getString("request_id"), rs.getString("bag_tag"), rs.getString("leg_id"),
                        getInstant(rs, "cutoff_snapshot"), getInstant(rs, "attempted_at"),
                        rs.getString("reason"), rs.getString("detail")),
                bagTag, legId);
        return rows.isEmpty() ? null : rows.get(0);
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

    /** 解析 ISO-8601 UTC 装载截止时刻；非法格式返回 400。 */
    private static Instant parseCutoff(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(
                    "loadingCutoff 必须为 UTC ISO-8601 时刻（如 2026-09-30T12:00:00Z）: " + text);
        }
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
                          String status, int version, Instant loadingCutoff,
                          String sealedManifest, String arrivalType, String arrivalActual) {
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

    /** 修改截止时刻幂等摘要参数。 */
    private record UpdateCutoffPayload(String legId, String loadingCutoff) {
    }

    /** 成功装载留痕行：截止快照与 UTC 装载时刻。 */
    private record LoadTrail(Instant cutoffSnapshot, Instant loadedAt) {
    }

    /** 装载拒绝流水行。 */
    private record RejectionRow(String requestId, String bagTag, String legId, Instant cutoff,
                                Instant attemptedAt, String reason, String detail) {
    }

    /** 装载被拒异常：携带按袋留痕行，消息即返回给调用方的拒绝原因。 */
    private static final class LoadRejectedException extends RuntimeException {
        private final transient List<RejectionRow> rows;

        private LoadRejectedException(String message, List<RejectionRow> rows) {
            super(message);
            this.rows = rows;
        }

        private List<RejectionRow> rows() {
            return rows;
        }
    }
}
