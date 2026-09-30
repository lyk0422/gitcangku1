package com.example.starter.pass.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 站点/天线数据访问。
 */
@Repository
public class StationRepository {

    private static final RowMapper<StationPo> MAPPER = (rs, n) -> {
        StationPo po = new StationPo();
        po.setId(rs.getLong("id"));
        po.setCode(rs.getString("code"));
        po.setName(rs.getString("name"));
        po.setAntennaCode(rs.getString("antenna_code"));
        po.setMinElevationDeg(rs.getBigDecimal("min_elevation_deg"));
        po.setBytesPerMinute(rs.getLong("bytes_per_minute"));
        Timestamp created = rs.getTimestamp("created_at");
        po.setCreatedAt(created == null ? null : created.toInstant());
        Timestamp updated = rs.getTimestamp("updated_at");
        po.setUpdatedAt(updated == null ? null : updated.toInstant());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public StationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按编码查站点。 */
    public Optional<StationPo> findByCode(String code) {
        List<StationPo> list = jdbcTemplate.query(
                "SELECT * FROM station WHERE code = ?", MAPPER, code);
        return list.stream().findFirst();
    }

    /**
     * 在当前事务内对站点行加行锁（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>站点与天线一一对应（antenna_code 唯一），站点行锁即天线分配串行锁。</p>
     */
    public StationPo lockByCode(String code) {
        List<StationPo> list = jdbcTemplate.query(
                "SELECT * FROM station WHERE code = ? FOR UPDATE", MAPPER, code);
        return list.stream().findFirst().orElse(null);
    }

    /** 列出全部站点。 */
    public List<StationPo> findAll() {
        return jdbcTemplate.query("SELECT * FROM station ORDER BY id", MAPPER);
    }
}
