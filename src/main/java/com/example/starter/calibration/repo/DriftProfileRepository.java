package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.DriftProfile;

/**
 * 参考漂移档案持久化。档案创建后不可修改，仅可撤销；
 * 撤销与测量提交、批量放行通过行锁串行化，保证按事务提交顺序生效。
 */
@Repository
public class DriftProfileRepository {

    private static final RowMapper<DriftProfile> MAPPER = (rs, rowNum) -> new DriftProfile(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("profiled_at", LocalDateTime.class)),
            rs.getBigDecimal("reference_offset"),
            rs.getBoolean("revoked"),
            JdbcTimes.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public DriftProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入漂移档案并返回生成的 ID。
     */
    public long insert(String instrumentId, Instant profiledAt,
                       java.math.BigDecimal referenceOffset, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drift_profile (instrument_id, profiled_at, reference_offset, revoked, created_at) "
                            + "VALUES (?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setObject(2, JdbcTimes.toDb(profiledAt));
            ps.setBigDecimal(3, referenceOffset);
            ps.setObject(4, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<DriftProfile> findById(long id) {
        return jdbc.query("SELECT * FROM drift_profile WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于撤销与引用核对的并发互斥。
     */
    public Optional<DriftProfile> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM drift_profile WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 撤销档案（须在持有行锁的事务内调用）。
     */
    public void markRevoked(long id, Instant revokedAt) {
        jdbc.update("UPDATE drift_profile SET revoked = TRUE, revoked_at = ? WHERE id = ?",
                JdbcTimes.toDb(revokedAt), id);
    }
}
