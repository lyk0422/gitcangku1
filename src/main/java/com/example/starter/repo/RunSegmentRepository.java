package com.example.starter.repo;

import com.example.starter.domain.RunMode;
import com.example.starter.domain.RunSegment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 运行段存取。
 */
@Repository
public class RunSegmentRepository {

    private static final RowMapper<RunSegment> MAPPER = RunSegmentRepository::map;

    private final JdbcTemplate jdbc;

    public RunSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RunSegment map(ResultSet rs, int rowNum) throws SQLException {
        RunSegment segment = new RunSegment();
        segment.setId(rs.getLong("id"));
        segment.setWellNo(rs.getString("well_no"));
        segment.setMode(RunMode.valueOf(rs.getString("mode")));
        segment.setStartTime(rs.getTimestamp("start_time").toInstant());
        Timestamp end = rs.getTimestamp("end_time");
        segment.setEndTime(end == null ? null : end.toInstant());
        segment.setRequestId(rs.getString("request_id"));
        return segment;
    }

    /** 插入运行段并回填主键。 */
    public RunSegment insert(RunSegment segment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO run_segment (well_no, mode, start_time, end_time, request_id) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, segment.getWellNo());
            ps.setString(2, segment.getMode().name());
            ps.setTimestamp(3, Timestamp.from(segment.getStartTime()));
            if (segment.getEndTime() == null) {
                ps.setTimestamp(4, null);
            } else {
                ps.setTimestamp(4, Timestamp.from(segment.getEndTime()));
            }
            ps.setString(5, segment.getRequestId());
            return ps;
        }, keyHolder);
        segment.setId(keyHolder.getKey().longValue());
        return segment;
    }

    /** 按主键查询运行段。 */
    public Optional<RunSegment> findById(long id) {
        List<RunSegment> rows = jdbc.query("SELECT * FROM run_segment WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }
    /** 查询井当前进行中的运行段。 */
    public Optional<RunSegment> findOpen(String wellNo) {
        List<RunSegment> rows = jdbc.query(
                "SELECT * FROM run_segment WHERE well_no = ? AND end_time IS NULL", MAPPER, wellNo);
        return rows.stream().findFirst();
    }

    /** 查询井最近一次运行段（按开始时刻与主键倒序）。 */
    public Optional<RunSegment> findLatest(String wellNo) {
        List<RunSegment> rows = jdbc.query(
                "SELECT * FROM run_segment WHERE well_no = ? ORDER BY start_time DESC, id DESC LIMIT 1",
                MAPPER, wellNo);
        return rows.stream().findFirst();
    }

    /** 按开始时刻升序查询井的全部运行段。 */
    public List<RunSegment> findByWell(String wellNo) {
        return jdbc.query("SELECT * FROM run_segment WHERE well_no = ? ORDER BY start_time, id", MAPPER, wellNo);
    }

    /** 统计井的运行段数量。 */
    public long countByWell(String wellNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_segment WHERE well_no = ?", Long.class, wellNo);
        return count == null ? 0 : count;
    }

    /** 关闭运行段（写入结束时刻，UTC）。 */
    public void close(long id, Instant endTime) {
        jdbc.update("UPDATE run_segment SET end_time = ? WHERE id = ?", Timestamp.from(endTime), id);
    }
}