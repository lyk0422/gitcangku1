package com.example.starter.exposure.budget.repo;

import com.example.starter.exposure.budget.web.TransferEvidenceResponse.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 转移前后账本快照数据访问。随转移单在同一事务内冻结。
 */
@Repository
public class BudgetTransferSnapshotRepository {

    private static final RowMapper<Snapshot> MAPPER = (rs, rowNum) -> new Snapshot(
            rs.getString("campaign_id"),
            rs.getLong("version_before"),
            rs.getLong("version_after"),
            rs.getLong("budget_before"),
            rs.getLong("budget_after"),
            rs.getLong("confirmed_count"),
            rs.getLong("inflight_count"));

    private final JdbcTemplate jdbc;

    public BudgetTransferSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 冻结单活动前后快照。 */
    public void insert(String transferKey, Snapshot snapshot) {
        jdbc.update("INSERT INTO budget_transfer_snapshot (transfer_key, campaign_id, "
                        + "version_before, version_after, budget_before, budget_after, "
                        + "confirmed_count, inflight_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                transferKey,
                snapshot.campaignId(),
                snapshot.versionBefore(),
                snapshot.versionAfter(),
                snapshot.budgetBefore(),
                snapshot.budgetAfter(),
                snapshot.confirmedCount(),
                snapshot.inflightCount());
    }

    /** 只读查询某转移单全部快照，按 campaignId 升序稳定排序。 */
    public List<Snapshot> findByTransferKey(String transferKey) {
        return jdbc.query("SELECT campaign_id, version_before, version_after, budget_before, budget_after, "
                        + "confirmed_count, inflight_count FROM budget_transfer_snapshot "
                        + "WHERE transfer_key = ? ORDER BY campaign_id ASC",
                MAPPER, transferKey);
    }
}
