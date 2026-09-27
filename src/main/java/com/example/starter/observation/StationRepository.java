package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 观测站持久化：站点登记与站点行锁（窗口写操作与批量提交通过站点行锁按提交顺序串行化）。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class StationRepository {

    private static final RowMapper<Station> STATION_MAPPER = (rs, rowNum) -> new Station(
            rs.getString("station_id"),
            rs.getTimestamp("activated_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public StationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入站点；stationId 重复时由主键抛出重复键异常。
     */
    public void insert(Station station) {
        jdbcTemplate.update(
                "INSERT INTO station (station_id, activated_at_utc, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                station.stationId(), Timestamp.from(station.activatedAtUtc()));
    }

    /**
     * 按标识查询站点（不加锁）。
     */
    public Optional<Station> findById(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, activated_at_utc FROM station WHERE station_id = ?",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }

    /**
     * 按标识查询站点并加站点行锁；窗口写事务与批量提交事务借此按提交顺序串行化。
     */
    public Optional<Station> findByIdForUpdate(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, activated_at_utc FROM station WHERE station_id = ? FOR UPDATE",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }
}
