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
 * 移交令版本表访问。有效期按 UTC 左闭右开解释：[validFrom, validTo)。
 */
@Repository
public class TransferOrderRepository {

    private static final TransferOrderRowMapper ROW_MAPPER = new TransferOrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记或续期移交令版本：不存在则插入，已存在则更新有效期（不删除历史版本）。
     */
    public void upsert(String orderVersion, LocalDateTime validFrom, LocalDateTime validTo,
                       LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE transfer_order SET valid_from = ?, valid_to = ? WHERE order_version = ?",
                validFrom, validTo, orderVersion);
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO transfer_order (order_version, valid_from, valid_to, created_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    orderVersion, validFrom, validTo, now);
        }
    }

    /**
     * 按版本查询移交令。
     */
    public Optional<TransferOrder> findByVersion(String orderVersion) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_version = ?", ROW_MAPPER, orderVersion);
        return rows.stream().findFirst();
    }

    private static final class TransferOrderRowMapper implements RowMapper<TransferOrder> {
        @Override
        public TransferOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferOrder(
                    rs.getString("order_version"),
                    rs.getObject("valid_from", LocalDateTime.class),
                    rs.getObject("valid_to", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
