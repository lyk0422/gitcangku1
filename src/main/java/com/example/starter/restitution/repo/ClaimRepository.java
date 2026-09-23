package com.example.starter.restitution.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 主张及其藏品子集仓储。
 */
@Repository
public class ClaimRepository {

    private static final RowMapper<ClaimRow> CLAIM_MAPPER = (rs, n) -> new ClaimRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getString("claim_key"),
            rs.getString("applicant"),
            rs.getString("statement"),
            rs.getString("status"),
            rs.getLong("evidence_version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记主张，证据版本初始 0。
     */
    public long insertClaim(long caseId, String claimKey, String applicant, String statement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into claim (case_id, claim_key, applicant, statement, status, evidence_version) "
                            + "values (?, ?, ?, ?, 'REGISTERED', 0)",
                    new String[]{"id"});
            ps.setLong(1, caseId);
            ps.setString(2, claimKey);
            ps.setString(3, applicant);
            ps.setString(4, statement);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void insertClaimArtifacts(long claimId, long caseId, List<String> artifactNos) {
        int ordinal = 0;
        for (String no : artifactNos) {
            jdbc.update(
                    "insert into claim_artifact (claim_id, case_id, artifact_no, ordinal) values (?, ?, ?, ?)",
                    claimId, caseId, no, ordinal++);
        }
    }

    public ClaimRow findByKey(long caseId, String claimKey) {
        List<ClaimRow> rows = jdbc.query(
                "select id, case_id, claim_key, applicant, statement, status, evidence_version, created_at, withdrawn_at "
                        + "from claim where case_id = ? and claim_key = ?",
                CLAIM_MAPPER, caseId, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ClaimRow lockByKey(long caseId, String claimKey) {
        List<ClaimRow> rows = jdbc.query(
                "select id, case_id, claim_key, applicant, statement, status, evidence_version, created_at, withdrawn_at "
                        + "from claim where case_id = ? and claim_key = ? for update",
                CLAIM_MAPPER, caseId, claimKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按主键锁定主张行。
     */
    public ClaimRow lockById(long claimId) {
        List<ClaimRow> rows = jdbc.query(
                "select id, case_id, claim_key, applicant, statement, status, evidence_version, created_at, withdrawn_at "
                        + "from claim where id = ? for update",
                CLAIM_MAPPER, claimId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> findClaimArtifactNos(long claimId) {
        return jdbc.queryForList(
                "select artifact_no from claim_artifact where claim_id = ? order by ordinal, id",
                String.class, claimId);
    }

    /**
     * 案内全部主张（含已撤回），按登记顺序返回。
     */
    public List<ClaimRow> findAllForCase(long caseId) {
        return jdbc.query(
                "select id, case_id, claim_key, applicant, statement, status, evidence_version, created_at, withdrawn_at "
                        + "from claim where case_id = ? order by id",
                CLAIM_MAPPER, caseId);
    }

    /**
     * 按案内编号批量锁定主张行。
     */
    public List<ClaimRow> lockByKeys(long caseId, List<String> claimKeys) {
        if (claimKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", claimKeys.stream().map(x -> "?").toList());
        return jdbc.query(
                "select id, case_id, claim_key, applicant, statement, status, evidence_version, created_at, withdrawn_at "
                        + "from claim where case_id = ? and claim_key in (" + placeholders + ") for update",
                CLAIM_MAPPER, joinArgs(caseId, claimKeys));
    }

    private Object[] joinArgs(long caseId, List<String> claimKeys) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(caseId);
        args.addAll(claimKeys);
        return args.toArray();
    }

    /**
     * 撤回主张，不可恢复：仅 REGISTERED 行可被更新为 WITHDRAWN。
     *
     * @return 是否更新到一行
     */
    public boolean withdraw(long claimId, LocalDateTime withdrawnAt) {
        return jdbc.update(
                "update claim set status = 'WITHDRAWN', withdrawn_at = ? where id = ? and status = 'REGISTERED'",
                withdrawnAt, claimId) == 1;
    }

    /**
     * 证据集版本加一（追加或撤销证据时）。
     */
    public void bumpEvidenceVersion(long claimId) {
        int updated = jdbc.update(
                "update claim set evidence_version = evidence_version + 1 where id = ?", claimId);
        if (updated != 1) {
            throw new IllegalStateException("证据版本递增失败: " + claimId);
        }
    }
}
