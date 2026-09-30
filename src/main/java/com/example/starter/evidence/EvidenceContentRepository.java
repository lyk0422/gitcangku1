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
 * 证物当前数字内容表访问。一件证物仅一行；封存登记通过 upsert 覆盖当前内容。
 * 写调用前必须已锁定 evidence 行，保证并发封存串行化。
 */
@Repository
public class EvidenceContentRepository {

    private static final ContentRowMapper ROW_MAPPER = new ContentRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次封存插入当前内容。
     */
    public void insert(String evidenceKey, byte[] content, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_content (evidence_key, content, updated_at)
                        VALUES (?, ?, ?)
                        """,
                evidenceKey, content, now);
    }

    /**
     * 重新封存覆盖当前内容（历史版本只在 sealed_content 留存摘要）。
     */
    public void updateContent(String evidenceKey, byte[] content, LocalDateTime now) {
        jdbc.update("UPDATE evidence_content SET content = ?, updated_at = ? WHERE evidence_key = ?",
                content, now, evidenceKey);
    }

    /**
     * 按业务键查询当前内容。
     */
    public Optional<EvidenceContent> findByEvidenceKey(String evidenceKey) {
        List<EvidenceContent> rows = jdbc.query(
                "SELECT * FROM evidence_content WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    private static final class ContentRowMapper implements RowMapper<EvidenceContent> {
        @Override
        public EvidenceContent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidenceContent(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getBytes("content"),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
