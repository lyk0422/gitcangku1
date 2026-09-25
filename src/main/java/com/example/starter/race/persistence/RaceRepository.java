package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamStatus;
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
    private static final TeamRowMapper TEAM_ROW_MAPPER = new TeamRowMapper();
    private static final TeamMemberRowMapper TEAM_MEMBER_ROW_MAPPER = new TeamMemberRowMapper();
    private static final RosterLockRowMapper ROSTER_LOCK_ROW_MAPPER = new RosterLockRowMapper();
    private static final TeamStandingRowMapper TEAM_STANDING_ROW_MAPPER =
            new TeamStandingRowMapper();
    private static final SnapshotTeamRowMapper SNAPSHOT_TEAM_ROW_MAPPER =
            new SnapshotTeamRowMapper();

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

    /** 新建赛事，初始版本1、状态OPEN。 */
    public void insertRace(String raceId, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, version, status, created_at) VALUES (?, 1, 'OPEN', ?)",
                raceId, now);
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

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM result_snapshot_team");
        jdbcTemplate.update("DELETE FROM team_standing");
        jdbcTemplate.update("DELETE FROM roster_lock_member");
        jdbcTemplate.update("DELETE FROM roster_lock");
        jdbcTemplate.update("DELETE FROM team_member");
        jdbcTemplate.update("DELETE FROM team");
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

    /** 按赛事与队伍ID查询队伍。 */
    public Optional<TeamRow> findTeam(String raceId, String teamId) {
        return jdbcTemplate
                .query("SELECT race_id, team_id, captain_bib, status, roster_version, created_at, updated_at "
                                + "FROM team WHERE race_id = ? AND team_id = ?",
                        TEAM_ROW_MAPPER, raceId, teamId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下当前处于锁定状态的队伍，按队伍ID字典序排列。 */
    public List<TeamRow> findLockedTeams(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, captain_bib, status, roster_version, created_at, updated_at "
                        + "FROM team WHERE race_id = ? AND status = 'LOCKED' ORDER BY team_id",
                TEAM_ROW_MAPPER, raceId);
    }

    /** 新建队伍，初始名单状态 OPEN、名单版本0。 */
    public void insertTeam(String raceId, String teamId, String captainBib, long now) {
        jdbcTemplate.update(
                "INSERT INTO team (race_id, team_id, captain_bib, status, roster_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, ?, ?)",
                raceId, teamId, captainBib, now, now);
    }

    /** 更新队伍名单状态与名单版本（锁定/解锁时调用）。 */
    public void updateTeamRosterState(String raceId, String teamId, TeamStatus status,
                                      int rosterVersion, long now) {
        jdbcTemplate.update(
                "UPDATE team SET status = ?, roster_version = ?, updated_at = ? "
                        + "WHERE race_id = ? AND team_id = ?",
                status.name(), rosterVersion, now, raceId, teamId);
    }

    /** 仅更新队伍名单变更时间（增删成员时调用）。 */
    public void touchTeam(String raceId, String teamId, long now) {
        jdbcTemplate.update(
                "UPDATE team SET updated_at = ? WHERE race_id = ? AND team_id = ?",
                now, raceId, teamId);
    }

    /** 查询队伍当前名单成员，按参赛号字典序排列。 */
    public List<TeamMemberRow> findTeamMembers(String raceId, String teamId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, bib, added_at FROM team_member "
                        + "WHERE race_id = ? AND team_id = ? ORDER BY bib",
                TEAM_MEMBER_ROW_MAPPER, raceId, teamId);
    }

    /** 查询参赛者在赛事内的当前队伍归属（每名参赛者最多一支队伍）。 */
    public Optional<TeamMemberRow> findMembership(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, team_id, bib, added_at FROM team_member "
                                + "WHERE race_id = ? AND bib = ?",
                        TEAM_MEMBER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 新增队伍成员；同一赛事内成员唯一由 uk_team_member_race_bib 保证。 */
    public void insertTeamMember(String raceId, String teamId, String bib, long now) {
        jdbcTemplate.update(
                "INSERT INTO team_member (race_id, team_id, bib, added_at) VALUES (?, ?, ?, ?)",
                raceId, teamId, bib, now);
    }

    /** 移除队伍成员；返回受影响行数（0 表示该成员不在队伍中）。 */
    public int deleteTeamMember(String raceId, String teamId, String bib) {
        return jdbcTemplate.update(
                "DELETE FROM team_member WHERE race_id = ? AND team_id = ? AND bib = ?",
                raceId, teamId, bib);
    }

    /** 清空队伍当前名单（锁定时以锁定名单整体重建）。 */
    public void deleteTeamMembers(String raceId, String teamId) {
        jdbcTemplate.update(
                "DELETE FROM team_member WHERE race_id = ? AND team_id = ?",
                raceId, teamId);
    }

    /** 写入名单锁定快照头（历史版本永久保留，解锁仅置标记）。 */
    public void insertRosterLock(RosterLockRow row) {
        jdbcTemplate.update(
                "INSERT INTO roster_lock "
                        + "(race_id, team_id, roster_version, race_version, locked_by, locked_at, "
                        + "unlocked, unlock_reason, unlocked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, FALSE, NULL, NULL)",
                row.raceId(), row.teamId(), row.rosterVersion(), row.raceVersion(),
                row.lockedBy(), row.lockedAt());
    }

    /** 查询队伍的全部名单锁定版本，按版本升序排列。 */
    public List<RosterLockRow> findRosterLocks(String raceId, String teamId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, race_version, locked_by, locked_at, "
                        + "unlocked, unlock_reason, unlocked_at FROM roster_lock "
                        + "WHERE race_id = ? AND team_id = ? ORDER BY roster_version",
                ROSTER_LOCK_ROW_MAPPER, raceId, teamId);
    }

    /** 裁判解锁：仅对当前生效（未解锁）的指定版本置解锁标记；返回受影响行数。 */
    public int markRosterLockUnlocked(String raceId, String teamId, int rosterVersion,
                                      String reason, long now) {
        return jdbcTemplate.update(
                "UPDATE roster_lock SET unlocked = TRUE, unlock_reason = ?, unlocked_at = ? "
                        + "WHERE race_id = ? AND team_id = ? AND roster_version = ? AND unlocked = FALSE",
                reason, now, raceId, teamId, rosterVersion);
    }

    /** 批量写入某次锁定的名单成员快照。 */
    public void insertRosterLockMembers(String raceId, String teamId, int rosterVersion,
                                        List<String> bibs) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO roster_lock_member (race_id, team_id, roster_version, bib) "
                        + "VALUES (?, ?, ?, ?)",
                bibs,
                bibs.size(),
                (ps, bib) -> {
                    ps.setString(1, raceId);
                    ps.setString(2, teamId);
                    ps.setInt(3, rosterVersion);
                    ps.setString(4, bib);
                });
    }

    /** 查询某次锁定快照的成员名单，按参赛号字典序排列。 */
    public List<String> findRosterLockMembers(String raceId, String teamId, int rosterVersion) {
        return jdbcTemplate.queryForList(
                "SELECT bib FROM roster_lock_member "
                        + "WHERE race_id = ? AND team_id = ? AND roster_version = ? ORDER BY bib",
                String.class, raceId, teamId, rosterVersion);
    }

    /** 覆盖写入队伍当前团队得分（按主键存在与否选择更新或插入）。 */
    public void upsertTeamStanding(TeamStandingRow row) {
        int updated = jdbcTemplate.update(
                "UPDATE team_standing SET roster_version = ?, race_version = ?, member_count = ?, "
                        + "ranked_count = ?, total_time_ms = ?, computed_at = ? "
                        + "WHERE race_id = ? AND team_id = ?",
                row.rosterVersion(), row.raceVersion(), row.memberCount(),
                row.rankedCount(), row.totalTimeMs(), row.computedAt(),
                row.raceId(), row.teamId());
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO team_standing "
                            + "(race_id, team_id, roster_version, race_version, member_count, "
                            + "ranked_count, total_time_ms, computed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    row.raceId(), row.teamId(), row.rosterVersion(), row.raceVersion(),
                    row.memberCount(), row.rankedCount(), row.totalTimeMs(), row.computedAt());
        }
    }

    /** 删除队伍当前团队得分（解锁后队伍不再参与重算）。 */
    public void deleteTeamStanding(String raceId, String teamId) {
        jdbcTemplate.update(
                "DELETE FROM team_standing WHERE race_id = ? AND team_id = ?",
                raceId, teamId);
    }

    /** 查询赛事下全部当前团队得分，按队伍ID字典序排列。 */
    public List<TeamStandingRow> findTeamStandings(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, race_version, member_count, "
                        + "ranked_count, total_time_ms, computed_at FROM team_standing "
                        + "WHERE race_id = ? ORDER BY team_id",
                TEAM_STANDING_ROW_MAPPER, raceId);
    }

    /** 封榜时固化全部锁定队伍的团队成绩快照。 */
    public void insertSnapshotTeams(List<SnapshotTeamRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_team "
                        + "(race_id, team_id, roster_version, race_version, member_count, "
                        + "ranked_count, total_time_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.teamId());
                    ps.setInt(3, row.rosterVersion());
                    ps.setInt(4, row.raceVersion());
                    ps.setInt(5, row.memberCount());
                    ps.setInt(6, row.rankedCount());
                    ps.setObject(7, row.totalTimeMs());
                });
    }

    /** 查询封榜固化的团队成绩快照，按队伍ID字典序排列；未封榜为空列表。 */
    public List<SnapshotTeamRow> findSnapshotTeams(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, race_version, member_count, "
                        + "ranked_count, total_time_ms FROM result_snapshot_team "
                        + "WHERE race_id = ? ORDER BY team_id",
                SNAPSHOT_TEAM_ROW_MAPPER, raceId);
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

    private static final class TeamRowMapper implements RowMapper<TeamRow> {
        @Override
        public TeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TeamRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getString("captain_bib"),
                    TeamStatus.valueOf(rs.getString("status")),
                    rs.getInt("roster_version"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class TeamMemberRowMapper implements RowMapper<TeamMemberRow> {
        @Override
        public TeamMemberRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TeamMemberRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getString("bib"),
                    rs.getLong("added_at"));
        }
    }

    private static final class RosterLockRowMapper implements RowMapper<RosterLockRow> {
        @Override
        public RosterLockRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RosterLockRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getInt("race_version"),
                    rs.getString("locked_by"),
                    rs.getLong("locked_at"),
                    rs.getBoolean("unlocked"),
                    rs.getString("unlock_reason"),
                    (Long) rs.getObject("unlocked_at"));
        }
    }

    private static final class TeamStandingRowMapper implements RowMapper<TeamStandingRow> {
        @Override
        public TeamStandingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TeamStandingRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getInt("race_version"),
                    rs.getInt("member_count"),
                    rs.getInt("ranked_count"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getLong("computed_at"));
        }
    }

    private static final class SnapshotTeamRowMapper implements RowMapper<SnapshotTeamRow> {
        @Override
        public SnapshotTeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotTeamRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getInt("race_version"),
                    rs.getInt("member_count"),
                    rs.getInt("ranked_count"),
                    (Long) rs.getObject("total_time_ms"));
        }
    }
}
