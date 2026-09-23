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
 * 重新封存申请表访问。记录只追加；每件证物至多一笔 PENDING（由证物行锁保证），
 * 确认/撤销只允许一次条件更新（status 必须仍为 PENDING），终态不可覆盖。
 */
@Repository
public class ResealApplicationRepository {

    private static final ResealRowMapper ROW_MAPPER = new ResealRowMapper();

    private final JdbcTemplate jdbc;

    public ResealApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔 PENDING 重新封存申请；reseal_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String resealKey, String evidenceKey, String applicantId, String witnessId,
                       String previousSealNo, String newSealNo, String reason, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO reseal_application
                            (reseal_key, evidence_key, applicant_id, witness_id,
                             previous_seal_no, new_seal_no, reason, status, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                resealKey, evidenceKey, applicantId, witnessId,
                previousSealNo, newSealNo, reason, ResealStatus.PENDING.name(), now);
    }

    /**
     * 按全局重新封存业务键查询。
     */
    public Optional<ResealApplication> findByResealKey(String resealKey) {
        List<ResealApplication> rows = jdbc.query(
                "SELECT * FROM reseal_application WHERE reseal_key = ?", ROW_MAPPER, resealKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前待确认（PENDING）的重新封存申请（调用前必须已锁定证物行）。
     */
    public Optional<ResealApplication> findPendingByEvidenceKey(String evidenceKey) {
        List<ResealApplication> rows = jdbc.query(
                "SELECT * FROM reseal_application WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, ResealStatus.PENDING.name());
        return rows.stream().findFirst();
    }

    /**
     * 条件决定：仅当申请仍为 PENDING 时置为终态并记录决定时刻。
     *
     * @return 是否成功决定（false 表示已被并发确认或撤销）
     */
    public boolean decide(long id, ResealStatus decided, LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "UPDATE reseal_application SET status = ?, decided_at = ? WHERE id = ? AND status = ?",
                decided.name(), decidedAt, id, ResealStatus.PENDING.name());
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
                    rs.getString("previous_seal_no"),
                    rs.getString("new_seal_no"),
                    rs.getString("reason"),
                    ResealStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
