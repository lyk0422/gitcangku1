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
 * 导出包与明细表访问。包与明细只追加；request_id 与 package_id 均全局唯一。
 */
@Repository
public class ExportPackageRepository {

    private static final ExportPackageRowMapper PACKAGE_MAPPER = new ExportPackageRowMapper();
    private static final ExportItemRowMapper ITEM_MAPPER = new ExportItemRowMapper();

    private final JdbcTemplate jdbc;

    public ExportPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加导出包主记录。
     */
    public void insertPackage(String packageId, String requestId, String firstEvidenceKey,
                              int firstVersionNo, String firstSha256, String witness1,
                              String witness2, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO export_package
                            (package_id, request_id, evidence_key, version_no, content_sha256,
                             witness1, witness2, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                packageId, requestId, firstEvidenceKey, firstVersionNo, firstSha256,
                witness1, witness2, now);
    }

    /**
     * 追加一条导出包明细。
     */
    public void insertItem(String packageId, String evidenceKey, int versionNo, String contentRef,
                           String contentSha256, String witnessIdsCsv) {
        jdbc.update("""
                        INSERT INTO export_item
                            (package_id, evidence_key, version_no, content_ref, content_sha256, witness_ids)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                packageId, evidenceKey, versionNo, contentRef, contentSha256, witnessIdsCsv);
    }

    /**
     * 按导出 requestId 查询首次包（不含明细）。
     */
    public Optional<ExportPackage> findByRequestId(String requestId) {
        List<ExportPackage> rows = jdbc.query(
                "SELECT * FROM export_package WHERE request_id = ?", PACKAGE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按包标识查询主记录（不含明细）。
     */
    public Optional<ExportPackage> findByPackageId(String packageId) {
        List<ExportPackage> rows = jdbc.query(
                "SELECT * FROM export_package WHERE package_id = ?", PACKAGE_MAPPER, packageId);
        return rows.stream().findFirst();
    }

    /**
     * 按明细顺序查询包内全部条目。
     */
    public List<ExportItem> findItems(String packageId) {
        return jdbc.query(
                "SELECT * FROM export_item WHERE package_id = ? ORDER BY id",
                ITEM_MAPPER, packageId);
    }

    /**
     * 按导出时间顺序查询证物全部历史导出包主记录。
     */
    public List<ExportPackage> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM export_package WHERE evidence_key = ? ORDER BY id",
                PACKAGE_MAPPER, evidenceKey);
    }

    /**
     * 按导出时间顺序查询所有包含该证物（出现在明细中）的导出包主记录。
     */
    public List<ExportPackage> findPackagesContainingEvidence(String evidenceKey) {
        return jdbc.query("""
                        SELECT p.* FROM export_package p
                        INNER JOIN export_item i ON i.package_id = p.package_id
                        WHERE i.evidence_key = ?
                        ORDER BY p.id
                        """,
                PACKAGE_MAPPER, evidenceKey);
    }

    private static final class ExportPackageRowMapper implements RowMapper<ExportPackage> {
        @Override
        public ExportPackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExportPackage(
                    rs.getLong("id"),
                    rs.getString("package_id"),
                    rs.getString("request_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("content_sha256"),
                    rs.getString("witness1"),
                    rs.getString("witness2"),
                    List.of(),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class ExportItemRowMapper implements RowMapper<ExportItem> {
        @Override
        public ExportItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExportItem(
                    rs.getLong("id"),
                    rs.getString("package_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("content_ref"),
                    rs.getString("content_sha256"),
                    rs.getString("witness_ids"));
        }
    }
}
