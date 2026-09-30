package com.example.starter.archival.repo;

import com.example.starter.archival.domain.TreatmentRound;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 处理轮次（读数）仓储。记录只追加，不提供更新/删除方法。
 */
@Repository
public class RoundRepository {

    private static final RowMapper<TreatmentRound> MAPPER = (rs, rowNum) -> {
        BigDecimal before = rs.getBigDecimal("ph_before");
        BigDecimal after = rs.getBigDecimal("ph_after");
        return new TreatmentRound(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("page_id"),
                rs.getInt("round_no"),
                before,
                after,
                rs.getString("reagent_lot"),
                rs.getTimestamp("created_at").toLocalDateTime());
    };

    private final JdbcTemplate jdbc;

    public RoundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条处理轮次，pH 为 null 表示读数暂缺。 */
    public int insert(long batchId, long pageId, int roundNo, BigDecimal phBefore,
                      BigDecimal phAfter, String reagentLot, LocalDateTime createdAt) {
        return jdbc.update(
                "INSERT INTO treatment_round (batch_id, page_id, round_no, ph_before, ph_after, reagent_lot, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchId, pageId, roundNo, phBefore, phAfter, reagentLot, Timestamp.valueOf(createdAt));
    }

    /** 查询某页在某批次内的全部轮次，按轮次升序。 */
    public List<TreatmentRound> findByBatchAndPage(long batchId, long pageId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, round_no, ph_before, ph_after, reagent_lot, created_at "
                        + "FROM treatment_round WHERE batch_id = ? AND page_id = ? ORDER BY round_no",
                MAPPER, batchId, pageId);
    }

    /** 查询某档案页的全部处理轮次（跨批次），按时间排序。 */
    public List<TreatmentRound> findByPage(long pageId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, round_no, ph_before, ph_after, reagent_lot, created_at "
                        + "FROM treatment_round WHERE page_id = ? ORDER BY created_at, id",
                MAPPER, pageId);
    }

    /** 查询批次内全部处理轮次。 */
    public List<TreatmentRound> findByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, round_no, ph_before, ph_after, reagent_lot, created_at "
                        + "FROM treatment_round WHERE batch_id = ? ORDER BY page_id, round_no",
                MAPPER, batchId);
    }
}
