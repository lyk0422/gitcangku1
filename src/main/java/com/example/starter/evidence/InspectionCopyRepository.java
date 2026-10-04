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
 * 阅检副本表访问。签发记录只追加，回收仅允许一次条件更新（status 必须仍为 ACTIVE），
 * 历史不可覆盖、不删除。副本有效性的时间判定（自然到期失效）由服务层按时钟计算。
 */
@Repository
public class InspectionCopyRepository {

    private static final CopyRowMapper ROW_MAPPER = new CopyRowMapper();

    private final JdbcTemplate jdbc;

    public InspectionCopyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条签发记录；copy_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String copyKey, String evidenceKey, String reviewerId, String digest,
                       String watermark, LocalDateTime validFrom, LocalDateTime validUntil,
                       LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO inspection_copy
                            (copy_key, evidence_key, reviewer_id, digest, watermark,
                             valid_from, valid_until, status, revoked_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                        """,
                copyKey, evidenceKey, reviewerId, digest, watermark,
                validFrom, validUntil, CopyStatus.ACTIVE.name(), createdAt);
    }

    /**
     * 按全局副本业务键查询。
     */
    public Optional<InspectionCopy> findByCopyKey(String copyKey) {
        List<InspectionCopy> rows = jdbc.query(
                "SELECT * FROM inspection_copy WHERE copy_key = ?", ROW_MAPPER, copyKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件回收：仅当记录仍为 ACTIVE 时写入回收时刻，历史不可覆盖。
     *
     * @return 是否成功回收（false 表示已被并发回收或记录不存在）
     */
    public boolean revoke(long id, LocalDateTime revokedAt) {
        int updated = jdbc.update("""
                        UPDATE inspection_copy
                        SET status = ?, revoked_at = ?
                        WHERE id = ? AND status = ?
                        """,
                CopyStatus.REVOKED.name(), revokedAt, id, CopyStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 查询证物在指定 UTC 时刻仍然有效的副本（未回收且未自然到期）。
     * 调用前必须已锁定证物行，保证与交接/借出判定互斥。
     */
    public List<InspectionCopy> findEffectiveByEvidenceKey(String evidenceKey, LocalDateTime nowUtc) {
        return jdbc.query("""
                        SELECT * FROM inspection_copy
                        WHERE evidence_key = ? AND status = ? AND valid_until > ?
                        ORDER BY id
                        """,
                ROW_MAPPER, evidenceKey, CopyStatus.ACTIVE.name(), nowUtc);
    }

    /**
     * 按发生顺序查询证物全部签发记录（含已回收）。
     */
    public List<InspectionCopy> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM inspection_copy WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class CopyRowMapper implements RowMapper<InspectionCopy> {
        @Override
        public InspectionCopy mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InspectionCopy(
                    rs.getLong("id"),
                    rs.getString("copy_key"),
                    rs.getString("evidence_key"),
                    rs.getString("reviewer_id"),
                    rs.getString("digest"),
                    rs.getString("watermark"),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_until", LocalDateTime.class),
                    CopyStatus.valueOf(rs.getString("status")),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}