package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 采样许可指纹（permitKey）去重持久化：仅在采样接收成功的业务事务内插入，失败回滚不占键。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class PermitSampleKeyRepository {

    /**
     * permitKey 去重记录。
     *
     * @param permitKey     许可采样指纹
     * @param requestId     首次成功接收的请求标识（回溯 request_log 原响应）
     * @param observationId 对应观测标识
     * @param permitId      首次命中的许可标识
     */
    public record SampleKey(String permitKey, String requestId, String observationId, String permitId) {
    }

    private static final RowMapper<SampleKey> MAPPER = (rs, rowNum) -> new SampleKey(
            rs.getString("permit_key"),
            rs.getString("request_id"),
            rs.getString("observation_id"),
            rs.getString("permit_id"));

    private final JdbcTemplate jdbcTemplate;

    public PermitSampleKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 permitKey 查询已成功接收的指纹记录；调用方须持有设备裁决锁以读到串行化后的已提交结果。
     */
    public Optional<SampleKey> find(String permitKey) {
        return jdbcTemplate.query(
                        "SELECT permit_key, request_id, observation_id, permit_id "
                                + "FROM permit_sample_key WHERE permit_key = ?",
                        MAPPER, permitKey)
                .stream().findFirst();
    }

    /**
     * 登记一次成功接收的 permitKey，与观测写入、计数扣减在同一事务提交。
     */
    public void insert(SampleKey sampleKey) {
        jdbcTemplate.update(
                "INSERT INTO permit_sample_key (permit_key, request_id, observation_id, permit_id, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                sampleKey.permitKey(), sampleKey.requestId(), sampleKey.observationId(), sampleKey.permitId());
    }
}
