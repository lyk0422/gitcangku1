package com.example.starter.repo;

import com.example.starter.domain.BottleStatus;
import com.example.starter.domain.ReagentBottle;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 试剂瓶表访问：接收登记、行锁读取、开封与扣减回写、批号隔离。
 */
@Repository
public class BottleRepository {

    private static final String COLUMNS =
            "id, batch_no, capacity_ml, remaining_ml, supplier_expiry_at, opened_at,"
            + " status, quarantine_reason, quarantined_at, created_at";

    private final JdbcTemplate jdbc;

    public BottleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 接收登记新瓶，初始剩余量等于标称容量，状态 ACTIVE，返回生成主键。
     */
    public long insert(String batchNo, BigDecimal capacityMl, Instant supplierExpiryAt, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO reagent_bottle (batch_no, capacity_ml, remaining_ml,"
                    + " supplier_expiry_at, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setBigDecimal(2, capacityMl);
            ps.setBigDecimal(3, capacityMl);
            ps.setObject(4, supplierExpiryAt);
            ps.setString(5, BottleStatus.ACTIVE.name());
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键读取（不加锁），用于查询视图。
     */
    public Optional<ReagentBottle> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reagent_bottle WHERE id = ?",
                mapper(), id).stream().findFirst();
    }

    /**
     * 按主键加行锁读取（SELECT ... FOR UPDATE），同一事务内串行化并发变更。
     */
    public Optional<ReagentBottle> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reagent_bottle WHERE id = ? FOR UPDATE",
                mapper(), id).stream().findFirst();
    }

    /**
     * 首次开封：固定开封时刻。
     */
    public void markOpened(long id, Instant openedAt) {
        jdbc.update("UPDATE reagent_bottle SET opened_at = ? WHERE id = ?", openedAt, id);
    }

    /**
     * 成功领用后回写剩余量。
     */
    public void updateRemaining(long id, BigDecimal remainingMl) {
        jdbc.update("UPDATE reagent_bottle SET remaining_ml = ? WHERE id = ?", remainingMl, id);
    }

    /**
     * 按批号加行锁选出仍有余量的可用瓶，供召回隔离。
     */
    public List<ReagentBottle> findActiveWithRemainingByBatchForUpdate(String batchNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reagent_bottle"
                + " WHERE batch_no = ? AND remaining_ml > 0 AND status = 'ACTIVE' FOR UPDATE",
                mapper(), batchNo);
    }

    /**
     * 将指定瓶置为隔离并记录原因与时刻。
     */
    public void quarantine(long id, String reason, Instant now) {
        jdbc.update("UPDATE reagent_bottle SET status = ?, quarantine_reason = ?,"
                + " quarantined_at = ? WHERE id = ?",
                BottleStatus.QUARANTINED.name(), reason, now, id);
    }

    private RowMapper<ReagentBottle> mapper() {
        return (rs, rowNum) -> mapRow(rs);
    }

    private ReagentBottle mapRow(ResultSet rs) throws SQLException {
        return new ReagentBottle(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getBigDecimal("capacity_ml"),
                rs.getBigDecimal("remaining_ml"),
                toInstant(rs, "supplier_expiry_at"),
                toInstant(rs, "opened_at"),
                BottleStatus.valueOf(rs.getString("status")),
                rs.getString("quarantine_reason"),
                toInstant(rs, "quarantined_at"),
                toInstant(rs, "created_at"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}