package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 观测站持久化：所有 SQL 使用参数化查询，UTC 时刻以 TIMESTAMP(6) 存储。
 */
@Repository
public class StationRepository {

    private static final RowMapper<Station> STATION_MAPPER = (rs, rowNum) -> new Station(
            rs.getString("station_id"),
            rs.getTimestamp("enabled_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public StationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入观测站。
     */
    public void insert(Station station) {
        jdbcTemplate.update(
                "INSERT INTO station (station_id, enabled_at_utc, created_at_utc) VALUES (?, ?, ?)",
                station.stationId(),
                Timestamp.from(station.enabledAtUtc()),
                Timestamp.from(station.createdAtUtc()));
    }

    /**
     * 按标识查询观测站（不加锁），用于只读接口。
     */
    public Optional<Station> findById(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, enabled_at_utc, created_at_utc FROM station WHERE station_id = ?",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }

    /**
     * 按标识查询观测站并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同站点的窗口变更与合并。
     */
    public Optional<Station> findByIdForUpdate(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, enabled_at_utc, created_at_utc FROM station WHERE station_id = ? FOR UPDATE",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }
}
