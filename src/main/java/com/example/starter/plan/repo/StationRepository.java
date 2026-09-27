package com.example.starter.plan.repo;

import com.example.starter.plan.model.PublishedStationStop;
import com.example.starter.plan.model.StationConfig;
import com.example.starter.plan.model.StationStop;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 站点配置、计划站停登记与发布站停快照的 JDBC 持久化。
 * 快照表只追加、不更新不删除；所有时刻以 UTC 毫秒存储。
 */
@Repository
public class StationRepository {

    private static final RowMapper<StationConfig> CONFIG_MAPPER = (rs, n) -> new StationConfig(
            rs.getLong("id"),
            rs.getString("station_id"),
            rs.getInt("version"),
            rs.getInt("min_dwell_minutes"),
            rs.getInt("platform_capacity"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<StationStop> STOP_MAPPER = (rs, n) -> new StationStop(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("station_id"),
            rs.getObject("arrival_utc") == null ? null
                    : Instant.ofEpochMilli(rs.getLong("arrival_utc")),
            rs.getObject("departure_utc") == null ? null
                    : Instant.ofEpochMilli(rs.getLong("departure_utc")));

    /**
     * 快照查询行：携带计划业务键与运营日。
     */
    public record SnapshotRow(String scheduleKey, LocalDate opDate, String status,
                              PublishedStationStop stop) {
    }

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) ->
            new SnapshotRow(
                    rs.getString("schedule_key"),
                    rs.getObject("op_date", LocalDate.class),
                    rs.getString("status"),
                    new PublishedStationStop(
                            rs.getLong("plan_id"),
                            rs.getInt("seq"),
                            rs.getString("train_no"),
                            rs.getString("station_id"),
                            rs.getInt("station_version"),
                            rs.getObject("arrival_utc") == null ? null
                                    : Instant.ofEpochMilli(rs.getLong("arrival_utc")),
                            rs.getObject("departure_utc") == null ? null
                                    : Instant.ofEpochMilli(rs.getLong("departure_utc")),
                            rs.getInt("min_dwell_minutes"),
                            rs.getInt("platform_capacity")));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 站点配置 ----------

    /**
     * 登记新站点（初始版本 1），返回主键；重复站点触发唯一约束冲突。
     */
    public long insertStationConfig(String stationId, int minDwellMinutes, int platformCapacity,
                                    long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO rail_station_config"
                            + " (station_id, version, min_dwell_minutes, platform_capacity,"
                            + " created_at, updated_at) VALUES (?, 1, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stationId);
            ps.setInt(2, minDwellMinutes);
            ps.setInt(3, platformCapacity);
            ps.setLong(4, nowMillis);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 修订站点配置：版本加一并更新规则值。
     */
    public void updateStationConfig(long id, int newVersion, int minDwellMinutes,
                                    int platformCapacity, long nowMillis) {
        jdbc.update("UPDATE rail_station_config SET version = ?, min_dwell_minutes = ?,"
                        + " platform_capacity = ?, updated_at = ? WHERE id = ?",
                newVersion, minDwellMinutes, platformCapacity, nowMillis, id);
    }

    /**
     * 按站点 ID 查询当前配置（不加锁）。
     */
    public Optional<StationConfig> findByStationId(String stationId) {
        return jdbc.query(stationSelect() + " WHERE station_id = ?", CONFIG_MAPPER, stationId)
                .stream().findFirst();
    }

    /**
     * 按站点 ID 查询并加行级写锁，须在事务内调用。
     */
    public Optional<StationConfig> findByStationIdForUpdate(String stationId) {
        return jdbc.query(stationSelect() + " WHERE station_id = ? FOR UPDATE",
                CONFIG_MAPPER, stationId).stream().findFirst();
    }

    /**
     * 按站点 ID 集合批量查询当前配置，返回 stationId → 配置。
     */
    public Map<String, StationConfig> findStations(Collection<String> stationIds) {
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        stationIds.forEach(s -> placeholders.add("?"));
        List<StationConfig> configs = jdbc.query(
                stationSelect() + " WHERE station_id IN (" + placeholders + ")",
                CONFIG_MAPPER, stationIds.toArray());
        Map<String, StationConfig> map = new LinkedHashMap<>();
        for (StationConfig config : configs) {
            map.put(config.stationId(), config);
        }
        return map;
    }

    private String stationSelect() {
        return "SELECT id, station_id, version, min_dwell_minutes, platform_capacity,"
                + " created_at, updated_at FROM rail_station_config";
    }

    // ---------- 计划站停登记 ----------

    /**
     * 整体替换计划的站停登记：先删后插，与占用替换同一事务提交。
     */
    public void replaceStationStops(long planId, List<StationStop> stops) {
        jdbc.update("DELETE FROM rail_plan_station_stop WHERE plan_id = ?", planId);
        insertStationStops(planId, stops);
    }

    /**
     * 批量插入站停登记。
     */
    public void insertStationStops(long planId, List<StationStop> stops) {
        if (stops.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_station_stop (plan_id, seq, train_no, station_id,"
                        + " arrival_utc, departure_utc) VALUES (?, ?, ?, ?, ?, ?)",
                stops, stops.size(),
                (ps, s) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.stationId());
                    setNullableInstant(ps, 5, s.arrivalUtc());
                    setNullableInstant(ps, 6, s.departureUtc());
                });
    }

    /**
     * 查询计划站停登记，按序号升序。
     */
    public List<StationStop> findStationStops(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, station_id, arrival_utc, departure_utc"
                + " FROM rail_plan_station_stop WHERE plan_id = ? ORDER BY seq", STOP_MAPPER, planId);
    }

    // ---------- 发布快照 ----------

    /**
     * 批量追加发布站停快照（只增不改）。每条按其站点当时配置固化版本与规则值。
     */
    public void insertPublishedStops(long planId, long nowMillis, List<StationStop> stops,
                                     Map<String, StationConfig> configsAtPublish) {
        if (stops.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_published_station_stop (plan_id, seq, train_no, station_id,"
                        + " station_version, arrival_utc, departure_utc, min_dwell_minutes,"
                        + " platform_capacity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stops, stops.size(),
                (ps, s) -> {
                    StationConfig config = configsAtPublish.get(s.stationId());
                    ps.setLong(1, planId);
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.stationId());
                    ps.setInt(5, config.version());
                    setNullableInstant(ps, 6, s.arrivalUtc());
                    setNullableInstant(ps, 7, s.departureUtc());
                    ps.setInt(8, config.minDwellMinutes());
                    ps.setInt(9, config.platformCapacity());
                    ps.setLong(10, nowMillis);
                });
    }

    /**
     * 查询计划的发布站停快照，按序号升序；未发布计划为空。
     */
    public List<PublishedStationStop> findPublishedStops(long planId) {
        return jdbc.query("SELECT plan_id, seq, train_no, station_id, station_version, arrival_utc,"
                + " departure_utc, min_dwell_minutes, platform_capacity"
                + " FROM rail_published_station_stop WHERE plan_id = ? ORDER BY seq",
                (rs, n) -> new PublishedStationStop(
                        rs.getLong("plan_id"),
                        rs.getInt("seq"),
                        rs.getString("train_no"),
                        rs.getString("station_id"),
                        rs.getInt("station_version"),
                        rs.getObject("arrival_utc") == null ? null
                                : Instant.ofEpochMilli(rs.getLong("arrival_utc")),
                        rs.getObject("departure_utc") == null ? null
                                : Instant.ofEpochMilli(rs.getLong("departure_utc")),
                        rs.getInt("min_dwell_minutes"),
                        rs.getInt("platform_capacity")), planId);
    }

    /**
     * 查询运营日上、指定站点集合中当前仍生效（PUBLISHED）计划的站停快照，
     * 排除给定计划集合（发布排除自身；改签排除新旧两个计划）。
     * 排序：站点、到达时刻（NULL 最后）、计划键、序号，保证容量扫描输入稳定。
     */
    public List<SnapshotRow> findActiveSnapshots(LocalDate opDate, Collection<String> stationIds,
                                                 Collection<Long> excludePlanIds) {
        if (stationIds.isEmpty()) {
            return List.of();
        }
        StringJoiner stationPlaceholders = new StringJoiner(", ");
        stationIds.forEach(s -> stationPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, p.op_date, p.status, s.plan_id, s.seq, s.train_no,"
                        + " s.station_id, s.station_version, s.arrival_utc, s.departure_utc,"
                        + " s.min_dwell_minutes, s.platform_capacity"
                        + " FROM rail_published_station_stop s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND s.station_id IN (").append(stationPlaceholders)
                .append(") ORDER BY s.station_id, s.arrival_utc NULLS LAST, p.schedule_key, s.seq");
        args.addAll(stationIds);
        return jdbc.query(sql.toString(), SNAPSHOT_MAPPER, args.toArray());
    }

    /**
     * 查询指定站点的全部发布快照（含已取消计划的历史快照），可按运营日过滤，
     * 排序：运营日、到达时刻（NULL 最后）、计划键、序号。
     */
    public List<SnapshotRow> findSnapshotHistory(String stationId, LocalDate opDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, p.op_date, p.status, s.plan_id, s.seq, s.train_no,"
                        + " s.station_id, s.station_version, s.arrival_utc, s.departure_utc,"
                        + " s.min_dwell_minutes, s.platform_capacity"
                        + " FROM rail_published_station_stop s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE s.station_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(stationId);
        if (opDate != null) {
            sql.append(" AND p.op_date = ?");
            args.add(Date.valueOf(opDate));
        }
        sql.append(" ORDER BY p.op_date, s.arrival_utc NULLS LAST, p.schedule_key, s.seq");
        return jdbc.query(sql.toString(), SNAPSHOT_MAPPER, args.toArray());
    }

    private static void setNullableInstant(java.sql.PreparedStatement ps, int index, Instant value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value.toEpochMilli());
        }
    }
}
