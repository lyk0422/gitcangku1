package com.example.starter.race.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 赛道与赛道纪录的 JDBC 仓储；纪录历史链只增不改，
 * 当前纪录指针切换与链追加在同一事务内完成。
 */
@Repository
public class CourseRepository {

    private static final CourseRowMapper COURSE_ROW_MAPPER = new CourseRowMapper();
    private static final CourseRecordRowMapper RECORD_ROW_MAPPER = new CourseRecordRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public CourseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按标识查询赛道。 */
    public Optional<CourseRow> findCourse(String courseKey) {
        return jdbcTemplate
                .query("SELECT course_key, current_record_id, created_at FROM course "
                                + "WHERE course_key = ?",
                        COURSE_ROW_MAPPER, courseKey)
                .stream()
                .findFirst();
    }

    /** 行锁查询赛道：认定事务内串行化同赛道并发认定。 */
    public Optional<CourseRow> findCourseForUpdate(String courseKey) {
        return jdbcTemplate
                .query("SELECT course_key, current_record_id, created_at FROM course "
                                + "WHERE course_key = ? FOR UPDATE",
                        COURSE_ROW_MAPPER, courseKey)
                .stream()
                .findFirst();
    }

    /** 登记赛道，初始无纪录。 */
    public void insertCourse(String courseKey, long now) {
        jdbcTemplate.update(
                "INSERT INTO course (course_key, current_record_id, created_at) "
                        + "VALUES (?, NULL, ?)",
                courseKey, now);
    }

    /** 按认定申请键查询纪录（幂等重放）。 */
    public Optional<CourseRecordRow> findRecordByClaimKey(String claimKey) {
        return jdbcTemplate
                .query("SELECT record_id, claim_key, course_key, seq, race_id, bib, time_ms, claimed_at "
                                + "FROM course_record WHERE claim_key = ?",
                        RECORD_ROW_MAPPER, claimKey)
                .stream()
                .findFirst();
    }

    /** 按纪录ID查询纪录。 */
    public Optional<CourseRecordRow> findRecord(String recordId) {
        return jdbcTemplate
                .query("SELECT record_id, claim_key, course_key, seq, race_id, bib, time_ms, claimed_at "
                                + "FROM course_record WHERE record_id = ?",
                        RECORD_ROW_MAPPER, recordId)
                .stream()
                .findFirst();
    }

    /** 查询赛道完整历史纪录链，按链内序号升序。 */
    public List<CourseRecordRow> findRecordHistory(String courseKey) {
        return jdbcTemplate.query(
                "SELECT record_id, claim_key, course_key, seq, race_id, bib, time_ms, claimed_at "
                        + "FROM course_record WHERE course_key = ? ORDER BY seq",
                RECORD_ROW_MAPPER, courseKey);
    }

    /** 追加纪录链节点并原子切换当前纪录指针（同事务）。 */
    public void appendRecordAndSwitch(CourseRecordRow record) {
        jdbcTemplate.update(
                "INSERT INTO course_record "
                        + "(record_id, claim_key, course_key, seq, race_id, bib, time_ms, claimed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.recordId(), record.claimKey(), record.courseKey(), record.seq(),
                record.raceId(), record.bib(), record.timeMs(), record.claimedAt());
        jdbcTemplate.update(
                "UPDATE course SET current_record_id = ? WHERE course_key = ?",
                record.recordId(), record.courseKey());
    }

    private static final class CourseRowMapper implements RowMapper<CourseRow> {
        @Override
        public CourseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CourseRow(
                    rs.getString("course_key"),
                    rs.getString("current_record_id"),
                    rs.getLong("created_at"));
        }
    }

    private static final class CourseRecordRowMapper implements RowMapper<CourseRecordRow> {
        @Override
        public CourseRecordRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CourseRecordRow(
                    rs.getString("record_id"),
                    rs.getString("claim_key"),
                    rs.getString("course_key"),
                    rs.getInt("seq"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getLong("time_ms"),
                    rs.getLong("claimed_at"));
        }
    }
}
