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
 * 阅检副本表访问。签发只追加；回收仅允许一次条件更新（status 必须仍为 ACTIVE），
 * 签发历史不删除。有效副本判定（valid_to 晚于当前 UTC 时刻）用于拦截交接与借出。
 */
@Repository
public class InspectionCopyRepository {

    private static final CopyRowMapper ROW_MAPPER = new CopyRowMapper();

    private final JdbcTemplate jdbc;

    public InspectionCopyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一份 ACTIVE 阅检副本；copy_id 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String copyId, String evidenceKey, String reviewerId, String digest,
                       String watermark, String syntheticText, LocalDateTime validFrom,
                       LocalDateTime validTo, LocalDateTime issuedAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO inspection_copy
                            (copy_id, evidence_key, reviewer_id, digest, watermark, synthetic_text,
                             valid_from, valid_to, status, issued_at, revoked_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                        """,
                copyId, evidenceKey, reviewerId, digest, watermark, syntheticText,
                validFrom, validTo, InspectionCopyStatus.ACTIVE.name(), issuedAt, createdAt);
    }

    /**
     * 按不可重复副本ID查询。
     */
    public Optional<InspectionCopy> findByCopyId(String copyId) {
        List<InspectionCopy> rows = jdbc.query(
                "SELECT * FROM inspection_copy WHERE copy_id = ?", ROW_MAPPER, copyId);
        return rows.stream().findFirst();
    }

    /**
     * 条件回收：仅当副本仍为 ACTIVE 时置为 REVOKED 并写入回收时刻，历史不可覆盖。
     *
     * @return 是否成功回收（false 表示已被并发回收或记录不存在）
     */
    public boolean revoke(long id, LocalDateTime revokedAt) {
        int updated = jdbc.update("""
                        UPDATE inspection_copy
                        SET status = ?, revoked_at = ?
                        WHERE id = ? AND status = ?
                        """,
                InspectionCopyStatus.REVOKED.name(), revokedAt,
                id, InspectionCopyStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 是否存在未回收且尚未自然到期的有效副本（调用前必须已锁定证物行）。
     * 自然到期（valid_to 不晚于当前 UTC 时刻）的副本视为失效，不再拦截交接或借出。
     */
    public boolean existsActive(String evidenceKey, LocalDateTime nowUtc) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM inspection_copy
                        WHERE evidence_key = ? AND status = ? AND valid_to > ?
                        """,
                Integer.class, evidenceKey, InspectionCopyStatus.ACTIVE.name(), nowUtc);
        return count != null && count > 0;
    }

    private static final class CopyRowMapper implements RowMapper<InspectionCopy> {
        @Override
        public InspectionCopy mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InspectionCopy(
                    rs.getLong("id"),
                    rs.getString("copy_id"),
                    rs.getString("evidence_key"),
                    rs.getString("reviewer_id"),
                    rs.getString("digest"),
                    rs.getString("watermark"),
                    rs.getString("synthetic_text"),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_to", LocalDateTime.class),
                    InspectionCopyStatus.valueOf(rs.getString("status")),
                    rs.getObject("issued_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}