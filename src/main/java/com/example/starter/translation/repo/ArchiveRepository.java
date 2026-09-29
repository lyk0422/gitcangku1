package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ArchiveEventRow;
import com.example.starter.translation.domain.Rows.ArchiveHistoryRow;
import com.example.starter.translation.domain.Rows.ArchivePackageRow;
import com.example.starter.translation.domain.Rows.ArchiveSegmentRow;
import com.example.starter.translation.domain.Rows.ReleaseSnapshotMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 译文归档包数据访问层：归档包、逐段证据、事件历史与发布撤回状态。
 * 所有时间列为数据库 TIMESTAMP，按 UTC 字面值读写（毫秒精度），不随 JVM 默认时区变化。
 */
@Repository
public class ArchiveRepository {

    private static final String PACKAGE_COLUMNS =
            "archive_id, document_id, archive_version, published_version, target_languages, term_version, "
                    + "segment_count, language_count, approval_count, evidence_json, status, revoked_at, "
                    + "revoked_by, revoke_reason, created_at";

    private static final RowMapper<ReleaseSnapshotMeta> RELEASE_META_MAPPER = (rs, n) -> new ReleaseSnapshotMeta(
            rs.getString("snapshot_json"), readInstant(rs, "created_at"),
            rs.getString("status"), readInstant(rs, "withdrawn_at"),
            rs.getString("withdrawn_by"), rs.getString("withdraw_reason"));

    private static final RowMapper<ArchivePackageRow> PACKAGE_MAPPER = (rs, n) -> new ArchivePackageRow(
            rs.getLong("archive_id"), rs.getLong("document_id"), rs.getInt("archive_version"),
            rs.getInt("published_version"),
            Arrays.stream(rs.getString("target_languages").split(",")).toList(),
            rs.getInt("term_version"), rs.getInt("segment_count"), rs.getInt("language_count"),
            rs.getInt("approval_count"), rs.getString("evidence_json"), rs.getString("status"),
            readInstant(rs, "revoked_at"), rs.getString("revoked_by"), rs.getString("revoke_reason"),
            readInstant(rs, "created_at"));

    private static final RowMapper<ArchiveSegmentRow> SEGMENT_MAPPER = (rs, n) -> new ArchiveSegmentRow(
            rs.getLong("archive_id"), rs.getString("segment_id"), rs.getString("language"),
            rs.getInt("translation_version"), rs.getInt("source_version"), rs.getInt("term_version"));

    private static final RowMapper<ArchiveHistoryRow> HISTORY_MAPPER = (rs, n) -> new ArchiveHistoryRow(
            rs.getLong("event_id"), rs.getLong("document_id"),
            (Long) rs.getObject("archive_id"), (Integer) rs.getObject("archive_version"),
            rs.getString("event_type"), (Integer) rs.getObject("published_version"),
            rs.getString("actor"), rs.getString("reason"), readInstant(rs, "event_time"));

    private final JdbcTemplate jdbc;

    public ArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 无时区 TIMESTAMP 按 UTC 字面量读取，保证统一 UTC 语义。 */
    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** UTC Instant 转数据库字面时间戳。 */
    private static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 查询发布快照元数据（证据 JSON、生成时刻与撤回状态），不加锁。 */
    public Optional<ReleaseSnapshotMeta> findReleaseMeta(long documentId, int publishedVersion) {
        return queryReleaseMeta(documentId, publishedVersion, false);
    }

    /** 对发布快照行加写锁并读取元数据，串行化撤回与归档的提交顺序裁决。 */
    public Optional<ReleaseSnapshotMeta> findReleaseMetaForUpdate(long documentId, int publishedVersion) {
        return queryReleaseMeta(documentId, publishedVersion, true);
    }

    private Optional<ReleaseSnapshotMeta> queryReleaseMeta(long documentId, int publishedVersion, boolean forUpdate) {
        List<ReleaseSnapshotMeta> rows = jdbc.query(
                "SELECT snapshot_json, created_at, status, withdrawn_at, withdrawn_by, withdraw_reason "
                        + "FROM release_snapshot WHERE document_id = ? AND published_version = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                RELEASE_META_MAPPER, documentId, publishedVersion);
        return rows.stream().findFirst();
    }

    /** 标记发布撤回；只更新状态列，snapshot_json 保持不变。 */
    public void markReleaseWithdrawn(long documentId, int publishedVersion, String actor,
                                     String reason, Instant at) {
        jdbc.update("UPDATE release_snapshot SET status = 'WITHDRAWN', withdrawn_at = ?, withdrawn_by = ?, "
                        + "withdraw_reason = ? WHERE document_id = ? AND published_version = ?",
                toLocal(at), actor, reason, documentId, publishedVersion);
    }

    /** 读取文档当前归档包数量（用于分配下一个归档版本），调用方须持有文档行锁。 */
    public int countArchives(long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    /** 插入归档包主记录并返回自增 archive_id。 */
    public long insertArchivePackage(ArchivePackageRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO archive_package (document_id, archive_version, published_version, "
                            + "target_languages, term_version, segment_count, language_count, approval_count, "
                            + "evidence_json, status, revoked_at, revoked_by, revoke_reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL, NULL, NULL, ?)",
                    new String[]{"archive_id"});
            ps.setLong(1, row.documentId());
            ps.setInt(2, row.archiveVersion());
            ps.setInt(3, row.publishedVersion());
            ps.setString(4, String.join(",", row.targetLanguages()));
            ps.setInt(5, row.termVersion());
            ps.setInt(6, row.segmentCount());
            ps.setInt(7, row.languageCount());
            ps.setInt(8, row.approvalCount());
            ps.setString(9, row.evidenceJson());
            ps.setObject(10, toLocal(row.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("归档包创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 批量插入逐段译文版本证据，与归档包主记录同一事务提交。 */
    public void insertArchiveSegments(long documentId, List<ArchiveSegmentRow> rows) {
        jdbc.batchUpdate("INSERT INTO archive_segment (archive_id, document_id, segment_id, language, "
                        + "translation_version, source_version, term_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.archiveId());
                    ps.setLong(2, documentId);
                    ps.setString(3, row.segmentId());
                    ps.setString(4, row.language());
                    ps.setInt(5, row.translationVersion());
                    ps.setInt(6, row.sourceVersion());
                    ps.setInt(7, row.termVersion());
                });
    }

    /** 查询单个归档包。 */
    public Optional<ArchivePackageRow> findArchivePackage(long documentId, int archiveVersion) {
        return queryPackage(documentId, archiveVersion, false);
    }

    /** 对归档包行加写锁后读取，串行化撤销。 */
    public Optional<ArchivePackageRow> findArchivePackageForUpdate(long documentId, int archiveVersion) {
        return queryPackage(documentId, archiveVersion, true);
    }

    private Optional<ArchivePackageRow> queryPackage(long documentId, int archiveVersion, boolean forUpdate) {
        List<ArchivePackageRow> rows = jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM archive_package "
                        + "WHERE document_id = ? AND archive_version = ?" + (forUpdate ? " FOR UPDATE" : ""),
                PACKAGE_MAPPER, documentId, archiveVersion);
        return rows.stream().findFirst();
    }

    /** 列出文档全部归档包，按归档版本稳定排序；可按引用发布版本过滤。 */
    public List<ArchivePackageRow> listArchivePackages(long documentId, Integer publishedVersion) {
        if (publishedVersion == null) {
            return jdbc.query(
                    "SELECT " + PACKAGE_COLUMNS + " FROM archive_package "
                            + "WHERE document_id = ? ORDER BY archive_version",
                    PACKAGE_MAPPER, documentId);
        }
        return jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM archive_package "
                        + "WHERE document_id = ? AND published_version = ? ORDER BY archive_version",
                PACKAGE_MAPPER, documentId, publishedVersion);
    }

    /** 撤销归档：仅更新状态列与撤销原因，证据列保持不变。 */
    public void markArchiveRevoked(long archiveId, String actor, String reason, Instant at) {
        jdbc.update("UPDATE archive_package SET status = 'REVOKED', revoked_at = ?, revoked_by = ?, "
                        + "revoke_reason = ? WHERE archive_id = ?",
                toLocal(at), actor, reason, archiveId);
    }

    /** 查询归档包逐段证据，按段落、语种稳定排序。 */
    public List<ArchiveSegmentRow> listArchiveSegments(long archiveId) {
        return jdbc.query(
                "SELECT archive_id, segment_id, language, translation_version, source_version, term_version "
                        + "FROM archive_segment WHERE archive_id = ? ORDER BY segment_id, language",
                SEGMENT_MAPPER, archiveId);
    }

    /** 统计归档包逐段证据行数（诊断实际值）。 */
    public int countArchiveSegments(long archiveId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_segment WHERE archive_id = ?", Integer.class, archiveId);
        return count == null ? 0 : count;
    }

    /** 追加领域事件。 */
    public void insertEvent(ArchiveEventRow row) {
        jdbc.update("INSERT INTO archive_event (document_id, archive_id, event_type, published_version, "
                        + "actor, reason, event_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.documentId(), row.archiveId(), row.eventType(), row.publishedVersion(),
                row.actor(), row.reason(), toLocal(row.eventTime()));
    }

    /** 查询文档事件历史，按提交顺序（event_id 升序）稳定输出，并关联归档版本。 */
    public List<ArchiveHistoryRow> listHistory(long documentId) {
        return jdbc.query(
                "SELECT e.event_id, e.document_id, e.archive_id, p.archive_version, e.event_type, "
                        + "e.published_version, e.actor, e.reason, e.event_time "
                        + "FROM archive_event e LEFT JOIN archive_package p ON e.archive_id = p.archive_id "
                        + "WHERE e.document_id = ? ORDER BY e.event_id",
                HISTORY_MAPPER, documentId);
    }
}
