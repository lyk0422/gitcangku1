package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次逐件不可变归还快照表访问。快照只追加，不可修改。
 */
@Repository
public class PackageReturnItemRepository {

    private static final ReturnItemRowMapper ROW_MAPPER = new ReturnItemRowMapper();

    private final JdbcTemplate jdbc;

    public PackageReturnItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条批次逐件归还快照。
     */
    public void insert(long batchId, long packageId, String evidenceKey, long sealVersion,
                       int itemSeq, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO package_return_item
                            (batch_id, package_id, evidence_key, seal_version, item_seq, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                batchId, packageId, evidenceKey, sealVersion, itemSeq, now);
    }

    /**
     * 按批次查询逐件快照（按包内稳定排序序号）。
     */
    public List<PackageReturnItem> findByBatchId(long batchId) {
        return jdbc.query(
                "SELECT * FROM package_return_item WHERE batch_id = ? ORDER BY item_seq",
                ROW_MAPPER, batchId);
    }

    /**
     * 按组合包查询全部归还快照（按包内稳定排序序号），用于链路证据。
     */
    public List<PackageReturnItem> findByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_return_item WHERE package_id = ? ORDER BY item_seq",
                ROW_MAPPER, packageId);
    }

    private static final class ReturnItemRowMapper implements RowMapper<PackageReturnItem> {
        @Override
        public PackageReturnItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PackageReturnItem(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getLong("package_id"),
                    rs.getString("evidence_key"),
                    rs.getLong("seal_version"),
                    rs.getInt("item_seq"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
