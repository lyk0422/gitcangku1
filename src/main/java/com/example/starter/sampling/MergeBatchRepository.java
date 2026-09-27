package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 批量合并幂等去重持久化：仅记录成功批次，与业务变更同事务原子提交；预校验失败回滚不占键。
 */
@Repository
public class MergeBatchRepository {

    private static final RowMapper<MergeBatchEntry> ENTRY_MAPPER = (rs, rowNum) -> new MergeBatchEntry(
            rs.getString("batch_id"),
            rs.getString("fingerprint"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public MergeBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按批次标识查询去重记录；不存在时返回空。
     */
    public Optional<MergeBatchEntry> find(String batchId) {
        return jdbcTemplate.query(
                        "SELECT batch_id, fingerprint, response_status, response_body "
                                + "FROM sampling_merge_batch WHERE batch_id = ?",
                        ENTRY_MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 在合并事务内先占位插入去重记录（响应暂为空），主键冲突时由数据库串行化并发同键请求。
     * 预校验失败时随事务回滚，不占用该键。
     */
    public void insertPlaceholder(String batchId, String fingerprint) {
        jdbcTemplate.update(
                "INSERT INTO sampling_merge_batch (batch_id, fingerprint, response_status, response_body, created_at) "
                        + "VALUES (?, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                batchId, fingerprint);
    }

    /**
     * 合并成功后回填响应，与业务变更在同一事务内提交。
     */
    public void complete(String batchId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE sampling_merge_batch SET response_status = ?, response_body = ? WHERE batch_id = ?",
                responseStatus, responseBody, batchId);
    }
}
