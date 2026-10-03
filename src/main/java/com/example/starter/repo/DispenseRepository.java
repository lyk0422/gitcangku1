package com.example.starter.repo;

import com.example.starter.domain.DeadlineBasis;
import com.example.starter.domain.DispenseRecord;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 领用流水表访问：成功扣减才写入；request_id 唯一约束支撑幂等。
 */
@Repository
public class DispenseRepository {

    private static final String COLUMNS =
            "id, bottle_id, request_id, amount_ml, remaining_after_ml, deadline_basis, dispensed_at";

    private final JdbcTemplate jdbc;

    public DispenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条成功领用记录，返回生成主键。
     */
    public long insert(long bottleId, String requestId, BigDecimal amountMl,
                       BigDecimal remainingAfterMl, DeadlineBasis basis, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO dispense_record (bottle_id, request_id, amount_ml,"
                    + " remaining_after_ml, deadline_basis, dispensed_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bottleId);
            ps.setString(2, requestId);
            ps.setBigDecimal(3, amountMl);
            ps.setBigDecimal(4, remainingAfterMl);
            ps.setString(5, basis.name());
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按幂等键查询既有领用记录，用于同参重放与异参冲突判定。
     */
    public Optional<DispenseRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dispense_record WHERE request_id = ?",
                (rs, rowNum) -> new DispenseRecord(
                        rs.getLong("id"),
                        rs.getLong("bottle_id"),
                        rs.getString("request_id"),
                        rs.getBigDecimal("amount_ml"),
                        rs.getBigDecimal("remaining_after_ml"),
                        DeadlineBasis.valueOf(rs.getString("deadline_basis")),
                        rs.getObject("dispensed_at", OffsetDateTime.class).toInstant()),
                requestId).stream().findFirst();
    }

    /**
     * 按瓶查询领用流水，按写入顺序升序。
     */
    public List<DispenseRecord> findByBottleId(long bottleId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dispense_record"
                        + " WHERE bottle_id = ? ORDER BY id",
                (rs, rowNum) -> new DispenseRecord(
                        rs.getLong("id"),
                        rs.getLong("bottle_id"),
                        rs.getString("request_id"),
                        rs.getBigDecimal("amount_ml"),
                        rs.getBigDecimal("remaining_after_ml"),
                        DeadlineBasis.valueOf(rs.getString("deadline_basis")),
                        rs.getObject("dispensed_at", OffsetDateTime.class).toInstant()),
                bottleId);
    }
}