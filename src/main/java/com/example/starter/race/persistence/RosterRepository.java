package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 团队名单锁定领域的 JDBC 仓储；所有写方法均假定运行在业务事务内。
 *
 * <p>名单版本、成员归属、退赛登记与封榜团队快照全部使用参数绑定 SQL；
 * 跨队唯一性由 roster_membership 的 (race_id,bib) 唯一约束兜底。
 */
@Repository
public class RosterRepository {

    private static final TeamRowMapper TEAM_ROW_MAPPER = new TeamRowMapper();
    private static final RosterLockRowMapper ROSTER_LOCK_ROW_MAPPER = new RosterLockRowMapper();
    private static final RosterLockEntryRowMapper ROSTER_LOCK_ENTRY_ROW_MAPPER =
            new RosterLockEntryRowMapper();
    private static final RosterMembershipRowMapper ROSTER_MEMBERSHIP_ROW_MAPPER =
            new RosterMembershipRowMapper();
    private static final WithdrawalRowMapper WITHDRAWAL_ROW_MAPPER = new WithdrawalRowMapper();
    private static final SnapshotTeamRowMapper SNAPSHOT_TEAM_ROW_MAPPER =
            new SnapshotTeamRowMapper();
    private static final SnapshotTeamMemberRowMapper SNAPSHOT_TEAM_MEMBER_ROW_MAPPER =
            new SnapshotTeamMemberRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RosterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询赛事内的团队，按团队ID字典序排列。 */
    public List<TeamRow> findTeams(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_id, created_at FROM team WHERE race_id = ? ORDER BY team_id",
                TEAM_ROW_MAPPER, raceId);
    }

    /** 按赛事与团队ID查询团队。 */
    public Optional<TeamRow> findTeam(String raceId, String teamId) {
        return jdbcTemplate
                .query("SELECT id, race_id, team_id, created_at FROM team "
                                + "WHERE race_id = ? AND team_id = ?",
                        TEAM_ROW_MAPPER, raceId, teamId)
                .stream()
                .findFirst();
    }

    /** 查询团队全部锁定版本，按版本号升序。 */
    public List<RosterLockRow> findRosterLocks(String raceId, String teamId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, race_version, locked_at "
                        + "FROM roster_lock WHERE race_id = ? AND team_id = ? ORDER BY roster_version",
                ROSTER_LOCK_ROW_MAPPER, raceId, teamId);
    }

    /** 查询团队最新（版本号最大）锁定版本；从未锁定返回 empty。 */
    public Optional<RosterLockRow> findLatestRosterLock(String raceId, String teamId) {
        return jdbcTemplate
                .query("SELECT race_id, team_id, roster_version, race_version, locked_at "
                                + "FROM roster_lock WHERE race_id = ? AND team_id = ? "
                                + "ORDER BY roster_version DESC LIMIT 1",
                        ROSTER_LOCK_ROW_MAPPER, raceId, teamId)
                .stream()
                .findFirst();
    }

    /** 查询指定团队的指定锁定版本头。 */
    public Optional<RosterLockRow> findRosterLock(String raceId, String teamId, int rosterVersion) {
        return jdbcTemplate
                .query("SELECT race_id, team_id, roster_version, race_version, locked_at "
                                + "FROM roster_lock WHERE race_id = ? AND team_id = ? AND roster_version = ?",
                        ROSTER_LOCK_ROW_MAPPER, raceId, teamId, rosterVersion)
                .stream()
                .findFirst();
    }

    /** 查询某团队某版本的全部固化成员，按参赛号字典序排列。 */
    public List<RosterLockEntryRow> findRosterEntries(String raceId, String teamId, int rosterVersion) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, bib, entry_status, finish_time_ms, "
                        + "penalty_ms, total_time_ms, checkpoint_count, covered_checkpoint_count "
                        + "FROM roster_lock_entry "
                        + "WHERE race_id = ? AND team_id = ? AND roster_version = ? ORDER BY bib",
                ROSTER_LOCK_ENTRY_ROW_MAPPER, raceId, teamId, rosterVersion);
    }

    /** 查询赛事内各团队最新版本的全部固化成员（实时团队计分用），按团队ID、参赛号排列。 */
    public List<RosterLockEntryRow> findLatestRosterEntries(String raceId) {
        return jdbcTemplate.query(
                "SELECT e.race_id, e.team_id, e.roster_version, e.bib, e.entry_status, "
                        + "e.finish_time_ms, e.penalty_ms, e.total_time_ms, "
                        + "e.checkpoint_count, e.covered_checkpoint_count "
                        + "FROM roster_lock_entry e "
                        + "JOIN (SELECT race_id, team_id, MAX(roster_version) AS max_version "
                        + "      FROM roster_lock WHERE race_id = ? GROUP BY race_id, team_id) m "
                        + "ON e.race_id = m.race_id AND e.team_id = m.team_id "
                        + "AND e.roster_version = m.max_version "
                        + "ORDER BY e.team_id, e.bib",
                ROSTER_LOCK_ENTRY_ROW_MAPPER, raceId);
    }

    /** 查询赛事内全部成员当前归属，按参赛号排列。 */
    public List<RosterMembershipRow> findMemberships(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, team_id, roster_version, active "
                        + "FROM roster_membership WHERE race_id = ? ORDER BY bib",
                ROSTER_MEMBERSHIP_ROW_MAPPER, raceId);
    }

    /** 查询单个成员的当前归属。 */
    public Optional<RosterMembershipRow> findMembership(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, bib, team_id, roster_version, active "
                                + "FROM roster_membership WHERE race_id = ? AND bib = ?",
                        ROSTER_MEMBERSHIP_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事内全部退赛登记，按登记时间与参赛号排列。 */
    public List<WithdrawalRow> findWithdrawals(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, bib, team_id, effective_session, reason, withdrawn_at "
                        + "FROM roster_withdrawal WHERE race_id = ? "
                        + "ORDER BY withdrawn_at, bib",
                WITHDRAWAL_ROW_MAPPER, raceId);
    }

    /** 查询单个成员的退赛登记。 */
    public Optional<WithdrawalRow> findWithdrawal(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, bib, team_id, effective_session, reason, withdrawn_at "
                                + "FROM roster_withdrawal WHERE race_id = ? AND bib = ?",
                        WITHDRAWAL_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 新建团队主数据（随首次名单锁定创建）。 */
    public void insertTeam(TeamRow team) {
        jdbcTemplate.update(
                "INSERT INTO team (race_id, team_id, created_at) VALUES (?, ?, ?)",
                team.raceId(), team.teamId(), team.createdAt());
    }

    /** 插入一个不可变的名单锁定版本头。 */
    public void insertRosterLock(RosterLockRow lock) {
        jdbcTemplate.update(
                "INSERT INTO roster_lock (race_id, team_id, roster_version, race_version, locked_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                lock.raceId(), lock.teamId(), lock.rosterVersion(),
                lock.raceVersion(), lock.lockedAt());
    }

    /** 批量插入名单版本固化成员及其锁定时刻个人成绩快照。 */
    public void insertRosterEntries(List<RosterLockEntryRow> entries) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO roster_lock_entry "
                        + "(race_id, team_id, roster_version, bib, entry_status, finish_time_ms, "
                        + "penalty_ms, total_time_ms, checkpoint_count, covered_checkpoint_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entries,
                entries.size(),
                (ps, entry) -> {
                    ps.setString(1, entry.raceId());
                    ps.setString(2, entry.teamId());
                    ps.setInt(3, entry.rosterVersion());
                    ps.setString(4, entry.bib());
                    ps.setString(5, entry.entryStatus().name());
                    ps.setObject(6, entry.finishTimeMs());
                    ps.setLong(7, entry.penaltyMs());
                    ps.setObject(8, entry.totalTimeMs());
                    ps.setInt(9, entry.checkpointCount());
                    ps.setInt(10, entry.coveredCheckpointCount());
                });
    }

    /** 插入成员当前归属（唯一约束 race_id+bib 由数据库保证）。 */
    public void insertMembership(RosterMembershipRow membership) {
        jdbcTemplate.update(
                "INSERT INTO roster_membership (race_id, bib, team_id, roster_version, active) "
                        + "VALUES (?, ?, ?, ?, ?)",
                membership.raceId(), membership.bib(), membership.teamId(),
                membership.rosterVersion(), membership.active());
    }

    /**
     * 登记退赛：插入只追加的退赛记录并把归属置为不活跃，同一成员只能退赛一次。
     *
     * @return 被置为不活跃的归属行数（0 表示重复退赛或归属缺失，调用方应先行校验）
     */
    public int insertWithdrawal(WithdrawalRow withdrawal) {
        jdbcTemplate.update(
                "INSERT INTO roster_withdrawal "
                        + "(race_id, bib, team_id, effective_session, reason, withdrawn_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                withdrawal.raceId(), withdrawal.bib(), withdrawal.teamId(),
                withdrawal.effectiveSession(), withdrawal.reason(), withdrawal.withdrawnAt());
        return jdbcTemplate.update(
                "UPDATE roster_membership SET active = FALSE WHERE race_id = ? AND bib = ? AND active = TRUE",
                withdrawal.raceId(), withdrawal.bib());
    }

    /** 查询封榜团队快照头，按展示顺序排列；未封榜或封榜时无团队均可能为空列表。 */
    public List<SnapshotTeamRow> findTeamSnapshotRows(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_id, roster_version, total_time_ms, member_count, "
                        + "scoring_count, display_order "
                        + "FROM result_snapshot_team WHERE race_id = ? ORDER BY display_order",
                SNAPSHOT_TEAM_ROW_MAPPER, raceId);
    }

    /** 查询封榜团队快照的全部成员明细，按团队展示顺序与参赛号排列。 */
    public List<SnapshotTeamMemberRow> findTeamSnapshotMembers(String raceId) {
        return jdbcTemplate.query(
                "SELECT m.race_id, m.team_id, m.bib, m.entry_status, m.finish_time_ms, "
                        + "m.penalty_ms, m.total_time_ms, m.scoring, m.withdrawn, m.effective_session "
                        + "FROM result_snapshot_team_member m "
                        + "JOIN result_snapshot_team t ON m.race_id = t.race_id AND m.team_id = t.team_id "
                        + "WHERE m.race_id = ? ORDER BY t.display_order, m.bib",
                SNAPSHOT_TEAM_MEMBER_ROW_MAPPER, raceId);
    }

    /** 封榜事务内原子写入全部团队头与成员明细。 */
    public void insertTeamSnapshot(List<SnapshotTeamRow> teams, List<SnapshotTeamMemberRow> members) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_team "
                        + "(race_id, team_id, roster_version, total_time_ms, member_count, "
                        + "scoring_count, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                teams,
                Math.max(teams.size(), 1),
                (ps, team) -> {
                    ps.setString(1, team.raceId());
                    ps.setString(2, team.teamId());
                    ps.setInt(3, team.rosterVersion());
                    ps.setLong(4, team.totalTimeMs());
                    ps.setInt(5, team.memberCount());
                    ps.setInt(6, team.scoringCount());
                    ps.setInt(7, team.displayOrder());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_team_member "
                        + "(race_id, team_id, bib, entry_status, finish_time_ms, penalty_ms, "
                        + "total_time_ms, scoring, withdrawn, effective_session) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                members,
                Math.max(members.size(), 1),
                (ps, member) -> {
                    ps.setString(1, member.raceId());
                    ps.setString(2, member.teamId());
                    ps.setString(3, member.bib());
                    ps.setString(4, member.entryStatus().name());
                    ps.setObject(5, member.finishTimeMs());
                    ps.setLong(6, member.penaltyMs());
                    ps.setObject(7, member.totalTimeMs());
                    ps.setBoolean(8, member.scoring());
                    ps.setBoolean(9, member.withdrawn());
                    ps.setObject(10, member.effectiveSession());
                });
    }

    /** 测试辅助：按外键依赖顺序清空团队名单相关数据。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM result_snapshot_team_member");
        jdbcTemplate.update("DELETE FROM result_snapshot_team");
        jdbcTemplate.update("DELETE FROM roster_withdrawal");
        jdbcTemplate.update("DELETE FROM roster_membership");
        jdbcTemplate.update("DELETE FROM roster_lock_entry");
        jdbcTemplate.update("DELETE FROM roster_lock");
        jdbcTemplate.update("DELETE FROM team");
    }

    private static final class TeamRowMapper implements RowMapper<TeamRow> {
        @Override
        public TeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TeamRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getLong("created_at"));
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
                    rs.getLong("locked_at"));
        }
    }

    private static final class RosterLockEntryRowMapper implements RowMapper<RosterLockEntryRow> {
        @Override
        public RosterLockEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RosterLockEntryRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getString("bib"),
                    EntryStatus.valueOf(rs.getString("entry_status")),
                    (Long) rs.getObject("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getInt("checkpoint_count"),
                    rs.getInt("covered_checkpoint_count"));
        }
    }

    private static final class RosterMembershipRowMapper implements RowMapper<RosterMembershipRow> {
        @Override
        public RosterMembershipRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RosterMembershipRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getBoolean("active"));
        }
    }

    private static final class WithdrawalRowMapper implements RowMapper<WithdrawalRow> {
        @Override
        public WithdrawalRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WithdrawalRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("team_id"),
                    rs.getInt("effective_session"),
                    rs.getString("reason"),
                    rs.getLong("withdrawn_at"));
        }
    }

    private static final class SnapshotTeamRowMapper implements RowMapper<SnapshotTeamRow> {
        @Override
        public SnapshotTeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotTeamRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getInt("roster_version"),
                    rs.getLong("total_time_ms"),
                    rs.getInt("member_count"),
                    rs.getInt("scoring_count"),
                    rs.getInt("display_order"));
        }
    }

    private static final class SnapshotTeamMemberRowMapper implements RowMapper<SnapshotTeamMemberRow> {
        @Override
        public SnapshotTeamMemberRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotTeamMemberRow(
                    rs.getString("race_id"),
                    rs.getString("team_id"),
                    rs.getString("bib"),
                    EntryStatus.valueOf(rs.getString("entry_status")),
                    (Long) rs.getObject("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getBoolean("scoring"),
                    rs.getBoolean("withdrawn"),
                    (Integer) rs.getObject("effective_session"));
        }
    }
}
