package com.example.starter.repo;

import com.example.starter.domain.ReadingType;
import com.example.starter.domain.Rows.ReadingRow;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 放电读数存取；读数不可变，作废仅置标志。 */
@Repository
public class ReadingRepository {

    private static final RowMapper<ReadingRow> MAPPER = (rs, n) -> new ReadingRow(
            rs.getString("reading_code"),
            rs.getString("cell_code"),
            rs.getString("batch_code"),
            ReadingType.valueOf(rs.getString("reading_type")),
            rs.getDouble("voltage"),
            rs.getTimestamp("measured_at").toInstant(),
            rs.getBoolean("valid"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS =
            "reading_code, cell_code, batch_code, reading_type, voltage, measured_at,"
                    + " valid, request_id, created_at";

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条读数。 */
    public void insert(ReadingRow row) {
        jdbc.update("INSERT INTO discharge_reading (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
                row.readingCode(), row.cellCode(), row.batchCode(), row.readingType().name(),
                row.voltage(), Timestamp.from(row.measuredAt()), row.valid(),
                row.requestId(), Timestamp.from(row.createdAt()));
    }

    /** 按读数编号查询。 */
    public Optional<ReadingRow> find(String readingCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM discharge_reading WHERE reading_code = ?",
                    MAPPER, readingCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询电芯在指定批次中的入批初始读数。 */
    public Optional<ReadingRow> findIntake(String cellCode, String batchCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM discharge_reading"
                            + " WHERE cell_code = ? AND batch_code = ? AND reading_type = 'INTAKE'",
                    MAPPER, cellCode, batchCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询电芯在指定批次中的最新有效终测（测量时刻最新者优先）。 */
    public Optional<ReadingRow> findLatestValidFinal(String cellCode, String batchCode) {
        List<ReadingRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM discharge_reading"
                        + " WHERE cell_code = ? AND batch_code = ? AND reading_type = 'FINAL'"
                        + " AND valid = TRUE"
                        + " ORDER BY measured_at DESC, created_at DESC LIMIT 1",
                MAPPER, cellCode, batchCode);
        return rows.stream().findFirst();
    }

    /** 交接专用：查询最新有效终测并锁定该行，与并发作废串行化。 */
    public Optional<ReadingRow> findLatestValidFinalForUpdate(String cellCode, String batchCode) {
        List<ReadingRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM discharge_reading"
                        + " WHERE cell_code = ? AND batch_code = ? AND reading_type = 'FINAL'"
                        + " AND valid = TRUE"
                        + " ORDER BY measured_at DESC, created_at DESC LIMIT 1 FOR UPDATE",
                MAPPER, cellCode, batchCode);
        return rows.stream().findFirst();
    }

    /** 查询电芯在指定批次中的完整读数链（按测量时刻升序）。 */
    public List<ReadingRow> findChain(String cellCode, String batchCode) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM discharge_reading"
                        + " WHERE cell_code = ? AND batch_code = ?"
                        + " ORDER BY measured_at, created_at, reading_code",
                MAPPER, cellCode, batchCode);
    }

    /** 作废读数；返回是否实际作废（已作废则返回 false）。 */
    public boolean invalidate(String readingCode) {
        int updated = jdbc.update(
                "UPDATE discharge_reading SET valid = FALSE WHERE reading_code = ? AND valid = TRUE",
                readingCode);
        return updated == 1;
    }
}