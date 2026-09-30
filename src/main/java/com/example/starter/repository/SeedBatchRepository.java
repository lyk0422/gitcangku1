package com.example.starter.repository;

import com.example.starter.domain.SeedBatch;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 种子批次表 seed_batch 数据访问。
 */
@Repository
public class SeedBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public SeedBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<SeedBatch> ROW_MAPPER = (rs, rowNum) -> new SeedBatch(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getString("crop_name"),
            rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 插入批次，返回生成的主键；批次编号重复时抛出 {@link DuplicateKeyException}。
     */
    public long insert(String batchNo, String cropName, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO seed_batch (batch_no, crop_name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, cropName);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批次编号查询批次。
     */
    public Optional<SeedBatch> findByBatchNo(String batchNo) {
        return jdbcTemplate.query(
                "SELECT id, batch_no, crop_name, created_at FROM seed_batch WHERE batch_no = ?",
                ROW_MAPPER, batchNo).stream().findFirst();
    }

    /**
     * 对批次行加行锁（SELECT ... FOR UPDATE），返回批次主键；不存在时返回空。
     * 必须在事务内调用，用于串接同一批次上的签发/作废/放行并发事务。
     */
    public Optional<Long> lockAndGetId(String batchNo) {
        return jdbcTemplate.query(
                "SELECT id FROM seed_batch WHERE batch_no = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), batchNo).stream().findFirst();
    }
}
