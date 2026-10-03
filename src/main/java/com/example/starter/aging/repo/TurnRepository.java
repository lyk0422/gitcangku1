package com.example.starter.aging.repo;

import com.example.starter.aging.domain.TurnRecord;
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
 * 每日翻面记录存取。
 */
@Repository
public class TurnRepository {

    private static final RowMapper<TurnRecord> MAPPER = (rs, n) -> new TurnRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getObject("turn_date", LocalDate.class),
            rs.getString("request_id"),
            rs.getString("operator_name"),
            rs.getString("note"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public TurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入翻面记录，唯一约束冲突时由调用方捕获处理。 */
    public long insert(long batchId, LocalDate turnDate, String requestId,
                       String operatorName, String note) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO turn_record (batch_id, turn_date, request_id, operator_name, note, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setObject(2, turnDate);
            ps.setString(3, requestId);
            ps.setString(4, operatorName);
            ps.setString(5, note);
            ps.setTimestamp(6, BatchRepository.toTs(Instant.now()));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<TurnRecord> findByBatchAndDate(long batchId, LocalDate date) {
        return jdbc.query("SELECT * FROM turn_record WHERE batch_id = ? AND turn_date = ?",
                MAPPER, batchId, date).stream().findFirst();
    }

    public Optional<TurnRecord> findByBatchAndRequestId(long batchId, String requestId) {
        return jdbc.query("SELECT * FROM turn_record WHERE batch_id = ? AND request_id = ?",
                MAPPER, batchId, requestId).stream().findFirst();
    }

    public List<TurnRecord> findByBatchAndDateBetween(long batchId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM turn_record WHERE batch_id = ? AND turn_date BETWEEN ? AND ?"
                + " ORDER BY turn_date", MAPPER, batchId, from, to);
    }
}