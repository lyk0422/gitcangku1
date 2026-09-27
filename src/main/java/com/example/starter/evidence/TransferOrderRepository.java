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
 * 移交令表访问。令版本全局唯一；撤销仅更新状态与撤销时间，不删除记录。
 */
@Repository
public class TransferOrderRepository {

    private static final TransferOrderRowMapper ROW_MAPPER = new TransferOrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记移交令，初始状态 VALID。
     */
    public void insert(String orderVersion, String sourceCaseKey, String targetCaseKey,
                       String createdBy, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO transfer_order
                            (order_version, source_case_key, target_case_key, status, created_by, created_at, revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL)
                        """,
                orderVersion, sourceCaseKey, targetCaseKey,
                TransferOrderStatus.VALID.name(), createdBy, nowUtc);
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
     * 按令版本查询并锁定行（SELECT ... FOR UPDATE），用于撤销与移交校验的串行化。
     */
    public Optional<TransferOrder> findByVersionForUpdate(String orderVersion) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_version = ? FOR UPDATE", ROW_MAPPER, orderVersion);
        return rows.stream().findFirst();
    }

    /**
     * 撤销移交令；仅当仍为 VALID 时生效。
     *
     * @return 是否成功撤销（false 表示已被并发撤销）
     */
    public boolean revoke(String orderVersion, LocalDateTime revokedAtUtc) {
        int updated = jdbc.update(
                "UPDATE transfer_order SET status = ?, revoked_at = ? WHERE order_version = ? AND status = ?",
                TransferOrderStatus.REVOKED.name(), revokedAtUtc, orderVersion,
                TransferOrderStatus.VALID.name());
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
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class));
        }
    }
}
