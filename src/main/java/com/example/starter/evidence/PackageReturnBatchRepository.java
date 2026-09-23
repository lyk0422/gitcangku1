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

/**
 * 组合包归还批次表访问。批次只追加；双人确认与逐件快照在同一事务落库。
 */
@Repository
public class PackageReturnBatchRepository {

    private static final BatchRowMapper ROW_MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbc;

    public PackageReturnBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个归还批次。
     *
     * @return 新批次主键
     */
    public long insert(long packageId, String receiverId, String reviewerId, int batchSeq,
                       LocalDateTime returnedAt, boolean closedPackage, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO package_return_batch
                                (package_id, receiver_id, reviewer_id, batch_seq,
                                 returned_at, closed_package, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, packageId);
            ps.setString(2, receiverId);
            ps.setString(3, reviewerId);
            ps.setInt(4, batchSeq);
            ps.setObject(5, returnedAt);
            ps.setInt(6, closedPackage ? 1 : 0);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("归还批次主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 按组合包查询全部归还批次（按提交顺序）。
     */
    public List<PackageReturnBatch> findByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_return_batch WHERE package_id = ? ORDER BY batch_seq",
                ROW_MAPPER, packageId);
    }

    private static final class BatchRowMapper implements RowMapper<PackageReturnBatch> {
        @Override
        public PackageReturnBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PackageReturnBatch(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("receiver_id"),
                    rs.getString("reviewer_id"),
                    rs.getInt("batch_seq"),
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getInt("closed_package") == 1,
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
