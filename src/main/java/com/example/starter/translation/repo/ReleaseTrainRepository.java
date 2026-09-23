package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ReleasePointerRow;
import com.example.starter.translation.domain.Rows.ReleaseTrainRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 发布列车数据访问层：列车主表、发布指针与列车快照，均使用参数化 SQL。
 */
@Repository
public class ReleaseTrainRepository {

    private static final RowMapper<ReleaseTrainRow> TRAIN_MAPPER = (rs, n) -> new ReleaseTrainRow(
            rs.getString("train_key"),
            rs.getLong("document_id"),
            rs.getInt("source_document_version"),
            rs.getTimestamp("scheduled_at").toInstant(),
            rs.getString("status"),
            rs.getString("candidates_json"),
            rs.getString("frozen_json"),
            (Integer) rs.getObject("release_train_version"),
            rs.getString("created_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("ready_at")),
            toInstant(rs.getTimestamp("published_at")),
            toInstant(rs.getTimestamp("cancelled_at")));

    private static final RowMapper<ReleasePointerRow> POINTER_MAPPER = (rs, n) -> new ReleasePointerRow(
            rs.getLong("document_id"), rs.getString("locale"), rs.getInt("release_train_version"));

    private final JdbcTemplate jdbc;

    public ReleaseTrainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 插入 DRAFT 列车；trainKey 主键冲突由调用方转 409。 */
    public void insertTrain(ReleaseTrainRow row) {
        jdbc.update("INSERT INTO release_train (train_key, document_id, source_document_version, scheduled_at, "
                        + "status, candidates_json, frozen_json, release_train_version, created_request_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                row.trainKey(), row.documentId(), row.sourceDocumentVersion(),
                Timestamp.from(row.scheduledAt()), row.status(), row.candidatesJson(),
                row.frozenJson(), row.createdRequestId());
    }

    public Optional<ReleaseTrainRow> findTrain(String trainKey) {
        List<ReleaseTrainRow> rows = jdbc.query(selectTrainColumns() + " FROM release_train WHERE train_key = ?",
                TRAIN_MAPPER, trainKey);
        return rows.stream().findFirst();
    }

    /** 按主键查询列车并加行级写锁，用于串行化同一列车的冻结、取消与激活。 */
    public Optional<ReleaseTrainRow> findTrainForUpdate(String trainKey) {
        List<ReleaseTrainRow> rows = jdbc.query(
                selectTrainColumns() + " FROM release_train WHERE train_key = ? FOR UPDATE",
                TRAIN_MAPPER, trainKey);
        return rows.stream().findFirst();
    }

    private static String selectTrainColumns() {
        return "SELECT train_key, document_id, source_document_version, scheduled_at, status, candidates_json, "
                + "frozen_json, release_train_version, created_request_id, created_at, ready_at, published_at, "
                + "cancelled_at";
    }

    /** 按文档列出全部列车，trainKey 稳定升序。 */
    public List<ReleaseTrainRow> listTrainsByDocument(long documentId) {
        return jdbc.query(selectTrainColumns() + " FROM release_train WHERE document_id = ? ORDER BY train_key",
                TRAIN_MAPPER, documentId);
    }

    /** 文档内已分配的最大发布列车版本号，无发布时返回 0。 */
    public int maxReleaseTrainVersion(long documentId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(release_train_version), 0) FROM release_train WHERE document_id = ?",
                Integer.class, documentId);
        return version == null ? 0 : version;
    }

    public void markReady(String trainKey, String frozenJson, Instant readyAt) {
        jdbc.update("UPDATE release_train SET status = 'READY', frozen_json = ?, ready_at = ? WHERE train_key = ?",
                frozenJson, Timestamp.from(readyAt), trainKey);
    }

    public void markCancelled(String trainKey, Instant cancelledAt) {
        jdbc.update("UPDATE release_train SET status = 'CANCELLED', cancelled_at = ? WHERE train_key = ?",
                Timestamp.from(cancelledAt), trainKey);
    }

    public void markPublished(String trainKey, int releaseTrainVersion, Instant publishedAt) {
        jdbc.update("UPDATE release_train SET status = 'PUBLISHED', release_train_version = ?, published_at = ? "
                + "WHERE train_key = ?", releaseTrainVersion, Timestamp.from(publishedAt), trainKey);
    }

    /** 读取文档全部发布指针并加行级写锁，保证同文档多列车激活串行提交。 */
    public List<ReleasePointerRow> listPointersForUpdate(long documentId) {
        return jdbc.query(
                "SELECT document_id, locale, release_train_version FROM release_pointer "
                        + "WHERE document_id = ? ORDER BY locale FOR UPDATE",
                POINTER_MAPPER, documentId);
    }

    public List<ReleasePointerRow> listPointers(long documentId) {
        return jdbc.query(
                "SELECT document_id, locale, release_train_version FROM release_pointer "
                        + "WHERE document_id = ? ORDER BY locale",
                POINTER_MAPPER, documentId);
    }

    /** 切换发布指针：有行更新、无行插入；与列车快照同事务提交。 */
    public void upsertPointer(long documentId, String locale, int releaseTrainVersion) {
        int updated = jdbc.update(
                "UPDATE release_pointer SET release_train_version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND locale = ?",
                releaseTrainVersion, documentId, locale);
        if (updated == 0) {
            jdbc.update("INSERT INTO release_pointer (document_id, locale, release_train_version) VALUES (?, ?, ?)",
                    documentId, locale, releaseTrainVersion);
        }
    }

    public void insertTrainSnapshot(String trainKey, long documentId, String locale, int releaseTrainVersion,
                                    int pointerBefore, int pointerAfter, String snapshotJson) {
        jdbc.update("INSERT INTO train_snapshot (train_key, document_id, locale, release_train_version, "
                        + "pointer_before, pointer_after, snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                trainKey, documentId, locale, releaseTrainVersion, pointerBefore, pointerAfter, snapshotJson);
    }

    public List<SnapshotRow> listTrainSnapshots(String trainKey) {
        return jdbc.query(
                "SELECT train_key, document_id, locale, release_train_version, pointer_before, pointer_after, "
                        + "snapshot_json, created_at FROM train_snapshot WHERE train_key = ? ORDER BY locale",
                SNAPSHOT_MAPPER, trainKey);
    }

    public Optional<SnapshotRow> findTrainSnapshot(String trainKey, String locale) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT train_key, document_id, locale, release_train_version, pointer_before, pointer_after, "
                        + "snapshot_json, created_at FROM train_snapshot WHERE train_key = ? AND locale = ?",
                SNAPSHOT_MAPPER, trainKey, locale);
        return rows.stream().findFirst();
    }

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getString("train_key"), rs.getLong("document_id"), rs.getString("locale"),
            rs.getInt("release_train_version"), rs.getInt("pointer_before"), rs.getInt("pointer_after"),
            rs.getString("snapshot_json"), rs.getTimestamp("created_at").toInstant());

    /** 列车快照行：不可变，记录源文、候选、术语与切换前后指针。 */
    public record SnapshotRow(String trainKey, long documentId, String locale, int releaseTrainVersion,
                              int pointerBefore, int pointerAfter, String snapshotJson, Instant createdAt) {
    }
}
