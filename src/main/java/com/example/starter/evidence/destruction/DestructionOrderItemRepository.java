package com.example.starter.evidence.destruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 销毁令入列证物表访问。创建时写入后不可变，不提供任何更新语句。
 * 冻结关系通过与 destruction_order 的连接查询得出：仅 PENDING/APPROVED 销毁令冻结证物。
 */
@Repository
public class DestructionOrderItemRepository {

    private final JdbcTemplate jdbc;

    public DestructionOrderItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加入列证物（批量，按提交原序写 position）。
     */
    public void insert(String destructionKey, List<String> evidenceKeys) {
        for (int i = 0; i < evidenceKeys.size(); i++) {
            jdbc.update("""
                            INSERT INTO destruction_order_item (destruction_key, evidence_key, position)
                            VALUES (?, ?, ?)
                            """,
                    destructionKey, evidenceKeys.get(i), i);
        }
    }

    /**
     * 按提交原序查询销毁令全部入列证物键。
     */
    public List<String> findEvidenceKeys(String destructionKey) {
        return jdbc.query("""
                        SELECT evidence_key FROM destruction_order_item
                        WHERE destruction_key = ? ORDER BY position
                        """,
                (rs, rowNum) -> rs.getString("evidence_key"), destructionKey);
    }

    /**
     * 查询冻结该证物的未终结（PENDING/APPROVED）销毁令键。调用方须先持有证物行锁。
     */
    public Optional<String> findActiveFreezeKey(String evidenceKey) {
        List<String> rows = jdbc.query("""
                        SELECT i.destruction_key
                        FROM destruction_order_item i
                        JOIN destruction_order o ON o.destruction_key = i.destruction_key
                        WHERE i.evidence_key = ? AND o.status IN (?, ?)
                        ORDER BY i.id
                        """,
                (rs, rowNum) -> rs.getString("destruction_key"),
                evidenceKey, DestructionStatus.PENDING.name(), DestructionStatus.APPROVED.name());
        return rows.stream().findFirst();
    }

    private static final class ItemRowMapper implements RowMapper<DestructionOrderItem> {
        @Override
        public DestructionOrderItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionOrderItem(
                    rs.getLong("id"),
                    rs.getString("destruction_key"),
                    rs.getString("evidence_key"),
                    rs.getInt("position"));
        }
    }
}
