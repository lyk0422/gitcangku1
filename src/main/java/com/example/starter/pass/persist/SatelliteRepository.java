package com.example.starter.pass.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 卫星主数据访问；卫星行锁用于串行化“预测修订”与“时隙确认”。
 */
@Repository
public class SatelliteRepository {

    private static final RowMapper<SatellitePo> MAPPER = (rs, n) -> {
        SatellitePo po = new SatellitePo();
        po.setId(rs.getLong("id"));
        po.setCode(rs.getString("code"));
        po.setName(rs.getString("name"));
        Timestamp created = rs.getTimestamp("created_at");
        po.setCreatedAt(created == null ? null : created.toInstant());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public SatelliteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按编码查卫星。 */
    public Optional<SatellitePo> findByCode(String code) {
        List<SatellitePo> list = jdbcTemplate.query(
                "SELECT * FROM satellite WHERE code = ?", MAPPER, code);
        return list.stream().findFirst();
    }

    /**
     * 事务内对卫星行加锁；不存在时插入并对新行加锁，保证并发修订版本号不丢。
     *
     * @return 卫星行（已持锁）
     */
    public SatellitePo lockByCodeOrCreate(String code, Instant now) {
        List<SatellitePo> existing = jdbcTemplate.query(
                "SELECT * FROM satellite WHERE code = ? FOR UPDATE", MAPPER, code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbcTemplate.update(
                "INSERT INTO satellite(code, name, created_at) VALUES(?, ?, ?)",
                code, code, Timestamp.from(now));
        List<SatellitePo> locked = jdbcTemplate.query(
                "SELECT * FROM satellite WHERE code = ? FOR UPDATE", MAPPER, code);
        return locked.get(0);
    }
}
