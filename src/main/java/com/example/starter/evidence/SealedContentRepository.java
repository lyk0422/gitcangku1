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
 * 封存版本摘要表访问。版本行只追加；(evidence_key, version_no) 唯一。
 */
@Repository
public class SealedContentRepository {

    private static final SealedContentRowMapper ROW_MAPPER = new SealedContentRowMapper();

    private final JdbcTemplate jdbc;

    public SealedContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个封存版本。
     */
    public void insert(String evidenceKey, int versionNo, String contentSha256, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sealed_content (evidence_key, version_no, content_sha256, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                evidenceKey, versionNo, contentSha256, now);
    }

    /**
     * 查询证物最新封存版本（版本号最大）；从未封存时为空。
     */
    public Optional<SealedContent> findLatestByEvidenceKey(String evidenceKey) {
        List<SealedContent> rows = jdbc.query("""
                        SELECT * FROM sealed_content WHERE evidence_key = ?
                        ORDER BY version_no DESC LIMIT 1
                        """,
                ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    private static final class SealedContentRowMapper implements RowMapper<SealedContent> {
        @Override
        public SealedContent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealedContent(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("content_sha256"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
