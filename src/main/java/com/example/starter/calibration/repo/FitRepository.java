package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.FitPoint;
import com.example.starter.calibration.model.FitStatus;
import com.example.starter.calibration.model.ReferenceFit;

/**
 * 参考点线性拟合持久化。拟合历史提交时冻结、只增不改；
 * 批准状态流转由行锁串行化，证书 ID 一旦写入不被撤证改写。
 */
@Repository
public class FitRepository {

    private static final RowMapper<ReferenceFit> MAPPER = (rs, rowNum) -> new ReferenceFit(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getBigDecimal("coeff_a"),
            rs.getBigDecimal("offset_b"),
            rs.getBigDecimal("residual_limit"),
            rs.getBigDecimal("max_abs_residual"),
            rs.getInt("point_count"),
            rs.getObject("expected_version", Long.class),
            rs.getString("submitted_by"),
            rs.getString("approved_by"),
            JdbcTimes.fromDb(rs.getObject("approved_at", LocalDateTime.class)),
            FitStatus.valueOf(rs.getString("status")),
            rs.getObject("certificate_id", Long.class),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<FitPoint> POINT_MAPPER = (rs, rowNum) -> new FitPoint(
            rs.getLong("id"),
            rs.getLong("fit_id"),
            rs.getInt("seq"),
            rs.getBigDecimal("x_value"),
            rs.getBigDecimal("y_value"),
            rs.getBigDecimal("residual"));

    private final JdbcTemplate jdbc;

    public FitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入拟合历史（状态 PENDING）并返回生成的拟合 ID。
     */
    public long insert(ReferenceFit fit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reference_fit "
                            + "(request_id, instrument_id, valid_from, valid_to, coeff_a, offset_b, "
                            + "residual_limit, max_abs_residual, point_count, expected_version, "
                            + "submitted_by, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fit.requestId());
            ps.setString(2, fit.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(fit.validFrom()));
            ps.setObject(4, JdbcTimes.toDb(fit.validTo()));
            ps.setBigDecimal(5, fit.a());
            ps.setBigDecimal(6, fit.b());
            ps.setBigDecimal(7, fit.residualLimit());
            ps.setBigDecimal(8, fit.maxAbsResidual());
            ps.setInt(9, fit.pointCount());
            if (fit.expectedVersion() == null) {
                ps.setObject(10, null);
            } else {
                ps.setLong(10, fit.expectedVersion());
            }
            ps.setString(11, fit.submittedBy());
            ps.setString(12, fit.status().name());
            ps.setObject(13, JdbcTimes.toDb(fit.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 追加一个冻结参考点（含残差评分）。
     */
    public void insertPoint(FitPoint point) {
        jdbc.update("INSERT INTO reference_fit_point (fit_id, seq, x_value, y_value, residual) "
                        + "VALUES (?, ?, ?, ?, ?)",
                point.fitId(), point.seq(), point.x(), point.y(), point.residual());
    }

    /**
     * 按拟合 ID 查询（不加锁）。
     */
    public Optional<ReferenceFit> findById(long id) {
        return jdbc.query("SELECT * FROM reference_fit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按拟合 ID 查询并加行锁（须在事务内调用），用于批准时串行化状态流转。
     */
    public Optional<ReferenceFit> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM reference_fit WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询拟合冻结的参考点（按输入顺序升序）。
     */
    public List<FitPoint> findPoints(long fitId) {
        return jdbc.query("SELECT * FROM reference_fit_point WHERE fit_id = ? ORDER BY seq",
                POINT_MAPPER, fitId);
    }

    /**
     * 将拟合置为已批准并固化证书 ID（须在持有行锁的事务内调用）。
     */
    public void markApproved(long id, String approvedBy, Instant approvedAt, long certificateId) {
        jdbc.update("UPDATE reference_fit SET status = ?, approved_by = ?, approved_at = ?, certificate_id = ? "
                        + "WHERE id = ?",
                FitStatus.APPROVED.name(), approvedBy, JdbcTimes.toDb(approvedAt), certificateId, id);
    }
}
