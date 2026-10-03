package com.example.starter.aging.repo;

import com.example.starter.aging.domain.TempStatus;
import com.example.starter.aging.domain.TemperatureRecord;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 测温记录存取。记录仅追加，作废通过状态迁移完成，不物理删除。
 */
@Repository
public class TemperatureRepository {

    private static final RowMapper<TemperatureRecord> MAPPER = (rs, n) -> {
        LocalDateTime voidedAt = rs.getObject("voided_at", LocalDateTime.class);
        long replacedBy = rs.getLong("replaced_by_id");
        return new TemperatureRecord(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getObject("temp_date", LocalDate.class),
                rs.getBigDecimal("celsius"),
                TempStatus.valueOf(rs.getString("status")),
                rs.getString("recorded_by"),
                rs.getString("void_reason"),
                voidedAt == null ? null : voidedAt.toInstant(ZoneOffset.UTC),
                rs.wasNull() ? null : replacedBy,
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    };

    private final JdbcTemplate jdbc;

    public TemperatureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条有效测温记录。 */
    public long insert(long batchId, LocalDate tempDate, BigDecimal celsius, String recordedBy) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO temperature_record (batch_id, temp_date, celsius, status, recorded_by, created_at)"
                            + " VALUES (?, ?, ?, 'VALID', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setObject(2, tempDate);
            ps.setBigDecimal(3, celsius);
            ps.setString(4, recordedBy);
            ps.setTimestamp(5, BatchRepository.toTs(Instant.now()));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<TemperatureRecord> findById(long id) {
        return jdbc.query("SELECT * FROM temperature_record WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<TemperatureRecord> findByBatchAndDateBetween(long batchId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM temperature_record WHERE batch_id = ? AND temp_date BETWEEN ? AND ?"
                + " ORDER BY temp_date, id", MAPPER, batchId, from, to);
    }

    /** 将记录置为作废，并记录原因、作废时刻与替代记录编号（无替代为空）。 */
    public void markVoid(long id, String reason, Instant voidedAt, Long replacedById) {
        jdbc.update("UPDATE temperature_record SET status = 'VOID', void_reason = ?, voided_at = ?,"
                        + " replaced_by_id = ? WHERE id = ?",
                reason, BatchRepository.toTs(voidedAt), replacedById, id);
    }
}