package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchSubmit;

/**
 * 批量测量提交幂等台账持久化。仅成功提交写入，失败不占键；
 * 相同 batchId 重放返回首次台账，不同载荷由调用方比对 fingerprint 后判 409。
 */
@Repository
public class BatchSubmitRepository {

    private static final RowMapper<BatchSubmit> MAPPER = (rs, rowNum) -> new BatchSubmit(
            rs.getString("batch_id"),
            rs.getString("submitted_by"),
            rs.getInt("item_count"),
            rs.getString("fingerprint"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchSubmitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入成功提交台账；batchId 已存在时抛出 DuplicateKeyException（由调用方转重放/冲突）。
     */
    public void insert(BatchSubmit batchSubmit) {
        jdbc.update("INSERT INTO batch_submit (batch_id, submitted_by, item_count, fingerprint, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batchSubmit.batchId(), batchSubmit.submittedBy(), batchSubmit.itemCount(),
                batchSubmit.fingerprint(), JdbcTimes.toDb(batchSubmit.createdAt()));
    }

    /**
     * 按 batchId 查询台账。
     */
    public Optional<BatchSubmit> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM batch_submit WHERE batch_id = ?", MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 是否为 batch_id 唯一冲突（区别于其他唯一键）；H2/MySQL 违例消息中的标识符大小写不一，统一大写判断。
     */
    public boolean isBatchIdDuplicate(DuplicateKeyException ex) {
        return ex.getMessage() != null && ex.getMessage().toUpperCase().contains("BATCH_ID");
    }
}
