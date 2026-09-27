package com.example.starter.plan.repo;

import com.example.starter.plan.model.StationConfig;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 中间站站台配置持久化。配置按站点版本化：首次登记版本 1，修订版本加一；
 * 发布快照只固化发布时读到的版本值，不依赖本表历史行，因此本表仅保存当前版本。
 */
@Repository
public class StationRepository {

    private static final RowMapper<StationConfig> CONFIG_MAPPER = (rs, n) -> new StationConfig(
            rs.getLong("id"),
            rs.getString("station_id"),
            rs.getInt("min_dwell_minutes"),
            rs.getInt("platform_capacity"),
            rs.getInt("version"),
            rs.getString("updated_by"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次登记站点配置（版本 1），返回自增主键；站点重复时由唯一约束拒绝。
     */
    public long insert(String stationId, int minDwellMinutes, int platformCapacity,
                       String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_station_config"
                            + " (station_id, min_dwell_minutes, platform_capacity, version,"
                            + " updated_by, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stationId);
            ps.setInt(2, minDwellMinutes);
            ps.setInt(3, platformCapacity);
            ps.setString(4, operator);
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 修订站点配置：版本加一并更新规则值与操作者。
     */
    public void update(StationConfig current, int minDwellMinutes, int platformCapacity,
                       String operator, long nowMillis) {
        jdbc.update("UPDATE rail_station_config SET min_dwell_minutes = ?, platform_capacity = ?,"
                        + " version = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                minDwellMinutes, platformCapacity, current.version() + 1, operator,
                nowMillis, current.id());
    }

    /**
     * 按站点查询当前配置（不加锁）。
     */
    public Optional<StationConfig> findByStationId(String stationId) {
        return jdbc.query("SELECT id, station_id, min_dwell_minutes, platform_capacity, version,"
                        + " updated_by, created_at, updated_at FROM rail_station_config"
                        + " WHERE station_id = ?",
                CONFIG_MAPPER, stationId).stream().findFirst();
    }

    /**
     * 按站点查询当前配置并加行级写锁，须在事务内调用，用于配置修订的提交顺序裁决。
     */
    public Optional<StationConfig> findByStationIdForUpdate(String stationId) {
        return jdbc.query("SELECT id, station_id, min_dwell_minutes, platform_capacity, version,"
                        + " updated_by, created_at, updated_at FROM rail_station_config"
                        + " WHERE station_id = ? FOR UPDATE",
                CONFIG_MAPPER, stationId).stream().findFirst();
    }

    /**
     * 按站点集合批量查询当前配置（发布事务内使用，调用方已持发布锁）。
     */
    public List<StationConfig> findByStationIds(Collection<String> stationIds) {
        if (stationIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        stationIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT id, station_id, min_dwell_minutes, platform_capacity, version,"
                        + " updated_by, created_at, updated_at FROM rail_station_config"
                        + " WHERE station_id IN (" + placeholders + ")",
                CONFIG_MAPPER, new ArrayList<>(stationIds).toArray());
    }
}
