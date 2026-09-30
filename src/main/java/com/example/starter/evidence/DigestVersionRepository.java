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
 * 数字摘要封存版本表访问。版本只追加；重新封存时旧 ACTIVE 版本条件更新为 SUPERSEDED。
 */
@Repository
public class DigestVersionRepository {

    private static final DigestVersionRowMapper ROW_MAPPER = new DigestVersionRowMapper();

    private final JdbcTemplate jdbc;

    public DigestVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个封存版本。
     */
    public void insert(String evidenceKey, int versionNo, String sealedBy, String contentRef,
                       String contentSha256, DigestVersionStatus status, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO digest_version
                            (evidence_key, version_no, sealed_by, content_ref, content_sha256, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, versionNo, sealedBy, contentRef, contentSha256, status.name(), now);
    }

    /**
     * 查询证物当前 ACTIVE 版本（不加锁；写事务内调用前须先锁定证物行）。
     */
    public Optional<DigestVersion> findActiveByEvidenceKey(String evidenceKey) {
        List<DigestVersion> rows = jdbc.query(
                "SELECT * FROM digest_version WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, DigestVersionStatus.ACTIVE.name());
        return rows.stream().findFirst();
    }

    /**
     * 按版本号查询（不限状态，用于见证已失效版本与历史查询）。
     */
    public Optional<DigestVersion> findByVersion(String evidenceKey, int versionNo) {
        List<DigestVersion> rows = jdbc.query(
                "SELECT * FROM digest_version WHERE evidence_key = ? AND version_no = ?",
                ROW_MAPPER, evidenceKey, versionNo);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物最大版本号；无版本时返回 0。
     */
    public int maxVersionNo(String evidenceKey) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM digest_version WHERE evidence_key = ?",
                Integer.class, evidenceKey);
        return max == null ? 0 : max;
    }

    /**
     * 将指定版本条件置为 SUPERSEDED（仅当仍为 ACTIVE 时生效）。
     *
     * @return 是否更新成功
     */
    public boolean markSuperseded(long id) {
        int updated = jdbc.update(
                "UPDATE digest_version SET status = ? WHERE id = ? AND status = ?",
                DigestVersionStatus.SUPERSEDED.name(), id, DigestVersionStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 按发生顺序查询证物全部封存版本。
     */
    public List<DigestVersion> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM digest_version WHERE evidence_key = ? ORDER BY version_no",
                ROW_MAPPER, evidenceKey);
    }

    private static final class DigestVersionRowMapper implements RowMapper<DigestVersion> {
        @Override
        public DigestVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DigestVersion(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("sealed_by"),
                    rs.getString("content_ref"),
                    rs.getString("content_sha256"),
                    DigestVersionStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
