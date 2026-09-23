package com.example.starter.evidence.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 联合取样单表访问。request_id 与 aliquot_key 均全局唯一，由唯一约束拒绝复用。
 * 审核推进使用条件更新（状态与版本必须匹配），杜绝并发下重复确认或终态后再变更。
 */
@Repository
public class SamplingOrderRepository {

    private static final SamplingOrderRowMapper ROW_MAPPER = new SamplingOrderRowMapper();

    private final JdbcTemplate jdbc;

    public SamplingOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一张 PENDING 联合取样单；request_id/aliquot_key 冲突由唯一约束拒绝。
     */
    public void insert(String requestId, String aliquotKey, String custodianId,
                       String requestFingerprint, String commandKey, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sampling_order
                            (request_id, aliquot_key, custodian_id, status, version,
                             request_fingerprint, command_key, created_at, confirmed_at, decided_at)
                        VALUES (?, ?, ?, ?, 0, ?, ?, ?, NULL, NULL)
                        """,
                requestId, aliquotKey, custodianId, SamplingStatus.PENDING.name(),
                requestFingerprint, commandKey, now);
    }

    /**
     * 按申请键查询（不加锁），用于只读与幂等重放预判。
     */
    public Optional<SamplingOrder> findByRequestId(String requestId) {
        List<SamplingOrder> rows = jdbc.query(
                "SELECT * FROM sampling_order WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按申请键查询并锁定行，用于审核确认/拒绝/取消。
     */
    public Optional<SamplingOrder> findByRequestIdForUpdate(String requestId) {
        List<SamplingOrder> rows = jdbc.query(
                "SELECT * FROM sampling_order WHERE request_id = ? FOR UPDATE",
                ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按子样键查询，用于 aliquotKey 复用检测。
     */
    public Optional<SamplingOrder> findByAliquotKey(String aliquotKey) {
        List<SamplingOrder> rows = jdbc.query(
                "SELECT * FROM sampling_order WHERE aliquot_key = ?", ROW_MAPPER, aliquotKey);
        return rows.stream().findFirst();
    }

    /**
     * 第一次确认：仅当仍为 PENDING 且版本为 0 时推进到版本 1。
     *
     * @return 是否推进成功（false 表示已被并发处理或版本不符）
     */
    public boolean advanceFirstConfirmation(String requestId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE sampling_order
                        SET version = 1
                        WHERE request_id = ? AND status = ? AND version = 0
                        """,
                requestId, SamplingStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 第二次确认：仅当仍为 PENDING 且版本为携带的申请版本（1）时，
     * 一次置为 CONFIRMED 并记录完成时间。
     *
     * @return 是否确认成功（false 表示状态或版本不符）
     */
    public boolean confirm(String requestId, long expectedVersion, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE sampling_order
                        SET status = ?, version = version + 1, confirmed_at = ?
                        WHERE request_id = ? AND status = ? AND version = ?
                        """,
                SamplingStatus.CONFIRMED.name(), now, requestId,
                SamplingStatus.PENDING.name(), expectedVersion);
        return updated == 1;
    }

    /**
     * 拒绝：仅当仍为 PENDING 时置为 REJECTED 并记录终态时间（审核任意阶段均可）。
     *
     * @return 是否拒绝成功（false 表示已非 PENDING）
     */
    public boolean reject(String requestId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE sampling_order
                        SET status = ?, decided_at = ?
                        WHERE request_id = ? AND status = ?
                        """,
                SamplingStatus.REJECTED.name(), now, requestId, SamplingStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 审核前取消：仅当仍为 PENDING 时置为 CANCELLED 并记录终态时间。
     *
     * @return 是否取消成功（false 表示已非 PENDING）
     */
    public boolean cancel(String requestId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE sampling_order
                        SET status = ?, decided_at = ?
                        WHERE request_id = ? AND status = ?
                        """,
                SamplingStatus.CANCELLED.name(), now, requestId, SamplingStatus.PENDING.name());
        return updated == 1;
    }

    private static final class SamplingOrderRowMapper implements RowMapper<SamplingOrder> {
        @Override
        public SamplingOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SamplingOrder(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("aliquot_key"),
                    rs.getString("custodian_id"),
                    SamplingStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getString("request_fingerprint"),
                    rs.getString("command_key"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("confirmed_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
