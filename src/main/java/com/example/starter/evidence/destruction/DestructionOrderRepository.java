package com.example.starter.evidence.destruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 销毁令表访问。销毁令行锁（SELECT ... FOR UPDATE）串行化同一销毁令的
 * 审批同意/拒绝/执行推进；条件更新只允许从 PENDING/APPROVED 向前推进。
 */
@Repository
public class DestructionOrderRepository {

    private static final DestructionOrderRowMapper ROW_MAPPER = new DestructionOrderRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建 PENDING 销毁令；destruction_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String destructionKey, String submitterId, String legalBasis,
                       String destructionMethod, boolean forceIncludeBroken, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_order
                            (destruction_key, submitter_id, legal_basis, destruction_method,
                             force_include_broken, status, reject_reason, rejected_by, rejected_at,
                             approved_at, destroyed_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                destructionKey, submitterId, legalBasis, destructionMethod,
                forceIncludeBroken ? 1 : 0, DestructionStatus.PENDING.name(), now, now);
    }

    /**
     * 按业务键查询销毁令（不加锁），用于只读明细查询。
     */
    public Optional<DestructionOrder> findByKey(String destructionKey) {
        List<DestructionOrder> rows = jdbc.query(
                "SELECT * FROM destruction_order WHERE destruction_key = ?",
                ROW_MAPPER, destructionKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定销毁令行，用于审批/拒绝/执行等状态推进。
     */
    public Optional<DestructionOrder> findByKeyForUpdate(String destructionKey) {
        List<DestructionOrder> rows = jdbc.query(
                "SELECT * FROM destruction_order WHERE destruction_key = ? FOR UPDATE",
                ROW_MAPPER, destructionKey);
        return rows.stream().findFirst();
    }

    /**
     * 第二次同意时条件推进 PENDING -> APPROVED。
     *
     * @return 是否成功（false 表示已被并发拒绝/执行或状态不为 PENDING）
     */
    public boolean markApproved(long id, LocalDateTime approvedAt, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE destruction_order
                        SET status = ?, approved_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.APPROVED.name(), approvedAt, updatedAt,
                id, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 拒绝时条件推进 PENDING -> REJECTED，拒绝原因一次写入、不可改写。
     *
     * @return 是否成功（false 表示已被并发同意凑齐两人或状态不为 PENDING）
     */
    public boolean markRejected(long id, String reason, String rejectedBy,
                                LocalDateTime rejectedAt, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE destruction_order
                        SET status = ?, reject_reason = ?, rejected_by = ?, rejected_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.REJECTED.name(), reason, rejectedBy, rejectedAt, updatedAt,
                id, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 执行时条件推进 APPROVED -> DESTROYED。
     *
     * @return 是否成功（false 表示销毁令不处于 APPROVED）
     */
    public boolean markDestroyed(long id, LocalDateTime destroyedAt, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE destruction_order
                        SET status = ?, destroyed_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.DESTROYED.name(), destroyedAt, updatedAt,
                id, DestructionStatus.APPROVED.name());
        return updated == 1;
    }

    /**
     * 待审清单：全部 PENDING 销毁令，按创建顺序返回。
     */
    public List<DestructionOrder> findPending() {
        return jdbc.query(
                "SELECT * FROM destruction_order WHERE status = ? ORDER BY id",
                ROW_MAPPER, DestructionStatus.PENDING.name());
    }

    private static final class DestructionOrderRowMapper implements RowMapper<DestructionOrder> {
        @Override
        public DestructionOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionOrder(
                    rs.getLong("id"),
                    rs.getString("destruction_key"),
                    rs.getString("submitter_id"),
                    rs.getString("legal_basis"),
                    rs.getString("destruction_method"),
                    rs.getInt("force_include_broken") == 1,
                    DestructionStatus.valueOf(rs.getString("status")),
                    rs.getString("reject_reason"),
                    rs.getString("rejected_by"),
                    rs.getObject("rejected_at", LocalDateTime.class),
                    rs.getObject("approved_at", LocalDateTime.class),
                    rs.getObject("destroyed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
