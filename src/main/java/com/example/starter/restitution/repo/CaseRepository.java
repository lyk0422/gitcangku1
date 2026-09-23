package com.example.starter.restitution.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;

/**
 * 案件与案件藏品清单仓储。
 */
@Repository
public class CaseRepository {

    private static final RowMapper<CaseRow> CASE_MAPPER = (rs, n) -> new CaseRow(
            rs.getLong("id"),
            rs.getString("case_key"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toLocalDateTime(),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建案件（状态 OPEN、版本 1），返回自增主键。
     */
    public long insertCase(String caseKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into restitution_case (case_key, status, version) values (?, 'OPEN', 1)",
                    new String[]{"id"});
            ps.setString(1, caseKey);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public CaseRow findByKey(String caseKey) {
        List<CaseRow> rows = jdbc.query(
                "select id, case_key, status, version, decided_at, created_at from restitution_case where case_key = ?",
                CASE_MAPPER, caseKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 取案件行并加行写锁；同案写操作由此串行化，配合版本号裁决竞争。
     */
    public CaseRow lockByKey(String caseKey) {
        List<CaseRow> rows = jdbc.query(
                "select id, case_key, status, version, decided_at, created_at from restitution_case where case_key = ? for update",
                CASE_MAPPER, caseKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> findArtifactNos(long caseId) {
        return jdbc.queryForList(
                "select artifact_no from case_artifact where case_id = ? order by ordinal, id",
                String.class, caseId);
    }

    public void insertArtifacts(long caseId, List<String> artifactNos) {
        int ordinal = 0;
        for (String no : artifactNos) {
            jdbc.update("insert into case_artifact (case_id, artifact_no, ordinal) values (?, ?, ?)",
                    caseId, no, ordinal++);
        }
    }

    /**
     * 案件版本加一，用于登记/撤回主张、证据追加/撤销、首次批准。
     */
    public void bumpVersion(long caseId) {
        int updated = jdbc.update(
                "update restitution_case set version = version + 1 where id = ?", caseId);
        if (updated != 1) {
            throw new IllegalStateException("案件版本递增失败: " + caseId);
        }
    }

    /**
     * 裁决落定：同一更新内置 DECIDED、版本加一与裁决时刻，保证原子。
     */
    public void markDecided(long caseId, java.time.LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "update restitution_case set status = 'DECIDED', version = version + 1, decided_at = ? where id = ?",
                decidedAt, caseId);
        if (updated != 1) {
            throw new IllegalStateException("案件裁决落定失败: " + caseId);
        }
    }
}
