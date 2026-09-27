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
 * 移交令版本表访问。移交必须引用现行版本；生效区间左闭右开 [validFrom, validTo)。
 */
@Repository
public class TransferOrderRepository {

    private static final OrderRowMapper ROW_MAPPER = new OrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个移交令版本（order_no + version 唯一）。
     */
    public void insert(String orderNo, int version, String fromCaseKey, String toCaseKey,
                       OrderStatus status, LocalDateTime validFrom, LocalDateTime validTo,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transfer_order
                            (order_no, version, from_case_key, to_case_key, status,
                             valid_from, valid_to, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orderNo, version, fromCaseKey, toCaseKey, status.name(),
                validFrom, validTo, now);
    }

    /**
     * 按编号与版本查询移交令版本。
     */
    public Optional<TransferOrder> findByVersion(String orderNo, int version) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_no = ? AND version = ?",
                ROW_MAPPER, orderNo, version);
        return rows.stream().findFirst();
    }

    private static final class OrderRowMapper implements RowMapper<TransferOrder> {
        @Override
        public TransferOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferOrder(
                    rs.getLong("id"),
                    rs.getString("order_no"),
                    rs.getInt("version"),
                    rs.getString("from_case_key"),
                    rs.getString("to_case_key"),
                    OrderStatus.valueOf(rs.getString("status")),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_to", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
