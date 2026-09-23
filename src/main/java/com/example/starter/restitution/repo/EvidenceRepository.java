package com.example.starter.restitution.repo;

import org.springframework.dao.DuplicateKeyException;
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
 * 证据仓储：每主张累计（含已撤销）至多 20 份，evidenceKey 案内唯一。
 */
@Repository
public class EvidenceRepository {

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, n) -> new EvidenceRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("claim_id"),
            rs.getString("evidence_key"),
            rs.getString("summary"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加证据；evidenceKey 案内唯一冲突由唯一约束抛出 {@link DuplicateKeyException}。
     */
    public long insert(long caseId, long claimId, String evidenceKey, String summary) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into evidence (case_id, claim_id, evidence_key, summary, status) "
                            + "values (?, ?, ?, ?, 'ACTIVE')",
                    new String[]{"id"});
            ps.setLong(1, caseId);
            ps.setLong(2, claimId);
            ps.setString(3, evidenceKey);
            ps.setString(4, summary);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public EvidenceRow findByKey(long caseId, String evidenceKey) {
        List<EvidenceRow> rows = jdbc.query(
                "select id, case_id, claim_id, evidence_key, summary, status, created_at, revoked_at "
                        + "from evidence where case_id = ? and evidence_key = ?",
                EVIDENCE_MAPPER, caseId, evidenceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EvidenceRow lockByKey(long caseId, String evidenceKey) {
        List<EvidenceRow> rows = jdbc.query(
                "select id, case_id, claim_id, evidence_key, summary, status, created_at, revoked_at "
                        + "from evidence where case_id = ? and evidence_key = ? for update",
                EVIDENCE_MAPPER, caseId, evidenceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 撤销证据，历史保留：仅 ACTIVE 行可更新。
     *
     * @return 是否更新到一行
     */
    public boolean revoke(long evidenceId, LocalDateTime revokedAt) {
        return jdbc.update(
                "update evidence set status = 'REVOKED', revoked_at = ? where id = ? and status = 'ACTIVE'",
                revokedAt, evidenceId) == 1;
    }

    public int countAllForClaim(long claimId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from evidence where claim_id = ?", Integer.class, claimId);
        return count == null ? 0 : count;
    }

    public int countActiveForClaim(long claimId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from evidence where claim_id = ? and status = 'ACTIVE'", Integer.class, claimId);
        return count == null ? 0 : count;
    }

    public List<EvidenceRow> findActiveForClaims(List<Long> claimIds) {
        if (claimIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", claimIds.stream().map(x -> "?").toList());
        return jdbc.query(
                "select id, case_id, claim_id, evidence_key, summary, status, created_at, revoked_at "
                        + "from evidence where claim_id in (" + placeholders + ") and status = 'ACTIVE' "
                        + "order by claim_id, id",
                EVIDENCE_MAPPER, claimIds.toArray());
    }

    public List<EvidenceRow> findForClaim(long claimId) {
        return jdbc.query(
                "select id, case_id, claim_id, evidence_key, summary, status, created_at, revoked_at "
                        + "from evidence where claim_id = ? order by id",
                EVIDENCE_MAPPER, claimId);
    }
}
