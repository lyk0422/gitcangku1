package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 移交令表访问。order_version 全局唯一；撤销仅允许一次条件更新（status 必须仍为 ACTIVE）。
 */
@Repository
public class TransferOrderRepository {

    private static final TransferOrderRowMapper ROW_MAPPER = new TransferOrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 签发一份移交令，初始状态 ACTIVE；order_version 冲突由唯一约束拒绝。
     */
    public void insert(String orderVersion, String sourceCaseKey, String targetCaseKey,
                       LocalDateTime validFrom, LocalDateTime validUntil,
                       String createdBy, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO transfer_order
                            (order_version, source_case_key, target_case_key, status,
                             valid_from, valid_until, created_by, created_at, revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                orderVersion, sourceCaseKey, targetCaseKey, TransferOrderStatus.ACTIVE.name(),
                validFrom, validUntil, createdBy, nowUtc);
    }

    /**
     * 按令版本查询。
     */
    public Optional<TransferOrder> findByVersion(String orderVersion) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_version = ?", ROW_MAPPER, orderVersion);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当令仍为 ACTIVE 时置为 REVOKED。
     *
     * @return 是否成功撤销（false 表示已被并发撤销或令不存在）
     */
    public boolean revoke(String orderVersion, LocalDateTime revokedAt) {
        int updated = jdbc.update(
                "UPDATE transfer_order SET status = ?, revoked_at = ? WHERE order_version = ? AND status = ?",
                TransferOrderStatus.REVOKED.name(), revokedAt, orderVersion,
                TransferOrderStatus.ACTIVE.name());
        return updated == 1;
    }

    private static final class TransferOrderRowMapper implements RowMapper<TransferOrder> {
        @Override
        public TransferOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferOrder(
                    rs.getLong("id"),
                    rs.getString("order_version"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    TransferOrderStatus.valueOf(rs.getString("status")),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_until", LocalDateTime.class),
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class));
        }
    }
}
