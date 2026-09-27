package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 库位迁移单表访问。move_key 全局唯一；状态流转前必须通过
 * {@link #findByKeyForUpdate} 锁定迁移单行，保证确认/撤销并发按事务提交顺序生效。
 */
@Repository
public class MoveOrderRepository {

    private static final MoveOrderRowMapper ROW_MAPPER = new MoveOrderRowMapper();

    private final JdbcTemplate jdbc;

    public MoveOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新迁移单，初始状态 PENDING。move_key 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String moveKey, String requestHash, String applicantId, List<String> evidenceKeys,
                       String sourceLocation, String targetLocation, int expectedVersion,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO move_order
                            (move_key, request_hash, applicant_id, evidence_keys,
                             source_location, target_location, expected_version, status,
                             first_confirmer, first_confirmed_at, second_confirmer,
                             cancelled_by, decided_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                moveKey, requestHash, applicantId, String.join(",", evidenceKeys),
                sourceLocation, targetLocation, expectedVersion,
                MoveStatus.PENDING.name(), now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景与幂等重放。
     */
    public Optional<MoveOrder> findByKey(String moveKey) {
        List<MoveOrder> rows = jdbc.query(
                "SELECT * FROM move_order WHERE move_key = ?", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定迁移单行（SELECT ... FOR UPDATE），用于确认与撤销。
     */
    public Optional<MoveOrder> findByKeyForUpdate(String moveKey) {
        List<MoveOrder> rows = jdbc.query(
                "SELECT * FROM move_order WHERE move_key = ? FOR UPDATE", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 记录首人确认：PENDING -> FIRST_CONFIRMED；仅当仍为 PENDING 时生效。
     *
     * @return 是否成功（false 表示已被并发事务先行流转）
     */
    public boolean markFirstConfirmed(long id, String confirmer, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE move_order
                        SET status = ?, first_confirmer = ?, first_confirmed_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.FIRST_CONFIRMED.name(), confirmer, now, now,
                id, MoveStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 记录第二人确认完成：FIRST_CONFIRMED -> COMPLETED；仅当仍为 FIRST_CONFIRMED 时生效。
     *
     * @return 是否成功（false 表示已被并发事务先行流转）
     */
    public boolean markCompleted(long id, String secondConfirmer, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE move_order
                        SET status = ?, second_confirmer = ?, decided_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.COMPLETED.name(), secondConfirmer, now, now,
                id, MoveStatus.FIRST_CONFIRMED.name());
        return updated == 1;
    }

    /**
     * 撤销迁移单：FIRST_CONFIRMED -> CANCELLED；仅当仍为 FIRST_CONFIRMED 时生效。
     *
     * @return 是否成功（false 表示已被并发事务先行流转）
     */
    public boolean markCancelled(long id, String cancelledBy, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE move_order
                        SET status = ?, cancelled_by = ?, decided_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.CANCELLED.name(), cancelledBy, now, now,
                id, MoveStatus.FIRST_CONFIRMED.name());
        return updated == 1;
    }

    private static final class MoveOrderRowMapper implements RowMapper<MoveOrder> {
        @Override
        public MoveOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MoveOrder(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("request_hash"),
                    rs.getString("applicant_id"),
                    Arrays.asList(rs.getString("evidence_keys").split(",")),
                    rs.getString("source_location"),
                    rs.getString("target_location"),
                    rs.getInt("expected_version"),
                    MoveStatus.valueOf(rs.getString("status")),
                    rs.getString("first_confirmer"),
                    rs.getObject("first_confirmed_at", LocalDateTime.class),
                    rs.getString("second_confirmer"),
                    rs.getString("cancelled_by"),
                    rs.getObject("decided_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
