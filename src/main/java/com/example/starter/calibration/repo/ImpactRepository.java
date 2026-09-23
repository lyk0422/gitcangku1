package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ImpactItem;

/**
 * 失效影响明细持久化。明细只增不改，影响查询按 impactVersion 只读重现。
 */
@Repository
public class ImpactRepository {

    private static final RowMapper<ImpactItem> MAPPER = (rs, rowNum) -> new ImpactItem(
            rs.getLong("id"),
            rs.getString("impact_version"),
            rs.getLong("measurement_id"),
            rs.getString("measurement_key"),
            rs.getString("standard_id"),
            rs.getString("path"),
            rs.getString("previous_status"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ImpactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条影响明细（须在失效激活事务内调用）。
     */
    public void insert(ImpactItem item) {
        jdbc.update("INSERT INTO impact_item "
                        + "(impact_version, measurement_id, measurement_key, standard_id, path, "
                        + "previous_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                item.impactVersion(),
                item.measurementId(),
                item.measurementKey(),
                item.standardId(),
                item.path(),
                item.previousStatus(),
                JdbcTimes.toDb(item.createdAt()));
    }

    /**
     * 按影响版本号查询全部明细（按测量键升序，保证输出稳定）。
     */
    public List<ImpactItem> findByImpactVersion(String impactVersion) {
        return jdbc.query("SELECT * FROM impact_item WHERE impact_version = ? ORDER BY measurement_key",
                MAPPER, impactVersion);
    }
}
