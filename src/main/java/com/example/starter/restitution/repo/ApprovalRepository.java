package com.example.starter.restitution.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 评审批准仓储：同一评审人对同一主张同一证据版本只计一次。
 */
@Repository
public class ApprovalRepository {

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("claim_id"),
            rs.getString("reviewer"),
            rs.getLong("evidence_version"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public ApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入批准；同键重复由唯一约束拒绝。
     */
    public int insert(long caseId, long claimId, String reviewer, long evidenceVersion) {
        return jdbc.update(
                "insert into approval (case_id, claim_id, reviewer, evidence_version) values (?, ?, ?, ?)",
                caseId, claimId, reviewer, evidenceVersion);
    }

    /**
     * 指定主张当前证据版本的不同评审人数（去重）。
     */
    public int countDistinctReviewersAtVersion(long claimId, long evidenceVersion) {
        Integer count = jdbc.queryForObject(
                "select count(distinct reviewer) from approval where claim_id = ? and evidence_version = ?",
                Integer.class, claimId, evidenceVersion);
        return count == null ? 0 : count;
    }

    /**
     * 指定主张当前证据版本的批准评审人列表（排序后稳定输出）。
     */
    public List<String> findReviewersAtVersion(long claimId, long evidenceVersion) {
        return jdbc.queryForList(
                "select distinct reviewer from approval where claim_id = ? and evidence_version = ? order by reviewer",
                String.class, claimId, evidenceVersion);
    }

    /**
     * 批量取多个主张在其各自当前证据版本上的批准行。
     */
    public List<ApprovalRow> findCurrentForClaims(List<ClaimRow> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "select id, case_id, claim_id, reviewer, evidence_version, created_at from approval where ");
        List<Object> args = new java.util.ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            if (i > 0) {
                sql.append(" or ");
            }
            sql.append("(claim_id = ? and evidence_version = ?)");
            ClaimRow claim = claims.get(i);
            args.add(claim.id());
            args.add(claim.evidenceVersion());
        }
        sql.append(" order by claim_id, reviewer");
        return jdbc.query(sql.toString(), APPROVAL_MAPPER, args.toArray());
    }
}
