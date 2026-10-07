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

import com.example.starter.calibration.model.DriftArchive;

/**
 * 参考漂移档案持久化。档案创建后不可修改，仅可撤销；
 * 撤销与放行通过行锁按事务提交顺序互斥。
 */
@Repository
public class DriftArchiveRepository {

    private static final RowMapper<DriftArchive> MAPPER = (rs, rowNum) -> new DriftArchive(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("referenced_at", LocalDateTime.class)),
            rs.getBigDecimal("offset_value"),
            rs.getBoolean("revoked"),
            JdbcTimes.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public DriftArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入档案并返回生成的档案 ID。
     */
    public long insert(String instrumentId, Instant referencedAt,
                       java.math.BigDecimal offset, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drift_reference_archive "
                            + "(instrument_id, referenced_at, offset_value, revoked, created_at) "
                            + "VALUES (?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setObject(2, JdbcTimes.toDb(referencedAt));
            ps.setBigDecimal(3, offset);
            ps.setObject(4, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<DriftArchive> findById(long id) {
        return jdbc.query("SELECT * FROM drift_reference_archive WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于撤销与放行的并发互斥。
     */
    public Optional<DriftArchive> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM drift_reference_archive WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 撤销档案（须在持有行锁的事务内调用）。
     */
    public void markRevoked(long id, Instant revokedAt) {
        jdbc.update("UPDATE drift_reference_archive SET revoked = TRUE, revoked_at = ? WHERE id = ?",
                JdbcTimes.toDb(revokedAt), id);
    }
}
