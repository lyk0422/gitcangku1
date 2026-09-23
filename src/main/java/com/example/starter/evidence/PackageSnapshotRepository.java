package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 不可变归还快照表访问。只追加，不提供更新/删除语句。
 */
@Repository
public class PackageSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public PackageSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条快照。
     */
    public void insert(long packageId, String snapshotType, Long batchId, String snapshotJson,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO package_snapshot
                            (package_id, snapshot_type, batch_id, snapshot_json, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                packageId, snapshotType, batchId, snapshotJson, now);
    }

    /**
     * 查询组合包全部快照，按生成顺序稳定排序。
     */
    public List<PackageSnapshot> findByPackageIdOrdered(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_snapshot WHERE package_id = ? ORDER BY id",
                ROW_MAPPER, packageId);
    }

    private static final class SnapshotRowMapper implements RowMapper<PackageSnapshot> {
        @Override
        public PackageSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PackageSnapshot(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("snapshot_type"),
                    rs.getObject("batch_id", Long.class),
                    rs.getString("snapshot_json"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
