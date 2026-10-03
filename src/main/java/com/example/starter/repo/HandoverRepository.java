package com.example.starter.repo;

import com.example.starter.domain.Rows.HandoverCellRow;
import com.example.starter.domain.Rows.HandoverRow;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 交接单及冻结明细存取。 */
@Repository
public class HandoverRepository {

    private static final RowMapper<HandoverRow> MAPPER = (rs, n) -> new HandoverRow(
            rs.getString("handover_code"),
            rs.getString("batch_code"),
            rs.getString("from_party"),
            rs.getString("to_party"),
            rs.getTimestamp("handed_over_at").toInstant(),
            rs.getString("request_id"));

    private static final RowMapper<HandoverCellRow> CELL_MAPPER = (rs, n) -> new HandoverCellRow(
            rs.getString("handover_code"),
            rs.getString("cell_code"),
            rs.getString("final_reading_code"));

    private final JdbcTemplate jdbc;

    public HandoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入交接单。 */
    public void insert(HandoverRow row) {
        jdbc.update(
                "INSERT INTO handover (handover_code, batch_code, from_party, to_party,"
                        + " handed_over_at, request_id) VALUES (?,?,?,?,?,?)",
                row.handoverCode(), row.batchCode(), row.fromParty(), row.toParty(),
                Timestamp.from(row.handedOverAt()), row.requestId());
    }

    /** 写入交接冻结明细。 */
    public void insertCell(HandoverCellRow row) {
        jdbc.update(
                "INSERT INTO handover_cell (handover_code, cell_code, final_reading_code)"
                        + " VALUES (?,?,?)",
                row.handoverCode(), row.cellCode(), row.finalReadingCode());
    }

    /** 按批次查询交接单。 */
    public Optional<HandoverRow> findByBatch(String batchCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT handover_code, batch_code, from_party, to_party, handed_over_at,"
                            + " request_id FROM handover WHERE batch_code = ?",
                    MAPPER, batchCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询交接单冻结明细（按电芯编号排序）。 */
    public List<HandoverCellRow> findCells(String handoverCode) {
        return jdbc.query(
                "SELECT handover_code, cell_code, final_reading_code FROM handover_cell"
                        + " WHERE handover_code = ? ORDER BY cell_code",
                CELL_MAPPER, handoverCode);
    }
}