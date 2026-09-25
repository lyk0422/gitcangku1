package com.example.starter.baggage;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

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
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReweighHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ReweighRecordItem;
import com.example.starter.baggage.BaggageDtos.ReweighRequest;
import com.example.starter.baggage.BaggageDtos.ReweighResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

/**
 * 联程行李装载交接核心业务：航段/行李登记、批量装载、封舱、到达确认与查询。
 * 所有写操作经 {@link IdempotencyService} 去重，业务变更与去重记录原子提交；
 * 航段行级锁（SELECT ... FOR UPDATE）保证装载与封舱并发时以版本决定唯一先后，
 * load_record 以 bag_tag 为主键保证同件行李不会进入两个清单。
 */
@Service
public class BaggageService {

    private static final String LEG_OPEN = "OPEN";
    private static final String LEG_SEALED = "SEALED";
    private static final String LEG_ARRIVED = "ARRIVED";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_DELIVERED = "DELIVERED";
    /** 超重提醒状态：无提醒。 */
    private static final String REMINDER_NONE = "NONE";
    /** 超重提醒状态：待人工处理。 */
    private static final String REMINDER_ACTIVE = "ACTIVE";
    /** 超重提醒状态：已人工清除（不可逆）。 */
    private static final String REMINDER_CLEARED = "CLEARED";
    /** 未登记免费限额时的默认值（千克）。 */
    private static final int DEFAULT_FREE_ALLOWANCE_KG = 20;

    private static final String BAG_COLUMNS = "bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
            + " weight_kg, free_allowance_kg, overweight_reminder, overweight_clear_note";

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("version"), rs.getString("sealed_manifest"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getString("loaded_leg_id"),
            rs.getObject("weight_kg", Integer.class), rs.getInt("free_allowance_kg"),
            rs.getString("overweight_reminder"), rs.getString("overweight_clear_note"));

    private static final RowMapper<ItineraryItem> ITINERARY_MAPPER = (rs, rowNum) -> new ItineraryItem(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"));

    private static final RowMapper<ReweighRecordItem> REWEIGH_MAPPER = (rs, rowNum) -> new ReweighRecordItem(
            rs.getString("reweigh_key"), rs.getObject("old_weight_kg", Integer.class),
            rs.getInt("new_weight_kg"), rs.getBoolean("weight_changed"),
            rs.getString("station_id"), toIso(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** 批量装载总重上限（千克），按装载时行李最新重量判定。 */
    private final int maxLoadTotalWeightKg;

    public BaggageService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper,
                          @Value("${baggage.max-load-total-weight-kg:500}") int maxLoadTotalWeightKg) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.maxLoadTotalWeightKg = maxLoadTotalWeightKg;
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

    /** 复重纠偏：未进入任何 SEALED 清单的行李可复重；重量不同则原子更新并写不可变复重记录，
     *  相同则仅记录事件；复重后按最新重量触发超重校验。 */
    public ReweighResponse reweigh(String bagTag, ReweighRequest request) {
        ReweighPayload payload = new ReweighPayload(bagTag, request.reweighKey(),
                request.measuredWeightKg(), request.stationId());
        return idempotencyService.execute(request.requestId(), "REWEIGH", 200,
                payload, ReweighResponse.class, () -> doReweigh(bagTag, request));
    }

    /** 清除超重提醒：需说明，清除不可逆；无待处理提醒时 422。 */
    public ClearOverweightResponse clearOverweight(String bagTag, ClearOverweightRequest request) {
        ClearOverweightPayload payload = new ClearOverweightPayload(bagTag, request.note());
        return idempotencyService.execute(request.requestId(), "CLEAR_OVERWEIGHT", 200,
                payload, ClearOverweightResponse.class, () -> doClearOverweight(bagTag, request));
    }

    /** 复重历史与当前提醒状态查询。 */
    public ReweighHistoryResponse getReweighHistory(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<ReweighRecordItem> history = jdbcTemplate.query(
                "SELECT reweigh_key, old_weight_kg, new_weight_kg, weight_changed, station_id, created_at"
                        + " FROM reweigh_record WHERE bag_tag = ? ORDER BY id",
                REWEIGH_MAPPER, bagTag);
        return new ReweighHistoryResponse(bag.bagTag(), bag.weightKg(), bag.freeAllowanceKg(),
                bag.overweightReminder(), bag.overweightClearNote(), history);
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
        jdbcTemplate.update(
                "INSERT INTO bag (bag_tag, current_location, next_leg_index, status, weight_kg, free_allowance_kg)"
                        + " VALUES (?, ?, 0, ?, ?, ?)",
                request.bagTag(), legs.get(0).origin(), BAG_IN_TRANSIT, request.weightKg(),
                request.freeAllowanceKg() == null ? DEFAULT_FREE_ALLOWANCE_KG : request.freeAllowanceKg());
        for (int i = 0; i < legs.size(); i++) {
            LegRow leg = legs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination) VALUES (?, ?, ?, ?, ?)",
                    request.bagTag(), i, leg.legId(), leg.origin(), leg.destination());
        }
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
        // 总重上限判定使用行锁下读到的最新重量（复重提交后装载必须读到新值）
        int totalWeightKg = bags.stream().mapToInt(bag -> bag.weightKg() == null ? 0 : bag.weightKg()).sum();
        if (totalWeightKg > maxLoadTotalWeightKg) {
            throw ApiException.unprocessable(
                    "批量装载总重 " + totalWeightKg + " 千克超过上限 " + maxLoadTotalWeightKg + " 千克");
        }
        // 未清除的超重提醒不阻止装载，但必须在响应中显式携带供人工确认
        List<String> overweightReminders = bags.stream()
                .filter(bag -> REMINDER_ACTIVE.equals(bag.overweightReminder()))
                .map(BagRow::bagTag)
                .toList();
        for (BagRow bag : bags) {
            jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES (?, ?)", bag.bagTag(), legId);
            jdbcTemplate.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ?", legId, bag.bagTag());
        }
        int newVersion = leg.version() + 1;
        jdbcTemplate.update("UPDATE leg SET version = ? WHERE leg_id = ?", newVersion, legId);
        return new LoadResponse(legId, LEG_OPEN, newVersion, sorted, totalWeightKg, overweightReminders);
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
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (isInSealedManifest(bagTag)) {
            throw ApiException.conflict("行李 " + bagTag + " 已进入 SEALED 航段清单，禁止复重");
        }
        if (reweighKeyExists(request.reweighKey())) {
            throw ApiException.conflict("reweighKey 已使用: " + request.reweighKey());
        }
        int measured = request.measuredWeightKg();
        Integer previous = bag.weightKg();
        boolean changed = previous == null || previous != measured;
        if (changed) {
            jdbcTemplate.update("UPDATE bag SET weight_kg = ? WHERE bag_tag = ?", measured, bagTag);
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO reweigh_record (bag_tag, reweigh_key, old_weight_kg, new_weight_kg,"
                            + " weight_changed, station_id) VALUES (?, ?, ?, ?, ?, ?)",
                    bagTag, request.reweighKey(), previous, measured, changed, request.stationId());
        } catch (DuplicateKeyException duplicate) {
            // 并发下唯一索引兜底：reweighKey 已被其他请求占用
            throw ApiException.conflict("reweighKey 已使用: " + request.reweighKey());
        }
        // 复重后触发超重校验：无既有分段重量规则，以本行李最新重量对照登记的免费限额
        String reminder = measured > bag.freeAllowanceKg() ? REMINDER_ACTIVE : REMINDER_NONE;
        jdbcTemplate.update(
                "UPDATE bag SET overweight_reminder = ?, overweight_clear_note = NULL,"
                        + " overweight_cleared_at = NULL WHERE bag_tag = ?",
                reminder, bagTag);
        Timestamp weighedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM reweigh_record WHERE reweigh_key = ?",
                Timestamp.class, request.reweighKey());
        return new ReweighResponse(bagTag, request.reweighKey(), previous, measured, changed,
                request.stationId(), toIso(weighedAt), reminder);
    }

    private ClearOverweightResponse doClearOverweight(String bagTag, ClearOverweightRequest request) {
        BagRow bag = lockBag(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        if (!REMINDER_ACTIVE.equals(bag.overweightReminder())) {
            throw ApiException.unprocessable("行李 " + bagTag + " 当前无待清除的超重提醒");
        }
        jdbcTemplate.update(
                "UPDATE bag SET overweight_reminder = ?, overweight_clear_note = ?,"
                        + " overweight_cleared_at = CURRENT_TIMESTAMP WHERE bag_tag = ?",
                REMINDER_CLEARED, request.note(), bagTag);
        Timestamp clearedAt = jdbcTemplate.queryForObject(
                "SELECT overweight_cleared_at FROM bag WHERE bag_tag = ?", Timestamp.class, bagTag);
        return new ClearOverweightResponse(bagTag, REMINDER_CLEARED, request.note(), toIso(clearedAt));
    }

    /** 行李是否已进入任一 SEALED/ARRIVED 航段的封舱清单（一旦进入永久禁止复重）。 */
    private boolean isInSealedManifest(String bagTag) {
        List<String> manifests = jdbcTemplate.queryForList(
                "SELECT sealed_manifest FROM leg WHERE sealed_manifest IS NOT NULL", String.class);
        for (String manifest : manifests) {
            if (readJsonList(manifest).contains(bagTag)) {
                return true;
            }
        }
        return false;
    }

    private boolean reweighKeyExists(String reweighKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reweigh_record WHERE reweigh_key = ?", Integer.class, reweighKey);
        return count != null && count > 0;
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
                "SELECT leg_id, origin, destination, status, version, sealed_manifest FROM leg WHERE leg_id = ?",
                LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest FROM leg"
                        + " WHERE leg_id = ? FOR UPDATE",
                LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT " + BAG_COLUMNS + " FROM bag WHERE bag_tag = ?",
                BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT " + BAG_COLUMNS + " FROM bag WHERE bag_tag = ? FOR UPDATE",
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
                bag.weightKg(), bag.freeAllowanceKg(), bag.overweightReminder());
    }

    private static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
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
                          String status, int version, String sealedManifest) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, Integer weightKg, int freeAllowanceKg,
                          String overweightReminder, String overweightClearNote) {
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

    /** 清除超重提醒幂等摘要参数。 */
    private record ClearOverweightPayload(String bagTag, String note) {
    }
}
