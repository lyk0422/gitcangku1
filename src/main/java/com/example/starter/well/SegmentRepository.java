package com.example.starter.well;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 运行段存取，所有时间按 UTC 存取。
 */
@Repository
public class SegmentRepository {

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WellSegment> MAPPER = (rs, rowNum) -> map(rs);

    private static WellSegment map(ResultSet rs) throws SQLException {
        return new WellSegment(
                rs.getLong("id"),
                rs.getString("well_no"),
                rs.getString("mode"),
                toInstant(rs, "start_utc"),
                toInstant(rs, "end_utc"));
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 查询井的未结束运行段。 */
    public Optional<WellSegment> findOpen(String wellNo) {
        List<WellSegment> rows = jdbc.query(
                "SELECT * FROM well_segment WHERE well_no = ? AND end_utc IS NULL", MAPPER, wellNo);
        return rows.stream().findFirst();
    }

    /** 查询井的未结束运行段并加行锁，用于审核与作废等并发串行化。 */
    public Optional<WellSegment> findOpenForUpdate(String wellNo) {
        List<WellSegment> rows = jdbc.query(
                "SELECT * FROM well_segment WHERE well_no = ? AND end_utc IS NULL FOR UPDATE", MAPPER, wellNo);
        return rows.stream().findFirst();
    }

    /** 按井号查询全部运行段，按开始时刻升序。 */
    public List<WellSegment> findByWell(String wellNo) {
        return jdbc.query("SELECT * FROM well_segment WHERE well_no = ? ORDER BY start_utc, id",
                MAPPER, wellNo);
    }

    /** 新建运行段，返回主键。 */
    public long insert(String wellNo, String mode, Instant startUtc, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO well_segment (well_no, mode, start_utc, end_utc, created_at)"
                            + " VALUES (?, ?, ?, NULL, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, wellNo);
            ps.setString(2, mode);
            ps.setObject(3, toOffset(startUtc));
            ps.setObject(4, toOffset(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 结束运行段。 */
    public void close(long id, Instant endUtc) {
        jdbc.update("UPDATE well_segment SET end_utc = ? WHERE id = ?", toOffset(endUtc), id);
    }

    /** 统计井的未结束运行段数量，用于并发断言。 */
    public int countOpen(String wellNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM well_segment WHERE well_no = ? AND end_utc IS NULL",
                Integer.class, wellNo);
        return count == null ? 0 : count;
    }
}