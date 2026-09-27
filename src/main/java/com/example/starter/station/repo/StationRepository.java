package com.example.starter.station.repo;

import com.example.starter.station.model.StationConfig;
import com.example.starter.station.model.StationVersion;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 站台配置与不可变版本历史的 JDBC 持久化。
 * 当前配置在 rail_station 中按 station_id 单行维护，修订在同一事务内更新当前行并追加版本行。
 */
@Repository
public class StationRepository {

    private static final RowMapper<StationConfig> CONFIG_MAPPER = (rs, n) -> new StationConfig(
            rs.getString("station_id"),
            rs.getInt("min_dwell_minutes"),
            rs.getInt("platform_capacity"),
            rs.getInt("version"),
            rs.getString("operator_id"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<StationVersion> VERSION_MAPPER = (rs, n) -> new StationVersion(
            rs.getString("station_id"),
            rs.getInt("version"),
            rs.getInt("min_dwell_minutes"),
            rs.getInt("platform_capacity"),
            rs.getString("operator_id"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询站点当前配置（不加锁）。
     */
    public Optional<StationConfig> find(String stationId) {
        return jdbc.query("SELECT station_id, min_dwell_minutes, platform_capacity, version,"
                        + " operator_id, created_at, updated_at FROM rail_station WHERE station_id = ?",
                CONFIG_MAPPER, stationId).stream().findFirst();
    }

    /**
     * 按站点集合批量查询当前配置（不加锁），以 station_id 为键返回；
     * 供发布事务在全局锁内一次性读取全部相关站点当前规则。
     */
    public Map<String, StationConfig> findByIds(Collection<String> stationIds) {
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        stationIds.forEach(s -> placeholders.add("?"));
        List<StationConfig> configs = jdbc.query(
                "SELECT station_id, min_dwell_minutes, platform_capacity, version,"
                        + " operator_id, created_at, updated_at FROM rail_station"
                        + " WHERE station_id IN (" + placeholders + ")",
                CONFIG_MAPPER, stationIds.toArray());
        Map<String, StationConfig> result = new java.util.HashMap<>();
        for (StationConfig config : configs) {
            result.put(config.stationId(), config);
        }
        return result;
    }

    /**
     * 查询站点当前配置并加行级写锁，须在事务内调用。
     */
    public Optional<StationConfig> findForUpdate(String stationId) {
        return jdbc.query("SELECT station_id, min_dwell_minutes, platform_capacity, version,"
                        + " operator_id, created_at, updated_at FROM rail_station WHERE station_id = ?"
                        + " FOR UPDATE",
                CONFIG_MAPPER, stationId).stream().findFirst();
    }

    /**
     * 首次创建站点配置（版本 1）并追加首版本；站点已存在时抛 {@link DuplicateKeyException}。
     */
    public void insert(StationConfig config, long nowMillis) {
        jdbc.update("INSERT INTO rail_station (station_id, min_dwell_minutes, platform_capacity,"
                        + " version, operator_id, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, ?)",
                config.stationId(), config.minDwellMinutes(), config.platformCapacity(),
                config.operatorId(), nowMillis, nowMillis);
        insertVersion(config.stationId(), 1, config.minDwellMinutes(), config.platformCapacity(),
                config.operatorId(), nowMillis);
    }

    /**
     * 修订当前配置：更新当前行（版本加一）并追加不可变版本行，须在持有行锁的事务内调用。
     */
    public void update(StationConfig current, int minDwellMinutes, int platformCapacity,
                       String operatorId, long nowMillis) {
        int nextVersion = current.version() + 1;
        jdbc.update("UPDATE rail_station SET min_dwell_minutes = ?, platform_capacity = ?,"
                        + " version = ?, operator_id = ?, updated_at = ? WHERE station_id = ?",
                minDwellMinutes, platformCapacity, nextVersion, operatorId, nowMillis,
                current.stationId());
        insertVersion(current.stationId(), nextVersion, minDwellMinutes, platformCapacity,
                operatorId, nowMillis);
    }

    /**
     * 查询站点不可变版本历史，按版本号升序稳定排序。
     */
    public List<StationVersion> findVersions(String stationId) {
        return jdbc.query("SELECT station_id, version, min_dwell_minutes, platform_capacity,"
                        + " operator_id, created_at FROM rail_station_version"
                        + " WHERE station_id = ? ORDER BY version",
                VERSION_MAPPER, stationId);
    }

    private void insertVersion(String stationId, int version, int minDwellMinutes,
                               int platformCapacity, String operatorId, long nowMillis) {
        jdbc.update("INSERT INTO rail_station_version (station_id, version, min_dwell_minutes,"
                        + " platform_capacity, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                stationId, version, minDwellMinutes, platformCapacity, operatorId, nowMillis);
    }
}
