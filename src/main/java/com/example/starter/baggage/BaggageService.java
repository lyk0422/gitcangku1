package com.example.starter.baggage;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.baggage.BaggageDtos.AlertItem;
import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ClearOverweightRequest;
import com.example.starter.baggage.BaggageDtos.ClearOverweightResponse;
import com.example.starter.baggage.BaggageDtos.ItineraryItem;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.OverweightStatusResponse;
import com.example.starter.baggage.BaggageDtos.OverweightWarning;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReweighHistoryItem;
import com.example.starter.baggage.BaggageDtos.ReweighHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ReweighRequest;
import com.example.starter.baggage.BaggageDtos.ReweighResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、到达确认、
 * 复重纠偏、超重提醒与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 *
 * <p>复重并发裁决：复重先对当前已装入航段加行锁再锁行李行，与装载/封舱使用一致的
 * “先航段锁后行李锁”顺序，避免死锁；装载在行李行锁内读取重量，因此复重一旦先提交，
 * 后到的装载必然读到复重后的最新重量。
 */
@Service
public class BaggageService {

    private static final String LEG_OPEN = "OPEN";
    private static final String LEG_SEALED = "SEALED";
    private static final String LEG_ARRIVED = "ARRIVED";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String ALERT_ACTIVE = "ACTIVE";
    private static final String ALERT_CLEARED = "CLEARED";

    /** 登记航段未指定总重上限时的默认值（千克）。 */
    static final int DEFAULT_LEG_MAX_LOAD_WEIGHT_KG = 1000;
    /** 登记行李未指定重量时的默认值（千克）。 */
    static final int DEFAULT_BAG_WEIGHT_KG = 23;
    /** 登记行李未指定免费限额时的默认值（千克）。 */
    static final int DEFAULT_FREE_ALLOWANCE_KG = 23;

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"),
            rs.getInt("max_load_weight_kg"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getInt("weight_kg"), rs.getInt("free_allowance_kg"),
            rs.getBoolean("overweight_active"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final BusinessClock businessClock;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper,
                          BusinessClock businessClock) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.businessClock = businessClock;
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

    /**
     * 批量装载：整批原子，任一行李不满足则 422 且无一件移动。
     * 总重上限按“装载后清单内全部行李的最新记录重量”合计判定；
     * 清单内存在未清除 OVERWEIGHT 提醒时不拦截，仅在响应中携带提醒清单。
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

    /** 到达确认：实际袋号集合须与封舱清单完全一致，匹配则原子转 ARRIVED 并推进各行李。 */
    public ArriveResponse arrive(String legId, ArriveRequest request) {
        ArrivePayload payload = new ArrivePayload(legId, sortedCopy(request.bagTags()));
        return idempotencyService.execute(request.requestId(), "ARRIVE", 200,
                payload, ArriveResponse.class, () -> doArrive(legId, request));
    }

    /**
     * 复重纠偏：行李尚未进入任何 SEALED 航段清单方可提交。实测重量与当前记录不同则
     * 原子更新并固化不可变复重记录，相同则仅记录事件；随后按整程累计重量校验免费限额，
     * 超限且无活动提醒时生成新的 OVERWEIGHT 提醒。
     */
    public ReweighResponse reweigh(String bagTag, ReweighRequest request) {
        ReweighPayload payload = new ReweighPayload(bagTag, request.reweighKey(),
                request.measuredWeightKg(), request.stationId());
        return idempotencyService.execute(request.requestId(), "REWEIGH", 200,
                payload, ReweighResponse.class, () -> doReweigh(bagTag, request));
    }

    /** 清除超重提醒：清除不可逆且必须提交超额说明；无活动提醒时 422。 */
    public ClearOverweightResponse clearOverweight(String bagTag, ClearOverweightRequest request) {
        ClearPayload payload = new ClearPayload(bagTag, request.explanation());
        return idempotencyService.execute(request.requestId(), "CLEAR_OVERWEIGHT", 200,
                payload, ClearOverweightResponse.class, () -> doClearOverweight(bagTag, request));
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

    /** 行李复重历史查询：返回全部不可变复重事件与当前重量/提醒状态。 */
    public ReweighHistoryResponse getReweighHistory(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ReweighRecordRow> rows = jdbcTemplate.query(
                "SELECT id, seq, reweigh_key, old_weight_kg, new_weight_kg, weight_changed, station_id,"
                        + " weighed_at FROM reweigh_record WHERE bag_tag = ? ORDER BY seq",
                (rs, rowNum) -> new ReweighRecordRow(rs.getLong("id"), rs.getInt("seq"),
                        rs.getString("reweigh_key"), rs.getInt("old_weight_kg"), rs.getInt("new_weight_kg"),
                        rs.getBoolean("weight_changed"), rs.getString("station_id"),
                        rs.getTimestamp("weighed_at")),
                bagTag);
        List<ReweighHistoryItem> history = new ArrayList<>();
        for (ReweighRecordRow row : rows) {
            Integer raised = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM overweight_alert WHERE reweigh_id = ?",
                    Integer.class, row.id());
            history.add(new ReweighHistoryItem(row.seq(), row.reweighKey(), row.oldWeightKg(),
                    row.newWeightKg(), row.weightChanged(), row.stationId(),
                    fmt(row.weighedAt()), raised != null && raised > 0));
        }
        return new ReweighHistoryResponse(bag.bagTag(), bag.weightKg(), journeyWeightKg(bag),
                bag.freeAllowanceKg(), bag.overweightActive(), history);
    }

    /** 当前超重提醒状态查询：含活动提醒与已清除的历史提醒。 */
    @Transactional(readOnly = true)
    public OverweightStatusResponse getOverweightStatus(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<AlertItem> alerts = jdbcTemplate.query(
                "SELECT a.seq AS seq, a.status AS status, a.journey_weight_kg AS journey_weight_kg,"
                        + " a.free_allowance_kg AS free_allowance_kg, a.raised_at AS raised_at,"
                        + " a.cleared_explanation AS cleared_explanation, a.cleared_at AS cleared_at,"
                        + " r.reweigh_key AS reweigh_key"
                        + " FROM overweight_alert a JOIN reweigh_record r ON r.id = a.reweigh_id"
                        + " WHERE a.bag_tag = ? ORDER BY a.seq",
                (rs, rowNum) -> new AlertItem(rs.getInt("seq"), rs.getString("status"),
                        rs.getInt("journey_weight_kg"), rs.getInt("free_allowance_kg"),
                        fmt(rs.getTimestamp("raised_at")), rs.getString("reweigh_key"),
                        rs.getString("cleared_explanation"), fmt(rs.getTimestamp("cleared_at"))),
                bagTag);
        return new OverweightStatusResponse(bag.bagTag(), bag.overweightActive(), alerts);
    }

    private LegResponse doRegisterLeg(RegisterLegRequest request) {
        int maxLoadWeightKg = request.maxLoadWeightKg() == null
                ? DEFAULT_LEG_MAX_LOAD_WEIGHT_KG : request.maxLoadWeightKg();
        if (findLeg(request.legId()) != null) {
            throw ApiException.conflict("航段已存在: " + request.legId());
        }
        jdbcTemplate.update(
                "INSERT INTO leg (leg_id, origin, destination, status, version, max_load_weight_kg)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                request.legId(), request.origin(), request.destination(), LEG_OPEN, maxLoadWeightKg);
        return new LegResponse(request.legId(), request.origin(), request.destination(),
                LEG_OPEN, 1, maxLoadWeightKg);
    }

    private BagResponse doRegisterBag(RegisterBagRequest request) {
        List<String> legIds = request.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw ApiException.unprocessable("行程航段不得重复");
        }
        if (findBag(request.bagTag()) != null) {
            throw ApiException.conflict("行李已存在: " + request.bagTag());
        }
        int weightKg = request.weightKg() == null ? DEFAULT_BAG_WEIGHT_KG : request.weightKg();
        int freeAllowanceKg = request.freeAllowanceKg() == null
                ? DEFAULT_FREE_ALLOWANCE_KG : request.freeAllowanceKg();
        if (weightKg < 1 || weightKg > 50) {
            throw ApiException.unprocessable("行李重量必须为 1~50 千克整数");
        }
        if (freeAllowanceKg <= 0) {
            throw ApiException.unprocessable("免费限额必须为正整数");
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
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, weight_kg, free_allowance_kg)"
                        + " VALUES (?, ?, 0, ?, ?, ?)",
                request.bagTag(), legs.get(0).origin(), BAG_IN_TRANSIT, weightKg, freeAllowanceKg);
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    request.bagTag(), i, leg.legId(), leg.origin(), leg.destination());
        }
        BagRow bag = findBag(request.bagTag());
        return toBagResponse(bag);
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
        List<String> incoming = sortedCopy(bagTags);
        List<BagRow> bags = new ArrayList<>();
        for (String bagTag : incoming) {
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            validateLoadable(bag, leg);
            bags.add(bag);
        }
        // 装载后清单 = 航段当前清单（可能经过复重，重量已变化）+ 本批新行李，
        // 总重一律按行锁内读到的最新记录重量合计。
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ?", String.class, legId);
        int totalWeightKg = 0;
        for (String bagTag : existing) {
            BagRow aboard = lockBag(bagTag);
            totalWeightKg += aboard.weightKg();
        }
        List<OverweightWarning> warnings = new ArrayList<>();
        for (BagRow bag : bags) {
            totalWeightKg += bag.weightKg();
            if (bag.overweightActive()) {
                warnings.add(toWarning(bag));
            }
        }
        if (totalWeightKg > leg.maxLoadWeightKg()) {
            throw ApiException.unprocessable(
                    "装载后清单总重 " + totalWeightKg + " 千克超过航段上限 " + leg.maxLoadWeightKg() + " 千克");
        }
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, incoming,
                totalWeightKg, leg.maxLoadWeightKg(), warnings);
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
            int nextIndex = bag.nextLegIndex() + 1;
            int total = itineraryCount(bagTag);
            String status = nextIndex >= total ? BAG_DELIVERED : BAG_IN_TRANSIT;
            jdbcTemplate.update(
                    "UPDATE bag SET current_location = ?, next_leg_index = ?, status = ?, loaded_leg_id = NULL"
                            + " WHERE bag_tag = ?",
                    leg.destination(), nextIndex, status, bagTag);
        }
        jdbcTemplate.update("DELETE FROM load_record WHERE leg_id = ?", legId);
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET status = ?, version = ? WHERE leg_id = ?",
                LEG_ARRIVED, newVersion, legId);
        return new ArriveResponse(legId, LEG_ARRIVED, newVersion, manifest);
    }

    private ReweighResponse doReweigh(String bagTag, ReweighRequest request) {
        int measuredWeightKg = request.measuredWeightKg();
        // 先不加锁读取，确定可能需要串行化的已装航段；随后锁航段、再锁行李，
        // 与装载的“先航段后行李”加锁顺序保持一致，杜绝交叉死锁。
        BagRow snapshot = findBag(bagTag);
        if (snapshot == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        Set<String> lockedLegs = new HashSet<>();
        if (snapshot.loadedLegId() != null) {
            lockLegIfOpen(snapshot.loadedLegId(), lockedLegs, bagTag);
        }
        BagRow bag = lockBag(bagTag);
        // 取得行李锁前可能有并发装载提交，补锁最新已装航段后状态即稳定（后续装载需行李锁）。
        if (bag.loadedLegId() != null && !lockedLegs.contains(bag.loadedLegId())) {
            lockLegIfOpen(bag.loadedLegId(), lockedLegs, bagTag);
        }
        if (bag.nextLegIndex() > 0) {
            throw ApiException.conflict("行李已到达过封舱航段，禁止复重: " + bagTag);
        }

        int oldWeightKg = bag.weightKg();
        boolean weightChanged = oldWeightKg != measuredWeightKg;
        Timestamp now = Timestamp.valueOf(businessClock.now());
        if (weightChanged) {
            jdbcTemplate.update("UPDATE bag SET weight_kg = ? WHERE bag_tag = ?", measuredWeightKg, bagTag);
        }
        int seq = countByBag("reweigh_record", bagTag);
        long reweighId = insertReweighRecord(bagTag, seq, request, oldWeightKg, measuredWeightKg,
                weightChanged, now);

        BagRow updated = new BagRow(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), measuredWeightKg, bag.freeAllowanceKg(),
                bag.overweightActive());
        int journeyWeightKg = journeyWeightKg(updated);
        boolean overweightRaised = false;
        boolean overweightActive = bag.overweightActive();
        if (journeyWeightKg > bag.freeAllowanceKg()) {
            if (!overweightActive) {
                int alertSeq = countAlerts(bagTag);
                jdbcTemplate.update(
                        "INSERT INTO overweight_alert (bag_tag, seq, reweigh_id, journey_weight_kg,"
                                + " free_allowance_kg, status, raised_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        bagTag, alertSeq, reweighId, journeyWeightKg, bag.freeAllowanceKg(),
                        ALERT_ACTIVE, now);
                jdbcTemplate.update("UPDATE bag SET overweight_active = TRUE WHERE bag_tag = ?", bagTag);
                overweightActive = true;
                overweightRaised = true;
            }
        }
        return new ReweighResponse(bagTag, measuredWeightKg, journeyWeightKg, bag.freeAllowanceKg(),
                overweightActive, seq, fmt(now));
    }

    private ClearOverweightResponse doClearOverweight(String bagTag, ClearOverweightRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (!bag.overweightActive()) {
            throw ApiException.unprocessable("行李不存在未清除的超重提醒: " + bagTag);
        }
        Timestamp now = Timestamp.valueOf(businessClock.now());
        int updated = jdbcTemplate.update(
                "UPDATE overweight_alert SET status = ?, cleared_explanation = ?, cleared_at = ?"
                        + " WHERE bag_tag = ? AND status = ?",
                ALERT_CLEARED, request.explanation(), now, bagTag, ALERT_ACTIVE);
        if (updated != 1) {
            throw ApiException.unprocessable("行李不存在未清除的超重提醒: " + bagTag);
        }
        jdbcTemplate.update("UPDATE bag SET overweight_active = FALSE WHERE bag_tag = ?", bagTag);
        return new ClearOverweightResponse(bagTag, false, request.explanation(), fmt(now));
    }

    /**
     * 锁行李当前所在的已装航段并校验封舱状态：航段已 SEALED 则复重返回 409。
     * OPEN 航段允许复重，持锁至事务提交以与并发封舱按提交顺序裁决。
     */
    private void lockLegIfOpen(String legId, Set<String> lockedLegs, String bagTag) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest, max_load_weight_kg"
                        + " FROM leg WHERE leg_id = ? FOR UPDATE",
                LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("行李已装载的航段不存在: " + legId);
        }
        if (LEG_SEALED.equals(rows.get(0).status())) {
            throw ApiException.conflict("行李已进入封舱航段 " + legId + " 的清单，禁止复重: " + bagTag);
        }
        lockedLegs.add(legId);
    }

    private long insertReweighRecord(String bagTag, int seq, ReweighRequest request,
                                     int oldWeightKg, int newWeightKg, boolean weightChanged,
                                     Timestamp weighedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO reweigh_record (bag_tag, seq, reweigh_key, old_weight_kg, new_weight_kg,"
                            + " weight_changed, station_id, weighed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, bagTag);
            ps.setInt(2, seq);
            ps.setString(3, request.reweighKey());
            ps.setInt(4, oldWeightKg);
            ps.setInt(5, newWeightKg);
            ps.setBoolean(6, weightChanged);
            ps.setString(7, request.stationId());
            ps.setTimestamp(8, weighedAt);
            return ps;
        }, keyHolder);
        List<Map<String, Object>> keyList = keyHolder.getKeyList();
        if (keyList.isEmpty() || keyList.get(0).get("id") == null) {
            throw new IllegalStateException("复重记录主键生成失败");
        }
        return ((Number) keyList.get(0).get("id")).longValue();
    }

    private void validateLoadable(BagRow bag, LegRow leg) {
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
                "SELECT leg_id, origin, destination, status, version, sealed_manifest, max_load_weight_kg"
                        + " FROM leg WHERE leg_id = ?",
                LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest, max_load_weight_kg"
                        + " FROM leg WHERE leg_id = ? FOR UPDATE",
                LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id, weight_kg,"
                        + " free_allowance_kg, overweight_active FROM bag WHERE bag_tag = ?",
                BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id, weight_kg,"
                        + " free_allowance_kg, overweight_active FROM bag WHERE bag_tag = ? FOR UPDATE",
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

    private BagResponse toBagResponse(BagRow bag) {
        return new BagResponse(bag.bagTag(), bag.currentLocation(), bag.nextLegIndex(),
                bag.status(), bag.loadedLegId(), toItinerary(bag.bagTag()),
                bag.weightKg(), bag.freeAllowanceKg(), bag.overweightActive());
    }

    private OverweightWarning toWarning(BagRow bag) {
        List<OverweightAlertRow> rows = jdbcTemplate.query(
                "SELECT journey_weight_kg, raised_at FROM overweight_alert"
                        + " WHERE bag_tag = ? AND status = ?",
                (rs, rowNum) -> new OverweightAlertRow(rs.getInt("journey_weight_kg"),
                        rs.getTimestamp("raised_at")),
                bag.bagTag(), ALERT_ACTIVE);
        OverweightAlertRow active = rows.isEmpty() ? null : rows.get(0);
        int journeyWeightKg = active == null ? journeyWeightKg(bag) : active.journeyWeightKg();
        String raisedAt = active == null ? null : fmt(active.raisedAt());
        return new OverweightWarning(bag.bagTag(), bag.weightKg(), journeyWeightKg,
                bag.freeAllowanceKg(), raisedAt);
    }

    /**
     * 整程累计重量：复用既有分段重量规则；当前无既有规则，按题干约定简单以本行李最新重量为准。
     */
    private int journeyWeightKg(BagRow bag) {
        return bag.weightKg();
    }

    private int countByBag(String table, String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE bag_tag = ?", Integer.class, bagTag);
        return count == null ? 0 : count;
    }

    private int countAlerts(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM overweight_alert WHERE bag_tag = ?", Integer.class, bagTag);
        return count == null ? 0 : count;
    }

    private static String fmt(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
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
                          String status, int version, String sealedManifest, int maxLoadWeightKg) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, int weightKg, int freeAllowanceKg,
                          boolean overweightActive) {
    }

    private record OverweightAlertRow(int journeyWeightKg, Timestamp raisedAt) {
    }

    private record ReweighRecordRow(long id, int seq, String reweighKey, int oldWeightKg,
                                    int newWeightKg, boolean weightChanged, String stationId,
                                    Timestamp weighedAt) {
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

    /** 复重幂等摘要参数。 */
    private record ReweighPayload(String bagTag, String reweighKey, int measuredWeightKg, String stationId) {
    }

    /** 清除超重提醒幂等摘要参数：超额说明同属请求参数，异参返回 409。 */
    private record ClearPayload(String bagTag, String explanation) {
    }
}
