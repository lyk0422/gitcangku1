package com.example.starter.plan.repo;

import com.example.starter.plan.model.RollingStock;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 车底主数据持久化：车底标识、版本化最小周转分钟数。
 */
@Repository
public class RollingStockRepository {

    private static final String COLUMNS =
            "id, stock_no, min_turnaround_minutes, version";

    private final JdbcTemplate jdbc;

    public RollingStockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按车底标识查询（不加锁）。
     */
    public Optional<RollingStock> findByStockNo(String stockNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_rolling_stock WHERE stock_no = ?",
                (rs, n) -> new RollingStock(rs.getLong("id"), rs.getString("stock_no"),
                        rs.getInt("min_turnaround_minutes"), rs.getInt("version")),
                stockNo).stream().findFirst();
    }

    /**
     * 按车底标识查询并对行加写锁，须在事务内调用。
     */
    public Optional<RollingStock> findByStockNoForUpdate(String stockNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_rolling_stock WHERE stock_no = ?"
                        + " FOR UPDATE",
                (rs, n) -> new RollingStock(rs.getLong("id"), rs.getString("stock_no"),
                        rs.getInt("min_turnaround_minutes"), rs.getInt("version")),
                stockNo).stream().findFirst();
    }

    /**
     * 首次登记车底（版本 1）；车底标识唯一冲突时抛 DuplicateKeyException。
     */
    public RollingStock insert(String stockNo, int minTurnaroundMinutes, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_rolling_stock (stock_no, min_turnaround_minutes, version,"
                            + " created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stockNo);
            ps.setInt(2, minTurnaroundMinutes);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return new RollingStock(keys.getKey().longValue(), stockNo, minTurnaroundMinutes, 1);
    }

    /**
     * 修改最小周转分钟数并版本加一（调用方须完成 expectedVersion 乐观校验与全量衔接重校验）。
     */
    public void updateTurnaround(long id, int minTurnaroundMinutes, int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_rolling_stock SET min_turnaround_minutes = ?, version = ?,"
                        + " updated_at = ? WHERE id = ?",
                minTurnaroundMinutes, newVersion, nowMillis, id);
    }
}
