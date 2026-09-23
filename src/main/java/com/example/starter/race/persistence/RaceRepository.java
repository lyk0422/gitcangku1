package com.example.starter.race.persistence;

import com.example.starter.race.domain.AppealRecommendation;
import com.example.starter.race.domain.AppealSecondAction;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
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
    private static final AppealRowMapper APPEAL_ROW_MAPPER = new AppealRowMapper();
    private static final AppealSegmentRowMapper APPEAL_SEGMENT_ROW_MAPPER =
            new AppealSegmentRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按ID查询赛事。 */
    public Optional<RaceRow> findRace(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, created_at FROM race WHERE race_id = ?",
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
                .query("SELECT race_id, version, status, created_at FROM race WHERE race_id = ? FOR UPDATE",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部选手，按参赛号字典序排列。 */
    public List<RunnerRow> findRunners(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, bib, finish_time_ms, timing_version, segment_version, "
                        + "created_at, updated_at "
                        + "FROM runner WHERE race_id = ? ORDER BY bib",
                RUNNER_ROW_MAPPER, raceId);
    }

    /** 按赛事与参赛号查询选手。 */
    public Optional<RunnerRow> findRunner(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT id, race_id, bib, finish_time_ms, timing_version, segment_version, "
                                + "created_at, updated_at "
                                + "FROM runner WHERE race_id = ? AND bib = ?",
                        RUNNER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 行锁方式查询选手，持有到事务结束，用于申诉裁决时重读计时与分段版本。 */
    public Optional<RunnerRow> findRunnerForUpdate(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT id, race_id, bib, finish_time_ms, timing_version, segment_version, "
                                + "created_at, updated_at "
                                + "FROM runner WHERE race_id = ? AND bib = ? FOR UPDATE",
                        RUNNER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部处罚（含已撤销与历史版本），按新增时间与处罚ID排列。 */
    public List<PenaltyRow> findPenalties(String raceId) {
        return jdbcTemplate.query(
                "SELECT penalty_id, race_id, bib, type, amount_ms, version, superseded, "
                        + "supersedes_penalty_id, revoked, created_at, revoked_at "
                        + "FROM penalty WHERE race_id = ? ORDER BY created_at, penalty_id",
                PENALTY_ROW_MAPPER, raceId);
    }

    /** 按全局处罚ID查询处罚。 */
    public Optional<PenaltyRow> findPenalty(String penaltyId) {
        return jdbcTemplate
                .query("SELECT penalty_id, race_id, bib, type, amount_ms, version, superseded, "
                                + "supersedes_penalty_id, revoked, created_at, revoked_at "
                                + "FROM penalty WHERE penalty_id = ?",
                        PENALTY_ROW_MAPPER, penaltyId)
                .stream()
                .findFirst();
    }

    /** 行锁方式查询处罚，持有到事务结束，用于申诉裁决时重读处罚版本。 */
    public Optional<PenaltyRow> findPenaltyForUpdate(String penaltyId) {
        return jdbcTemplate
                .query("SELECT penalty_id, race_id, bib, type, amount_ms, version, superseded, "
                                + "supersedes_penalty_id, revoked, created_at, revoked_at "
                                + "FROM penalty WHERE penalty_id = ? FOR UPDATE",
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

    /** 新建赛事，初始版本1、状态OPEN。 */
    public void insertRace(String raceId, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, version, status, created_at) VALUES (?, 1, 'OPEN', ?)",
                raceId, now);
    }

    /** 登记选手；finishTimeMs 为 null 表示计时缺失；计时/分段版本初始均为1。 */
    public void insertRunner(String raceId, String bib, Long finishTimeMs, long now) {
        jdbcTemplate.update(
                "INSERT INTO runner (race_id, bib, finish_time_ms, timing_version, segment_version, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, 1, ?, ?)",
                raceId, bib, finishTimeMs, now, now);
    }

    /**
     * 修订选手原始完赛耗时并把计时版本加一；返回受影响行数（0 表示选手不存在）。
     * finishAt（updated_at）同步更新，申诉30分钟窗口据此判定。
     */
    public int updateRunnerTiming(String raceId, String bib, Long finishTimeMs, long now) {
        return jdbcTemplate.update(
                "UPDATE runner SET finish_time_ms = ?, timing_version = timing_version + 1, "
                        + "updated_at = ? WHERE race_id = ? AND bib = ?",
                finishTimeMs, now, raceId, bib);
    }

    /** 该选手新增一条分段记录后，把分段判定版本加一。 */
    public void incrementSegmentVersion(String raceId, String bib) {
        jdbcTemplate.update(
                "UPDATE runner SET segment_version = segment_version + 1 "
                        + "WHERE race_id = ? AND bib = ?",
                raceId, bib);
    }

    /** 新增处罚（不可覆盖历史）；版本1、未被取代。 */
    public void insertPenalty(
            String penaltyId,
            String raceId,
            String bib,
            PenaltyType type,
            Long amountMs,
            long now) {
        jdbcTemplate.update(
                "INSERT INTO penalty "
                        + "(penalty_id, race_id, bib, type, amount_ms, version, superseded, "
                        + "supersedes_penalty_id, revoked, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, FALSE, NULL, FALSE, ?, NULL)",
                penaltyId, raceId, bib, type.name(), amountMs, now);
    }

    /**
     * REPLACE 裁决：在同一事务内插入新处罚版本（关联旧版本）。
     * 新版本号=旧版本号+1，类型固定 ADD_TIME，替代罚时非负（允许0）。
     */
    public void insertReplacementPenalty(
            String newPenaltyId,
            PenaltyRow oldPenalty,
            long replacementMs,
            int newVersion,
            long now) {
        jdbcTemplate.update(
                "INSERT INTO penalty "
                        + "(penalty_id, race_id, bib, type, amount_ms, version, superseded, "
                        + "supersedes_penalty_id, revoked, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, 'ADD_TIME', ?, ?, FALSE, ?, FALSE, ?, NULL)",
                newPenaltyId, oldPenalty.raceId(), oldPenalty.bib(), replacementMs,
                newVersion, oldPenalty.penaltyId(), now);
    }

    /** 把旧处罚版本标记为已被取代（superseded=TRUE），历史行保留不删除；返回受影响行数。 */
    public int markPenaltySuperseded(String penaltyId) {
        return jdbcTemplate.update(
                "UPDATE penalty SET superseded = TRUE WHERE penalty_id = ? AND superseded = FALSE",
                penaltyId);
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

    private static final String APPEAL_COLUMNS =
            "appeal_key, race_id, bib, penalty_id, status, reason, penalty_version, "
                    + "timing_version, segment_version, frozen_leaderboard_version, "
                    + "frozen_finish_time_ms, frozen_penalty_ms, frozen_total_time_ms, "
                    + "frozen_rank, frozen_entry_status, finish_at_ms, before_leaderboard, "
                    + "after_leaderboard, new_penalty_id, first_steward_id, first_recommendation, "
                    + "first_replacement_ms, first_recorded_at, second_steward_id, second_action, "
                    + "second_recorded_at, created_at, decided_at";

    private static final String APPEAL_SELECT =
            "SELECT " + APPEAL_COLUMNS + " FROM penalty_appeal ";

    /** 按申诉键查询申诉。 */
    public Optional<PenaltyAppealRow> findAppeal(String appealKey) {
        return jdbcTemplate
                .query(APPEAL_SELECT + "WHERE appeal_key = ?", APPEAL_ROW_MAPPER, appealKey)
                .stream()
                .findFirst();
    }

    /** 行锁方式查询申诉，持有到事务结束，用于两人裁决串行化。 */
    public Optional<PenaltyAppealRow> findAppealForUpdate(String appealKey) {
        return jdbcTemplate
                .query(APPEAL_SELECT + "WHERE appeal_key = ? FOR UPDATE",
                        APPEAL_ROW_MAPPER, appealKey)
                .stream()
                .findFirst();
    }

    /** 查询某处罚当前仍在待决（PENDING）的申诉；用于禁止重复申诉、禁止撤销与封榜门禁。 */
    public Optional<PenaltyAppealRow> findPendingAppealForPenalty(String penaltyId) {
        return jdbcTemplate
                .query(APPEAL_SELECT + "WHERE penalty_id = ? AND status = 'PENDING'",
                        APPEAL_ROW_MAPPER, penaltyId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部待决申诉（用于封榜门禁）。 */
    public List<PenaltyAppealRow> findPendingAppealsForRace(String raceId) {
        return jdbcTemplate.query(
                APPEAL_SELECT + "WHERE race_id = ? AND status = 'PENDING' ORDER BY created_at, appeal_key",
                APPEAL_ROW_MAPPER, raceId);
    }

    /** 查询赛事下全部申诉证据，按受理时间与申诉键稳定排序（只读）。 */
    public List<PenaltyAppealRow> findAppealsForRace(String raceId) {
        return jdbcTemplate.query(
                APPEAL_SELECT + "WHERE race_id = ? ORDER BY created_at, appeal_key",
                APPEAL_ROW_MAPPER, raceId);
    }

    /** 受理申诉：写入 PENDING 冻结行（appealKey 全局唯一由数据库约束保证）。 */
    public void insertAppeal(PenaltyAppealRow row) {
        jdbcTemplate.update(
                "INSERT INTO penalty_appeal (" + APPEAL_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?, ?, ?, ?, ?)",
                row.appealKey(), row.raceId(), row.bib(), row.penaltyId(), row.status().name(),
                row.reason(), row.penaltyVersion(), row.timingVersion(), row.segmentVersion(),
                row.frozenLeaderboardVersion(), row.frozenFinishTimeMs(), row.frozenPenaltyMs(),
                row.frozenTotalTimeMs(), row.frozenRank(), row.frozenEntryStatus().name(),
                row.finishAtMs(), row.beforeLeaderboard(), row.afterLeaderboard(),
                row.newPenaltyId(), row.firstStewardId(),
                row.firstRecommendation() == null ? null : row.firstRecommendation().name(),
                row.firstReplacementMs(), row.firstRecordedAt(), row.secondStewardId(),
                row.secondAction() == null ? null : row.secondAction().name(),
                row.secondRecordedAt(), row.createdAt(), row.decidedAt());
    }

    /** 写入受理时冻结的分段判定明细（缺失检查点 elapsed/timing 为 null）。 */
    public void insertAppealSegments(List<PenaltyAppealSegmentRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO penalty_appeal_segment "
                        + "(appeal_key, bib, checkpoint_code, position, elapsed_millis, timing_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, segment) -> {
                    ps.setString(1, segment.appealKey());
                    ps.setString(2, segment.bib());
                    ps.setString(3, segment.checkpointCode());
                    ps.setInt(4, segment.position());
                    ps.setObject(5, segment.elapsedMillis());
                    ps.setString(6, segment.timingId());
                });
    }

    /** 查询申诉冻结的分段判定，按检查点顺序稳定返回。 */
    public List<PenaltyAppealSegmentRow> findAppealSegments(String appealKey) {
        return jdbcTemplate.query(
                "SELECT appeal_key, bib, checkpoint_code, position, elapsed_millis, timing_id "
                        + "FROM penalty_appeal_segment WHERE appeal_key = ? ORDER BY position",
                APPEAL_SEGMENT_ROW_MAPPER, appealKey);
    }

    /** 第一人提交建议后写回第一干事意见（申诉仍 PENDING）。 */
    public void updateAppealFirstOpinion(
            String appealKey,
            String stewardId,
            com.example.starter.race.domain.AppealRecommendation recommendation,
            Long replacementMs,
            long recordedAt) {
        jdbcTemplate.update(
                "UPDATE penalty_appeal SET first_steward_id = ?, first_recommendation = ?, "
                        + "first_replacement_ms = ?, first_recorded_at = ? WHERE appeal_key = ?",
                stewardId, recommendation.name(), replacementMs, recordedAt, appealKey);
    }

    /** 第二人裁决终态：写回第二干事意见、终态、新处罚与重算后榜单快照。 */
    public void completeAppealDecision(
            String appealKey,
            String secondStewardId,
            com.example.starter.race.domain.AppealSecondAction action,
            com.example.starter.race.domain.AppealStatus finalStatus,
            String newPenaltyId,
            String afterLeaderboard,
            long recordedAt) {
        jdbcTemplate.update(
                "UPDATE penalty_appeal SET second_steward_id = ?, second_action = ?, "
                        + "second_recorded_at = ?, status = ?, new_penalty_id = ?, "
                        + "after_leaderboard = ?, decided_at = ? WHERE appeal_key = ?",
                secondStewardId, action.name(), recordedAt, finalStatus.name(), newPenaltyId,
                afterLeaderboard, recordedAt, appealKey);
    }

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM penalty_appeal_segment");
        jdbcTemplate.update("DELETE FROM penalty_appeal");
        jdbcTemplate.update("DELETE FROM result_snapshot_checkpoint");
        jdbcTemplate.update("DELETE FROM result_snapshot_entry");
        jdbcTemplate.update("DELETE FROM result_snapshot");
        jdbcTemplate.update("DELETE FROM idempotency_record");
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
                    rs.getInt("timing_version"),
                    rs.getInt("segment_version"),
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
                    rs.getInt("version"),
                    rs.getBoolean("superseded"),
                    rs.getString("supersedes_penalty_id"),
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

    private static final class AppealRowMapper implements RowMapper<PenaltyAppealRow> {
        @Override
        public PenaltyAppealRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            String firstRecommendation = rs.getString("first_recommendation");
            String secondAction = rs.getString("second_action");
            return new PenaltyAppealRow(
                    rs.getString("appeal_key"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("penalty_id"),
                    AppealStatus.valueOf(rs.getString("status")),
                    rs.getString("reason"),
                    rs.getInt("penalty_version"),
                    rs.getInt("timing_version"),
                    rs.getInt("segment_version"),
                    rs.getInt("frozen_leaderboard_version"),
                    (Long) rs.getObject("frozen_finish_time_ms"),
                    rs.getLong("frozen_penalty_ms"),
                    (Long) rs.getObject("frozen_total_time_ms"),
                    (Integer) rs.getObject("frozen_rank"),
                    EntryStatus.valueOf(rs.getString("frozen_entry_status")),
                    rs.getLong("finish_at_ms"),
                    rs.getString("before_leaderboard"),
                    rs.getString("after_leaderboard"),
                    rs.getString("new_penalty_id"),
                    rs.getString("first_steward_id"),
                    firstRecommendation == null
                            ? null : AppealRecommendation.valueOf(firstRecommendation),
                    (Long) rs.getObject("first_replacement_ms"),
                    (Long) rs.getObject("first_recorded_at"),
                    rs.getString("second_steward_id"),
                    secondAction == null ? null : AppealSecondAction.valueOf(secondAction),
                    (Long) rs.getObject("second_recorded_at"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("decided_at"));
        }
    }

    private static final class AppealSegmentRowMapper implements RowMapper<PenaltyAppealSegmentRow> {
        @Override
        public PenaltyAppealSegmentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PenaltyAppealSegmentRow(
                    rs.getString("appeal_key"),
                    rs.getString("bib"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    (Long) rs.getObject("elapsed_millis"),
                    rs.getString("timing_id"));
        }
    }
}
