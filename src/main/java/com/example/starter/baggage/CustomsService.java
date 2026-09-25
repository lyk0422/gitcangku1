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

import com.example.starter.baggage.BaggageDtos.ClearanceChainResponse;
import com.example.starter.baggage.BaggageDtos.ClearanceItem;
import com.example.starter.baggage.BaggageDtos.ClearanceResponse;
import com.example.starter.baggage.BaggageDtos.GateItem;
import com.example.starter.baggage.BaggageDtos.HoldImpactResponse;
import com.example.starter.baggage.BaggageDtos.HoldItem;
import com.example.starter.baggage.BaggageDtos.LegGateResponse;
import com.example.starter.baggage.BaggageDtos.RegisterClearanceRequest;

/**
 * 行李海关放行与持续门禁核心：
 * 每件跨境行李按检查版本登记放行（RELEASED）或拦截（HELD）终态，记录含 UTC 时刻与原因；
 * 同一检查版本只能一个终态，新终态必须以更高检查版本登记，不得覆盖历史。
 * clearanceKey 为 行李+检查版本+状态+国家+原因 的内容指纹：同键重放原记录，失败不占键。
 * 新拦截不删除历史放行，但把该行李未起飞（OPEN）的后续航段标记 CUSTOMS_HOLD 并写入快照，
 * 已起飞（SEALED/ARRIVED）的航段不改写；更高版本放行登记后对应国家的标记转 CLEARED。
 * 装载/补到/改派到国际航段时经 {@link #assertLoadGate} 持续门禁校验。
 * 检查登记与装载/改派按提交顺序裁决：登记先取行李行锁，装载先取航段行锁再取行李行锁。
 */
@Service
public class CustomsService {

    /** 检查终态：放行。 */
    public static final String STATUS_RELEASED = "RELEASED";
    /** 检查终态：拦截。 */
    public static final String STATUS_HELD = "HELD";

    private static final String LEG_OPEN = "OPEN";
    private static final String HOLD_ACTIVE = "CUSTOMS_HOLD";
    private static final String HOLD_CLEARED = "CLEARED";

    private static final RowMapper<ClearanceRow> CLEARANCE_MAPPER = (rs, rowNum) -> new ClearanceRow(
            rs.getString("bag_tag"), rs.getInt("inspection_version"), rs.getString("status"),
            rs.getString("country"), rs.getString("reason"), rs.getString("clearance_key"),
            getInstant(rs, "registered_at"));

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, rowNum) -> new HoldRow(
            rs.getLong("id"), rs.getString("bag_tag"), rs.getString("leg_id"), rs.getInt("seq"),
            rs.getString("country"), rs.getString("status"), rs.getInt("inspection_version"),
            (Integer) rs.getObject("resolved_version"),
            getInstant(rs, "created_at"), getInstant(rs, "resolved_at"));

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

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 登记海关检查终态：同 clearanceKey 重放原记录，失败不占键。 */
    public ClearanceResponse registerClearance(RegisterClearanceRequest request) {
        ClearancePayload payload = new ClearancePayload(request.bagTag(), request.inspectionVersion(),
                request.status(), request.country(), request.reason());
        return idempotencyService.execute(request.requestId(), "CUSTOMS_CLEARANCE", 201,
                payload, ClearanceResponse.class, () -> doRegisterClearance(request));
    }

    /** 行李检查链查询：按检查版本升序返回全部终态记录。 */
    public ClearanceChainResponse getChain(String bagTag) {
        requireBag(bagTag);
        List<ClearanceItem> chain = jdbcTemplate.query(
                "SELECT bag_tag, inspection_version, status, country, reason, clearance_key, registered_at"
                        + " FROM customs_clearance WHERE bag_tag = ? ORDER BY inspection_version",
                CLEARANCE_MAPPER, bagTag).stream()
                .map(row -> new ClearanceItem(row.inspectionVersion(), row.status(), row.country(),
                        row.reason(), row.clearanceKey(), row.registeredAt().toString()))
                .toList();
        return new ClearanceChainResponse(bagTag, chain);
    }

    /** 航段门禁查询：返回该航段当前已装载行李的海关门禁评估。 */
    public LegGateResponse getLegGate(String legId) {
        List<Map<String, Object>> legs = jdbcTemplate.queryForList(
                "SELECT origin_country, destination_country FROM leg WHERE leg_id = ?", legId);
        if (legs.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        String originCountry = (String) legs.get(0).get("origin_country");
        String destinationCountry = (String) legs.get(0).get("destination_country");
        boolean international = isInternational(originCountry, destinationCountry);
        List<String> loadedBags = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM load_record WHERE leg_id = ? ORDER BY bag_tag", String.class, legId);
        List<GateItem> items = loadedBags.stream()
                .map(bagTag -> evaluateGate(bagTag, international, destinationCountry))
                .toList();
        return new LegGateResponse(legId, international, originCountry, destinationCountry, items);
    }

    /** 拦截影响查询：返回该行李全部海关拦截标记及解除情况。 */
    public HoldImpactResponse getHolds(String bagTag) {
        requireBag(bagTag);
        List<HoldItem> holds = jdbcTemplate.query(
                "SELECT id, bag_tag, leg_id, seq, country, status, inspection_version,"
                        + " resolved_version, created_at, resolved_at FROM customs_hold"
                        + " WHERE bag_tag = ? ORDER BY id",
                HOLD_MAPPER, bagTag).stream()
                .map(row -> new HoldItem(row.id(), row.legId(), row.seq(), row.country(), row.status(),
                        row.inspectionVersion(), row.resolvedVersion(),
                        row.createdAt().toString(),
                        row.resolvedAt() == null ? null : row.resolvedAt().toString()))
                .toList();
        return new HoldImpactResponse(bagTag, holds);
    }

    /**
     * 持续门禁：装载/补到/改派到国际航段时调用。
     * 国内航段（目的国未登记或与始发国相同）直接放行；国际航段要求该行李持有
     * 目的国对应的最新检查终态为 RELEASED，缺失或最新为拦截均 422。
     */
    public void assertLoadGate(String bagTag, String legId, String originCountry, String destinationCountry) {
        if (!isInternational(originCountry, destinationCountry)) {
            return;
        }
        ClearanceRow latest = latestClearance(bagTag, destinationCountry);
        if (latest == null) {
            throw ApiException.unprocessable("海关放行缺失: 行李 " + bagTag + " 无目的国 "
                    + destinationCountry + " 的放行版本，不能进入国际航段 " + legId);
        }
        if (STATUS_HELD.equals(latest.status())) {
            throw ApiException.unprocessable("海关拦截: 行李 " + bagTag + " 目的国 " + destinationCountry
                    + " 最新检查版本 " + latest.inspectionVersion() + " 为拦截，不能进入国际航段 " + legId);
        }
    }

    /** 判断航段是否为国际航段：目的国已登记且与始发国不同。 */
    public boolean isInternational(String originCountry, String destinationCountry) {
        return destinationCountry != null && !destinationCountry.equals(originCountry);
    }

    private ClearanceResponse doRegisterClearance(RegisterClearanceRequest request) {
        String bagTag = request.bagTag();
        // 行李行锁：与装载/改派按提交顺序互斥裁决
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT bag_tag FROM bag WHERE bag_tag = ? FOR UPDATE", String.class, bagTag);
        if (existing.isEmpty()) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        String status = request.status();
        if (!STATUS_RELEASED.equals(status) && !STATUS_HELD.equals(status)) {
            throw ApiException.unprocessable("检查状态非法: " + status + "，仅允许 RELEASED/HELD");
        }
        if (STATUS_HELD.equals(status) && (request.reason() == null || request.reason().isBlank())) {
            throw ApiException.unprocessable("拦截原因不能为空");
        }
        String clearanceKey = clearanceKey(bagTag, request.inspectionVersion(), status,
                request.country(), request.reason());
        ClearanceRow replayed = findByKey(clearanceKey);
        if (replayed != null) {
            return toResponse(replayed);
        }
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(inspection_version) FROM customs_clearance WHERE bag_tag = ?",
                Integer.class, bagTag);
        Integer sameVersion = jdbcTemplate.query(
                "SELECT inspection_version FROM customs_clearance WHERE bag_tag = ? AND inspection_version = ?",
                (rs, rowNum) -> rs.getInt(1), bagTag, request.inspectionVersion())
                .stream().findFirst().orElse(null);
        if (sameVersion != null) {
            throw ApiException.conflict("同一检查版本只能一个终态: 行李 " + bagTag + " 版本 "
                    + request.inspectionVersion() + " 已登记，不得覆盖");
        }
        if (maxVersion != null && request.inspectionVersion() <= maxVersion) {
            throw ApiException.unprocessable("检查版本必须高于已有最高版本 " + maxVersion
                    + "，提交为 " + request.inspectionVersion());
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO customs_clearance (bag_tag, inspection_version, status, country, reason,"
                        + " clearance_key, registered_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                bagTag, request.inspectionVersion(), status, request.country(), request.reason(),
                clearanceKey, now);
        if (STATUS_HELD.equals(status)) {
            markHolds(bagTag, request, now);
        } else {
            resolveHolds(bagTag, request, now);
        }
        return new ClearanceResponse(bagTag, request.inspectionVersion(), status, request.country(),
                request.reason(), clearanceKey, now.toInstant().toString());
    }

    /** 新拦截：把该行李未起飞（OPEN）的后续航段标记 CUSTOMS_HOLD 并写入快照；已起飞航段不改写。 */
    private void markHolds(String bagTag, RegisterClearanceRequest request, OffsetDateTime now) {
        List<Map<String, Object>> legs = jdbcTemplate.queryForList(
                "SELECT i.seq, i.leg_id, i.origin, i.destination, l.status AS leg_status,"
                        + " l.destination_country, b.status AS bag_status, b.current_location"
                        + " FROM bag_itinerary i JOIN leg l ON l.leg_id = i.leg_id"
                        + " JOIN bag b ON b.bag_tag = i.bag_tag"
                        + " WHERE i.bag_tag = ? AND i.seq >= b.next_leg_index AND l.status = ?"
                        + " ORDER BY i.seq",
                bagTag, LEG_OPEN);
        for (Map<String, Object> leg : legs) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("bagTag", bagTag);
            snapshot.put("legId", leg.get("leg_id"));
            snapshot.put("seq", leg.get("seq"));
            snapshot.put("legStatus", leg.get("leg_status"));
            snapshot.put("legOrigin", leg.get("origin"));
            snapshot.put("legDestination", leg.get("destination"));
            snapshot.put("destinationCountry", leg.get("destination_country"));
            snapshot.put("bagStatus", leg.get("bag_status"));
            snapshot.put("currentLocation", leg.get("current_location"));
            snapshot.put("inspectionVersion", request.inspectionVersion());
            snapshot.put("reason", request.reason());
            snapshot.put("heldAt", now.toInstant().toString());
            jdbcTemplate.update(
                    "INSERT INTO customs_hold (bag_tag, leg_id, seq, country, status,"
                            + " inspection_version, snapshot, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    bagTag, leg.get("leg_id"), leg.get("seq"), request.country(), HOLD_ACTIVE,
                    request.inspectionVersion(), writeJson(snapshot), now);
        }
    }

    /** 更高版本放行：解除该行李同国家仍生效的拦截标记，历史放行与拦截记录均保留。 */
    private void resolveHolds(String bagTag, RegisterClearanceRequest request, OffsetDateTime now) {
        jdbcTemplate.update(
                "UPDATE customs_hold SET status = ?, resolved_version = ?, resolved_at = ?"
                        + " WHERE bag_tag = ? AND country = ? AND status = ?",
                HOLD_CLEARED, request.inspectionVersion(), now,
                bagTag, request.country(), HOLD_ACTIVE);
    }

    private GateItem evaluateGate(String bagTag, boolean international, String destinationCountry) {
        if (!international) {
            return new GateItem(bagTag, "NOT_REQUIRED", null, null);
        }
        ClearanceRow latest = latestClearance(bagTag, destinationCountry);
        if (latest == null) {
            return new GateItem(bagTag, "MISSING", null, null);
        }
        String gateStatus = STATUS_HELD.equals(latest.status()) ? "HELD" : "CLEARED";
        return new GateItem(bagTag, gateStatus, latest.inspectionVersion(), latest.status());
    }

    private ClearanceRow latestClearance(String bagTag, String country) {
        List<ClearanceRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, inspection_version, status, country, reason, clearance_key, registered_at"
                        + " FROM customs_clearance WHERE bag_tag = ? AND country = ?"
                        + " ORDER BY inspection_version DESC LIMIT 1",
                CLEARANCE_MAPPER, bagTag, country);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClearanceRow findByKey(String clearanceKey) {
        List<ClearanceRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, inspection_version, status, country, reason, clearance_key, registered_at"
                        + " FROM customs_clearance WHERE clearance_key = ?",
                CLEARANCE_MAPPER, clearanceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireBag(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
        if (count == null || count == 0) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
    }

    private ClearanceResponse toResponse(ClearanceRow row) {
        return new ClearanceResponse(row.bagTag(), row.inspectionVersion(), row.status(),
                row.country(), row.reason(), row.clearanceKey(), row.registeredAt().toString());
    }

    /** clearanceKey 指纹：行李+检查版本+状态+国家+原因 的 SHA-256。 */
    private static String clearanceKey(String bagTag, int inspectionVersion, String status,
                                       String country, String reason) {
        String material = String.join("|", bagTag, String.valueOf(inspectionVersion), status,
                country, reason == null ? "" : reason);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
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

    private record ClearanceRow(String bagTag, int inspectionVersion, String status,
                                String country, String reason, String clearanceKey,
                                Instant registeredAt) {
    }

    private record HoldRow(long id, String bagTag, String legId, int seq, String country,
                           String status, int inspectionVersion, Integer resolvedVersion,
                           Instant createdAt, Instant resolvedAt) {
    }

    /** 海关登记幂等摘要参数（不含 requestId）。 */
    private record ClearancePayload(String bagTag, int inspectionVersion, String status,
                                    String country, String reason) {
    }
}
