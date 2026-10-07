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

import com.example.starter.calibration.model.FitStatus;
import com.example.starter.calibration.model.ReferenceFit;

/**
 * 参考点拟合持久化。request_id 全局唯一作为幂等键；批准状态流转在行锁内完成。
 */
@Repository
public class ReferenceFitRepository {

    private static final RowMapper<ReferenceFit> MAPPER = (rs, rowNum) -> new ReferenceFit(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getBigDecimal("max_residual"),
            rs.getBigDecimal("coeff_a"),
            rs.getBigDecimal("coeff_b"),
            rs.getBigDecimal("max_abs_residual"),
            rs.getString("points_json"),
            rs.getString("submitted_by"),
            FitStatus.valueOf(rs.getString("status")),
            rs.getObject("certificate_id") == null ? null : rs.getLong("certificate_id"),
            rs.getString("approved_by"),
            JdbcTimes.fromDb(rs.getObject("approved_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReferenceFitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入拟合记录（初始状态 PENDING）并返回生成的 ID；request_id 冲突时抛出 DuplicateKeyException。
     */
    public long insert(ReferenceFit fit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reference_fit "
                            + "(request_id, instrument_id, valid_from, valid_to, max_residual, coeff_a, coeff_b, "
                            + "max_abs_residual, points_json, submitted_by, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fit.requestId());
            ps.setString(2, fit.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(fit.validFrom()));
            ps.setObject(4, JdbcTimes.toDb(fit.validTo()));
            ps.setBigDecimal(5, fit.maxResidual());
            ps.setBigDecimal(6, fit.a());
            ps.setBigDecimal(7, fit.b());
            ps.setBigDecimal(8, fit.maxAbsResidual());
            ps.setString(9, fit.pointsJson());
            ps.setString(10, fit.submittedBy());
            ps.setString(11, FitStatus.PENDING.name());
            ps.setObject(12, JdbcTimes.toDb(fit.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<ReferenceFit> findById(long id) {
        return jdbc.query("SELECT * FROM reference_fit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于批准时串行化状态流转。
     */
    public Optional<ReferenceFit> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM reference_fit WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 将拟合记录置为已批准并登记证书 ID（须在持有行锁的事务内调用）。
     */
    public void markApproved(long id, long certificateId, String approvedBy, Instant approvedAt) {
        jdbc.update("UPDATE reference_fit SET status = ?, certificate_id = ?, approved_by = ?, approved_at = ? "
                        + "WHERE id = ?",
                FitStatus.APPROVED.name(), certificateId, approvedBy, JdbcTimes.toDb(approvedAt), id);
    }
}
