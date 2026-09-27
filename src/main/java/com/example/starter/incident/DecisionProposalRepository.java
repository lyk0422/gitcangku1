package com.example.starter.incident;

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
 * 决策提案、所需角色集合与投票记录的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内，投票/撤回/修订/到期判定按事务提交顺序生效。
 * 投票记录的 role/vote/taskVersion/castAt 落库后不改写，仅 status/updatedAt 随生命周期更新。
 */
@Repository
public class DecisionProposalRepository {

    private final JdbcTemplate jdbc;

    public DecisionProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DecisionProposal> PROPOSAL_MAPPER = (rs, n) -> mapProposal(rs);

    private static final RowMapper<ProposalVote> VOTE_MAPPER = (rs, n) -> mapVote(rs);

    private static DecisionProposal mapProposal(ResultSet rs) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        int decidedTaskVersion = rs.getInt("decided_task_version");
        boolean decidedTaskVersionNull = rs.wasNull();
        return new DecisionProposal(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("proposal_key"),
                rs.getLong("task_id"), rs.getInt("task_version"),
                rs.getString("proposed_title"), rs.getInt("quorum"),
                rs.getTimestamp("expires_at").toInstant(),
                ProposalStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(),
                decidedTaskVersionNull ? null : decidedTaskVersion);
    }

    private static ProposalVote mapVote(ResultSet rs) throws SQLException {
        return new ProposalVote(
                rs.getLong("id"), rs.getLong("proposal_id"), rs.getString("role"),
                VoteChoice.valueOf(rs.getString("vote")),
                rs.getInt("task_version"),
                VoteStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("cast_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 OPEN 提案，返回生成主键。(incident_id, proposal_key) 唯一约束兜底并发重复插入。
     */
    public long insert(DecisionProposal proposal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO decision_proposals (incident_id, proposal_key, task_id,"
                            + " task_version, proposed_title, quorum, expires_at, status,"
                            + " created_by, created_at, decided_at, decided_task_version)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, proposal.incidentId());
            ps.setString(2, proposal.proposalKey());
            ps.setLong(3, proposal.taskId());
            ps.setInt(4, proposal.taskVersion());
            ps.setString(5, proposal.proposedTitle());
            ps.setInt(6, proposal.quorum());
            ps.setTimestamp(7, Timestamp.from(proposal.expiresAt()));
            ps.setString(8, proposal.status().name());
            ps.setString(9, proposal.createdBy());
            ps.setTimestamp(10, Timestamp.from(proposal.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 登记提案所需角色（提案内唯一）。
     */
    public void insertRole(long proposalId, String role) {
        jdbc.update("INSERT INTO decision_proposal_roles (proposal_id, role) VALUES (?,?)",
                proposalId, role);
    }

    /**
     * 按事件与 proposalKey 查询提案。
     */
    public Optional<DecisionProposal> findByKey(long incidentId, String proposalKey) {
        List<DecisionProposal> rows = jdbc.query(
                "SELECT * FROM decision_proposals WHERE incident_id = ? AND proposal_key = ?",
                PROPOSAL_MAPPER, incidentId, proposalKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部提案，按登记顺序返回。
     */
    public List<DecisionProposal> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM decision_proposals WHERE incident_id = ? ORDER BY id",
                PROPOSAL_MAPPER, incidentId);
    }

    /**
     * 查询任务下仍 OPEN 的提案（任务修订时做到期判定与 STALE 转移用），按登记顺序返回。
     */
    public List<DecisionProposal> listOpenByTask(long taskId) {
        return jdbc.query("SELECT * FROM decision_proposals WHERE task_id = ? AND status = 'OPEN'"
                + " ORDER BY id", PROPOSAL_MAPPER, taskId);
    }

    /**
     * 查询提案所需角色集合，按字典序返回（稳定排序）。
     */
    public List<String> listRoles(long proposalId) {
        return jdbc.queryForList("SELECT role FROM decision_proposal_roles WHERE proposal_id = ?"
                + " ORDER BY role", String.class, proposalId);
    }

    /**
     * 裁决提案：置为 EFFECTIVE/EXPIRED，固化裁决 UTC 时刻与当时任务版本，终态不再改写。
     */
    public void markDecided(long proposalId, ProposalStatus status, Instant decidedAt,
                            int decidedTaskVersion) {
        jdbc.update("UPDATE decision_proposals SET status = ?, decided_at = ?,"
                        + " decided_task_version = ? WHERE id = ?",
                status.name(), Timestamp.from(decidedAt), decidedTaskVersion, proposalId);
    }

    /**
     * 追加一条 CAST 投票记录，固化投票时的任务版本与 UTC 时刻。
     */
    public long insertVote(ProposalVote vote) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO decision_proposal_votes (proposal_id, role, vote, task_version,"
                            + " status, cast_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vote.proposalId());
            ps.setString(2, vote.role());
            ps.setString(3, vote.vote().name());
            ps.setInt(4, vote.taskVersion());
            ps.setString(5, vote.status().name());
            ps.setTimestamp(6, Timestamp.from(vote.castAt()));
            ps.setTimestamp(7, Timestamp.from(vote.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询提案全部投票记录（含 WITHDRAWN/STALE 历史），按投票先后返回。
     */
    public List<ProposalVote> listVotes(long proposalId) {
        return jdbc.query("SELECT * FROM decision_proposal_votes WHERE proposal_id = ?"
                + " ORDER BY id", VOTE_MAPPER, proposalId);
    }

    /**
     * 查询角色在提案上的当前有效票（CAST）。同一角色同一提案至多一票 CAST，
     * 由事件行锁串行化保证。
     */
    public Optional<ProposalVote> findActiveVote(long proposalId, String role) {
        List<ProposalVote> rows = jdbc.query(
                "SELECT * FROM decision_proposal_votes WHERE proposal_id = ? AND role = ?"
                        + " AND status = 'CAST'",
                VOTE_MAPPER, proposalId, role);
        return rows.stream().findFirst();
    }

    /**
     * 统计提案当前有效票（CAST）中指定取向的票数。
     */
    public long countActiveByChoice(long proposalId, VoteChoice choice) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_proposal_votes WHERE proposal_id = ?"
                        + " AND vote = ? AND status = 'CAST'",
                Long.class, proposalId, choice.name());
        return count == null ? 0 : count;
    }

    /**
     * 撤回有效票：CAST → WITHDRAWN，记录状态变更 UTC 时刻。
     */
    public void withdrawVote(long voteId, Instant at) {
        jdbc.update("UPDATE decision_proposal_votes SET status = 'WITHDRAWN', updated_at = ?"
                + " WHERE id = ?", Timestamp.from(at), voteId);
    }

    /**
     * 任务修订后，将该任务所有 OPEN 提案的 CAST 票转为 STALE（不计入新版本）。
     * 已裁决（EFFECTIVE/EXPIRED）提案的投票历史不改写。
     */
    public void staleVotesForTask(long taskId, Instant at) {
        jdbc.update("UPDATE decision_proposal_votes SET status = 'STALE', updated_at = ?"
                        + " WHERE status = 'CAST' AND proposal_id IN"
                        + " (SELECT id FROM decision_proposals WHERE task_id = ? AND status = 'OPEN')",
                Timestamp.from(at), taskId);
    }
}
