package com.example.starter.plan.repo;

import com.example.starter.plan.model.ChainBreak;
import com.example.starter.plan.model.RollingStock;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 车底登记、最小周转参数与断链记录的 JDBC 持久化。断链记录只增不改。
 */
@Repository
public class RollingStockRepository {

    private static final RowMapper<RollingStock> STOCK_MAPPER = (rs, n) -> new RollingStock(
            rs.getLong("id"),
            rs.getString("stock_key"),
            rs.getInt("min_turnaround_minutes"),
            rs.getInt("version"));

    private static final RowMapper<ChainBreak> BREAK_MAPPER = (rs, n) -> new ChainBreak(
            rs.getLong("id"),
            rs.getString("stock_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getLong("cancelled_plan_id"),
            rs.getString("cancelled_schedule_key"),
            rs.getString("prev_schedule_key"),
            rs.getString("next_schedule_key"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RollingStockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记车底，初始版本 1，返回自增主键；stockKey 冲突时抛出 DuplicateKeyException。
     */
    public long insertStock(String stockKey, int minTurnaroundMinutes, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_rolling_stock (stock_key, min_turnaround_minutes, version,"
                            + " created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stockKey);
            ps.setInt(2, minTurnaroundMinutes);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按标识查询车底（不加锁）。
     */
    public Optional<RollingStock> findByKey(String stockKey) {
        return jdbc.query("SELECT id, stock_key, min_turnaround_minutes, version"
                        + " FROM rail_rolling_stock WHERE stock_key = ?",
                STOCK_MAPPER, stockKey).stream().findFirst();
    }

    /**
     * 按标识查询车底并加行级写锁，须在事务内调用，用于周转参数修改的乐观校验。
     */
    public Optional<RollingStock> findByKeyForUpdate(String stockKey) {
        return jdbc.query("SELECT id, stock_key, min_turnaround_minutes, version"
                        + " FROM rail_rolling_stock WHERE stock_key = ? FOR UPDATE",
                STOCK_MAPPER, stockKey).stream().findFirst();
    }

    /**
     * 更新最小周转分钟数并递增版本。
     */
    public void updateTurnaround(long stockId, int minTurnaroundMinutes, int version,
                                 long nowMillis) {
        jdbc.update("UPDATE rail_rolling_stock SET min_turnaround_minutes = ?, version = ?,"
                        + " updated_at = ? WHERE id = ?",
                minTurnaroundMinutes, version, nowMillis, stockId);
    }

    /**
     * 追加不可变断链记录。
     */
    public void insertBreak(String stockKey, LocalDate opDate, long cancelledPlanId,
                            String cancelledScheduleKey, String prevScheduleKey,
                            String nextScheduleKey, long nowMillis) {
        jdbc.update("INSERT INTO rail_chain_break (stock_key, op_date, cancelled_plan_id,"
                        + " cancelled_schedule_key, prev_schedule_key, next_schedule_key, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                stockKey, Date.valueOf(opDate), cancelledPlanId, cancelledScheduleKey,
                prevScheduleKey, nextScheduleKey, nowMillis);
    }

    /**
     * 查询指定车底的全部断链记录，按创建时刻与主键升序。
     */
    public List<ChainBreak> findBreaksByStock(String stockKey) {
        return jdbc.query("SELECT id, stock_key, op_date, cancelled_plan_id,"
                        + " cancelled_schedule_key, prev_schedule_key, next_schedule_key, created_at"
                        + " FROM rail_chain_break WHERE stock_key = ? ORDER BY created_at, id",
                BREAK_MAPPER, stockKey);
    }
}
