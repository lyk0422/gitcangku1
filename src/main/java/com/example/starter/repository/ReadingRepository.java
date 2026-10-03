package com.example.starter.repository;

import com.example.starter.domain.ReadingRecord;
import com.example.starter.domain.ReadingStatus;
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
 * 读数表 cell_reading 的 JDBC 仓储。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<ReadingRecord> MAPPER = (rs, n) -> new ReadingRecord(
            rs.getLong("id"),
            rs.getLong("cell_id"),
            rs.getBigDecimal("voltage"),
            rs.getTimestamp("measured_at").toInstant(),
            ReadingStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条放电读数（默认有效）。
     */
    public long insert(long cellId, BigDecimal voltage, Instant measuredAt, String requestId, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cell_reading (cell_id, voltage, measured_at, status, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, cellId);
            ps.setBigDecimal(2, voltage);
            ps.setTimestamp(3, Timestamp.from(measuredAt));
            ps.setString(4, ReadingStatus.VALID.name());
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按读数编号查询。
     */
    public Optional<ReadingRecord> findById(long id) {
        List<ReadingRecord> rows = jdbc.query("SELECT * FROM cell_reading WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询电芯的全部读数链，按测量时刻与编号升序。
     */
    public List<ReadingRecord> findByCellId(long cellId) {
        return jdbc.query(
                "SELECT * FROM cell_reading WHERE cell_id = ? ORDER BY measured_at, id", MAPPER, cellId);
    }

    /**
     * 查询电芯最新一条仍有效的读数（终测候选）。
     */
    public Optional<ReadingRecord> findLatestValid(long cellId) {
        List<ReadingRecord> rows = jdbc.query(
                "SELECT * FROM cell_reading WHERE cell_id = ? AND status = ?"
                        + " ORDER BY measured_at DESC, id DESC LIMIT 1",
                MAPPER, cellId, ReadingStatus.VALID.name());
        return rows.stream().findFirst();
    }

    /**
     * 作废读数，仅状态翻转，记录保留。
     */
    public void markVoid(long id) {
        jdbc.update("UPDATE cell_reading SET status = ? WHERE id = ?", ReadingStatus.VOID.name(), id);
    }
}