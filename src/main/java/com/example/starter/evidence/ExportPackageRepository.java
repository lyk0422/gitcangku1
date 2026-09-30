package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 导出包与包清单表访问。包与清单只追加；request_id、package_key 唯一。
 * 所有读写都在已按序锁定包内全部证物行的事务内进行，保证裁决基于提交时的完整版本。
 */
@Repository
public class ExportPackageRepository {

    private static final PackageRowMapper PACKAGE_MAPPER = new PackageRowMapper();
    private static final ItemRowMapper ITEM_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public ExportPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入导出包并回填自增主键。
     */
    public ExportPackage insert(String packageKey, String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO export_package (package_key, request_id, created_at) "
                            + "VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, packageKey);
            ps.setString(2, requestId);
            ps.setObject(3, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return new ExportPackage(id, packageKey, requestId, now);
    }

    /**
     * 追加一条包清单条目（含建包裁决时该证物的版本号）。
     */
    public void insertItem(long packageId, String evidenceKey, int versionNo, String contentSha256) {
        jdbc.update("""
                        INSERT INTO export_package_item (package_id, evidence_key, version_no, content_sha256)
                        VALUES (?, ?, ?, ?)
                        """,
                packageId, evidenceKey, versionNo, contentSha256);
    }

    /**
     * 按请求幂等键查询已生成的包；重放判定使用。
     */
    public Optional<ExportPackage> findByRequestId(String requestId) {
        List<ExportPackage> rows = jdbc.query(
                "SELECT * FROM export_package WHERE request_id = ?", PACKAGE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按包标识查询。
     */
    public Optional<ExportPackage> findByPackageKey(String packageKey) {
        List<ExportPackage> rows = jdbc.query(
                "SELECT * FROM export_package WHERE package_key = ?", PACKAGE_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询某包的全部清单条目（按证物键排序保证输出稳定）。
     */
    public List<ExportPackageItem> findItemsByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM export_package_item WHERE package_id = ? ORDER BY evidence_key",
                ITEM_MAPPER, packageId);
    }

    /**
     * 查询某请求键对应包的全部清单条目。
     */
    public List<ExportPackageItem> findItemsByRequestId(String requestId) {
        return jdbc.query("""
                        SELECT i.* FROM export_package_item i
                        JOIN export_package p ON p.id = i.package_id
                        WHERE p.request_id = ? ORDER BY i.evidence_key
                        """,
                ITEM_MAPPER, requestId);
    }

    /**
     * 按关联证物查询涉及该证物的全部历史导出包标识（去重、按生成顺序）。
     */
    public List<String> findPackageKeysByEvidenceKey(String evidenceKey) {
        return jdbc.queryForList("""
                        SELECT DISTINCT p.package_key FROM export_package p
                        JOIN export_package_item i ON i.package_id = p.id
                        WHERE i.evidence_key = ? ORDER BY p.id
                        """,
                String.class, evidenceKey);
    }

    private static final class PackageRowMapper implements RowMapper<ExportPackage> {
        @Override
        public ExportPackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExportPackage(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("request_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class ItemRowMapper implements RowMapper<ExportPackageItem> {
        @Override
        public ExportPackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExportPackageItem(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("content_sha256"));
        }
    }
}
