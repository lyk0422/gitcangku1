package com.example.starter.repo;

import com.example.starter.domain.PollenBatch;
import com.example.starter.domain.PollenBatch.BatchStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 花粉批次表数据访问。
 */
@Repository
public class PollenBatchRepository {

    private static final RowMapper<PollenBatch> MAPPER = (rs, rowNum) -> new PollenBatch(
            rs.getLong("id"),
            rs.getString("batch_no"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PollenBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建可用状态的花粉批次，返回含自增主键的完整记录。 */
    public PollenBatch insert(String batchNo, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pollen_batch (batch_no, status, created_at) VALUES (?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setTimestamp(2, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return new PollenBatch(keys.getKey().longValue(), batchNo, BatchStatus.ACTIVE, createdAt);
    }

    /** 按批号查询。 */
    public Optional<PollenBatch> findByBatchNo(String batchNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM pollen_batch WHERE batch_no = ?", MAPPER, batchNo));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /** 停用批次（仅当当前为 ACTIVE 时生效），返回受影响行数。 */
    public int disable(String batchNo) {
        return jdbc.update(
                "UPDATE pollen_batch SET status = 'DISABLED' WHERE batch_no = ? AND status = 'ACTIVE'",
                batchNo);
    }
}