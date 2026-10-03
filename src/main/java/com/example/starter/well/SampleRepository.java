package com.example.starter.well;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 压力采样存取，读数只追加；作废通过标记与替代读数完成。
 */
@Repository
public class SampleRepository {

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PressureSample> MAPPER = (rs, rowNum) -> map(rs);

    private static PressureSample map(ResultSet rs) throws SQLException {
        long replacedBy = rs.getLong("replaced_by_id");
        return new PressureSample(
                rs.getLong("id"),
                rs.getString("well_no"),
                rs.getString("request_id"),
                rs.getString("payload_hash"),
                rs.getBigDecimal("pressure_mpa"),
                SegmentRepository.toInstant(rs, "sampled_at"),
                rs.getBoolean("voided"),
                rs.getString("void_reason"),
                rs.wasNull() ? null : replacedBy);
    }

    public Optional<PressureSample> findById(long id) {
        List<PressureSample> rows = jdbc.query("SELECT * FROM pressure_sample WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按主键查询并加行锁，作废与审核并发时基于同一已提交集合。 */
    public Optional<PressureSample> findByIdForUpdate(long id) {
        List<PressureSample> rows = jdbc.query(
                "SELECT * FROM pressure_sample WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<PressureSample> findByRequestId(String wellNo, String requestId) {
        List<PressureSample> rows = jdbc.query(
                "SELECT * FROM pressure_sample WHERE well_no = ? AND request_id = ?",
                MAPPER, wellNo, requestId);
        return rows.stream().findFirst();
    }

    /** 查询窗口内未作废采样，按采样时刻升序。 */
    public List<PressureSample> findValidInWindow(String wellNo, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM pressure_sample WHERE well_no = ? AND voided = FALSE"
                        + " AND sampled_at >= ? AND sampled_at <= ? ORDER BY sampled_at, id",
                MAPPER, wellNo,
                SegmentRepository.toOffset(from), SegmentRepository.toOffset(to));
    }

    /** 追加采样，返回主键。 */
    public long insert(PressureSample sample, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pressure_sample (well_no, request_id, payload_hash, pressure_mpa,"
                            + " sampled_at, voided, created_at) VALUES (?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sample.wellNo());
            ps.setString(2, sample.requestId());
            ps.setString(3, sample.payloadHash());
            ps.setBigDecimal(4, sample.pressureMpa());
            ps.setObject(5, SegmentRepository.toOffset(sample.sampledAt()));
            ps.setObject(6, SegmentRepository.toOffset(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 追加作废标记并关联替代读数。 */
    public void markVoided(long id, String reason, Instant voidedAt, long replacedById) {
        jdbc.update("UPDATE pressure_sample SET voided = TRUE, void_reason = ?, voided_at = ?,"
                        + " replaced_by_id = ? WHERE id = ?",
                reason, SegmentRepository.toOffset(voidedAt), replacedById, id);
    }
}