package com.example.starter.exposure.budget.repo;

import com.example.starter.exposure.budget.web.BudgetTransferActivateResponse.FrozenDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 预算转移单数据访问。transfer_key 主键唯一、request_id 唯一。
 */
@Repository
public class BudgetTransferRepository {

    /**
     * 转移单视图。
     *
     * @param transferKey    转移单业务唯一键
     * @param requestId      激活请求幂等键
     * @param tenantId       租户编号
     * @param windowStartUtc 窗口起点（含）
     * @param windowEndUtc   窗口终点（不含）
     * @param audienceRule   受众规则
     * @param detailsJson    冻结的规范化明细 JSON
     * @param status         转移单状态
     * @param activatedAtUtc 激活时刻
     */
    public record TransferRecord(
            String transferKey,
            String requestId,
            String tenantId,
            long windowStartUtc,
            long windowEndUtc,
            String audienceRule,
            String detailsJson,
            String status,
            long activatedAtUtc
    ) {
    }

    private static final RowMapper<TransferRecord> MAPPER = (rs, rowNum) -> new TransferRecord(
            rs.getString("transfer_key"),
            rs.getString("request_id"),
            rs.getString("tenant_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getString("audience_rule"),
            rs.getString("details_json"),
            rs.getString("status"),
            rs.getLong("activated_at_utc"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BudgetTransferRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(TransferRecord record, long createdAtUtc) {
        jdbc.update("INSERT INTO budget_transfer (transfer_key, request_id, tenant_id, "
                        + "window_start_utc, window_end_utc, audience_rule, details_json, status, "
                        + "created_at_utc, activated_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.transferKey(),
                record.requestId(),
                record.tenantId(),
                record.windowStartUtc(),
                record.windowEndUtc(),
                record.audienceRule(),
                record.detailsJson(),
                record.status(),
                createdAtUtc,
                record.activatedAtUtc());
    }

    public Optional<TransferRecord> findByTransferKey(String transferKey) {
        return jdbc.query("SELECT * FROM budget_transfer WHERE transfer_key = ?",
                        MAPPER, transferKey)
                .stream()
                .findFirst();
    }

    /** 行锁读取转移单。 */
    public Optional<TransferRecord> lockByTransferKey(String transferKey) {
        return jdbc.query("SELECT * FROM budget_transfer WHERE transfer_key = ? FOR UPDATE",
                        MAPPER, transferKey)
                .stream()
                .findFirst();
    }

    public Optional<TransferRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM budget_transfer WHERE request_id = ?",
                        MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /** 反序列化冻结明细；稳定排序由激活时保证。 */
    public List<FrozenDetail> parseDetails(String detailsJson) {
        try {
            return objectMapper.readValue(detailsJson, new TypeReference<List<FrozenDetail>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse frozen transfer details", e);
        }
    }

    /** 序列化规范化明细为冻结 JSON。 */
    public String writeDetails(List<FrozenDetail> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize transfer details", e);
        }
    }
}
