package com.example.starter.station;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 地面站数据访问。
 */
@Repository
public class StationRepository {

    private static final RowMapper<GroundStation> MAPPER = (rs, rowNum) -> new GroundStation(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("antenna_band"),
            rs.getBigDecimal("min_elevation_deg"),
            rs.getInt("capacity_mb_per_min"));

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按站点代码查询。
     */
    public Optional<GroundStation> findByCode(String code) {
        return jdbc.query("SELECT * FROM ground_station WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<GroundStation> findById(long id) {
        return jdbc.query("SELECT * FROM ground_station WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键加行锁查询，用于串行化同站分配。
     */
    public Optional<GroundStation> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM ground_station WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }
}