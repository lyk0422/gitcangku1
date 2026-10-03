package com.example.starter.repo;

import com.example.starter.domain.CellStatus;
import com.example.starter.domain.Rows.CellRow;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 电芯存取。 */
@Repository
public class CellRepository {

    private static final RowMapper<CellRow> MAPPER = (rs, n) -> new CellRow(
            rs.getString("cell_code"),
            CellStatus.valueOf(rs.getString("status")),
            rs.getString("batch_code"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public CellRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询电芯。 */
    public Optional<CellRow> find(String cellCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT cell_code, status, batch_code, created_at FROM battery_cell WHERE cell_code = ?",
                    MAPPER, cellCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询批次当前在批电芯（按编号排序，保证清单稳定）。 */
    public List<CellRow> findByBatch(String batchCode) {
        return jdbc.query(
                "SELECT cell_code, status, batch_code, created_at FROM battery_cell"
                        + " WHERE batch_code = ? ORDER BY cell_code",
                MAPPER, batchCode);
    }

    /** 统计批次当前在批电芯数量。 */
    public int countByBatch(String batchCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_cell WHERE batch_code = ?", Integer.class, batchCode);
        return count == null ? 0 : count;
    }

    /** 抢占式入批：仅当电芯不属于任何批次时占用；返回是否占用成功。 */
    public boolean occupyForBatch(String cellCode, String batchCode) {
        int updated = jdbc.update(
                "UPDATE battery_cell SET status = ?, batch_code = ?"
                        + " WHERE cell_code = ? AND batch_code IS NULL",
                CellStatus.IN_BATCH.name(), batchCode, cellCode);
        return updated == 1;
    }

    /** 隔离：电芯退出批次并置为已隔离，历史读数保留。 */
    public void markIsolated(String cellCode) {
        jdbc.update(
                "UPDATE battery_cell SET status = ?, batch_code = NULL WHERE cell_code = ?",
                CellStatus.ISOLATED.name(), cellCode);
    }

    /** 交接完成：批次内全部电芯置为已交接。 */
    public void markHandedOver(String batchCode) {
        jdbc.update(
                "UPDATE battery_cell SET status = ? WHERE batch_code = ?",
                CellStatus.HANDED_OVER.name(), batchCode);
    }
}