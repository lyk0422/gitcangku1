package com.example.starter.restitution.data;

import com.example.starter.restitution.domain.ApprovalRow;
import com.example.starter.restitution.domain.CaseRow;
import com.example.starter.restitution.domain.ClaimRow;
import com.example.starter.restitution.domain.EvidenceRow;
import com.example.starter.restitution.domain.FrozenApprovalRow;
import com.example.starter.restitution.domain.FrozenClaimRow;
import com.example.starter.restitution.domain.FrozenEvidenceRow;
import com.example.starter.restitution.domain.FrozenItemRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 藏品归还裁决数据访问：所有 SQL 均参数化，写操作由服务层事务包裹。
 */
@Repository
public class RestitutionRepository {

    private final JdbcTemplate jdbc;

    public RestitutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CaseRow> CASE_MAPPER = (rs, n) -> new CaseRow(
            rs.getString("id"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("decided_at"));

    private static final RowMapper<ClaimRow> CLAIM_MAPPER = (rs, n) -> new ClaimRow(
            rs.getLong("id"),
            rs.getString("case_id"),
            rs.getString("claim_key"),
            rs.getString("applicant"),
            rs.getString("statement"),
            rs.getBoolean("withdrawn"),
            rs.getLong("version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("withdrawn_at"));

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, n) -> new EvidenceRow(
            rs.getLong("id"),
            rs.getString("case_id"),
            rs.getLong("claim_id"),
            rs.getString("evidence_key"),
            rs.getString("summary"),
            rs.getLong("version"),
            rs.getBoolean("active"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"),
            rs.getString("case_id"),
            rs.getLong("claim_id"),
            rs.getLong("evidence_version"),
            rs.getString("reviewer"),
            rs.getLong("created_at"));

    private static final RowMapper<FrozenClaimRow> FROZEN_CLAIM_MAPPER = (rs, n) -> new FrozenClaimRow(
            rs.getString("claim_key"), rs.getString("applicant"), rs.getString("statement"));

    private static final RowMapper<FrozenEvidenceRow> FROZEN_EVIDENCE_MAPPER = (rs, n) ->
            new FrozenEvidenceRow(rs.getString("claim_key"), rs.getString("evidence_key"),
                    rs.getString("summary"), rs.getLong("version"));

    private static final RowMapper<FrozenApprovalRow> FROZEN_APPROVAL_MAPPER = (rs, n) ->
            new FrozenApprovalRow(rs.getString("claim_key"), rs.getLong("evidence_version"),
                    rs.getString("reviewer"), rs.getLong("created_at"));

    private static final RowMapper<FrozenItemRow> FROZEN_ITEM_MAPPER = (rs, n) -> new FrozenItemRow(
            rs.getString("item_no"), rs.getString("claim_key"), rs.getString("applicant"));

    public void insertCase(String caseId, long now) {
        jdbc.update("INSERT INTO restitution_case (id, status, version, created_at, decided_at) "
                + "VALUES (?, ?, 1, ?, NULL)", caseId, CaseRow.OPEN, now);
    }

    public void insertCaseItem(String caseId, String itemNo, int ord) {
        jdbc.update("INSERT INTO case_item (case_id, item_no, ord) VALUES (?, ?, ?)",
                caseId, itemNo, ord);
    }

    public CaseRow findCase(String caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, status, version, created_at, decided_at FROM restitution_case WHERE id = ?",
                    CASE_MAPPER, caseId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** SELECT ... FOR UPDATE，需在事务内调用。 */
    public CaseRow lockCase(String caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, status, version, created_at, decided_at FROM restitution_case "
                            + "WHERE id = ? FOR UPDATE",
                    CASE_MAPPER, caseId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<String> findCaseItems(String caseId) {
        return jdbc.queryForList(
                "SELECT item_no FROM case_item WHERE case_id = ? ORDER BY ord, item_no",
                String.class, caseId);
    }

    public long insertClaim(String caseId, String claimKey, String applicant, String statement, long now) {
        jdbc.update("INSERT INTO claim (case_id, claim_key, applicant, statement, withdrawn, version, "
                + "created_at, withdrawn_at) VALUES (?, ?, ?, ?, FALSE, 0, ?, NULL)",
                caseId, claimKey, applicant, statement, now);
        Long id = jdbc.queryForObject("SELECT id FROM claim WHERE case_id = ? AND claim_key = ?",
                Long.class, caseId, claimKey);
        return id == null ? 0L : id;
    }

    public void insertClaimItem(long claimId, String itemNo, int ord) {
        jdbc.update("INSERT INTO claim_item (claim_id, item_no, ord) VALUES (?, ?, ?)",
                claimId, itemNo, ord);
    }

    public ClaimRow findClaim(String caseId, String claimKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, claim_key, applicant, statement, withdrawn, version, created_at, "
                            + "withdrawn_at FROM claim WHERE case_id = ? AND claim_key = ?",
                    CLAIM_MAPPER, caseId, claimKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<String> findClaimItems(long claimId) {
        return jdbc.queryForList(
                "SELECT item_no FROM claim_item WHERE claim_id = ? ORDER BY ord, item_no",
                String.class, claimId);
    }

    public List<ClaimRow> findClaimsByCase(String caseId) {
        return jdbc.query(
                "SELECT id, case_id, claim_key, applicant, statement, withdrawn, version, created_at, "
                        + "withdrawn_at FROM claim WHERE case_id = ? ORDER BY id",
                CLAIM_MAPPER, caseId);
    }

    /** 乐观条件更新：仅 OPEN 且版本匹配时将案件推进一个版本，返回影响行数。 */
    public int bumpCaseVersion(String caseId, long expectedVersion) {
        return jdbc.update("UPDATE restitution_case SET version = version + 1 "
                + "WHERE id = ? AND status = 'OPEN' AND version = ?", caseId, expectedVersion);
    }

    public int markClaimWithdrawn(long claimId, long now) {
        return jdbc.update("UPDATE claim SET withdrawn = TRUE, withdrawn_at = ? "
                + "WHERE id = ? AND withdrawn = FALSE", now, claimId);
    }

    public void updateClaimEvidenceVersion(long claimId, long newVersion) {
        jdbc.update("UPDATE claim SET version = ? WHERE id = ?", newVersion, claimId);
    }

    public int countEvidenceForClaim(long claimId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence WHERE claim_id = ?", Integer.class, claimId);
        return count == null ? 0 : count;
    }

    public void insertEvidence(String caseId, long claimId, String evidenceKey,
                               String summary, long version, long now) {
        jdbc.update("INSERT INTO evidence (case_id, claim_id, evidence_key, summary, version, active, "
                + "created_at, revoked_at) VALUES (?, ?, ?, ?, ?, TRUE, ?, NULL)",
                caseId, claimId, evidenceKey, summary, version, now);
    }

    public EvidenceRow findEvidenceByCaseKey(String caseId, String evidenceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, claim_id, evidence_key, summary, version, active, created_at, "
                            + "revoked_at FROM evidence WHERE case_id = ? AND evidence_key = ?",
                    EVIDENCE_MAPPER, caseId, evidenceKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<EvidenceRow> findEvidencesByClaim(long claimId) {
        return jdbc.query(
                "SELECT id, case_id, claim_id, evidence_key, summary, version, active, created_at, "
                        + "revoked_at FROM evidence WHERE claim_id = ? ORDER BY id",
                EVIDENCE_MAPPER, claimId);
    }

    public int revokeEvidence(long evidenceId, long now) {
        return jdbc.update("UPDATE evidence SET active = FALSE, revoked_at = ? "
                + "WHERE id = ? AND active = TRUE", now, evidenceId);
    }

    public void insertApproval(String caseId, long claimId, long evidenceVersion, String reviewer, long now) {
        jdbc.update("INSERT INTO approval (case_id, claim_id, evidence_version, reviewer, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", caseId, claimId, evidenceVersion, reviewer, now);
    }

    public List<ApprovalRow> findApprovalsByClaim(long claimId) {
        return jdbc.query(
                "SELECT id, case_id, claim_id, evidence_version, reviewer, created_at "
                        + "FROM approval WHERE claim_id = ? ORDER BY id",
                APPROVAL_MAPPER, claimId);
    }

    public ApprovalRow findApproval(long claimId, long evidenceVersion, String reviewer) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, claim_id, evidence_version, reviewer, created_at "
                            + "FROM approval WHERE claim_id = ? AND evidence_version = ? AND reviewer = ?",
                    APPROVAL_MAPPER, claimId, evidenceVersion, reviewer);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 乐观条件置终态：仅 OPEN 且版本匹配时生效，返回影响行数。 */
    public int decideCase(String caseId, long expectedVersion, long now) {
        return jdbc.update("UPDATE restitution_case SET status = 'DECIDED', version = version + 1, "
                + "decided_at = ? WHERE id = ? AND status = 'OPEN' AND version = ?",
                now, caseId, expectedVersion);
    }

    public void insertFrozenClaim(String caseId, ClaimRow claim) {
        jdbc.update("INSERT INTO frozen_claim (case_id, claim_key, applicant, statement) "
                + "VALUES (?, ?, ?, ?)", caseId, claim.claimKey(), claim.applicant(), claim.statement());
    }

    public void insertFrozenItem(String caseId, String itemNo, String claimKey, String applicant, int ord) {
        jdbc.update("INSERT INTO frozen_item (case_id, item_no, claim_key, applicant, ord) "
                + "VALUES (?, ?, ?, ?, ?)", caseId, itemNo, claimKey, applicant, ord);
    }

    public void insertFrozenEvidence(String caseId, String claimKey, EvidenceRow evidence) {
        jdbc.update("INSERT INTO frozen_evidence (case_id, claim_key, evidence_key, summary, version) "
                + "VALUES (?, ?, ?, ?, ?)", caseId, claimKey, evidence.evidenceKey(),
                evidence.summary(), evidence.version());
    }

    public void insertFrozenApproval(String caseId, String claimKey, ApprovalRow approval) {
        jdbc.update("INSERT INTO frozen_approval (case_id, claim_key, evidence_version, reviewer, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", caseId, claimKey,
                approval.evidenceVersion(), approval.reviewer(), approval.createdAt());
    }

    public List<FrozenClaimRow> findFrozenClaims(String caseId) {
        return jdbc.query(
                "SELECT claim_key, applicant, statement FROM frozen_claim WHERE case_id = ? ORDER BY claim_key",
                FROZEN_CLAIM_MAPPER, caseId);
    }

    public List<FrozenEvidenceRow> findFrozenEvidences(String caseId) {
        return jdbc.query(
                "SELECT claim_key, evidence_key, summary, version FROM frozen_evidence "
                        + "WHERE case_id = ? ORDER BY claim_key, evidence_key",
                FROZEN_EVIDENCE_MAPPER, caseId);
    }

    public List<FrozenApprovalRow> findFrozenApprovals(String caseId) {
        return jdbc.query(
                "SELECT claim_key, evidence_version, reviewer, created_at FROM frozen_approval "
                        + "WHERE case_id = ? ORDER BY claim_key, created_at, reviewer",
                FROZEN_APPROVAL_MAPPER, caseId);
    }

    public List<FrozenItemRow> findFrozenItems(String caseId) {
        return jdbc.query(
                "SELECT item_no, claim_key, applicant FROM frozen_item WHERE case_id = ? ORDER BY ord, item_no",
                FROZEN_ITEM_MAPPER, caseId);
    }

    public void saveRequestRecord(String requestId, String actor, String requestHash,
                                  int statusCode, String responseBody, long now) {
        jdbc.update("INSERT INTO request_record (request_id, actor, request_hash, status_code, "
                + "response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, actor, requestHash, statusCode, responseBody, now);
    }

    public RequestRecordRow findRequestRecord(String requestId, String actor) {
        try {
            return jdbc.queryForObject(
                    "SELECT request_hash, status_code, response_body FROM request_record "
                            + "WHERE request_id = ? AND actor = ?",
                    (rs, n) -> new RequestRecordRow(
                            rs.getString("request_hash"),
                            rs.getInt("status_code"),
                            rs.getString("response_body")),
                    requestId, actor);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 幂等记录行。
     *
     * @param requestHash  请求参数指纹
     * @param statusCode   首次执行状态码
     * @param responseBody 首次执行响应体 JSON
     */
    public record RequestRecordRow(String requestHash, int statusCode, String responseBody) {
    }
}
