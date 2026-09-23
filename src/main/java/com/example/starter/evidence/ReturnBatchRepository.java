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
 * 分批归还批次表访问。批次只追加、不可变，不提供更新/删除语句。
 */
@Repository
public class ReturnBatchRepository {

    private static final BatchRowMapper ROW_MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbc;

    public ReturnBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个归还批次并回填自增主键。
     */
    public long insert(long packageId, String receiverId, String reviewerId,
                       LocalDateTime returnedAt, String note, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO return_batch
                                (package_id, receiver_id, reviewer_id, returned_at, note, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, packageId);
            ps.setString(2, receiverId);
            ps.setString(3, reviewerId);
            ps.setObject(4, returnedAt);
            ps.setString(5, note);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("归还批次主键回填失败");
        }
        return key.longValue();
    }

    /**
     * 查询组合包全部归还批次，按批次号稳定排序。
     */
    public List<ReturnBatch> findByPackageIdOrdered(long packageId) {
        return jdbc.query(
                "SELECT * FROM return_batch WHERE package_id = ? ORDER BY id",
                ROW_MAPPER, packageId);
    }

    private static final class BatchRowMapper implements RowMapper<ReturnBatch> {
        @Override
        public ReturnBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReturnBatch(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("receiver_id"),
                    rs.getString("reviewer_id"),
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
