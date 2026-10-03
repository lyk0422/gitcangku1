package com.example.starter.repository;

import com.example.starter.domain.CellRecord;
import com.example.starter.domain.CellStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 电芯表 battery_cell 的 JDBC 仓储；cell_code 全局唯一约束保证并发入批最多一个成功。
 */
@Repository
public class CellRepository {

    private static final RowMapper<CellRecord> MAPPER = (rs, n) -> new CellRecord(
            rs.getLong("id"),
            rs.getString("cell_code"),
            rs.getLong("batch_id"),
            rs.getBigDecimal("initial_voltage"),
            rs.getTimestamp("enrolled_at").toInstant(),
            CellStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public CellRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 电芯入批，记录初始电压与入批时刻（UTC）。
     */
    public long insert(String cellCode, long batchId, BigDecimal initialVoltage, Instant enrolledAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO battery_cell (cell_code, batch_id, initial_voltage, enrolled_at, status)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cellCode);
            ps.setLong(2, batchId);
            ps.setBigDecimal(3, initialVoltage);
            ps.setTimestamp(4, Timestamp.from(enrolledAt));
            ps.setString(5, CellStatus.ACTIVE.name());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按电芯编号查询（全局唯一）。
     */
    public Optional<CellRecord> findByCellCode(String cellCode) {
        List<CellRecord> rows = jdbc.query(
                "SELECT * FROM battery_cell WHERE cell_code = ?", MAPPER, cellCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询批次内全部电芯（含已隔离，保留历史）。
     */
    public List<CellRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM battery_cell WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 统计批次内在批（未隔离）电芯数量。
     */
    public int countActiveByBatchId(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_cell WHERE batch_id = ? AND status = ?",
                Integer.class, batchId, CellStatus.ACTIVE.name());
        return count == null ? 0 : count;
    }

    /**
     * 将电芯标记为已隔离，历史读数保留。
     */
    public void markIsolated(long cellId) {
        jdbc.update("UPDATE battery_cell SET status = ? WHERE id = ?",
                CellStatus.ISOLATED.name(), cellId);
    }

    /**
     * 按主键查询电芯。
     */
    public Optional<CellRecord> findById(long id) {
        List<CellRecord> rows = jdbc.query("SELECT * FROM battery_cell WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }}