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
 * 证物当前内容表访问。调用方必须已持有证物行锁，避免与并发封存/导出读到半截内容。
 */
@Repository
public class DigestContentRepository {

    private static final DigestContentRowMapper ROW_MAPPER = new DigestContentRowMapper();

    private final JdbcTemplate jdbc;

    public DigestContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记或整体替换证物当前内容（封存时登记，后续改动/恢复由内容管理流程覆盖）。
     */
    public void upsert(String evidenceKey, String contentRef, String contentBase64,
                       LocalDateTime now) {
        jdbc.update("""
                        MERGE INTO digest_content
                            (evidence_key, content_ref, content_base64, updated_at)
                        KEY (evidence_key)
                        VALUES (?, ?, ?, ?)
                        """,
                evidenceKey, contentRef, contentBase64, now);
    }

    /**
     * 按证物键查询当前内容（调用前必须已锁定证物行）。
     */
    public Optional<DigestContent> findByEvidenceKey(String evidenceKey) {
        List<DigestContent> rows = jdbc.query(
                "SELECT * FROM digest_content WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    private static final class DigestContentRowMapper implements RowMapper<DigestContent> {
        @Override
        public DigestContent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DigestContent(
                    rs.getString("evidence_key"),
                    rs.getString("content_ref"),
                    rs.getString("content_base64"),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
