package com.example.starter.repo;

import com.example.starter.domain.PressureSample;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 压力采样存取；采样只追加，作废仅更新标记列。
 */
@Repository
public class PressureSampleRepository {

    private static final RowMapper<PressureSample> MAPPER = PressureSampleRepository::map;

    private final JdbcTemplate jdbc;

    public PressureSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static PressureSample map(ResultSet rs, int rowNum) throws SQLException {
        PressureSample sample = new PressureSample();
        sample.setId(rs.getLong("id"));
        sample.setWellNo(rs.getString("well_no"));
        sample.setSegmentId(rs.getLong("segment_id"));
        sample.setPressureMpa(rs.getBigDecimal("pressure_mpa"));
        sample.setSampledAt(rs.getTimestamp("sampled_at").toInstant());
        sample.setRequestId(rs.getString("request_id"));
        sample.setVoided(rs.getBoolean("voided"));
        sample.setVoidReason(rs.getString("void_reason"));
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        sample.setVoidedAt(voidedAt == null ? null : voidedAt.toInstant());
        long replacedBy = rs.getLong("replaced_by_id");
        sample.setReplacedById(rs.wasNull() ? null : replacedBy);
        return sample;
    }

    /** 插入采样并回填主键。 */
    public PressureSample insert(PressureSample sample) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO pressure_sample (well_no, segment_id, pressure_mpa, sampled_at, request_id)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sample.getWellNo());
            ps.setLong(2, sample.getSegmentId());
            ps.setBigDecimal(3, sample.getPressureMpa());
            ps.setTimestamp(4, Timestamp.from(sample.getSampledAt()));
            ps.setString(5, sample.getRequestId());
            return ps;
        }, keyHolder);
        sample.setId(keyHolder.getKey().longValue());
        return sample;
    }

    /** 按主键查询采样。 */
    public Optional<PressureSample> findById(long id) {
        List<PressureSample> rows = jdbc.query("SELECT * FROM pressure_sample WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 查询窗口内有效（未作废）采样，按采样时刻与主键升序；下界开区间，上界闭区间。 */
    public List<PressureSample> findValidInWindow(long segmentId, Instant fromExclusive, Instant toInclusive) {
        return jdbc.query(
                "SELECT * FROM pressure_sample WHERE segment_id = ? AND voided = FALSE"
                        + " AND sampled_at > ? AND sampled_at <= ? ORDER BY sampled_at, id",
                MAPPER, segmentId, Timestamp.from(fromExclusive), Timestamp.from(toInclusive));
    }

    /** 查询运行段内有效采样的最新时刻。 */
    public Optional<Instant> findLatestValidTime(long segmentId) {
        List<Timestamp> rows = jdbc.query(
                "SELECT MAX(sampled_at) FROM pressure_sample WHERE segment_id = ? AND voided = FALSE",
                (rs, rowNum) -> rs.getTimestamp(1), segmentId);
        Timestamp latest = rows.get(0);
        return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
    }

    /** 追加作废标记并记录替代读数。 */
    public void markVoided(long id, String reason, Instant voidedAt, long replacedById) {
        jdbc.update(
                "UPDATE pressure_sample SET voided = TRUE, void_reason = ?, voided_at = ?, replaced_by_id = ?"
                        + " WHERE id = ?",
                reason, Timestamp.from(voidedAt), replacedById, id);
    }
}