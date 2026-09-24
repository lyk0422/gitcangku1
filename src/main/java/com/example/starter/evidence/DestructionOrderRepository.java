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
 * 销毁令表访问。销毁令业务字段创建后不可更新；状态流转全部用条件更新（必须仍处于期望状态），
 * 保证并发审批/拒绝/执行按事务提交顺序裁决。
 */
@Repository
public class DestructionOrderRepository {

    private static final DestructionOrderRowMapper ROW_MAPPER = new DestructionOrderRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建 PENDING 销毁令；destruction_key 冲突时由唯一约束拒绝。
     */
    public void insert(String destructionKey, String submittedBy, String legalBasis,
                      String destructionMethod, boolean forceIncludeBroken, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_order
                            (destruction_key, submitted_by, legal_basis, destruction_method,
                             force_include_broken, status, reject_reason,
                             created_at, decided_at, executed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, ?)
                        """,
                destructionKey, submittedBy, legalBasis, destructionMethod,
                forceIncludeBroken ? 1 : 0, DestructionStatus.PENDING.name(), now, now);
    }

    /**
     * 按销毁键查询销毁令（不加锁），用于只读场景。
     */
    public Optional<DestructionOrder> findByKey(String destructionKey) {
        List<DestructionOrder> rows = jdbc.query(
                "SELECT * FROM destruction_order WHERE destruction_key = ?", ROW_MAPPER, destructionKey);
        return rows.stream().findFirst();
    }

    /**
     * 按销毁键查询并锁定销毁令行（SELECT ... FOR UPDATE），用于审批/拒绝/执行。
     */
    public Optional<DestructionOrder> findByKeyForUpdate(String destructionKey) {
        List<DestructionOrder> rows = jdbc.query(
                "SELECT * FROM destruction_order WHERE destruction_key = ? FOR UPDATE",
                ROW_MAPPER, destructionKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件状态流转：仅当当前状态等于 expected 时更新为 next 并刷新 updatedAt。
     *
     * @return 是否更新成功（false 表示已被并发事务先行流转）
     */
    public boolean compareAndUpdateStatus(String destructionKey, DestructionStatus expected,
                                       DestructionStatus next, LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE destruction_order SET status = ?, decided_at = ?, updated_at = ? "
                        + "WHERE destruction_key = ? AND status = ?",
                next.name(), now, now, destructionKey, expected.name());
        return updated == 1;
    }

    /**
     * 拒绝：写入拒绝原因并转 REJECTED；仅当仍为 PENDING 时生效，原因此后不可改写。
     *
     * @return 是否拒绝成功（false 表示销毁令已不在 PENDING）
     */
    public boolean reject(String destructionKey, String reason, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE destruction_order
                        SET status = ?, reject_reason = ?, decided_at = ?, updated_at = ?
                        WHERE destruction_key = ? AND status = ?
                        """,
                DestructionStatus.REJECTED.name(), reason, now, now,
                destructionKey, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 执行：转 DESTROYED 并写执行时间；仅当仍为 APPROVED 时生效。
     *
     * @return 是否执行成功（false 表示销毁令不在 APPROVED，含已执行或已被拒绝）
     */
    public boolean markExecuted(String destructionKey, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE destruction_order
                        SET status = ?, executed_at = ?, updated_at = ?
                        WHERE destruction_key = ? AND status = ?
                        """,
                DestructionStatus.DESTROYED.name(), now, now,
                destructionKey, DestructionStatus.APPROVED.name());
        return updated == 1;
    }

    /**
     * 查询全部待审批（PENDING）销毁令，按创建顺序。
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
                    rs.getString("submitted_by"),
                    rs.getString("legal_basis"),
                    rs.getString("destruction_method"),
                    rs.getInt("force_include_broken") == 1,
                    DestructionStatus.valueOf(rs.getString("status")),
                    rs.getString("reject_reason"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class),
                    rs.getObject("executed_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
