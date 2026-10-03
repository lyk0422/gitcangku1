package com.example.starter.repo;

import com.example.starter.model.GroundStation;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 地面站数据访问。
 */
@Repository
public class GroundStationRepository {

    private static final RowMapper<GroundStation> MAPPER = (rs, n) -> new GroundStation(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("antenna_capability"),
            rs.getBigDecimal("min_elevation_deg"),
            rs.getLong("capacity_bytes_per_min"));

    private final JdbcTemplate jdbc;

    public GroundStationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编码查询站点。 */
    public Optional<GroundStation> findByCode(String code) {
        List<GroundStation> rows = jdbc.query(
                "SELECT * FROM ground_station WHERE code = ?", MAPPER, code);
        return rows.stream().findFirst();
    }

    /** 按主键查询站点。 */
    public Optional<GroundStation> findById(long id) {
        List<GroundStation> rows = jdbc.query(
                "SELECT * FROM ground_station WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键锁定站点行（SELECT ... FOR UPDATE），
     * 用于串行化同一天线上的时隙确认，防止并发重叠。
     */
    public Optional<GroundStation> lockById(long id) {
        List<GroundStation> rows = jdbc.query(
                "SELECT * FROM ground_station WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }
}