package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 决策提案与投票的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行、再锁定提案行的写事务内，
 * 投票、撤回、任务修订与到期判定按事务提交顺序裁决。
 * 投票行的票型/角色/任务版本/投票时刻登记后不改写，仅状态随生命周期流转。
 */
@Repository
public class DecisionRepository {

    private static final TypeReference<List<String>> ROLE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<IncidentDecision> decisionMapper = (rs, n) -> mapDecision(rs);

    public DecisionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private static final RowMapper<DecisionVote> VOTE_MAPPER = (rs, n) -> mapVote(rs);

    private IncidentDecision mapDecision(ResultSet rs) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        int decidedTaskVersion = rs.getInt("decided_task_version");
        boolean decidedTaskVersionNull = rs.wasNull();
        return new IncidentDecision(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("proposal_key"),
                rs.getLong("task_id"), rs.getString("task_key"),
                DecisionAction.valueOf(rs.getString("task_action")),
                rs.getInt("current_task_version"),
                readRoles(rs.getString("required_roles")),
                rs.getInt("quorum"),
                DecisionStatus.valueOf(rs.getString("status")),
                decidedTaskVersionNull ? null : decidedTaskVersion,
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static DecisionVote mapVote(ResultSet rs) throws SQLException {
        return new DecisionVote(
                rs.getLong("id"), rs.getLong("decision_id"), rs.getString("role"),
                VoteBallot.valueOf(rs.getString("ballot")),
                rs.getInt("task_version"),
                VoteStatus.valueOf(rs.getString("status")),
                rs.getString("actor"),
                rs.getTimestamp("cast_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<String> readRoles(String json) {
        try {
            return objectMapper.readValue(json, ROLE_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("角色集合反序列化失败", e);
        }
    }

    private String writeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("角色集合序列化失败", e);
        }
    }

    /**
     * 插入 OPEN 提案，返回生成主键。(incident_id, proposal_key) 唯一约束兜底并发重复登记。
     * requiredRoles 以规范化（排序去重）JSON 文本固化，登记后不变。
     */
    public long insert(IncidentDecision decision) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_decisions (incident_id, proposal_key, task_id, task_key,"
                            + " task_action, current_task_version, required_roles, quorum, status,"
                            + " decided_task_version, decided_at, expires_at, created_by,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, decision.incidentId());
            ps.setString(2, decision.proposalKey());
            ps.setLong(3, decision.taskId());
            ps.setString(4, decision.taskKey());
            ps.setString(5, decision.taskAction().name());
            ps.setInt(6, decision.currentTaskVersion());
            ps.setString(7, writeRoles(decision.requiredRoles()));
            ps.setInt(8, decision.quorum());
            ps.setString(9, decision.status().name());
            ps.setObject(10, decision.decidedTaskVersion());
            ps.setTimestamp(11, decision.decidedAt() == null ? null
                    : Timestamp.from(decision.decidedAt()));
            ps.setTimestamp(12, Timestamp.from(decision.expiresAt()));
            ps.setString(13, decision.createdBy());
            ps.setTimestamp(14, Timestamp.from(decision.createdAt()));
            ps.setTimestamp(15, Timestamp.from(decision.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 proposalKey 查询提案（不加锁），用于只读场景。
     */
    public Optional<IncidentDecision> findByKey(long incidentId, String proposalKey) {
        List<IncidentDecision> rows = jdbc.query(
                "SELECT * FROM incident_decisions WHERE incident_id = ? AND proposal_key = ?",
                decisionMapper, incidentId, proposalKey);
        return rows.stream().findFirst();
    }

    /**
     * 按事件与 proposalKey 查询并锁定提案行（SELECT ... FOR UPDATE），
     * 串行化投票、撤回、修订联动与到期判定。
     */
    public Optional<IncidentDecision> lockByKey(long incidentId, String proposalKey) {
        List<IncidentDecision> rows = jdbc.query(
                "SELECT * FROM incident_decisions WHERE incident_id = ? AND proposal_key = ?"
                        + " FOR UPDATE",
                decisionMapper, incidentId, proposalKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部提案，按登记顺序返回。
     */
    public List<IncidentDecision> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_decisions WHERE incident_id = ? ORDER BY id",
                decisionMapper, incidentId);
    }

    /**
     * 查询任务的全部 OPEN 提案（任务修订时联动处理），按登记顺序返回。
     */
    public List<IncidentDecision> listOpenByTask(long taskId) {
        return jdbc.query("SELECT * FROM incident_decisions WHERE task_id = ? AND status = 'OPEN'"
                + " ORDER BY id", decisionMapper, taskId);
    }

    /**
     * 将 OPEN 提案置为 EFFECTIVE，固化生效时的任务版本与 UTC 时刻。
     */
    public void markEffective(long id, int decidedTaskVersion, Instant decidedAt) {
        jdbc.update("UPDATE incident_decisions SET status = 'EFFECTIVE', decided_task_version = ?,"
                        + " decided_at = ?, updated_at = ? WHERE id = ?",
                decidedTaskVersion, Timestamp.from(decidedAt), Timestamp.from(decidedAt), id);
    }

    /**
     * 将 OPEN 提案置为 EXPIRED，记录到期判定的 UTC 时刻。
     */
    public void markExpired(long id, Instant decidedAt) {
        jdbc.update("UPDATE incident_decisions SET status = 'EXPIRED', decided_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                Timestamp.from(decidedAt), Timestamp.from(decidedAt), id);
    }

    /**
     * 任务修订后推进 OPEN 提案的当前计票版本。
     */
    public void bumpCurrentTaskVersion(long id, int newTaskVersion, Instant updatedAt) {
        jdbc.update("UPDATE incident_decisions SET current_task_version = ?, updated_at = ?"
                        + " WHERE id = ?",
                newTaskVersion, Timestamp.from(updatedAt), id);
    }

    /**
     * 追加一票（CAST）。(decision_id, role, task_version) 唯一约束兜底并发重复投票。
     */
    public long insertVote(DecisionVote vote) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_decision_votes (decision_id, role, ballot, task_version,"
                            + " status, actor, cast_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, vote.decisionId());
            ps.setString(2, vote.role());
            ps.setString(3, vote.ballot().name());
            ps.setInt(4, vote.taskVersion());
            ps.setString(5, vote.status().name());
            ps.setString(6, vote.actor());
            ps.setTimestamp(7, Timestamp.from(vote.castAt()));
            ps.setTimestamp(8, Timestamp.from(vote.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询角色对提案指定任务版本的唯一投票（不存在返回空）。
     */
    public Optional<DecisionVote> findVote(long decisionId, String role, int taskVersion) {
        List<DecisionVote> rows = jdbc.query(
                "SELECT * FROM incident_decision_votes WHERE decision_id = ? AND role = ?"
                        + " AND task_version = ?",
                VOTE_MAPPER, decisionId, role, taskVersion);
        return rows.stream().findFirst();
    }

    /**
     * 查询提案全部投票（含 WITHDRAWN/STALE 历史证据），按投票顺序返回。
     */
    public List<DecisionVote> listVotes(long decisionId) {
        return jdbc.query("SELECT * FROM incident_decision_votes WHERE decision_id = ?"
                + " ORDER BY id", VOTE_MAPPER, decisionId);
    }

    /**
     * 统计提案当前任务版本下 CAST 状态的指定票型数量（法定人数判定用）。
     */
    public int countCastVotes(long decisionId, int taskVersion, VoteBallot ballot) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes WHERE decision_id = ?"
                        + " AND task_version = ? AND ballot = ? AND status = 'CAST'",
                Integer.class, decisionId, taskVersion, ballot.name());
        return count == null ? 0 : count;
    }

    /**
     * 统计提案全部投票中处于指定状态的数量（诊断用）。
     */
    public int countVotesByStatus(long decisionId, VoteStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes WHERE decision_id = ? AND status = ?",
                Integer.class, decisionId, status.name());
        return count == null ? 0 : count;
    }

    /**
     * 将 CAST 票原子置为 WITHDRAWN（撤回），记录变更时刻；票型/角色/时刻不改写。
     */
    public void withdrawVote(long voteId, Instant updatedAt) {
        jdbc.update("UPDATE incident_decision_votes SET status = 'WITHDRAWN', updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(updatedAt), voteId);
    }

    /**
     * 任务修订时将提案全部 CAST 票原子置为 STALE，返回失效票数；
     * 票型/角色/任务版本/投票时刻保留为历史证据。
     */
    public int staleCastVotes(long decisionId, Instant updatedAt) {
        return jdbc.update("UPDATE incident_decision_votes SET status = 'STALE', updated_at = ?"
                        + " WHERE decision_id = ? AND status = 'CAST'",
                Timestamp.from(updatedAt), decisionId);
    }
}
