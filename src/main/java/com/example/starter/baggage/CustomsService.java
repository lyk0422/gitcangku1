package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.BaggageDtos.CustomsCheckRequest;
import com.example.starter.baggage.BaggageDtos.CustomsCheckResponse;
import com.example.starter.baggage.BaggageDtos.GateItem;
import com.example.starter.baggage.BaggageDtos.HoldImpactResponse;
import com.example.starter.baggage.BaggageDtos.InspectionChainResponse;
import com.example.starter.baggage.BaggageDtos.InspectionItem;
import com.example.starter.baggage.BaggageDtos.LegGateResponse;

/**
 * 行李海关放行与持续门禁核心服务：
 * 检查登记（放行/拦截终态、检查版本链、UTC 时刻与原因、clearanceKey 指纹幂等）、
 * 检查链/航段门禁/拦截影响查询，以及供装载、补到、改派、起飞调用的持续门禁裁决。
 *
 * <p>裁决顺序：检查登记与装载/改派/起飞均先持有行李行锁（SELECT ... FOR UPDATE），
 * 因此按事务提交顺序串行裁决；拦截只改写未起飞（OPEN/SEALED）航段门禁，
 * 已 DEPARTED 的航段门禁冻结不改写。解除拦截必须以更高版本登记放行，历史行永不删除。
 */
@Service
public class CustomsService {

    static final String CHECK_RELEASED = "RELEASED";
    static final String CHECK_HELD = "HELD";
    static final String GATE_RELEASED = "RELEASED";
    static final String GATE_HELD = "CUSTOMS_HOLD";
    private static final String EVT_CUSTOMS_HELD = "CUSTOMS_HELD";
    private static final String EVT_CUSTOMS_RELEASED = "CUSTOMS_RELEASED";

    private static final RowMapper<InspectionItem> INSPECTION_MAPPER = (rs, rowNum) -> new InspectionItem(
            rs.getInt("check_version"), rs.getString("status"), rs.getString("country"),
            rs.getString("reason"), getInstant(rs, "checked_at").toString(), rs.getString("clearance_key"));

    private static final RowMapper<GateItem> GATE_MAPPER = (rs, rowNum) -> new GateItem(
            rs.getString("bag_tag"), rs.getString("leg_id"), rs.getString("country"),
            rs.getString("gate_status"), rs.getInt("check_version"),
            getInstant(rs, "updated_at").toString(), rs.getString("hold_snapshot"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public CustomsService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），所有检查时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 登记海关检查终态：放行或拦截。
     * <ul>
     *   <li>同一检查版本只能一个终态：同指纹重放原结果，同版本异参 409；</li>
     *   <li>检查版本按行李严格递增，更小版本 422；解除拦截必须使用更高版本；</li>
     *   <li>拦截原因不能为空（400 由校验注解处理，空白 422）；</li>
     *   <li>拦截把未起飞的目的国后续国际航段门禁置 CUSTOMS_HOLD 并写只读快照，放行清除之；</li>
     *   <li>失败在任何写入前抛出，不占 clearanceKey，不留半成品。</li>
     * </ul>
     */
    public CustomsCheckResponse registerCheck(CustomsCheckRequest request) {
        String status = request.status() == null ? "" : request.status().trim().toUpperCase();
        String country = request.country() == null ? "" : request.country().trim().toUpperCase();
        String reason = request.reason() == null ? null : request.reason().trim();
        int checkVersion = request.checkVersion() == null ? -1 : request.checkVersion();
        // 参数语义校验在事务与写入之前完成，失败不占 requestId 与 clearanceKey
        if (!CHECK_RELEASED.equals(status) && !CHECK_HELD.equals(status)) {
            throw ApiException.unprocessable("海关检查状态只能为 RELEASED 或 HELD: " + request.status());
        }
        if (country.isBlank()) {
            throw ApiException.unprocessable("country 不能为空");
        }
        if (checkVersion <= 0) {
            throw ApiException.unprocessable("checkVersion 必须为正整数");
        }
        if (CHECK_HELD.equals(status) && (reason == null || reason.isEmpty())) {
            throw ApiException.unprocessable("拦截原因不能为空");
        }
        String normalizedReason = CHECK_RELEASED.equals(status) ? null : reason;
        CustomsCheckRequest normalized = new CustomsCheckRequest(
                request.requestId(), request.bagTag(), checkVersion, status, country, normalizedReason);
        // requestId 幂等包裹事务；clearanceKey 同指纹重放在行锁内由 doRegisterCheck 处理
        return idempotencyService.execute(request.requestId(), "CUSTOMS_CHECK", 200,
                normalized, CustomsCheckResponse.class, () -> doRegisterCheck(normalized));
    }

    private CustomsCheckResponse doRegisterCheck(CustomsCheckRequest request) {
        String bagTag = request.bagTag();
        int checkVersion = request.checkVersion();
        String status = request.status();
        String country = request.country();
        String reason = request.reason();
        String clearanceKey = clearanceKey(bagTag, checkVersion, status, country, reason);
        // 行李行锁：与装载/改派/起飞按提交顺序裁决；同行李并发检查在此串行
        BagGateInput bag = lockBagForGate(bagTag);
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        InspectionRow keyRow = findByClearanceKey(clearanceKey);
        if (keyRow != null) {
            // 同指纹（行李+版本+状态+国家+原因）重放原结果，不重复生效
            return toCheckResponse(bagTag, keyRow);
        }
        InspectionRow sameVersion = findInspection(bagTag, checkVersion);
        if (sameVersion != null) {
            throw ApiException.conflict(
                    "检查版本 " + checkVersion + " 已存在终态 " + sameVersion.status()
                            + "，同一检查版本只能一个终态");
        }
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(check_version) FROM customs_inspection WHERE bag_tag = ?",
                Integer.class, bagTag);
        if (maxVersion != null && checkVersion <= maxVersion) {
            throw ApiException.unprocessable(
                    "检查版本必须严格递增: 提交 " + checkVersion + "，当前最大 " + maxVersion
                            + "；解除拦截必须创建更高检查版本，不能覆盖原终态");
        }
        Instant now = clock.get();
        OffsetDateTime checkedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        List<String> affectedLegs;
        if (CHECK_HELD.equals(status)) {
            affectedLegs = applyHold(bag, checkVersion, country, reason, checkedAt, now);
        } else {
            affectedLegs = applyRelease(bagTag, bag.nextLegIndex(), checkVersion, country, checkedAt);
        }
        String affectedJson = writeJson(affectedLegs);
        jdbcTemplate.update(
                "INSERT INTO customs_inspection (bag_tag, check_version, status, country, reason,"
                        + " checked_at, clearance_key, affected_legs) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, checkVersion, status, country, reason, checkedAt, clearanceKey, affectedJson);
        return toCheckResponse(bagTag, new InspectionRow(
                checkVersion, status, country, reason, now, clearanceKey, affectedLegs));
    }

    /** 拦截：将未起飞（OPEN/SEALED）的目的国后续国际航段门禁置 CUSTOMS_HOLD 并写快照。 */
    private List<String> applyHold(BagGateInput bag, int checkVersion, String country, String reason,
                                   OffsetDateTime eventTime, Instant now) {
        List<FutureLeg> legs = futureLegs(bag.bagTag(), bag.nextLegIndex(), country);
        List<String> affected = new java.util.ArrayList<>();
        for (FutureLeg leg : legs) {
            String snapshot = writeJson(holdSnapshot(bag, leg, checkVersion, country, reason, now));
            upsertGate(bag.bagTag(), leg.legId(), country, GATE_HELD, checkVersion, snapshot, eventTime);
            insertGateEvent(bag.bagTag(), EVT_CUSTOMS_HELD, leg, eventTime);
            affected.add(leg.legId());
        }
        return affected;
    }

    /** 放行：把未起飞的目的国后续国际航段门禁置 RELEASED 并清除快照；已起飞记录不改写。 */
    private List<String> applyRelease(String bagTag, int nextLegIndex, int checkVersion,
                                      String country, OffsetDateTime eventTime) {
        List<FutureLeg> legs = futureLegs(bagTag, nextLegIndex, country);
        List<String> affected = new java.util.ArrayList<>();
        for (FutureLeg leg : legs) {
            boolean wasHeld = gateStatus(bagTag, leg.legId()).equals(GATE_HELD);
            upsertGate(bagTag, leg.legId(), country, GATE_RELEASED, checkVersion, null, eventTime);
            if (wasHeld) {
                insertGateEvent(bagTag, EVT_CUSTOMS_RELEASED, leg, eventTime);
            }
            affected.add(leg.legId());
        }
        return affected;
    }

    /** 查询行李完整检查链与当前各航段门禁。 */
    public InspectionChainResponse getChain(String bagTag) {
        if (findBagForGate(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<InspectionItem> inspections = jdbcTemplate.query(
                "SELECT check_version, status, country, reason, checked_at, clearance_key"
                        + " FROM customs_inspection WHERE bag_tag = ? ORDER BY check_version",
                INSPECTION_MAPPER, bagTag);
        return new InspectionChainResponse(bagTag, inspections, listGates(
                "SELECT bag_tag, leg_id, country, gate_status, check_version, updated_at, hold_snapshot"
                        + " FROM bag_gate WHERE bag_tag = ? ORDER BY leg_id", bagTag));
    }

    /** 查询航段上所有行李的持续门禁。 */
    public LegGateResponse getLegGates(String legId) {
        List<LegMeta> metas = jdbcTemplate.query(
                "SELECT status, destination_country FROM leg WHERE leg_id = ?",
                (rs, rowNum) -> new LegMeta(rs.getString("status"), rs.getString("destination_country")),
                legId);
        if (metas.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        List<GateItem> gates = listGates(
                "SELECT bag_tag, leg_id, country, gate_status, check_version, updated_at, hold_snapshot"
                        + " FROM bag_gate WHERE leg_id = ? ORDER BY bag_tag", legId);
        return new LegGateResponse(legId, metas.get(0).status(), metas.get(0).destinationCountry(), gates);
    }

    /** 查询行李当前仍生效的拦截影响（CUSTOMS_HOLD 门禁与只读快照）。 */
    public HoldImpactResponse getHoldImpact(String bagTag) {
        if (findBagForGate(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        List<GateItem> holds = listGates(
                "SELECT bag_tag, leg_id, country, gate_status, check_version, updated_at, hold_snapshot"
                        + " FROM bag_gate WHERE bag_tag = ? AND gate_status = ? ORDER BY leg_id",
                bagTag, GATE_HELD);
        return new HoldImpactResponse(bagTag, holds);
    }

    /**
     * 在装载/补到/改派的业务事务内校验国际航段持续门禁：
     * 行李必须持有目的国对应且有效的放行版本；缺少放行 422，当前被拦截 422，原因可区分。
     * 国内航段直接放行。调用方须已持有该行李行锁。
     */
    void verifyGateForLoad(String bagTag, LegGateInput leg) {
        if (!isInternational(leg)) {
            return;
        }
        String country = leg.destinationCountry();
        String gateStatus = gateStatus(bagTag, leg.legId());
        if (GATE_HELD.equals(gateStatus)) {
            Integer version = jdbcTemplate.queryForObject(
                    "SELECT check_version FROM bag_gate WHERE bag_tag = ? AND leg_id = ?",
                    Integer.class, bagTag, leg.legId());
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 在目的国 " + country + " 被海关拦截（检查版本 "
                            + version + "），航段 " + leg.legId() + " 禁止装载");
        }
        if (GATE_RELEASED.equals(gateStatus)) {
            return;
        }
        // 改派等场景下新航段可能尚无门禁行：以该国最新检查终态裁决
        InspectionRow latest = latestInspection(bagTag, country);
        if (latest == null) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 缺少目的国 " + country + " 对应航段 "
                            + leg.legId() + " 的海关放行版本");
        }
        if (CHECK_HELD.equals(latest.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 在目的国 " + country + " 被海关拦截（检查版本 "
                            + latest.checkVersion() + "），航段 " + leg.legId() + " 禁止装载");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        upsertGate(bagTag, leg.legId(), country, GATE_RELEASED, latest.checkVersion(), null, now);
    }

    /**
     * 起飞前只读裁决：机上每件行李在该国际航段目的国必须持有放行门禁且未被新拦截。
     * 不写入门禁行；国内航段直接放行。调用方事务持有航段与行李行锁。
     */
    void assertDepartureCleared(String bagTag, LegGateInput leg) {
        if (!isInternational(leg)) {
            return;
        }
        String country = leg.destinationCountry();
        List<GateRow> rows = jdbcTemplate.query(
                "SELECT gate_status, check_version FROM bag_gate WHERE bag_tag = ? AND leg_id = ?",
                (rs, rowNum) -> new GateRow(rs.getString("gate_status"), rs.getInt("check_version")),
                bagTag, leg.legId());
        if (!rows.isEmpty()) {
            GateRow gate = rows.get(0);
            if (GATE_HELD.equals(gate.gateStatus())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 在目的国 " + country + " 被海关拦截（检查版本 "
                                + gate.checkVersion() + "），航段 " + leg.legId() + " 禁止起飞");
            }
            return;
        }
        InspectionRow latest = latestInspection(bagTag, country);
        if (latest == null) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 缺少目的国 " + country + " 对应航段 "
                            + leg.legId() + " 的海关放行版本，禁止起飞");
        }
        if (CHECK_HELD.equals(latest.status())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 在目的国 " + country + " 被海关拦截（检查版本 "
                            + latest.checkVersion() + "），航段 " + leg.legId() + " 禁止起飞");
        }
    }

    /** 改派写入新行程后调用：为每条新国际航段校验并补建放行门禁；
     * 缺失放行或最新终态为拦截均 422，调用方事务整体回滚。
     */
    void verifyGatesForNewItinerary(String bagTag, List<LegGateInput> newLegs) {
        for (LegGateInput leg : newLegs) {
            if (isInternational(leg)) {
                verifyGateForLoad(bagTag, leg);
            }
        }
    }

    /** 改派删除旧的未起飞航段门禁（已飞航海段门禁冻结保留）。 */
    void deleteFutureGatesExcept(String bagTag, java.util.Collection<String> keepLegIds) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT leg_id FROM bag_gate WHERE bag_tag = ?", String.class, bagTag);
        for (String legId : existing) {
            if (!keepLegIds.contains(legId)) {
                String legStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM leg WHERE leg_id = ?", String.class, legId);
                if (legStatus != null && !"DEPARTED".equals(legStatus) && !"ARRIVED".equals(legStatus)) {
                    jdbcTemplate.update("DELETE FROM bag_gate WHERE bag_tag = ? AND leg_id = ?",
                            bagTag, legId);
                }
            }
        }
    }

    private static boolean isInternational(LegGateInput leg) {
        return !leg.originCountry().equals(leg.destinationCountry());
    }

    /** 该行李未起飞（OPEN/SEALED）且目的国匹配的后续国际航段；已 DEPARTED/ARRIVED 不改写。 */
    private List<FutureLeg> futureLegs(String bagTag, int nextLegIndex, String destinationCountry) {
        return jdbcTemplate.query(
                "SELECT i.seq, i.leg_id, i.origin, i.destination, l.origin_country, l.destination_country,"
                        + " l.status FROM bag_itinerary i JOIN leg l ON i.leg_id = l.leg_id"
                        + " WHERE i.bag_tag = ? AND i.seq >= ? AND l.status IN ('OPEN', 'SEALED')"
                        + " AND l.destination_country = ? AND l.origin_country <> l.destination_country"
                        + " ORDER BY i.seq",
                (rs, rowNum) -> new FutureLeg(rs.getInt("seq"), rs.getString("leg_id"),
                        rs.getString("origin"), rs.getString("destination"),
                        rs.getString("origin_country"), rs.getString("destination_country"),
                        rs.getString("status")),
                bagTag, nextLegIndex, destinationCountry);
    }

    private void upsertGate(String bagTag, String legId, String country, String gateStatus,
                            int checkVersion, String snapshot, OffsetDateTime updatedAt) {
        int updated = jdbcTemplate.update(
                "UPDATE bag_gate SET country = ?, gate_status = ?, check_version = ?,"
                        + " hold_snapshot = ?, updated_at = ? WHERE bag_tag = ? AND leg_id = ?",
                country, gateStatus, checkVersion, snapshot, updatedAt, bagTag, legId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO bag_gate (bag_tag, leg_id, country, gate_status, check_version,"
                            + " hold_snapshot, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    bagTag, legId, country, gateStatus, checkVersion, snapshot, updatedAt);
        }
    }

    /** 在持有行李行锁后追加事件，seq 按该行李已有事件数递增。 */
    private void insertGateEvent(String bagTag, String eventType, FutureLeg leg, OffsetDateTime eventTime) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_tag, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, leg.legId(), leg.origin(), eventTime);
    }

    private Map<String, Object> holdSnapshot(BagGateInput bag, FutureLeg leg, int checkVersion,
                                             String country, String reason, Instant now) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bagTag", bag.bagTag());
        snapshot.put("checkVersion", checkVersion);
        snapshot.put("country", country);
        snapshot.put("reason", reason);
        snapshot.put("bagStatus", bag.status());
        snapshot.put("currentLocation", bag.currentLocation());
        snapshot.put("nextLegIndex", bag.nextLegIndex());
        snapshot.put("loadedLegId", bag.loadedLegId());
        snapshot.put("legId", leg.legId());
        snapshot.put("legStatus", leg.legStatus());
        snapshot.put("origin", leg.origin());
        snapshot.put("destination", leg.destination());
        snapshot.put("markedAt", now.toString());
        return snapshot;
    }

    private List<GateItem> listGates(String sql, Object... args) {
        return jdbcTemplate.query(sql, GATE_MAPPER, args);
    }

    private String gateStatus(String bagTag, String legId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT gate_status FROM bag_gate WHERE bag_tag = ? AND leg_id = ?",
                String.class, bagTag, legId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private InspectionRow latestInspection(String bagTag, String country) {
        List<InspectionRow> rows = jdbcTemplate.query(
                "SELECT check_version, status, country, reason, checked_at, clearance_key, affected_legs"
                        + " FROM customs_inspection WHERE bag_tag = ? AND country = ?"
                        + " ORDER BY check_version DESC LIMIT 1",
                inspectionRowMapper, bagTag, country);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private InspectionRow findInspection(String bagTag, int checkVersion) {
        List<InspectionRow> rows = jdbcTemplate.query(
                "SELECT check_version, status, country, reason, checked_at, clearance_key, affected_legs"
                        + " FROM customs_inspection WHERE bag_tag = ? AND check_version = ?",
                inspectionRowMapper, bagTag, checkVersion);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private InspectionRow findByClearanceKey(String clearanceKey) {
        List<InspectionRow> rows = jdbcTemplate.query(
                "SELECT check_version, status, country, reason, checked_at, clearance_key, affected_legs"
                        + " FROM customs_inspection WHERE clearance_key = ?",
                inspectionRowMapper, clearanceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagGateInput findBagForGate(String bagTag) {
        List<BagGateInput> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id"
                        + " FROM bag WHERE bag_tag = ?",
                (rs, rowNum) -> new BagGateInput(rs.getString("bag_tag"),
                        rs.getString("current_location"), rs.getInt("next_leg_index"),
                        rs.getString("status"), rs.getString("loaded_leg_id")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagGateInput lockBagForGate(String bagTag) {
        List<BagGateInput> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id"
                        + " FROM bag WHERE bag_tag = ? FOR UPDATE",
                (rs, rowNum) -> new BagGateInput(rs.getString("bag_tag"),
                        rs.getString("current_location"), rs.getInt("next_leg_index"),
                        rs.getString("status"), rs.getString("loaded_leg_id")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CustomsCheckResponse toCheckResponse(String bagTag, InspectionRow row) {
        // heldLegs 仅对拦截终态有值；放行的 affectedLegs 是被解除门禁的航段，不属于 heldLegs
        List<String> heldLegs = CHECK_HELD.equals(row.status())
                ? (row.affectedLegs() == null ? List.of() : row.affectedLegs())
                : List.of();
        return new CustomsCheckResponse(bagTag, row.checkVersion(), row.status(), row.country(),
                row.reason(), row.checkedAt().toString(), row.clearanceKey(), heldLegs);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("快照序列化失败", ex);
        }
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** clearanceKey 指纹：行李、检查版本、状态、国家和原因；放行原因空按空串参与指纹。 */
    static String clearanceKey(String bagTag, int checkVersion, String status, String country, String reason) {
        String raw = String.join("|", bagTag, Integer.toString(checkVersion), status, country,
                reason == null ? "" : reason);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private final RowMapper<InspectionRow> inspectionRowMapper = (rs, rowNum) -> new InspectionRow(
            rs.getInt("check_version"), rs.getString("status"), rs.getString("country"),
            rs.getString("reason"), getInstant(rs, "checked_at"), rs.getString("clearance_key"),
            readLegs(rs.getString("affected_legs")));

    private List<String> readLegs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            throw new IllegalStateException("航段列表反序列化失败", ex);
        }
    }

    private record InspectionRow(int checkVersion, String status, String country, String reason,
                                 Instant checkedAt, String clearanceKey, List<String> affectedLegs) {
    }

    private record BagGateInput(String bagTag, String currentLocation, int nextLegIndex,
                                String status, String loadedLegId) {
    }

    private record FutureLeg(int seq, String legId, String origin, String destination,
                             String originCountry, String destinationCountry, String legStatus) {
    }

    private record LegMeta(String status, String destinationCountry) {
    }

    private record GateRow(String gateStatus, int checkVersion) {
    }

    /** 海关检查所需的航段门禁输入（由装载服务构造，避免与 BaggageService 循环依赖行模型）。 */
    record LegGateInput(String legId, String originCountry, String destinationCountry, String legStatus) {
    }
}
