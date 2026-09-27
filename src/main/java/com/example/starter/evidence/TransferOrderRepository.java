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
 * 移交令表访问。令登记后不可修改，不提供任何更新语句。
 */
@Repository
public class TransferOrderRepository {

    private static final OrderRowMapper ROW_MAPPER = new OrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记移交令；order_version 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String orderVersion, String sourceCaseKey, String targetCaseKey,
                       LocalDateTime validFrom, LocalDateTime validTo,
                       String createdBy, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO transfer_order
                            (order_version, source_case_key, target_case_key, valid_from, valid_to, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                orderVersion, sourceCaseKey, targetCaseKey, validFrom, validTo, createdBy, nowUtc);
    }

    /**
     * 按令版本查询。
     */
    public Optional<TransferOrder> findByVersion(String orderVersion) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_version = ?", ROW_MAPPER, orderVersion);
        return rows.stream().findFirst();
    }

    private static final class OrderRowMapper implements RowMapper<TransferOrder> {
        @Override
        public TransferOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferOrder(
                    rs.getLong("id"),
                    rs.getString("order_version"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_to", LocalDateTime.class),
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
