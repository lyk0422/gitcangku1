package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 赛事封榜领域的 JDBC 仓储；所有写方法均假定运行在业务事务内，
 * 版本推进与封榜全部使用条件 UPDATE 保证并发下无丢失更新。
 */
@Repository
public class RaceRepository {

    private static final RaceRowMapper RACE_ROW_MAPPER = new RaceRowMapper();
    private static final RunnerRowMapper RUNNER_ROW_MAPPER = new RunnerRowMapper();
    private static final PenaltyRowMapper PENALTY_ROW_MAPPER = new PenaltyRowMapper();
    private static final SnapshotEntryRowMapper SNAPSHOT_ENTRY_ROW_MAPPER =
            new SnapshotEntryRowMapper();
    private static final IdempotencyRowMapper IDEMPOTENCY_ROW_MAPPER = new IdempotencyRowMapper();
    private static final CourseRowMapper COURSE_ROW_MAPPER = new CourseRowMapper();
    private static final CourseRecordRowMapper COURSE_RECORD_ROW_MAPPER =
            new CourseRecordRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按ID查询赛事。 */
    public Optional<RaceRow> findRace(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, course_key, version, status, created_at "
                                + "FROM race WHERE race_id = ?",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部选手，按参赛号字典序排列。 */
    public List<RunnerRow> findRunners(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                        + "FROM runner WHERE race_id = ? ORDER BY bib",
                RUNNER_ROW_MAPPER, raceId);
    }

    /** 按赛事与参赛号查询选手。 */
    public Optional<RunnerRow> findRunner(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                                + "FROM runner WHERE race_id = ? AND bib = ?",
                        RUNNER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部处罚（含已撤销），按新增时间与处罚ID排列。 */
    public List<PenaltyRow> findPenalties(String raceId) {
        return jdbcTemplate.query(
                "SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                        + "FROM penalty WHERE race_id = ? ORDER BY created_at, penalty_id",
                PENALTY_ROW_MAPPER, raceId);
    }

    /** 按全局处罚ID查询处罚。 */
    public Optional<PenaltyRow> findPenalty(String penaltyId) {
        return jdbcTemplate
                .query("SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                                + "FROM penalty WHERE penalty_id = ?",
                        PENALTY_ROW_MAPPER, penaltyId)
                .stream()
                .findFirst();
    }

    /** 查询封榜快照（含全部条目）；未封榜返回 empty。 */
    public Optional<SnapshotRow> findSnapshot(String raceId) {
        List<SnapshotRow> headers = jdbcTemplate.query(
                "SELECT race_id, version, sealed_at FROM result_snapshot WHERE race_id = ?",
                (rs, rowNum) -> new SnapshotRow(
                        rs.getString("race_id"),
                        rs.getInt("version"),
                        rs.getLong("sealed_at"),
                        List.of()),
                raceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow header = headers.getFirst();
        List<SnapshotEntryRow> entries = jdbcTemplate.query(
                "SELECT race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, display_order "
                        + "FROM result_snapshot_entry WHERE race_id = ? ORDER BY display_order",
                SNAPSHOT_ENTRY_ROW_MAPPER, raceId);
        return Optional.of(new SnapshotRow(header.raceId(), header.version(), header.sealedAt(),
                entries));
    }

    /** 新建赛事，初始版本1、状态OPEN；必须关联已登记赛道。 */
    public void insertRace(String raceId, String courseKey, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, course_key, version, status, created_at) "
                        + "VALUES (?, ?, 1, 'OPEN', ?)",
                raceId, courseKey, now);
    }

    /** 登记选手；finishTimeMs 为 null 表示计时缺失。 */
    public void insertRunner(String raceId, String bib, Long finishTimeMs, long now) {
        jdbcTemplate.update(
                "INSERT INTO runner (race_id, bib, finish_time_ms, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                raceId, bib, finishTimeMs, now, now);
    }

    /** 修订选手原始完赛耗时；返回受影响行数（0 表示选手不存在）。 */
    public int updateRunnerTiming(String raceId, String bib, Long finishTimeMs, long now) {
        return jdbcTemplate.update(
                "UPDATE runner SET finish_time_ms = ?, updated_at = ? WHERE race_id = ? AND bib = ?",
                finishTimeMs, now, raceId, bib);
    }

    /** 新增处罚（不可覆盖历史）。 */
    public void insertPenalty(
            String penaltyId,
            String raceId,
            String bib,
            PenaltyType type,
            Long amountMs,
            long now) {
        jdbcTemplate.update(
                "INSERT INTO penalty "
                        + "(penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, FALSE, ?, NULL)",
                penaltyId, raceId, bib, type.name(), amountMs, now);
    }

    /** 撤销处罚，仅对当前未撤销的处罚生效；返回受影响行数。 */
    public int markPenaltyRevoked(String penaltyId, long now) {
        return jdbcTemplate.update(
                "UPDATE penalty SET revoked = TRUE, revoked_at = ? WHERE penalty_id = ? AND revoked = FALSE",
                now, penaltyId);
    }

    /**
     * 条件推进版本：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时加一。
     *
     * @return 受影响行数；0 表示赛事不存在、已封榜或版本已被其他写操作推进
     */
    public int bumpVersionIfOpen(String raceId, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = version + 1 "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                raceId, expectedVersion);
    }

    /**
     * 条件封榜：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时转为 SEALED 并推进版本。
     *
     * @return 受影响行数；0 表示不存在、已封榜或版本不匹配
     */
    public int sealIfOpenAtVersion(String raceId, int expectedVersion, int newVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = ?, status = 'SEALED' "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                newVersion, raceId, expectedVersion);
    }

    /** 原子写入封榜快照头表与全部条目。 */
    public void insertSnapshot(SnapshotRow snapshot) {
        jdbcTemplate.update(
                "INSERT INTO result_snapshot (race_id, version, sealed_at) VALUES (?, ?, ?)",
                snapshot.raceId(), snapshot.version(), snapshot.sealedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_entry "
                        + "(race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.entries(),
                snapshot.entries().size(),
                (ps, entry) -> {
                    ps.setString(1, entry.raceId());
                    ps.setString(2, entry.bib());
                    ps.setObject(3, entry.rank());
                    ps.setString(4, entry.status().name());
                    ps.setObject(5, entry.finishTimeMs());
                    ps.setLong(6, entry.penaltyMs());
                    ps.setObject(7, entry.totalTimeMs());
                    ps.setInt(8, entry.displayOrder());
                });
    }

    /** 按请求ID查询幂等记录。 */
    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ?",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /**
     * 插入幂等占位行（response_status=0 表示进行中），同事务失败回滚即不占键。
     * 并发同键时由唯一约束拒绝，调用方再通过 {@link #findIdempotencyForUpdate} 等待先行者结束。
     */
    public void insertIdempotencyPlaceholder(
            String requestId, String operation, String requestDigest, long now) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record "
                        + "(request_id, operation, request_digest, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, 0, '', ?)",
                requestId, operation, requestDigest, now);
    }

    /**
     * 行锁方式查询幂等记录：若先行者事务未结束则阻塞至其提交或回滚；
     * 回滚后占位行随事务消失，返回 empty，调用方可重新占位执行。
     */
    public Optional<IdempotencyRow> findIdempotencyForUpdate(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ? FOR UPDATE",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /** 业务成功后把占位行补写为最终响应，与业务变更同事务原子提交。 */
    public void completeIdempotency(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? "
                        + "WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    /** 登记赛道，初始无纪录（current_record_id 为 NULL）。 */
    public void insertCourse(String courseKey, long now) {
        jdbcTemplate.update(
                "INSERT INTO course (course_key, current_record_id, created_at) "
                        + "VALUES (?, NULL, ?)",
                courseKey, now);
    }

    /** 按标识查询赛道。 */
    public Optional<CourseRow> findCourse(String courseKey) {
        return jdbcTemplate
                .query("SELECT course_key, current_record_id, created_at "
                                + "FROM course WHERE course_key = ?",
                        COURSE_ROW_MAPPER, courseKey)
                .stream()
                .findFirst();
    }

    /**
     * 行锁方式查询赛道：序列化同一赛道的并发纪录认定，
     * 保证认定事务读到的是最新已提交的当前纪录。
     */
    public Optional<CourseRow> findCourseForUpdate(String courseKey) {
        return jdbcTemplate
                .query("SELECT course_key, current_record_id, created_at "
                                + "FROM course WHERE course_key = ? FOR UPDATE",
                        COURSE_ROW_MAPPER, courseKey)
                .stream()
                .findFirst();
    }

    /** 追加一条赛道纪录（历史链只增长）；返回自增纪录ID。 */
    public long insertCourseRecord(
            String courseKey,
            String raceId,
            String bib,
            long timeMs,
            String recordClaimKey,
            long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO course_record "
                            + "(course_key, race_id, bib, time_ms, record_claim_key, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, courseKey);
            ps.setString(2, raceId);
            ps.setString(3, bib);
            ps.setLong(4, timeMs);
            ps.setString(5, recordClaimKey);
            ps.setLong(6, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按纪录ID查询纪录。 */
    public Optional<CourseRecordRow> findCourseRecordById(long recordId) {
        return jdbcTemplate
                .query("SELECT id, course_key, race_id, bib, time_ms, record_claim_key, created_at "
                                + "FROM course_record WHERE id = ?",
                        COURSE_RECORD_ROW_MAPPER, recordId)
                .stream()
                .findFirst();
    }

    /** 按认定申请键查询纪录（认定幂等判重）。 */
    public Optional<CourseRecordRow> findCourseRecordByClaimKey(String recordClaimKey) {
        return jdbcTemplate
                .query("SELECT id, course_key, race_id, bib, time_ms, record_claim_key, created_at "
                                + "FROM course_record WHERE record_claim_key = ?",
                        COURSE_RECORD_ROW_MAPPER, recordClaimKey)
                .stream()
                .findFirst();
    }

    /** 查询赛道完整历史纪录链，按认定先后（id 自增）升序。 */
    public List<CourseRecordRow> findCourseRecords(String courseKey) {
        return jdbcTemplate.query(
                "SELECT id, course_key, race_id, bib, time_ms, record_claim_key, created_at "
                        + "FROM course_record WHERE course_key = ? ORDER BY id",
                COURSE_RECORD_ROW_MAPPER, courseKey);
    }

    /** 原子切换赛道当前纪录指针；调用方须已持有赛道行锁。 */
    public void updateCourseCurrentRecord(String courseKey, long recordId) {
        jdbcTemplate.update(
                "UPDATE course SET current_record_id = ? WHERE course_key = ?",
                recordId, courseKey);
    }

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM result_snapshot_entry");
        jdbcTemplate.update("DELETE FROM result_snapshot");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM penalty");
        jdbcTemplate.update("DELETE FROM runner");
        jdbcTemplate.update("DELETE FROM course_record");
        jdbcTemplate.update("DELETE FROM race");
        jdbcTemplate.update("DELETE FROM course");
    }

    private static final class RaceRowMapper implements RowMapper<RaceRow> {
        @Override
        public RaceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RaceRow(
                    rs.getString("race_id"),
                    rs.getString("course_key"),
                    rs.getInt("version"),
                    RaceStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at"));
        }
    }

    private static final class CourseRowMapper implements RowMapper<CourseRow> {
        @Override
        public CourseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CourseRow(
                    rs.getString("course_key"),
                    (Long) rs.getObject("current_record_id"),
                    rs.getLong("created_at"));
        }
    }

    private static final class CourseRecordRowMapper implements RowMapper<CourseRecordRow> {
        @Override
        public CourseRecordRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CourseRecordRow(
                    rs.getLong("id"),
                    rs.getString("course_key"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getLong("time_ms"),
                    rs.getString("record_claim_key"),
                    rs.getLong("created_at"));
        }
    }

    private static final class RunnerRowMapper implements RowMapper<RunnerRow> {
        @Override
        public RunnerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long finishTimeMs = (Long) rs.getObject("finish_time_ms");
            return new RunnerRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    finishTimeMs,
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class PenaltyRowMapper implements RowMapper<PenaltyRow> {
        @Override
        public PenaltyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PenaltyRow(
                    rs.getString("penalty_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    PenaltyType.valueOf(rs.getString("type")),
                    (Long) rs.getObject("amount_ms"),
                    rs.getBoolean("revoked"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("revoked_at"));
        }
    }

    private static final class SnapshotEntryRowMapper implements RowMapper<SnapshotEntryRow> {
        @Override
        public SnapshotEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotEntryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    (Integer) rs.getObject("rank_no"),
                    EntryStatus.valueOf(rs.getString("status")),
                    (Long) rs.getObject("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getInt("display_order"));
        }
    }

    private static final class IdempotencyRowMapper implements RowMapper<IdempotencyRow> {
        @Override
        public IdempotencyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotencyRow(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("request_digest"),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getLong("created_at"));
        }
    }
}
