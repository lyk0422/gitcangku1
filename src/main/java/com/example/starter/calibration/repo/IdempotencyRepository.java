package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotencyRecord;

/**
 * 幂等记录持久化。request_id 全局唯一；仅成功请求在事务内插入，失败回滚不占键。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("endpoint"),
            rs.getString("request_hash"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询（不加锁）。
     */
    public Optional<IdempotencyRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 插入幂等记录（须在事务内调用，随业务结果一同提交或回滚）。
     */
    public void insert(IdempotencyRecord record) {
        jdbc.update("INSERT INTO idempotency_record (request_id, endpoint, request_hash, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                record.requestId(),
                record.endpoint(),
                record.requestHash(),
                record.responseBody(),
                JdbcTimes.toDb(record.createdAt()));
    }
}