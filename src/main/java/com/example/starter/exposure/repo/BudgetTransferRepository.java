package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.TransferLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 预算转移单及规范化明细数据访问。转移单激活后只读，证据查询稳定排序。
 */
@Repository
public class BudgetTransferRepository {

    /**
     * 预算转移单视图。
     *
     * @param transferKey         转移单业务编号，全局唯一
     * @param requestId           激活请求的幂等键
     * @param normalizedLinesJson 规范化明细 JSON
     * @param beforeSnapshotJson  激活前账本快照 JSON
     * @param afterSnapshotJson   激活后账本快照 JSON
     * @param createdAtUtc        激活时刻，epoch 毫秒，UTC
     */
    public record BudgetTransferRecord(String transferKey, String requestId,
                                       String normalizedLinesJson, String beforeSnapshotJson,
                                       String afterSnapshotJson, long createdAtUtc) {
    }

    private static final RowMapper<BudgetTransferRecord> TRANSFER_MAPPER =
            (rs, rowNum) -> new BudgetTransferRecord(
                    rs.getString("transfer_key"),
                    rs.getString("request_id"),
                    rs.getString("normalized_lines_json"),
                    rs.getString("before_snapshot_json"),
                    rs.getString("after_snapshot_json"),
                    rs.getLong("created_at_utc"));

    private static final RowMapper<TransferLine> LINE_MAPPER = (rs, rowNum) -> new TransferLine(
            rs.getString("source_campaign_id"),
            rs.getString("target_campaign_id"),
            rs.getInt("amount"));

    private final JdbcTemplate jdbc;

    public BudgetTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入转移单；transfer_key 冲突由调用方依据唯一约束处理。 */
    public void insertTransfer(BudgetTransferRecord record) {
        jdbc.update("INSERT INTO budget_transfer "
                        + "(transfer_key, request_id, normalized_lines_json, "
                        + "before_snapshot_json, after_snapshot_json, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                record.transferKey(),
                record.requestId(),
                record.normalizedLinesJson(),
                record.beforeSnapshotJson(),
                record.afterSnapshotJson(),
                record.createdAtUtc());
    }

    /** 插入一条规范化明细，lineNo 须按（源,目标）字典序从 0 递增。 */
    public void insertLine(String transferKey, int lineNo, TransferLine line) {
        jdbc.update("INSERT INTO budget_transfer_line "
                        + "(transfer_key, line_no, source_campaign_id, target_campaign_id, amount) "
                        + "VALUES (?, ?, ?, ?, ?)",
                transferKey, lineNo, line.sourceCampaignId(), line.targetCampaignId(), line.amount());
    }

    /** 按编号查询转移单（无锁，只读证据）。 */
    public Optional<BudgetTransferRecord> findTransfer(String transferKey) {
        return jdbc.query("SELECT transfer_key, request_id, normalized_lines_json, "
                        + "before_snapshot_json, after_snapshot_json, created_at_utc "
                        + "FROM budget_transfer WHERE transfer_key = ?",
                        TRANSFER_MAPPER, transferKey)
                .stream()
                .findFirst();
    }

    /** 查询转移单规范化明细，按 line_no 稳定排序。 */
    public List<TransferLine> findLines(String transferKey) {
        return jdbc.query("SELECT source_campaign_id, target_campaign_id, amount "
                        + "FROM budget_transfer_line WHERE transfer_key = ? ORDER BY line_no",
                LINE_MAPPER, transferKey);
    }
}
