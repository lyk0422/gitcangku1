package com.example.starter.pass.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求记录数据访问。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequestPo> MAPPER = (rs, n) -> {
        IdempotentRequestPo po = new IdempotentRequestPo();
        po.setRequestId(rs.getString("request_id"));
        po.setOperation(rs.getString("operation"));
        po.setPayloadHash(rs.getString("payload_hash"));
        po.setAllocationId(rs.getLong("allocation_id"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入幂等记录。
     *
     * @return true 插入成功（首次请求）；false 触发唯一约束（requestId 已存在）
     */
    public boolean tryInsert(String requestId, String operation, String payloadHash,
                             long allocationId, Instant now) {
        int rows = jdbcTemplate.update(
                "INSERT INTO idempotent_request(request_id, operation, payload_hash, allocation_id, created_at) "
                        + "VALUES(?, ?, ?, ?, ?)",
                requestId, operation, payloadHash, allocationId, Timestamp.from(now));
        return rows == 1;
    }

    /** 按 requestId 查（含行锁）。 */
    public Optional<IdempotentRequestPo> lockByRequestId(String requestId) {
        return jdbcTemplate.query(
                        "SELECT * FROM idempotent_request WHERE request_id = ? FOR UPDATE", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 按 requestId 查。 */
    public Optional<IdempotentRequestPo> findByRequestId(String requestId) {
        List<IdempotentRequestPo> list = jdbcTemplate.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst();
    }

    /** 业务落库后回填关联时隙主键（占位记录 allocation_id=0）。 */
    public int bindAllocation(String requestId, long allocationId) {
        return jdbcTemplate.update(
                "UPDATE idempotent_request SET allocation_id = ? WHERE request_id = ? AND allocation_id = 0",
                allocationId, requestId);
    }
}
