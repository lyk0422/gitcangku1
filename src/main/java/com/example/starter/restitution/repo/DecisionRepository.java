package com.example.starter.restitution.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 裁决冻结快照仓储：与案件落定在同一事务写入，终态历史查询只读这些快照。
 */
@Repository
public class DecisionRepository {

    private final JdbcTemplate jdbc;

    public DecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDecision(long caseId, long version, String requestId, LocalDateTime decidedAt) {
        jdbc.update(
                "insert into frozen_decision (case_id, version, request_id, decided_at) values (?, ?, ?, ?)",
                caseId, version, requestId, decidedAt);
    }

    public void insertFrozenClaim(long caseId, long claimId, String claimKey, String applicant,
                                  String statement, int ordinal) {
        jdbc.update(
                "insert into frozen_claim (case_id, claim_id, claim_key, applicant, statement, ordinal) "
                        + "values (?, ?, ?, ?, ?, ?)",
                caseId, claimId, claimKey, applicant, statement, ordinal);
    }

    public void insertFrozenArtifact(long caseId, String artifactNo, long claimId, String applicant, int ordinal) {
        jdbc.update(
                "insert into frozen_artifact (case_id, artifact_no, claim_id, applicant, ordinal) "
                        + "values (?, ?, ?, ?, ?)",
                caseId, artifactNo, claimId, applicant, ordinal);
    }

    public void insertFrozenEvidence(long caseId, long claimId, long evidenceId, String evidenceKey,
                                     String summary, long evidenceVersion, int ordinal) {
        jdbc.update(
                "insert into frozen_evidence (case_id, claim_id, evidence_id, evidence_key, summary, evidence_version, ordinal) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                caseId, claimId, evidenceId, evidenceKey, summary, evidenceVersion, ordinal);
    }

    public void insertFrozenApproval(long caseId, long claimId, String reviewer, long evidenceVersion, int ordinal) {
        jdbc.update(
                "insert into frozen_approval (case_id, claim_id, reviewer, evidence_version, ordinal) "
                        + "values (?, ?, ?, ?, ?)",
                caseId, claimId, reviewer, evidenceVersion, ordinal);
    }

    public boolean exists(long caseId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from frozen_decision where case_id = ?", Integer.class, caseId);
        return count != null && count > 0;
    }

    /**
     * 读取冻结裁决主信息。
     */
    public FrozenDecisionHeader findHeader(long caseId) {
        List<FrozenDecisionHeader> rows = jdbc.query(
                "select version, request_id, decided_at from frozen_decision where case_id = ?",
                (rs, n) -> new FrozenDecisionHeader(
                        rs.getLong("version"),
                        rs.getString("request_id"),
                        rs.getTimestamp("decided_at").toLocalDateTime()),
                caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<FrozenClaimSnapshot> findClaims(long caseId) {
        return jdbc.query(
                "select claim_id, claim_key, applicant, statement from frozen_claim where case_id = ? order by ordinal, id",
                (rs, n) -> new FrozenClaimSnapshot(
                        rs.getLong("claim_id"), rs.getString("claim_key"),
                        rs.getString("applicant"), rs.getString("statement")),
                caseId);
    }

    public List<FrozenArtifactSnapshot> findArtifacts(long caseId) {
        return jdbc.query(
                "select artifact_no, claim_id, applicant from frozen_artifact where case_id = ? order by ordinal, id",
                (rs, n) -> new FrozenArtifactSnapshot(
                        rs.getString("artifact_no"), rs.getLong("claim_id"), rs.getString("applicant")),
                caseId);
    }

    public List<FrozenEvidenceSnapshot> findEvidence(long caseId) {
        return jdbc.query(
                "select claim_id, evidence_id, evidence_key, summary, evidence_version "
                        + "from frozen_evidence where case_id = ? order by ordinal, id",
                (rs, n) -> new FrozenEvidenceSnapshot(
                        rs.getLong("claim_id"), rs.getLong("evidence_id"), rs.getString("evidence_key"),
                        rs.getString("summary"), rs.getLong("evidence_version")),
                caseId);
    }

    public List<FrozenApprovalSnapshot> findApprovals(long caseId) {
        return jdbc.query(
                "select claim_id, reviewer, evidence_version from frozen_approval where case_id = ? order by ordinal, id",
                (rs, n) -> new FrozenApprovalSnapshot(
                        rs.getLong("claim_id"), rs.getString("reviewer"), rs.getLong("evidence_version")),
                caseId);
    }

    /**
     * 冻结裁决主信息。
     */
    public record FrozenDecisionHeader(long version, String requestId, LocalDateTime decidedAt) {
    }

    public record FrozenClaimSnapshot(long claimId, String claimKey, String applicant, String statement) {
    }

    public record FrozenArtifactSnapshot(String artifactNo, long claimId, String applicant) {
    }

    public record FrozenEvidenceSnapshot(long claimId, long evidenceId, String evidenceKey,
                                         String summary, long evidenceVersion) {
    }

    public record FrozenApprovalSnapshot(long claimId, String reviewer, long evidenceVersion) {
    }
}
