package com.example.starter.translation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文档与目标语言的数据访问。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 文档行快照。
     *
     * @param documentId       全局唯一文档 ID
     * @param draftVersion     草稿版本（从 1 开始）
     * @param publishedVersion 发布版本（从 0 开始）
     * @param createdAt        创建时间（UTC）
     */
    public record DocumentRow(String documentId, int draftVersion, int publishedVersion, Instant createdAt) {
    }

    public void insert(String documentId, Instant now) {
        jdbc.update("INSERT INTO document (document_id, draft_version, published_version, created_at) VALUES (?, 1, 0, ?)",
                documentId, Timestamp.from(now));
    }

    public Optional<DocumentRow> findById(String documentId) {
        return jdbc.query("SELECT document_id, draft_version, published_version, created_at FROM document WHERE document_id = ?",
                (rs, i) -> new DocumentRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toInstant()),
                documentId).stream().findFirst();
    }

    /**
     * 以行锁读取文档，用于串行化同一文档的写操作，保证发布与修改/审核看到一致状态。
     */
    public Optional<DocumentRow> findByIdForUpdate(String documentId) {
        return jdbc.query("SELECT document_id, draft_version, published_version, created_at FROM document WHERE document_id = ? FOR UPDATE",
                (rs, i) -> new DocumentRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toInstant()),
                documentId).stream().findFirst();
    }

    public void updateDraftVersion(String documentId, int draftVersion) {
        jdbc.update("UPDATE document SET draft_version = ? WHERE document_id = ?", draftVersion, documentId);
    }

    public void updatePublishedVersion(String documentId, int publishedVersion) {
        jdbc.update("UPDATE document SET published_version = ? WHERE document_id = ?", publishedVersion, documentId);
    }

    public void insertLanguage(String documentId, String language) {
        jdbc.update("INSERT INTO document_language (document_id, language) VALUES (?, ?)", documentId, language);
    }

    public List<String> findLanguages(String documentId) {
        return jdbc.query("SELECT language FROM document_language WHERE document_id = ? ORDER BY language",
                (rs, i) -> rs.getString(1), documentId);
    }
}
