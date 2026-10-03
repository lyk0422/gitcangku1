package com.example.starter.program;

import java.sql.ResultSet;
import java.sql.SQLException;
import com.example.starter.common.UtcTimes;
import java.time.Clock;
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
 * 程序版本与程序段的持久化，所有时间按 UTC 存取。
 */
@Repository
public class ProgramRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProgramRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 新增程序版本，返回自增ID。
     */
    public long insertVersion(String name, int versionNo) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firing_program_version (name, version_no, created_at) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, versionNo);
            ps.setObject(3, UtcTimes.toDb(clock.instant()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 新增程序段，返回自增ID。
     */
    public long insertSegment(long versionId, int seq, SegmentInfo seg) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firing_program_segment (program_version_id, seq, start_time, end_time,"
                            + " target_temp_c, tolerance_c) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, versionId);
            ps.setInt(2, seq);
            ps.setObject(3, UtcTimes.toDb(seg.startTime()));
            ps.setObject(4, UtcTimes.toDb(seg.endTime()));
            ps.setBigDecimal(5, seg.targetTempC());
            ps.setBigDecimal(6, seg.toleranceC());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按ID查询程序版本及其全部程序段（按段序号升序）。
     */
    public Optional<ProgramVersionInfo> findById(long id) {
        List<ProgramVersionInfo> versions = jdbc.query(
                "SELECT id, name, version_no, created_at FROM firing_program_version WHERE id = ?",
                versionMapper(), id);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        List<SegmentInfo> segments = jdbc.query(
                "SELECT id, seq, start_time, end_time, target_temp_c, tolerance_c"
                        + " FROM firing_program_segment WHERE program_version_id = ? ORDER BY seq",
                segmentMapper(), id);
        ProgramVersionInfo v = versions.get(0);
        return Optional.of(new ProgramVersionInfo(v.id(), v.name(), v.versionNo(), v.createdAt(), segments));
    }

    private RowMapper<ProgramVersionInfo> versionMapper() {
        return (rs, rowNum) -> new ProgramVersionInfo(rs.getLong("id"), rs.getString("name"),
                rs.getInt("version_no"), UtcTimes.fromDb(rs.getObject("created_at", java.time.LocalDateTime.class)), List.of());
    }

    private RowMapper<SegmentInfo> segmentMapper() {
        return (rs, rowNum) -> mapSegment(rs);
    }

    static SegmentInfo mapSegment(ResultSet rs) throws SQLException {
        return new SegmentInfo(rs.getLong("id"), rs.getInt("seq"),
                UtcTimes.fromDb(rs.getObject("start_time", java.time.LocalDateTime.class)), UtcTimes.fromDb(rs.getObject("end_time", java.time.LocalDateTime.class)),
                rs.getBigDecimal("target_temp_c"), rs.getBigDecimal("tolerance_c"));
    }
}