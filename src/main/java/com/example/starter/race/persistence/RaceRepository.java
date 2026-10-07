package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceMode;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RulingStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final CheckpointRowMapper CHECKPOINT_ROW_MAPPER = new CheckpointRowMapper();
    private static final CheckpointTimingRowMapper CHECKPOINT_TIMING_ROW_MAPPER =
            new CheckpointTimingRowMapper();
    private static final SnapshotCheckpointRowMapper SNAPSHOT_CHECKPOINT_ROW_MAPPER =
            new SnapshotCheckpointRowMapper();
    private static final PursuitPairRowMapper PURSUIT_PAIR_ROW_MAPPER = new PursuitPairRowMapper();
    private static final TrajectoryRowMapper TRAJECTORY_ROW_MAPPER = new TrajectoryRowMapper();
    private static final TrajectorySampleRowMapper TRAJECTORY_SAMPLE_ROW_MAPPER =
            new TrajectorySampleRowMapper();
    private static final PursuitRulingRowMapper PURSUIT_RULING_ROW_MAPPER =
            new PursuitRulingRowMapper();
    private static final SnapshotTrajectoryRowMapper SNAPSHOT_TRAJECTORY_ROW_MAPPER =
            new SnapshotTrajectoryRowMapper();
    private static final SnapshotRulingRowMapper SNAPSHOT_RULING_ROW_MAPPER =
            new SnapshotRulingRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按ID查询赛事。 */
    public Optional<RaceRow> findRace(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, mode, created_at FROM race WHERE race_id = ?",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁方式查询赛事：获取 race 行写锁并持有到事务提交/回滚。
     * 写操作据此与其它写者在读取任何依赖数据之前先串行化，避免 READ_COMMITTED 下
     * “先读到旧版本、后读到他人已提交明细”造成的误判（如分段重复误报422）。
     */
    public Optional<RaceRow> findRaceForUpdate(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, mode, created_at FROM race WHERE race_id = ? FOR UPDATE",
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

    /** 查询赛事的全部检查点，按顺序 position 升序排列；未配置时为空列表。 */
    public List<CheckpointRow> findCheckpoints(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, checkpoint_code, position, created_at "
                        + "FROM checkpoint WHERE race_id = ? ORDER BY position",
                CHECKPOINT_ROW_MAPPER, raceId);
    }

    /** 按赛事与代码查询检查点。 */
    public Optional<CheckpointRow> findCheckpoint(String raceId, String checkpointCode) {
        return jdbcTemplate
                .query("SELECT race_id, checkpoint_code, position, created_at "
                                + "FROM checkpoint WHERE race_id = ? AND checkpoint_code = ?",
                        CHECKPOINT_ROW_MAPPER, raceId, checkpointCode)
                .stream()
                .findFirst();
    }

    /** 统计赛事下的分段记录总数（用于“尚无任何分段记录时才可配置检查点”）。 */
    public int countTimings(String raceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkpoint_timing WHERE race_id = ?",
                Integer.class, raceId);
        return count == null ? 0 : count;
    }

    /** 查询某选手的全部分段记录，按 position 升序排列。 */
    public List<CheckpointTimingRow> findTimingsForRunner(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                        + "FROM checkpoint_timing WHERE race_id = ? AND bib = ? ORDER BY position",
                CHECKPOINT_TIMING_ROW_MAPPER, raceId, bib);
    }

    /** 查询赛事下全部分段记录（用于实时成绩与封榜计算）。 */
    public List<CheckpointTimingRow> findAllTimings(String raceId) {
        return jdbcTemplate.query(
                "SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                        + "FROM checkpoint_timing WHERE race_id = ? ORDER BY bib, position",
                CHECKPOINT_TIMING_ROW_MAPPER, raceId);
    }

    /** 按全局分段ID查询记录（用于 timingId 幂等重放）。 */
    public Optional<CheckpointTimingRow> findTiming(String timingId) {
        return jdbcTemplate
                .query("SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                                + "FROM checkpoint_timing WHERE timing_id = ?",
                        CHECKPOINT_TIMING_ROW_MAPPER, timingId)
                .stream()
                .findFirst();
    }

    /** 新增选手分段通过记录（全局唯一 timingId 与选手+检查点唯一键由数据库约束保证）。 */
    public void insertTiming(CheckpointTimingRow row) {
        jdbcTemplate.update(
                "INSERT INTO checkpoint_timing "
                        + "(timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.timingId(), row.raceId(), row.bib(), row.checkpointCode(),
                row.position(), row.elapsedMillis(), row.createdAt());
    }

    /** 一次性写入赛事检查点配置（配置后不可修改）。 */
    public void insertCheckpoints(List<CheckpointRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO checkpoint (race_id, checkpoint_code, position, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.checkpointCode());
                    ps.setInt(3, row.position());
                    ps.setLong(4, row.createdAt());
                });
    }

    /** 查询封榜快照（含全部条目与分段明细）；未封榜返回 empty。 */
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
        List<SnapshotEntryRow> entryRows = jdbcTemplate.query(
                "SELECT race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, "
                        + "display_order, checkpoint_count, covered_checkpoint_count "
                        + "FROM result_snapshot_entry WHERE race_id = ? ORDER BY display_order",
                SNAPSHOT_ENTRY_ROW_MAPPER, raceId);
        List<SnapshotCheckpointRow> checkpoints = jdbcTemplate.query(
                "SELECT race_id, bib, checkpoint_code, position, elapsed_millis, timing_id "
                        + "FROM result_snapshot_checkpoint WHERE race_id = ? "
                        + "ORDER BY bib, position",
                SNAPSHOT_CHECKPOINT_ROW_MAPPER, raceId);
        // 缺失检查点由明细表中 elapsed_millis 为 NULL 的行派生，保持展示顺序稳定。
        java.util.Map<String, List<String>> missingByBib = new java.util.LinkedHashMap<>();
        for (SnapshotCheckpointRow detail : checkpoints) {
            if (detail.elapsedMillis() == null) {
                missingByBib.computeIfAbsent(detail.bib(), key -> new java.util.ArrayList<>())
                        .add(detail.checkpointCode());
            }
        }
        List<SnapshotEntryRow> entries = entryRows.stream()
                .map(entry -> new SnapshotEntryRow(
                        entry.raceId(), entry.bib(), entry.rank(), entry.status(),
                        entry.finishTimeMs(), entry.penaltyMs(), entry.totalTimeMs(),
                        entry.displayOrder(), entry.checkpointCount(),
                        entry.coveredCheckpointCount(),
                        missingByBib.getOrDefault(entry.bib(), List.of())))
                .toList();
        return Optional.of(new SnapshotRow(header.raceId(), header.version(), header.sealedAt(),
                entries, checkpoints));
    }

    /** 新建赛事，初始版本1、状态OPEN、模式STANDARD。 */
    public void insertRace(String raceId, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, version, status, mode, created_at) "
                        + "VALUES (?, 1, 'OPEN', 'STANDARD', ?)",
                raceId, now);
    }

    /** 将赛事模式切换为 PURSUIT（仅允许在 OPEN 且无任何计时/轨迹数据前执行一次）。 */
    public void markRacePursuit(String raceId) {
        jdbcTemplate.update(
                "UPDATE race SET mode = 'PURSUIT' WHERE race_id = ?",
                raceId);
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

    /** 原子写入封榜快照头表、全部条目以及每名选手的分段明细。 */
    public void insertSnapshot(SnapshotRow snapshot) {
        jdbcTemplate.update(
                "INSERT INTO result_snapshot (race_id, version, sealed_at) VALUES (?, ?, ?)",
                snapshot.raceId(), snapshot.version(), snapshot.sealedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_entry "
                        + "(race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, "
                        + "display_order, checkpoint_count, covered_checkpoint_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                    ps.setInt(9, entry.checkpointCount());
                    ps.setInt(10, entry.coveredCheckpointCount());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_checkpoint "
                        + "(race_id, bib, checkpoint_code, position, elapsed_millis, timing_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                snapshot.checkpoints(),
                snapshot.checkpoints().size(),
                (ps, detail) -> {
                    ps.setString(1, detail.raceId());
                    ps.setString(2, detail.bib());
                    ps.setString(3, detail.checkpointCode());
                    ps.setInt(4, detail.position());
                    ps.setObject(5, detail.elapsedMillis());
                    ps.setString(6, detail.timingId());
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

    /** 查询赛事全部追逐对声明，按 pairId 字典序排列；非 PURSUIT 赛事为空列表。 */
    public List<PursuitPairRow> findPursuitPairs(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, pair_id, pursuer_bib, pursued_bib, pursuer_start_mm, pursued_start_mm, "
                        + "created_at FROM pursuit_pair WHERE race_id = ? ORDER BY pair_id",
                PURSUIT_PAIR_ROW_MAPPER, raceId);
    }

    /** 按赛事与追逐对ID查询声明。 */
    public Optional<PursuitPairRow> findPursuitPair(String raceId, String pairId) {
        return jdbcTemplate
                .query("SELECT race_id, pair_id, pursuer_bib, pursued_bib, pursuer_start_mm, "
                                + "pursued_start_mm, created_at "
                                + "FROM pursuit_pair WHERE race_id = ? AND pair_id = ?",
                        PURSUIT_PAIR_ROW_MAPPER, raceId, pairId)
                .stream()
                .findFirst();
    }

    /** 一次性写入追逐对声明（启用 PURSUIT 模式时固定，之后不可修改）。 */
    public void insertPursuitPairs(List<PursuitPairRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO pursuit_pair "
                        + "(race_id, pair_id, pursuer_bib, pursued_bib, pursuer_start_mm, pursued_start_mm, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.pairId());
                    ps.setString(3, row.pursuerBib());
                    ps.setString(4, row.pursuedBib());
                    ps.setLong(5, row.pursuerStartMm());
                    ps.setLong(6, row.pursuedStartMm());
                    ps.setLong(7, row.createdAt());
                });
    }

    /** 按赛事与参赛号查询轨迹头。 */
    public Optional<TrajectoryRow> findTrajectory(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, bib, version, sample_count, operator, updated_at "
                                + "FROM trajectory WHERE race_id = ? AND bib = ?",
                        TRAJECTORY_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事全部轨迹头，按参赛号字典序排列。 */
    public List<TrajectoryRow> findTrajectories(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, version, sample_count, operator, updated_at "
                        + "FROM trajectory WHERE race_id = ? ORDER BY bib",
                TRAJECTORY_ROW_MAPPER, raceId);
    }

    /** 新建轨迹头（版本1）。 */
    public void insertTrajectory(String raceId, String bib, int sampleCount, String operator, long now) {
        jdbcTemplate.update(
                "INSERT INTO trajectory (race_id, bib, version, sample_count, operator, updated_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?)",
                raceId, bib, sampleCount, operator, now);
    }

    /** 推进轨迹版本并替换采样点数；返回受影响行数（0 表示轨迹不存在）。 */
    public int bumpTrajectoryVersion(
            String raceId, String bib, int expectedVersion, int sampleCount, String operator, long now) {
        return jdbcTemplate.update(
                "UPDATE trajectory SET version = version + 1, sample_count = ?, operator = ?, "
                        + "updated_at = ? WHERE race_id = ? AND bib = ? AND version = ?",
                sampleCount, operator, now, raceId, bib, expectedVersion);
    }

    /** 查询轨迹当前版本全部采样点，按 seq 升序。 */
    public List<TrajectorySampleRow> findTrajectorySamples(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, seq, elapsed_ms, progress_mm "
                        + "FROM trajectory_sample WHERE race_id = ? AND bib = ? ORDER BY seq",
                TRAJECTORY_SAMPLE_ROW_MAPPER, raceId, bib);
    }

    /** 查询赛事全部轨迹采样点，按参赛号与 seq 升序（用于封榜固化完整点）。 */
    public List<TrajectorySampleRow> findAllTrajectorySamples(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, seq, elapsed_ms, progress_mm "
                        + "FROM trajectory_sample WHERE race_id = ? ORDER BY bib, seq",
                TRAJECTORY_SAMPLE_ROW_MAPPER, raceId);
    }

    /** 整体替换轨迹采样点（同事务内先删后插，旧版本采样不保留）。 */
    public void replaceTrajectorySamples(String raceId, String bib, List<TrajectorySampleRow> rows) {
        jdbcTemplate.update(
                "DELETE FROM trajectory_sample WHERE race_id = ? AND bib = ?", raceId, bib);
        jdbcTemplate.batchUpdate(
                "INSERT INTO trajectory_sample (race_id, bib, seq, elapsed_ms, progress_mm) "
                        + "VALUES (?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.bib());
                    ps.setInt(3, row.seq());
                    ps.setLong(4, row.elapsedMs());
                    ps.setLong(5, row.progressMm());
                });
    }

    /** 查询追逐对的当前有效裁定。 */
    public Optional<PursuitRulingRow> findCurrentRuling(String raceId, String pairId) {
        return jdbcTemplate
                .query("SELECT ruling_id, race_id, pair_id, pursuer_trajectory_version, "
                                + "pursued_trajectory_version, catch_time_num, catch_time_den, status, "
                                + "operator, created_at "
                                + "FROM pursuit_ruling WHERE race_id = ? AND pair_id = ? AND status = 'CURRENT'",
                        PURSUIT_RULING_ROW_MAPPER, raceId, pairId)
                .stream()
                .findFirst();
    }

    /** 按全局裁定ID查询裁定（用于 rulingId 幂等重放）。 */
    public Optional<PursuitRulingRow> findRuling(String rulingId) {
        return jdbcTemplate
                .query("SELECT ruling_id, race_id, pair_id, pursuer_trajectory_version, "
                                + "pursued_trajectory_version, catch_time_num, catch_time_den, status, "
                                + "operator, created_at "
                                + "FROM pursuit_ruling WHERE ruling_id = ?",
                        PURSUIT_RULING_ROW_MAPPER, rulingId)
                .stream()
                .findFirst();
    }

    /** 查询赛事全部当前有效裁定，按 pairId 字典序排列。 */
    public List<PursuitRulingRow> findCurrentRulings(String raceId) {
        return jdbcTemplate.query(
                "SELECT ruling_id, race_id, pair_id, pursuer_trajectory_version, "
                        + "pursued_trajectory_version, catch_time_num, catch_time_den, status, "
                        + "operator, created_at "
                        + "FROM pursuit_ruling WHERE race_id = ? AND status = 'CURRENT' ORDER BY pair_id",
                PURSUIT_RULING_ROW_MAPPER, raceId);
    }

    /** 新增裁定（初始状态 CURRENT）。 */
    public void insertRuling(PursuitRulingRow row) {
        jdbcTemplate.update(
                "INSERT INTO pursuit_ruling "
                        + "(ruling_id, race_id, pair_id, pursuer_trajectory_version, "
                        + "pursued_trajectory_version, catch_time_num, catch_time_den, status, "
                        + "operator, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?)",
                row.rulingId(), row.raceId(), row.pairId(), row.pursuerTrajectoryVersion(),
                row.pursuedTrajectoryVersion(), row.catchTimeNum(), row.catchTimeDen(),
                row.operator(), row.createdAt());
    }

    /** 将追逐对的当前裁定置为 SUPERSEDED（轨迹更正时调用）；返回受影响行数。 */
    public int supersedeCurrentRuling(String raceId, String pairId) {
        return jdbcTemplate.update(
                "UPDATE pursuit_ruling SET status = 'SUPERSEDED' "
                        + "WHERE race_id = ? AND pair_id = ? AND status = 'CURRENT'",
                raceId, pairId);
    }

    /** 将赛事内涉及指定选手的全部当前裁定置为 SUPERSEDED；返回受影响行数。 */
    public int supersedeRulingsForBib(String raceId, String bib) {
        return jdbcTemplate.update(
                "UPDATE pursuit_ruling SET status = 'SUPERSEDED' "
                        + "WHERE race_id = ? AND status = 'CURRENT' AND pair_id IN "
                        + "(SELECT pair_id FROM pursuit_pair WHERE race_id = ? "
                        + "AND (pursuer_bib = ? OR pursued_bib = ?))",
                raceId, raceId, bib, bib);
    }

    /** 封榜同事务固化轨迹完整采样点。 */
    public void insertSnapshotTrajectories(List<SnapshotTrajectoryRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_trajectory "
                        + "(race_id, bib, trajectory_version, seq, elapsed_ms, progress_mm) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.bib());
                    ps.setInt(3, row.trajectoryVersion());
                    ps.setInt(4, row.seq());
                    ps.setLong(5, row.elapsedMs());
                    ps.setLong(6, row.progressMm());
                });
    }

    /** 封榜同事务固化追逐对当前裁定（交会分数）。 */
    public void insertSnapshotRulings(List<SnapshotRulingRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_ruling "
                        + "(race_id, pair_id, ruling_id, pursuer_trajectory_version, "
                        + "pursued_trajectory_version, catch_time_num, catch_time_den) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.pairId());
                    ps.setString(3, row.rulingId());
                    ps.setInt(4, row.pursuerTrajectoryVersion());
                    ps.setInt(5, row.pursuedTrajectoryVersion());
                    ps.setString(6, row.catchTimeNum());
                    ps.setString(7, row.catchTimeDen());
                });
    }

    /** 查询封榜固化的轨迹完整采样点，按参赛号与 seq 升序。 */
    public List<SnapshotTrajectoryRow> findSnapshotTrajectories(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, trajectory_version, seq, elapsed_ms, progress_mm "
                        + "FROM result_snapshot_trajectory WHERE race_id = ? ORDER BY bib, seq",
                SNAPSHOT_TRAJECTORY_ROW_MAPPER, raceId);
    }

    /** 查询封榜固化的追逐对裁定，按 pairId 字典序排列。 */
    public List<SnapshotRulingRow> findSnapshotRulings(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, pair_id, ruling_id, pursuer_trajectory_version, "
                        + "pursued_trajectory_version, catch_time_num, catch_time_den "
                        + "FROM result_snapshot_ruling WHERE race_id = ? ORDER BY pair_id",
                SNAPSHOT_RULING_ROW_MAPPER, raceId);
    }

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM result_snapshot_ruling");
        jdbcTemplate.update("DELETE FROM result_snapshot_trajectory");
        jdbcTemplate.update("DELETE FROM result_snapshot_checkpoint");
        jdbcTemplate.update("DELETE FROM result_snapshot_entry");
        jdbcTemplate.update("DELETE FROM result_snapshot");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM pursuit_ruling");
        jdbcTemplate.update("DELETE FROM trajectory_sample");
        jdbcTemplate.update("DELETE FROM trajectory");
        jdbcTemplate.update("DELETE FROM pursuit_pair");
        jdbcTemplate.update("DELETE FROM checkpoint_timing");
        jdbcTemplate.update("DELETE FROM checkpoint");
        jdbcTemplate.update("DELETE FROM penalty");
        jdbcTemplate.update("DELETE FROM runner");
        jdbcTemplate.update("DELETE FROM race");
    }

    private static final class RaceRowMapper implements RowMapper<RaceRow> {
        @Override
        public RaceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RaceRow(
                    rs.getString("race_id"),
                    rs.getInt("version"),
                    RaceStatus.valueOf(rs.getString("status")),
                    RaceMode.valueOf(rs.getString("mode")),
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
                    rs.getInt("display_order"),
                    rs.getInt("checkpoint_count"),
                    rs.getInt("covered_checkpoint_count"),
                    List.of());
        }
    }

    private static final class CheckpointRowMapper implements RowMapper<CheckpointRow> {
        @Override
        public CheckpointRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckpointRow(
                    rs.getString("race_id"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    rs.getLong("created_at"));
        }
    }

    private static final class CheckpointTimingRowMapper implements RowMapper<CheckpointTimingRow> {
        @Override
        public CheckpointTimingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckpointTimingRow(
                    rs.getString("timing_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    rs.getLong("elapsed_millis"),
                    rs.getLong("created_at"));
        }
    }

    private static final class SnapshotCheckpointRowMapper
            implements RowMapper<SnapshotCheckpointRow> {
        @Override
        public SnapshotCheckpointRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotCheckpointRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    (Long) rs.getObject("elapsed_millis"),
                    rs.getString("timing_id"));
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

    private static final class PursuitPairRowMapper implements RowMapper<PursuitPairRow> {
        @Override
        public PursuitPairRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitPairRow(
                    rs.getString("race_id"),
                    rs.getString("pair_id"),
                    rs.getString("pursuer_bib"),
                    rs.getString("pursued_bib"),
                    rs.getLong("pursuer_start_mm"),
                    rs.getLong("pursued_start_mm"),
                    rs.getLong("created_at"));
        }
    }

    private static final class TrajectoryRowMapper implements RowMapper<TrajectoryRow> {
        @Override
        public TrajectoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TrajectoryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("version"),
                    rs.getInt("sample_count"),
                    rs.getString("operator"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class TrajectorySampleRowMapper implements RowMapper<TrajectorySampleRow> {
        @Override
        public TrajectorySampleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TrajectorySampleRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("seq"),
                    rs.getLong("elapsed_ms"),
                    rs.getLong("progress_mm"));
        }
    }

    private static final class PursuitRulingRowMapper implements RowMapper<PursuitRulingRow> {
        @Override
        public PursuitRulingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PursuitRulingRow(
                    rs.getString("ruling_id"),
                    rs.getString("race_id"),
                    rs.getString("pair_id"),
                    rs.getInt("pursuer_trajectory_version"),
                    rs.getInt("pursued_trajectory_version"),
                    rs.getString("catch_time_num"),
                    rs.getString("catch_time_den"),
                    RulingStatus.valueOf(rs.getString("status")),
                    rs.getString("operator"),
                    rs.getLong("created_at"));
        }
    }

    private static final class SnapshotTrajectoryRowMapper
            implements RowMapper<SnapshotTrajectoryRow> {
        @Override
        public SnapshotTrajectoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotTrajectoryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getInt("trajectory_version"),
                    rs.getInt("seq"),
                    rs.getLong("elapsed_ms"),
                    rs.getLong("progress_mm"));
        }
    }

    private static final class SnapshotRulingRowMapper implements RowMapper<SnapshotRulingRow> {
        @Override
        public SnapshotRulingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotRulingRow(
                    rs.getString("race_id"),
                    rs.getString("pair_id"),
                    rs.getString("ruling_id"),
                    rs.getInt("pursuer_trajectory_version"),
                    rs.getInt("pursued_trajectory_version"),
                    rs.getString("catch_time_num"),
                    rs.getString("catch_time_den"));
        }
    }
}
