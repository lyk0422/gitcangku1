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
 * 销毁令入列证物表访问。行只追加、创建后不可变；
 * 是否被未终结销毁令冻结通过与 destruction_order 的连接查询判定。
 */
@Repository
public class DestructionItemRepository {

    private static final DestructionItemRowMapper ROW_MAPPER = new DestructionItemRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加入列证物行。
     */
    public void insert(String destructionKey, String evidenceKey, EvidenceStatus includedStatus,
                      boolean forcedBroken, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_order_item
                            (destruction_key, evidence_key, included_status, forced_broken, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                destructionKey, evidenceKey, includedStatus.name(), forcedBroken ? 1 : 0, now);
    }

    /**
     * 按销毁键查询全部入列证物（按入列顺序）。
     */
    public List<DestructionOrderItem> findByDestructionKey(String destructionKey) {
        return jdbc.query(
                "SELECT * FROM destruction_order_item WHERE destruction_key = ? ORDER BY id",
                ROW_MAPPER, destructionKey);
    }

    /**
     * 查询冻结该证物的未终结（PENDING/APPROVED）销毁令键。
     * REJECTED 与 DESTROYED 的销毁令不再冻结证物。
     */
    public Optional<String> findOpenOrderKeyForEvidence(String evidenceKey) {
        List<String> keys = jdbc.query("""
                        SELECT i.destruction_key
                        FROM destruction_order_item i
                        JOIN destruction_order o ON o.destruction_key = i.destruction_key
                        WHERE i.evidence_key = ?
                          AND o.status IN (?, ?)
                        ORDER BY i.id
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getString("destruction_key"),
                evidenceKey, DestructionStatus.PENDING.name(), DestructionStatus.APPROVED.name());
        return keys.stream().findFirst();
    }

    private static final class DestructionItemRowMapper implements RowMapper<DestructionOrderItem> {
        @Override
        public DestructionOrderItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionOrderItem(
                    rs.getLong("id"),
                    rs.getString("destruction_key"),
                    rs.getString("evidence_key"),
                    EvidenceStatus.valueOf(rs.getString("included_status")),
                    rs.getInt("forced_broken") == 1,
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
