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
 * 移交令版本表访问。版本按 [effective_from, effective_to) UTC 左闭右开区间生效，
 * effective_to 为 null 表示长期有效；区间判定使用统一的 {@link EvidenceClock}。
 */
@Repository
public class TransferOrderRepository {

    private static final TransferOrderRowMapper ROW_MAPPER = new TransferOrderRowMapper();

    private final JdbcTemplate jdbc;

    public TransferOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记移交令版本（同编号同版本已存在则忽略），用于合成数据与测试夹具准备。
     */
    public void register(String orderKey, String orderVersion, String fromCaseKey, String toCaseKey,
                         LocalDateTime effectiveFrom, LocalDateTime effectiveTo, LocalDateTime now) {
        jdbc.update("""
                        MERGE INTO transfer_order
                            (order_key, order_version, from_case_key, to_case_key,
                             effective_from, effective_to, created_at)
                        KEY (order_key, order_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                orderKey, orderVersion, fromCaseKey, toCaseKey,
                effectiveFrom, effectiveTo, now);
    }

    /**
     * 按编号与版本查询令版本。
     */
    public Optional<TransferOrder> findVersion(String orderKey, String orderVersion) {
        List<TransferOrder> rows = jdbc.query(
                "SELECT * FROM transfer_order WHERE order_key = ? AND order_version = ?",
                ROW_MAPPER, orderKey, orderVersion);
        return rows.stream().findFirst();
    }

    private static final class TransferOrderRowMapper implements RowMapper<TransferOrder> {
        @Override
        public TransferOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferOrder(
                    rs.getLong("id"),
                    rs.getString("order_key"),
                    rs.getString("order_version"),
                    rs.getString("from_case_key"),
                    rs.getString("to_case_key"),
                    rs.getObject("effective_from", LocalDateTime.class),
                    rs.getObject("effective_to", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
