package com.example.starter.custody;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 批次表访问:拆分与移交通过 {@link #lockByCode} 行锁串行化,保证并发守恒。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<BatchRecord> MAPPER = (rs, n) -> new BatchRecord(
            rs.getLong("id"),
            rs.getString("batch_code"),
            rs.getString("voucher_no"),
            rs.getString("species"),
            rs.getInt("weight_kg"),
            rs.getObject("parent_id", Long.class),
            rs.getString("holder"),
            BatchStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(BatchRecord batch) {
        jdbc.update("""
                INSERT INTO batch (batch_code, voucher_no, species, weight_kg, parent_id, holder, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                batch.batchCode(), batch.voucherNo(), batch.species(), batch.weightKg(),
                batch.parentId(), batch.holder(), batch.status().name());
    }

    public Optional<BatchRecord> findByCode(String batchCode) {
        List<BatchRecord> rows = jdbc.query(
                "SELECT * FROM batch WHERE batch_code = ?", MAPPER, batchCode);
        return rows.stream().findFirst();
    }

    /** 按编号加行锁读取,须在事务内调用;不存在时返回空。 */
    public Optional<BatchRecord> lockByCode(String batchCode) {
        List<BatchRecord> rows = jdbc.query(
                "SELECT * FROM batch WHERE batch_code = ? FOR UPDATE", MAPPER, batchCode);
        return rows.stream().findFirst();
    }

    public Optional<BatchRecord> findById(long id) {
        List<BatchRecord> rows = jdbc.query(
                "SELECT * FROM batch WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsByCode(String batchCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_code = ?", Integer.class, batchCode);
        return count != null && count > 0;
    }

    public void markSplit(long id) {
        jdbc.update("UPDATE batch SET status = ? WHERE id = ?", BatchStatus.SPLIT.name(), id);
    }

    public void updateHolder(long id, String holder) {
        jdbc.update("UPDATE batch SET holder = ? WHERE id = ?", holder, id);
    }

    public List<BatchRecord> findChildren(long parentId) {
        return jdbc.query(
                "SELECT * FROM batch WHERE parent_id = ? ORDER BY id", MAPPER, parentId);
    }
}