package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 双人重新封存申请表访问。记录只追加；PENDING 申请经一次条件更新决定为 CONFIRMED/CANCELLED，
 * 终态不可再变。每件证物至多一笔 PENDING，由证物行锁与服务层校验保证。
 */
@Repository
public class ResealApplicationRepository {

    private static final ResealRowMapper ROW_MAPPER = new ResealRowMapper();

    private final JdbcTemplate jdbc;

    public ResealApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔 PENDING 申请；reseal_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String resealKey, String evidenceKey, String applicantId, String witnessId,
                       String reason, String newSealNo, LocalDateTime appliedAt) {
        jdbc.update("""
                        INSERT INTO reseal_application
                            (reseal_key, evidence_key, applicant_id, witness_id, reason, new_seal_no,
                             status, old_seal_no, confirmed_seal_no, applied_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)
                        """,
                resealKey, evidenceKey, applicantId, witnessId, reason, newSealNo,
                ResealStatus.PENDING.name(), appliedAt);
    }

    /**
     * 按全局业务键查询申请。
     */
    public Optional<ResealApplication> findByResealKey(String resealKey) {
        List<ResealApplication> rows = jdbc.query(
                "SELECT * FROM reseal_application WHERE reseal_key = ?", ROW_MAPPER, resealKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前 PENDING 申请（调用前必须已锁定证物行）。
     */
    public Optional<ResealApplication> findPendingByEvidenceKey(String evidenceKey) {
        List<ResealApplication> rows = jdbc.query(
                "SELECT * FROM reseal_application WHERE evidence_key = ? AND status = ? ORDER BY id",
                ROW_MAPPER, evidenceKey, ResealStatus.PENDING.name());
        return rows.stream().findFirst();
    }

    /**
     * 条件确认：仅当仍为 PENDING 时写入终态 CONFIRMED 与确认快照（旧/新封条、UTC 决定时刻）。
     *
     * @return 是否成功（false 表示已被并发决定）
     */
    public boolean confirm(long id, String oldSealNo, String confirmedSealNo, LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE reseal_application
                        SET status = ?, old_seal_no = ?, confirmed_seal_no = ?, decided_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ResealStatus.CONFIRMED.name(), oldSealNo, confirmedSealNo, decidedAt,
                id, ResealStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 条件撤销：仅当仍为 PENDING 时写入终态 CANCELLED 与决定时刻（不记录封条快照）。
     *
     * @return 是否成功（false 表示已被并发决定）
     */
    public boolean cancel(long id, LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "UPDATE reseal_application SET status = ?, decided_at = ? WHERE id = ? AND status = ?",
                ResealStatus.CANCELLED.name(), decidedAt, id, ResealStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 按发生顺序查询证物全部重新封存申请。
     */
    public List<ResealApplication> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM reseal_application WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class ResealRowMapper implements RowMapper<ResealApplication> {
        @Override
        public ResealApplication mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ResealApplication(
                    rs.getLong("id"),
                    rs.getString("reseal_key"),
                    rs.getString("evidence_key"),
                    rs.getString("applicant_id"),
                    rs.getString("witness_id"),
                    rs.getString("reason"),
                    rs.getString("new_seal_no"),
                    ResealStatus.valueOf(rs.getString("status")),
                    rs.getString("old_seal_no"),
                    rs.getString("confirmed_seal_no"),
                    rs.getObject("applied_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
