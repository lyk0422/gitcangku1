package com.example.starter.translation.archive;

import com.example.starter.translation.archive.ArchiveRows.ArchivePackageRow;
import com.example.starter.translation.archive.ArchiveRows.TermRetirementRow;
import com.example.starter.translation.archive.ArchiveRows.WithdrawalRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 发布撤回、术语退役与归档包的数据访问层，基于 JdbcTemplate 与参数化 SQL。
 * 时间列以 UTC 写入（TIMESTAMP 不含时区），读取时按 UTC 解释为 Instant。
 */
@Repository
public class ArchiveRepository {

    private static final RowMapper<WithdrawalRow> WITHDRAWAL_MAPPER = (rs, n) -> new WithdrawalRow(
            rs.getLong("document_id"), rs.getInt("published_version"), rs.getString("withdrawn_by"),
            rs.getString("reason"), rs.getTimestamp("withdrawn_at").toInstant());

    private static final RowMapper<TermRetirementRow> RETIREMENT_MAPPER = (rs, n) -> new TermRetirementRow(
            rs.getLong("document_id"), rs.getInt("term_version"), rs.getString("retired_by"),
            rs.getString("reason"), rs.getTimestamp("retired_at").toInstant());

    private static final RowMapper<ArchivePackageRow> PACKAGE_MAPPER = (rs, n) -> {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ArchivePackageRow(
                rs.getLong("archive_id"), rs.getLong("document_id"), rs.getInt("published_version"),
                rs.getString("status"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                (String) rs.getObject("revoked_by"), revokedAt == null ? null : revokedAt.toInstant(),
                (String) rs.getObject("revoked_reason"), rs.getString("package_json"));
    };

    private final JdbcTemplate jdbc;

    public ArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertWithdrawal(WithdrawalRow row) {
        jdbc.update("INSERT INTO release_withdrawal (document_id, published_version, withdrawn_by, reason, "
                        + "withdrawn_at) VALUES (?, ?, ?, ?, ?)",
                row.documentId(), row.publishedVersion(), row.withdrawnBy(), row.reason(),
                Timestamp.from(row.withdrawnAt()));
    }

    public Optional<WithdrawalRow> findWithdrawal(long documentId, int publishedVersion) {
        List<WithdrawalRow> rows = jdbc.query(
                "SELECT document_id, published_version, withdrawn_by, reason, withdrawn_at "
                        + "FROM release_withdrawal WHERE document_id = ? AND published_version = ?",
                WITHDRAWAL_MAPPER, documentId, publishedVersion);
        return rows.stream().findFirst();
    }

    public void insertTermRetirement(TermRetirementRow row) {
        jdbc.update("INSERT INTO term_retirement (document_id, term_version, retired_by, reason, retired_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.documentId(), row.termVersion(), row.retiredBy(), row.reason(),
                Timestamp.from(row.retiredAt()));
    }

    public Optional<TermRetirementRow> findTermRetirement(long documentId, int termVersion) {
        List<TermRetirementRow> rows = jdbc.query(
                "SELECT document_id, term_version, retired_by, reason, retired_at "
                        + "FROM term_retirement WHERE document_id = ? AND term_version = ?",
                RETIREMENT_MAPPER, documentId, termVersion);
        return rows.stream().findFirst();
    }

    /** 插入归档包并返回自增 archiveId；同文档同发布版本唯一约束兜底重复归档。 */
    public long insertArchivePackage(long documentId, int publishedVersion, String createdBy, Instant createdAt,
                                     String packageJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO archive_package (document_id, published_version, status, created_by, created_at, "
                            + "package_json) VALUES (?, ?, 'ACTIVE', ?, ?, ?)",
                    new String[]{"archive_id"});
            ps.setLong(1, documentId);
            ps.setInt(2, publishedVersion);
            ps.setString(3, createdBy);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            ps.setString(5, packageJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("归档包创建后未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<ArchivePackageRow> findArchiveByVersion(long documentId, int publishedVersion) {
        List<ArchivePackageRow> rows = jdbc.query(selectArchiveColumns() + " WHERE document_id = ? "
                + "AND published_version = ?", PACKAGE_MAPPER, documentId, publishedVersion);
        return rows.stream().findFirst();
    }

    public Optional<ArchivePackageRow> findArchiveById(long documentId, long archiveId) {
        List<ArchivePackageRow> rows = jdbc.query(selectArchiveColumns() + " WHERE document_id = ? AND archive_id = ?",
                PACKAGE_MAPPER, documentId, archiveId);
        return rows.stream().findFirst();
    }

    public List<ArchivePackageRow> listArchives(long documentId) {
        return jdbc.query(selectArchiveColumns() + " WHERE document_id = ? "
                + "ORDER BY created_at, archive_id", PACKAGE_MAPPER, documentId);
    }

    /** 按创建时刻左闭右开列出归档包：from/to 为 UTC，null 表示该侧不限制；按时刻与 ID 稳定升序。 */
    public List<ArchivePackageRow> listArchivesBetween(long documentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(selectArchiveColumns())
                .append(" WHERE document_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(documentId);
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY created_at, archive_id");
        return jdbc.query(sql.toString(), PACKAGE_MAPPER, args.toArray());
    }

    /** 撤销归档：仅 ACTIVE 行可被更新，返回受影响行数（0 表示已被并发撤销）。 */
    public int markArchiveRevoked(long archiveId, String revokedBy, Instant revokedAt, String reason) {
        return jdbc.update("UPDATE archive_package SET status = 'REVOKED', revoked_by = ?, revoked_at = ?, "
                + "revoked_reason = ? WHERE archive_id = ? AND status = 'ACTIVE'",
                revokedBy, Timestamp.from(revokedAt), reason, archiveId);
    }

    private static String selectArchiveColumns() {
        return "SELECT archive_id, document_id, published_version, status, created_by, created_at, revoked_by, "
                + "revoked_at, revoked_reason, package_json FROM archive_package";
    }
}
