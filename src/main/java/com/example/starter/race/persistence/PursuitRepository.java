package com.example.starter.race.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 追逐赛（PURSUIT）领域的 JDBC 仓储；所有写方法均假定运行在业务事务内。
 * 轨迹更正整体替换采样点并推进轨迹版本；裁定按追逐对整体替换，
 * 轨迹更正同事务删除涉及该选手的旧裁定，保证裁定始终对应当前轨迹版本。
 */
@Repository
public class PursuitRepository {

    private static final PursuitPairRowMapper PAIR_ROW_MAPPER = new PursuitPairRowMapper();
    private static final PursuitTrajectoryRowMapper TRAJECTORY_ROW_MAPPER =
            new PursuitTrajectoryRowMapper();
    private static final PursuitSampleRowMapper SAMPLE_ROW_MAPPER = new PursuitSampleRowMapper();
    private static final PursuitAdjudicationRowMapper ADJUDICATION_ROW_MAPPER =
            new PursuitAdjudicationRowMapper();
    private static final SnapshotPursuitPairRowMapper SNAPSHOT_PAIR_ROW_MAPPER =
            new SnapshotPursuitPairRowMapper();
    private static final SnapshotPursuitSampleRowMapper SNAPSHOT_SAMPLE_ROW_MAPPER =
            new SnapshotPursuitSampleRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public PursuitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一次性写入赛事追逐对配置（启用PURSUIT时固定，之后不可修改）。 */
    public void insertPairs(List<PursuitPairRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO pursuit_pair "
                        + "(race_id, pair_no, chaser_bib, chased_bib, chaser_start_mm, chased_start_mm, "
                        + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setInt(2, row.pairNo());
                    ps.setString(3, row.chaserBib());
                    ps.setString(4, row.chasedBib());
                    ps.setLong(5, row.chaserStartMm());
                    ps.setLong(6, row.chasedStartMm());
                    ps.setLong(7, row.createdAt());
                    ps.setString(8, row.createdBy());
                });
    }

    /** 查询赛事全部追逐对，按序号升序；未启用PURSUIT时为空列表。 */
    public List<PursuitPairRow> findPairs(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, pair_no, chaser_bib, chased_bib, chaser_start_mm, chased_start_mm, "
                        + "created_at, created_by FROM pursuit_pair WHERE race_id = ? ORDER BY pair_no",
                PAIR_ROW_MAPPER, raceId);
    }

    /** 按赛事与序号查询追逐对。 */
    public Optional<PursuitPairRow> findPair(String raceId, int pairNo) {
        return jdbcTemplate
                .query("SELECT race_id, pair_no, chaser_bib, chased_bib, chaser_start_mm, chased_start_mm, "
                                + "created_at, created_by FROM pursuit_pair "
                                + "WHERE race_id = ? AND pair_no = ?",
                        PAIR_ROW_MAPPER, raceId, pairNo)
                .stream()
                .findFirst();
    }

    /** 查询选手当前轨迹版本；尚无轨迹返回 empty。 */
    public Optional<PursuitTrajectoryRow> findTrajectory(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, bib, version, updated_at, updated_by "
                                + "FROM pursuit_trajectory WHERE race_id = ? AND bib = ?",
                        TRAJECTORY_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事全部轨迹版本记录，按参赛号字典序。 */
    public List<PursuitTrajectoryRow> findTrajectories(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, version, updated_at, updated_by "
                        + "FROM pursuit_trajectory WHERE race_id = ? ORDER BY bib",
                TRAJECTORY_ROW_MAPPER, raceId);
    }

    /** 新建轨迹版本记录（首次采样，版本1）。 */
    public void insertTrajectory(PursuitTrajectoryRow row) {
        jdbcTemplate.update(
                "INSERT INTO pursuit_trajectory (race_id, bib, version, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.raceId(), row.bib(), row.version(), row.updatedAt(), row.updatedBy());
    }

    /** 推进轨迹版本；返回受影响行数（0 表示轨迹不存在）。 */
    public int bumpTrajectoryVersion(String raceId, String bib, int newVersion,
                                     long now, String operator) {
        return jdbcTemplate.update(
                "UPDATE pursuit_trajectory SET version = ?, updated_at = ?, updated_by = ? "
                        + "WHERE race_id = ? AND bib = ?",
                newVersion, now, operator, raceId, bib);
    }

    /** 整体替换选手轨迹采样点（先删后插，同事务）。 */
    public void replaceSamples(String raceId, String bib, List<PursuitSampleRow> rows) {
        jdbcTemplate.update(
                "DELETE FROM pursuit_sample WHERE race_id = ? AND bib = ?", raceId, bib);
        jdbcTemplate.batchUpdate(
                "INSERT INTO pursuit_sample (race_id, bib, seq, time_ms, distance_mm) "
                        + "VALUES (?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.bib());
                    ps.setInt(3, row.seq());
                    ps.setLong(4, row.timeMs());
                    ps.setLong(5, row.distanceMm());
                });
    }

    /** 查询选手轨迹采样点，按序号升序。 */
    public List<PursuitSampleRow> findSamples(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, seq, time_ms, distance_mm "
                        + "FROM pursuit_sample WHERE race_id = ? AND bib = ? ORDER BY seq",
                SAMPLE_ROW_MAPPER, raceId, bib);
    }

    /** 查询赛事全部轨迹采样点，按参赛号与序号升序。 */
    public List<PursuitSampleRow> findAllSamples(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, seq, time_ms, distance_mm "
                        + "FROM pursuit_sample WHERE race_id = ? ORDER BY bib, seq",
                SAMPLE_ROW_MAPPER, raceId);
    }

    /** 整体替换追逐对的当前裁定（先删后插，同事务）。 */
    public void replaceAdjudication(PursuitAdjudicationRow row) {
        jdbcTemplate.update(
                "DELETE FROM pursuit_adjudication WHERE race_id = ? AND pair_no = ?",
                row.raceId(), row.pairNo());
        jdbcTemplate.update(
                "INSERT INTO pursuit_adjudication "
                        + "(race_id, pair_no, chaser_bib, chased_bib, chaser_trajectory_version, "
                        + "chased_trajectory_version, catch_time_num, catch_time_den, created_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.raceId(), row.pairNo(), row.chaserBib(), row.chasedBib(),
                row.chaserTrajectoryVersion(), row.chasedTrajectoryVersion(),
                row.catchTimeNum(), row.catchTimeDen(), row.createdAt(), row.createdBy());
    }

    /** 按赛事与序号查询当前裁定；未裁定返回 empty。 */
    public Optional<PursuitAdjudicationRow> findAdjudication(String raceId, int pairNo) {
        return jdbcTemplate
                .query("SELECT race_id, pair_no, chaser_bib, chased_bib, chaser_trajectory_version, "
                                + "chased_trajectory_version, catch_time_num, catch_time_den, "
                                + "created_at, created_by FROM pursuit_adjudication "
                                + "WHERE race_id = ? AND pair_no = ?",
                        ADJUDICATION_ROW_MAPPER, raceId, pairNo)
                .stream()
                .findFirst();
    }

    /** 查询赛事全部当前裁定，按追逐对序号升序。 */
    public List<PursuitAdjudicationRow> findAdjudications(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, pair_no, chaser_bib, chased_bib, chaser_trajectory_version, "
                        + "chased_trajectory_version, catch_time_num, catch_time_den, "
                        + "created_at, created_by FROM pursuit_adjudication "
                        + "WHERE race_id = ? ORDER BY pair_no",
                ADJUDICATION_ROW_MAPPER, raceId);
    }

    /** 轨迹更正后使涉及该选手的旧裁定失效（删除）；返回失效裁定数。 */
    public int deleteAdjudicationsForBib(String raceId, String bib) {
        return jdbcTemplate.update(
                "DELETE FROM pursuit_adjudication "
                        + "WHERE race_id = ? AND (chaser_bib = ? OR chased_bib = ?)",
                raceId, bib, bib);
    }

    /** 封榜同事务冻结全部追逐对裁定（交会分数）与完整轨迹点。 */
    public void insertSnapshotPursuit(List<SnapshotPursuitPairRow> pairs,
                                      List<SnapshotPursuitSampleRow> samples) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_pursuit_pair "
                        + "(race_id, pair_no, chaser_bib, chased_bib, chaser_start_mm, chased_start_mm, "
                        + "chaser_trajectory_version, chased_trajectory_version, "
                        + "catch_time_num, catch_time_den) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pairs,
                pairs.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setInt(2, row.pairNo());
                    ps.setString(3, row.chaserBib());
                    ps.setString(4, row.chasedBib());
                    ps.setLong(5, row.chaserStartMm());
                    ps.setLong(6, row.chasedStartMm());
                    ps.setInt(7, row.chaserTrajectoryVersion());
                    ps.setInt(8, row.chasedTrajectoryVersion());
                    ps.setLong(9, row.catchTimeNum());
                    ps.setLong(10, row.catchTimeDen());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_pursuit_sample "
                        + "(race_id, bib, seq, time_ms, distance_mm) VALUES (?, ?, ?, ?, ?)",
                samples,
                samples.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.bib());
                    ps.setInt(3, row.seq());
                    ps.setLong(4, row.timeMs());
                    ps.setLong(5, row.distanceMm());
                });
    }

    /** 查询封榜冻结的追逐对裁定，按序号升序；未封榜或非追逐赛为空列表。 */
    public List<SnapshotPursuitPairRow> findSnapshotPursuitPairs(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, pair_no, chaser_bib, chased_bib, chaser_start_mm, chased_start_mm, "
                        + "chaser_trajectory_version, chased_trajectory_version, "
                        + "catch_time_num, catch_time_den "
                        + "FROM result_snapshot_pursuit_pair WHERE race_id = ? ORDER BY pair_no",
                SNAPSHOT_PAIR_ROW_MAPPER, raceId);
    }

    /** 查询封榜冻结的完整轨迹点，按参赛号与序号升序。 */
    public List<SnapshotPursuitSampleRow> findSnapshotPursuitSamples(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, seq, time_ms, distance_mm "
                        + "FROM result_snapshot_pursuit_sample WHERE race_id = ? ORDER BY bib, seq",
                SNAPSHOT_SAMPLE_ROW_MAPPER, raceId);
    }

    private static final class PursuitPairRowMapper implements RowMapper<PursuitPairRow> {
        @Override
        public PursuitPairRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitPairRow(
                    rs.getString("race_id"),
                    rs.getInt("pair_no"),
                    rs.getString("chaser_bib"),
                    rs.getString("chased_bib"),
                    rs.getLong("chaser_start_mm"),
                    rs.getLong("chased_start_mm"),
                    rs.getLong("created_at"),
                    rs.getString("created_by"));
        }
    }

    private static final class PursuitTrajectoryRowMapper implements RowMapper<PursuitTrajectoryRow> {
        @Override
        public PursuitTrajectoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitTrajectoryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("version"),
                    rs.getLong("updated_at"),
                    rs.getString("updated_by"));
        }
    }

    private static final class PursuitSampleRowMapper implements RowMapper<PursuitSampleRow> {
        @Override
        public PursuitSampleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitSampleRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("seq"),
                    rs.getLong("time_ms"),
                    rs.getLong("distance_mm"));
        }
    }

    private static final class PursuitAdjudicationRowMapper
            implements RowMapper<PursuitAdjudicationRow> {
        @Override
        public PursuitAdjudicationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitAdjudicationRow(
                    rs.getString("race_id"),
                    rs.getInt("pair_no"),
                    rs.getString("chaser_bib"),
                    rs.getString("chased_bib"),
                    rs.getInt("chaser_trajectory_version"),
                    rs.getInt("chased_trajectory_version"),
                    rs.getLong("catch_time_num"),
                    rs.getLong("catch_time_den"),
                    rs.getLong("created_at"),
                    rs.getString("created_by"));
        }
    }

    private static final class SnapshotPursuitPairRowMapper
            implements RowMapper<SnapshotPursuitPairRow> {
        @Override
        public SnapshotPursuitPairRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotPursuitPairRow(
                    rs.getString("race_id"),
                    rs.getInt("pair_no"),
                    rs.getString("chaser_bib"),
                    rs.getString("chased_bib"),
                    rs.getLong("chaser_start_mm"),
                    rs.getLong("chased_start_mm"),
                    rs.getInt("chaser_trajectory_version"),
                    rs.getInt("chased_trajectory_version"),
                    rs.getLong("catch_time_num"),
                    rs.getLong("catch_time_den"));
        }
    }

    private static final class SnapshotPursuitSampleRowMapper
            implements RowMapper<SnapshotPursuitSampleRow> {
        @Override
        public SnapshotPursuitSampleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotPursuitSampleRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("seq"),
                    rs.getLong("time_ms"),
                    rs.getLong("distance_mm"));
        }
    }
}
