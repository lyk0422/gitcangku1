package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次逐件保管链表访问。记录只追加、不可变，不提供更新/删除语句。
 */
@Repository
public class PackageReturnChainRepository {

    private static final ChainRowMapper ROW_MAPPER = new ChainRowMapper();

    private final JdbcTemplate jdbc;

    public PackageReturnChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条逐件保管链记录。
     */
    public void insert(long packageId, long batchId, String evidenceKey, long sealVersion,
                       String receiverId, String reviewerId, LocalDateTime eventAt) {
        jdbc.update("""
                        INSERT INTO package_return_chain
                            (package_id, batch_id, evidence_key, seal_version,
                             receiver_id, reviewer_id, event_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                packageId, batchId, evidenceKey, sealVersion,
                receiverId, reviewerId, eventAt);
    }

    /**
     * 查询组合包全部逐件保管链，按批次号、证物键稳定排序。
     */
    public List<PackageReturnChain> findByPackageIdOrdered(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_return_chain WHERE package_id = ? ORDER BY id, evidence_key",
                ROW_MAPPER, packageId);
    }

    /**
     * 查询单个批次的逐件保管链，按证物键稳定排序。
     */
    public List<PackageReturnChain> findByBatchIdOrdered(long batchId) {
        return jdbc.query(
                "SELECT * FROM package_return_chain WHERE batch_id = ? ORDER BY evidence_key",
                ROW_MAPPER, batchId);
    }

    private static final class ChainRowMapper implements RowMapper<PackageReturnChain> {
        @Override
        public PackageReturnChain mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PackageReturnChain(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getLong("batch_id"),
                    rs.getString("evidence_key"),
                    rs.getLong("seal_version"),
                    rs.getString("receiver_id"),
                    rs.getString("reviewer_id"),
                    rs.getObject("event_at", LocalDateTime.class));
        }
    }
}
