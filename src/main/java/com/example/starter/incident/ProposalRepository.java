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
 * 依赖图变更提案、不可变名册席位与按人员票决的 JDBC 仓储。
 * 写路径均处于先锁定提案行/依赖图全局锁的事务内，保证并发投票、激活按提交顺序收敛。
 */
@Repository
public class ProposalRepository {

    private final JdbcTemplate jdbc;

    public ProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DependencyProposal> PROPOSAL_MAPPER =
            (rs, n) -> mapProposal(rs);

    private static DependencyProposal mapProposal(ResultSet rs) throws SQLException {
        Timestamp activatedAt = rs.getTimestamp("activated_at");
        Long activatedVersion = (Long) rs.getObject("activated_graph_version");
        return new DependencyProposal(
                rs.getLong("id"), rs.getString("proposal_key"),
                rs.getLong("expected_graph_version"), rs.getString("business_note"),
                rs.getString("safety_reviewer"), rs.getString("created_by"),
                rs.getString("changes_json"),
                ProposalStatus.valueOf(rs.getString("status")),
                activatedVersion,
                rs.getString("before_edges_json"), rs.getString("after_edges_json"),
                rs.getTimestamp("created_at").toInstant(),
                activatedAt == null ? null : activatedAt.toInstant());
    }

    private static final RowMapper<ProposalRosterEntry> ROSTER_MAPPER = (rs, n) ->
            new ProposalRosterEntry(rs.getLong("id"), rs.getLong("proposal_id"),
                    (Long) rs.getObject("incident_id"), rs.getString("person_id"),
                    RosterRole.valueOf(rs.getString("role")),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ProposalVote> VOTE_MAPPER = (rs, n) -> new ProposalVote(
            rs.getLong("id"), rs.getLong("proposal_id"), rs.getString("person_id"),
            VoteChoice.valueOf(rs.getString("choice")),
            rs.getTimestamp("voted_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    /** 插入提案，返回生成主键。proposal_key 唯一约束兜底并发重复创建。 */
    public long insertProposal(DependencyProposal proposal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO dependency_change_proposals (proposal_key, expected_graph_version,"
                            + " business_note, safety_reviewer, created_by, changes_json, status,"
                            + " activated_graph_version, before_edges_json, after_edges_json,"
                            + " created_at, activated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, proposal.proposalKey());
            ps.setLong(2, proposal.expectedGraphVersion());
            ps.setString(3, proposal.businessNote());
            ps.setString(4, proposal.safetyReviewer());
            ps.setString(5, proposal.createdBy());
            ps.setString(6, proposal.changesJson());
            ps.setString(7, proposal.status().name());
            ps.setObject(8, proposal.activatedGraphVersion());
            ps.setString(9, proposal.beforeEdgesJson());
            ps.setString(10, proposal.afterEdgesJson());
            ps.setTimestamp(11, Timestamp.from(proposal.createdAt()));
            ps.setTimestamp(12, proposal.activatedAt() == null ? null
                    : Timestamp.from(proposal.activatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按业务键查询提案（普通读）。 */
    public Optional<DependencyProposal> findByKey(String proposalKey) {
        List<DependencyProposal> rows = jdbc.query(
                "SELECT * FROM dependency_change_proposals WHERE proposal_key = ?",
                PROPOSAL_MAPPER, proposalKey);
        return rows.stream().findFirst();
    }

    /** 按业务键锁定提案行（SELECT ... FOR UPDATE），串行化同提案投票与激活。 */
    public Optional<DependencyProposal> lockByKey(String proposalKey) {
        List<DependencyProposal> rows = jdbc.query(
                "SELECT * FROM dependency_change_proposals WHERE proposal_key = ? FOR UPDATE",
                PROPOSAL_MAPPER, proposalKey);
        return rows.stream().findFirst();
    }

    /** 按主键锁定提案行。 */
    public Optional<DependencyProposal> lockById(long id) {
        List<DependencyProposal> rows = jdbc.query(
                "SELECT * FROM dependency_change_proposals WHERE id = ? FOR UPDATE",
                PROPOSAL_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 追加不可变名册席位。 */
    public void insertRosterEntry(ProposalRosterEntry entry) {
        jdbc.update("INSERT INTO proposal_roster_entries (proposal_id, incident_id, person_id,"
                        + " role, created_at) VALUES (?,?,?,?,?)",
                entry.proposalId(), entry.incidentId(), entry.personId(),
                entry.role().name(), Timestamp.from(entry.createdAt()));
    }

    /** 查询提案全部名册席位，按 id 稳定排序。 */
    public List<ProposalRosterEntry> listRoster(long proposalId) {
        return jdbc.query(
                "SELECT * FROM proposal_roster_entries WHERE proposal_id = ? ORDER BY id",
                ROSTER_MAPPER, proposalId);
    }

    /** 追加按人员票决。(proposal_id, person_id) 唯一约束兜底并发重复投票。 */
    public void insertVote(ProposalVote vote) {
        jdbc.update("INSERT INTO proposal_votes (proposal_id, person_id, choice, voted_at,"
                        + " created_at) VALUES (?,?,?,?,?)",
                vote.proposalId(), vote.personId(), vote.choice().name(),
                Timestamp.from(vote.votedAt()), Timestamp.from(vote.createdAt()));
    }

    /** 查询某提案某人的票决。 */
    public Optional<ProposalVote> findVote(long proposalId, String personId) {
        List<ProposalVote> rows = jdbc.query(
                "SELECT * FROM proposal_votes WHERE proposal_id = ? AND person_id = ?",
                VOTE_MAPPER, proposalId, personId);
        return rows.stream().findFirst();
    }

    /** 查询提案全部票决，按 id 稳定排序。 */
    public List<ProposalVote> listVotes(long proposalId) {
        return jdbc.query("SELECT * FROM proposal_votes WHERE proposal_id = ? ORDER BY id",
                VOTE_MAPPER, proposalId);
    }

    /** 将提案置为 REJECTED（条件：仍为 PENDING），返回受影响行数。 */
    public int markRejected(long proposalId, Instant at) {
        return jdbc.update(
                "UPDATE dependency_change_proposals SET status = 'REJECTED', activated_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                Timestamp.from(at), proposalId);
    }

    /**
     * 激活提交：写入新版本号、前后边集快照与时刻，状态置为 ACTIVATED。
     * 条件更新要求状态仍为 PENDING；activated_graph_version 唯一约束兜底并发版本冲突。
     */
    public int markActivated(long proposalId, long activatedGraphVersion, String beforeEdgesJson,
                             String afterEdgesJson, Instant at) {
        return jdbc.update(
                "UPDATE dependency_change_proposals SET status = 'ACTIVATED',"
                        + " activated_graph_version = ?, before_edges_json = ?,"
                        + " after_edges_json = ?, activated_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                activatedGraphVersion, beforeEdgesJson, afterEdgesJson,
                Timestamp.from(at), proposalId);
    }

    /** 按激活后图版本查询提案（证据还原用）。 */
    public Optional<DependencyProposal> findByActivatedGraphVersion(long graphVersion) {
        List<DependencyProposal> rows = jdbc.query(
                "SELECT * FROM dependency_change_proposals WHERE activated_graph_version = ?",
                PROPOSAL_MAPPER, graphVersion);
        return rows.stream().findFirst();
    }
}
