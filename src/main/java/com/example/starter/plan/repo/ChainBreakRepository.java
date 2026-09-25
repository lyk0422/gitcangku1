package com.example.starter.plan.repo;

import com.example.starter.plan.model.ChainBreak;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 不可变断链记录持久化，仅追加、不修改不删除。
 */
@Repository
public class ChainBreakRepository {

    private final JdbcTemplate jdbc;

    public ChainBreakRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条断链记录。
     */
    public void insert(String stockNo, LocalDate opDate, long cancelledPlanId,
                       long predecessorPlanId, long successorPlanId, String reason, long nowMillis) {
        jdbc.update("INSERT INTO rail_chain_break (stock_no, op_date, cancelled_plan_id,"
                        + " predecessor_plan_id, successor_plan_id, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                stockNo, java.sql.Date.valueOf(opDate), cancelledPlanId,
                predecessorPlanId, successorPlanId, reason, nowMillis);
    }

    /**
     * 查询某车底全部断链记录，按运营日与写入顺序升序。
     */
    public List<ChainBreak> findByStockNo(String stockNo) {
        return jdbc.query("SELECT id, stock_no, op_date, cancelled_plan_id, predecessor_plan_id,"
                        + " successor_plan_id, reason, created_at FROM rail_chain_break"
                        + " WHERE stock_no = ? ORDER BY op_date, id",
                (rs, n) -> new ChainBreak(rs.getLong("id"), rs.getString("stock_no"),
                        rs.getObject("op_date", LocalDate.class),
                        rs.getLong("cancelled_plan_id"), rs.getLong("predecessor_plan_id"),
                        rs.getLong("successor_plan_id"), rs.getString("reason"),
                        rs.getLong("created_at")),
                stockNo);
    }
}
