package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 处置提案、所需角色与投票的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；提案、投票的并发裁决由
 * 事件行锁与 uk_decision_active_role 部分唯一索引共同保证。
 */
@Repository
public class DecisionRepository {

    private final JdbcTemplate jdbc;

    public DecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DecisionProposal> PROPOSAL_MAPPER = (rs, n) -> mapProposal(rs);
    private static final RowMapper<DecisionVote> VOTE_MAPPER = (rs, n) -> mapVote(rs);

    private static DecisionProposal mapProposal(ResultSet rs) throws SQLException {
        Timestamp effectiveAt = rs.getTimestamp("effective_at");
        Timestamp expiredAt = rs.getTimestamp("expired_at");
        Integer effectiveRevision = (Integer) rs.getObject("effective_task_revision");
        return new DecisionProposal(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("proposal_key"),
                rs.getString("task_key"), DecisionType.valueOf(rs.getString("decision_type")),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("quorum"),
                rs.getInt("base_task_revision"),
                DecisionStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                effectiveAt == null ? null : effectiveAt.toInstant(),
                rs.getString("effective_by"),
                effectiveRevision,
                expiredAt == null ? null : expiredAt.toInstant());
    }

    private static DecisionVote mapVote(ResultSet rs) throws SQLException {
        Timestamp staleAt = rs.getTimestamp("stale_at");
        Timestamp withdrawnAt = rs.getTimestamp("withdrawn_at");
        return new DecisionVote(
                rs.getLong("id"), rs.getLong("proposal_id"), rs.getInt("task_revision"),
                rs.getString("voter_actor"), rs.getString("role_code"),
                VoteChoice.valueOf(rs.getString("choice")),
                VoteStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("voted_at").toInstant(),
                staleAt == null ? null : staleAt.toInstant(),
                withdrawnAt == null ? null : withdrawnAt.toInstant());
    }

    /** 插入 OPEN 提案，返回生成主键。(incident_id, proposal_key) 唯一约束兜底并发重复登记。 */
    public long insertProposal(DecisionProposal proposal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_decisions (incident_id, proposal_key, task_key, decision_type,"
                            + " expires_at, quorum, base_task_revision, status, created_by, created_at,"
                            + " effective_at, effective_by, effective_task_revision, expired_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, proposal.incidentId());
            ps.setString(2, proposal.proposalKey());
            ps.setString(3, proposal.taskKey());
            ps.setString(4, proposal.decisionType().name());
            ps.setTimestamp(5, Timestamp.from(proposal.expiresAt()));
            ps.setInt(6, proposal.quorum());
            ps.setInt(7, proposal.baseTaskRevision());
            ps.setString(8, proposal.status().name());
            ps.setString(9, proposal.createdBy());
            ps.setTimestamp(10, Timestamp.from(proposal.createdAt()));
            ps.setTimestamp(11, null);
            ps.setString(12, null);
            ps.setNull(13, Types.INTEGER);
            ps.setTimestamp(14, null);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 登记所需角色集合快照。 */
    public void insertRole(long proposalId, String roleCode) {
        jdbc.update("INSERT INTO decision_required_roles (proposal_id, role_code) VALUES (?,?)",
                proposalId, roleCode);
    }

    /** 查询提案的所需角色集合，按角色编码升序稳定返回。 */
    public List<String> listRoles(long proposalId) {
        return jdbc.queryForList(
                "SELECT role_code FROM decision_required_roles WHERE proposal_id = ?"
                        + " ORDER BY role_code ASC, id ASC",
                String.class, proposalId);
    }

    /**
     * 按事件与 proposalKey 查询提案（不加锁，用于只读场景）。
     */
    public Optional<DecisionProposal> findByKey(long incidentId, String proposalKey) {
        List<DecisionProposal> rows = jdbc.query(
                "SELECT * FROM incident_decisions WHERE incident_id = ? AND proposal_key = ?",
                PROPOSAL_MAPPER, incidentId, proposalKey);
        return rows.stream().findFirst();
    }

    /** 按主键锁定提案行（SELECT ... FOR UPDATE）。 */
    public Optional<DecisionProposal> lockById(long proposalId) {
        List<DecisionProposal> rows = jdbc.query(
                "SELECT * FROM incident_decisions WHERE id = ? FOR UPDATE",
                PROPOSAL_MAPPER, proposalId);
        return rows.stream().findFirst();
    }

    /** 查询事件全部提案，按登记顺序返回。 */
    public List<DecisionProposal> listByIncident(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_decisions WHERE incident_id = ? ORDER BY id",
                PROPOSAL_MAPPER, incidentId);
    }

    /** 查询事件内引用指定任务且仍 OPEN 的提案，按登记顺序返回（任务修订时批量失效用）。 */
    public List<DecisionProposal> listOpenByTask(long incidentId, String taskKey) {
        return jdbc.query(
                "SELECT * FROM incident_decisions WHERE incident_id = ? AND task_key = ?"
                        + " AND status = 'OPEN' ORDER BY id",
                PROPOSAL_MAPPER, incidentId, taskKey);
    }

    /**
     * 插入一张 CAST 票。
     *
     * @throws DuplicateKeyException 同提案同角色已存在 CAST 票（active_role 唯一索引）时抛出
     */
    public long insertVote(DecisionVote vote) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO decision_votes (proposal_id, task_revision, voter_actor, role_code,"
                            + " choice, status, voted_at, stale_at, withdrawn_at, active_role)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vote.proposalId());
            ps.setInt(2, vote.taskRevision());
            ps.setString(3, vote.voterActor());
            ps.setString(4, vote.roleCode());
            ps.setString(5, vote.choice().name());
            ps.setString(6, vote.status().name());
            ps.setTimestamp(7, Timestamp.from(vote.votedAt()));
            ps.setTimestamp(8, null);
            ps.setTimestamp(9, null);
            ps.setString(10, vote.roleCode());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询提案全部投票（含历史票），按落库顺序稳定返回。 */
    public List<DecisionVote> listVotes(long proposalId) {
        return jdbc.query(
                "SELECT * FROM decision_votes WHERE proposal_id = ? ORDER BY id",
                VOTE_MAPPER, proposalId);
    }

    /** 查询提案当前版本的 CAST 票，按落库顺序返回。 */
    public List<DecisionVote> listCastVotes(long proposalId, int taskRevision) {
        return jdbc.query(
                "SELECT * FROM decision_votes WHERE proposal_id = ? AND task_revision = ?"
                        + " AND status = 'CAST' ORDER BY id",
                VOTE_MAPPER, proposalId, taskRevision);
    }

    /** 统计提案当前版本指定立场的 CAST 票数。 */
    public int countCastVotes(long proposalId, int taskRevision, VoteChoice choice) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_votes WHERE proposal_id = ? AND task_revision = ?"
                        + " AND status = 'CAST' AND choice = ?",
                Integer.class, proposalId, taskRevision, choice.name());
        return count == null ? 0 : count;
    }

    /**
     * 统计提案全部版本中指定立场的 CAST 票数。终态提案（EFFECTIVE/EXPIRED）到期/生效后
     * 不再发生 STALE 转换，CAST 票即其终局计票，不受后续任务修订影响。
     */
    public int countCastVotesAnyRevision(long proposalId, VoteChoice choice) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_votes WHERE proposal_id = ?"
                        + " AND status = 'CAST' AND choice = ?",
                Integer.class, proposalId, choice.name());
        return count == null ? 0 : count;
    }

    /** 查询角色在提案内最新一张票（任意状态），按落库倒序取第一条。 */
    public Optional<DecisionVote> findLatestByRole(long proposalId, String roleCode) {
        List<DecisionVote> rows = jdbc.query(
                "SELECT * FROM decision_votes WHERE proposal_id = ? AND role_code = ?"
                        + " ORDER BY id DESC LIMIT 1",
                VOTE_MAPPER, proposalId, roleCode);
        return rows.stream().findFirst();
    }

    /**
     * 撤回 CAST 票：置 WITHDRAWN 并清空 active_role 唯一键占用；
     * 条件包含 status='CAST'，非本人或非 CAST 更新 0 行。
     */
    public int withdraw(long voteId, String voterActor, Instant withdrawnAt) {
        return jdbc.update("UPDATE decision_votes SET status = 'WITHDRAWN', withdrawn_at = ?,"
                        + " active_role = NULL WHERE id = ? AND voter_actor = ? AND status = 'CAST'",
                Timestamp.from(withdrawnAt), voteId, voterActor);
    }

    /**
     * 任务修订后将指定 OPEN 提案当前版本全部 CAST 票置 STALE，清空 active_role 占用，
     * 使各角色可在新版本重新投票；STALE/WITHDRAWN 历史行保持不变，返回失效票数。
     * 调用方须保证仅对仍 OPEN 的提案调用：EFFECTIVE/EXPIRED 提案的投票历史不得被改写。
     */
    public int markCastVotesStale(long proposalId, Instant staleAt) {
        return jdbc.update("UPDATE decision_votes SET status = 'STALE', stale_at = ?,"
                        + " active_role = NULL WHERE proposal_id = ? AND status = 'CAST'",
                Timestamp.from(staleAt), proposalId);
    }

    /**
     * 提案生效：条件置 EFFECTIVE 并固化生效时刻、生效操作人与任务版本；
     * 仅 OPEN 可生效，并发达法定人数时只可能一个事务更新 1 行。
     */
    public int markEffective(long proposalId, Instant effectiveAt, String effectiveBy,
                             int effectiveTaskRevision) {
        return jdbc.update("UPDATE incident_decisions SET status = 'EFFECTIVE', effective_at = ?,"
                        + " effective_by = ?, effective_task_revision = ?"
                        + " WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(effectiveAt), effectiveBy, effectiveTaskRevision, proposalId);
    }

    /**
     * 提案到期：条件置 EXPIRED 并固化到期时刻；仅 OPEN 可到期，返回更新行数（0 或 1）。
     */
    public int markExpired(long proposalId, Instant expiredAt) {
        return jdbc.update("UPDATE incident_decisions SET status = 'EXPIRED', expired_at = ?"
                        + " WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(expiredAt), proposalId);
    }
}
