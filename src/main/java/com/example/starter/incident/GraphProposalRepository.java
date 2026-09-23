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

import com.example.starter.incident.DependencyGraphRepository.GraphEdge;

/**
 * 依赖图变更提案仓储：提案本体、边集、名册、票决与前后快照。
 * 投票与激活路径先 SELECT ... FOR UPDATE 锁定提案行，并发票决/激活按事务提交顺序收敛；
 * (proposal_id, person) 唯一约束兜底同人并发重复投票。
 */
@Repository
public class GraphProposalRepository {

    private final JdbcTemplate jdbc;

    public GraphProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<GraphProposal> PROPOSAL_MAPPER = (rs, n) -> mapProposal(rs);
    private static final RowMapper<GraphProposalEdge> EDGE_MAPPER = (rs, n) -> new GraphProposalEdge(
            rs.getLong("proposal_id"), EdgeOperation.valueOf(rs.getString("operation")),
            rs.getLong("from_incident_id"), rs.getLong("to_incident_id"));
    private static final RowMapper<GraphRosterSeat> ROSTER_MAPPER = (rs, n) -> new GraphRosterSeat(
            rs.getLong("proposal_id"), RosterRole.valueOf(rs.getString("role")),
            rs.getLong("incident_id"), rs.getString("person"));
    private static final RowMapper<GraphVote> VOTE_MAPPER = (rs, n) -> new GraphVote(
            rs.getLong("proposal_id"), rs.getString("person"),
            VoteDecision.valueOf(rs.getString("decision")),
            rs.getTimestamp("voted_at").toInstant());

    private static GraphProposal mapProposal(ResultSet rs) throws SQLException {
        long applied = rs.getLong("applied_graph_version");
        Long appliedVersion = rs.wasNull() ? null : applied;
        return new GraphProposal(rs.getLong("id"), rs.getString("proposal_key"),
                GraphProposalStatus.valueOf(rs.getString("status")), rs.getString("rationale"),
                rs.getString("proposer"), rs.getString("safety_reviewer"),
                rs.getLong("expected_graph_version"), appliedVersion,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 PENDING 提案，返回生成主键。proposal_key 唯一约束兜底并发重复创建。
     */
    public long insertProposal(GraphProposal proposal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO graph_proposals (proposal_key, status, rationale, proposer,"
                            + " safety_reviewer, expected_graph_version, applied_graph_version,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, proposal.proposalKey());
            ps.setString(2, proposal.status().name());
            ps.setString(3, proposal.rationale());
            ps.setString(4, proposal.proposer());
            ps.setString(5, proposal.safetyReviewer());
            ps.setLong(6, proposal.expectedGraphVersion());
            ps.setNull(7, java.sql.Types.BIGINT);
            ps.setTimestamp(8, Timestamp.from(proposal.createdAt()));
            ps.setTimestamp(9, Timestamp.from(proposal.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询提案（不加锁），用于只读场景。
     */
    public Optional<GraphProposal> findByKey(String proposalKey) {
        List<GraphProposal> rows = jdbc.query("SELECT * FROM graph_proposals WHERE proposal_key = ?",
                PROPOSAL_MAPPER, proposalKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定提案行（SELECT ... FOR UPDATE），用于投票/激活串行化。
     */
    public Optional<GraphProposal> lockByKey(String proposalKey) {
        List<GraphProposal> rows = jdbc.query(
                "SELECT * FROM graph_proposals WHERE proposal_key = ? FOR UPDATE",
                PROPOSAL_MAPPER, proposalKey);
        return rows.stream().findFirst();
    }

    /**
     * 更新提案状态（投票推进：PENDING → APPROVED/REJECTED）。
     */
    public void updateStatus(long id, GraphProposalStatus status, Instant now) {
        jdbc.update("UPDATE graph_proposals SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(now), id);
    }

    /**
     * 激活落库：状态置 ACTIVATED 并写入新图版本。
     */
    public void markActivated(long id, long newGraphVersion, Instant now) {
        jdbc.update("UPDATE graph_proposals SET status = 'ACTIVATED', applied_graph_version = ?,"
                        + " updated_at = ? WHERE id = ?",
                newGraphVersion, Timestamp.from(now), id);
    }

    /**
     * 追加一条提案边（结构化去重后的结果）。
     */
    public void insertEdge(GraphProposalEdge edge) {
        jdbc.update("INSERT INTO graph_proposal_edges (proposal_id, operation, from_incident_id,"
                        + " to_incident_id) VALUES (?,?,?,?)",
                edge.proposalId(), edge.operation().name(), edge.fromIncidentId(),
                edge.toIncidentId());
    }

    /**
     * 查询提案边集，按操作与事件 id 稳定排序。
     */
    public List<GraphProposalEdge> listEdges(long proposalId) {
        return jdbc.query("SELECT * FROM graph_proposal_edges WHERE proposal_id = ?"
                + " ORDER BY operation, from_incident_id, to_incident_id", EDGE_MAPPER, proposalId);
    }

    /**
     * 追加一个名册席位（创建时冻结，之后不改写）。
     */
    public void insertRosterSeat(GraphRosterSeat seat) {
        jdbc.update("INSERT INTO graph_proposal_roster (proposal_id, role, incident_id, person)"
                        + " VALUES (?,?,?,?)",
                seat.proposalId(), seat.role().name(), seat.incidentId(), seat.person());
    }

    /**
     * 查询提案名册，按角色与事件 id 稳定排序。
     */
    public List<GraphRosterSeat> listRoster(long proposalId) {
        return jdbc.query("SELECT * FROM graph_proposal_roster WHERE proposal_id = ?"
                + " ORDER BY role, incident_id", ROSTER_MAPPER, proposalId);
    }

    /**
     * 记录一票。(proposal_id, person) 唯一约束兜底同人并发重复投票。
     */
    public void insertVote(GraphVote vote) {
        jdbc.update("INSERT INTO graph_proposal_votes (proposal_id, person, decision, voted_at)"
                        + " VALUES (?,?,?,?)",
                vote.proposalId(), vote.person(), vote.decision().name(),
                Timestamp.from(vote.votedAt()));
    }

    /**
     * 查询提案全部票决，按落库顺序返回。
     */
    public List<GraphVote> listVotes(long proposalId) {
        return jdbc.query("SELECT * FROM graph_proposal_votes WHERE proposal_id = ? ORDER BY id",
                VOTE_MAPPER, proposalId);
    }

    /**
     * 查询尚未满足的席位：席位人员尚未投赞成票。返回空列表即达到法定人数。
     */
    public List<GraphRosterSeat> listUnsatisfiedSeats(long proposalId) {
        return jdbc.query("SELECT r.* FROM graph_proposal_roster r WHERE r.proposal_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM graph_proposal_votes v"
                        + " WHERE v.proposal_id = r.proposal_id AND v.person = r.person"
                        + " AND v.decision = 'APPROVE') ORDER BY r.role, r.incident_id",
                ROSTER_MAPPER, proposalId);
    }

    /**
     * 写入激活前后完整边集快照。
     */
    public void insertSnapshot(long proposalId, String phase, long graphVersion, List<GraphEdge> edges) {
        for (GraphEdge edge : edges) {
            jdbc.update("INSERT INTO graph_snapshots (proposal_id, phase, graph_version,"
                            + " from_incident_id, to_incident_id) VALUES (?,?,?,?,?)",
                    proposalId, phase, graphVersion, edge.fromIncidentId(), edge.toIncidentId());
        }
    }

    /**
     * 查询提案某阶段快照边集，按事件 id 稳定排序。
     */
    public List<GraphEdge> listSnapshot(long proposalId, String phase) {
        return jdbc.query("SELECT from_incident_id, to_incident_id FROM graph_snapshots"
                        + " WHERE proposal_id = ? AND phase = ?"
                        + " ORDER BY from_incident_id, to_incident_id",
                (rs, n) -> new GraphEdge(rs.getLong(1), rs.getLong(2)), proposalId, phase);
    }

    /**
     * 按激活生成的图版本查询提案（还原该版本的变更证据），按提案键稳定排序。
     */
    public List<GraphProposal> listByAppliedVersion(long graphVersion) {
        return jdbc.query("SELECT * FROM graph_proposals WHERE applied_graph_version = ?"
                + " ORDER BY proposal_key", PROPOSAL_MAPPER, graphVersion);
    }
}
