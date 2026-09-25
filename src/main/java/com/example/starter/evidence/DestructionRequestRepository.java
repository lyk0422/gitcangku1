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
 * 销毁申请表访问。申请与清单、阻断快照只追加；
 * 阻断（PENDING -&gt; HOLD_BLOCKED）与完成（PENDING -&gt; DESTROYED）均为一次条件更新，
 * 历史状态不可覆盖，已完成/已阻断行不会被再次改写。
 */
@Repository
public class DestructionRequestRepository {

    private static final DestructionRowMapper ROW_MAPPER = new DestructionRowMapper();
    private static final BlockSnapshotRowMapper SNAPSHOT_MAPPER = new BlockSnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 PENDING 销毁申请。request_key 冲突由唯一约束拒绝。
     */
    public void insert(String requestKey, String requestedBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_request
                            (request_key, status, requested_by, blocked_reason,
                             created_at, blocked_at, completed_at)
                        VALUES (?, ?, ?, NULL, ?, NULL, NULL)
                        """,
                requestKey, DestructionStatus.PENDING.name(), requestedBy, now);
    }

    /**
     * 追加申请清单一行，order 为规范化排序序号。
     */
    public void insertItem(long requestPk, String evidenceKey, int order) {
        jdbc.update("""
                        INSERT INTO destruction_request_item (request_pk, evidence_key, item_order)
                        VALUES (?, ?, ?)
                        """,
                requestPk, evidenceKey, order);
    }

    /**
     * 按销毁申请业务键查询。
     */
    public Optional<DestructionRequest> findByRequestKey(String requestKey) {
        List<DestructionRequest> rows = jdbc.query(
                "SELECT * FROM destruction_request WHERE request_key = ?", ROW_MAPPER, requestKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询申请的规范化证物清单（按 item_order）。
     */
    public List<String> findItems(long requestPk) {
        return jdbc.queryForList(
                "SELECT evidence_key FROM destruction_request_item WHERE request_pk = ? ORDER BY item_order",
                String.class, requestPk);
    }

    /**
     * 条件阻断：仅当申请仍为 PENDING 时写入不可变原因与阻断时刻，转入 HOLD_BLOCKED。
     *
     * @return 是否阻断成功（false 表示申请已不在待审状态）
     */
    public boolean markBlocked(long requestPk, String blockedReason, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE destruction_request
                        SET status = ?, blocked_reason = ?, blocked_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.HOLD_BLOCKED.name(), blockedReason, now,
                requestPk, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 追加一条命中冻结的不可变快照。
     */
    public void insertBlockSnapshot(long requestPk, DestructionBlockSnapshotView view, int order) {
        jdbc.update("""
                        INSERT INTO destruction_block_snapshot
                            (request_pk, hold_id, hold_version, case_key,
                             effective_at, expire_at, reason, snapshot_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestPk, view.holdId(), view.holdVersion(), view.caseKey(),
                view.effectiveAt(), view.expireAt(), view.reason(), order);
    }

    /**
     * 查询申请的全部阻断冻结快照（按稳定序号）。
     */
    public List<DestructionBlockSnapshot> findBlockSnapshots(long requestPk) {
        return jdbc.query(
                "SELECT * FROM destruction_block_snapshot WHERE request_pk = ? ORDER BY snapshot_order",
                SNAPSHOT_MAPPER, requestPk);
    }

    /**
     * 条件完成：仅当申请仍为 PENDING 时写入完成时刻，转入 DESTROYED。
     *
     * @return 是否完成成功（false 表示申请已被阻断或已完成）
     */
    public boolean markCompleted(long requestPk, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE destruction_request
                        SET status = ?, completed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.DESTROYED.name(), now,
                requestPk, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 查询一件证物关联的全部销毁申请（按创建顺序），用于历史查询。
     */
    public List<DestructionRequest> findAllByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT r.* FROM destruction_request r
                        JOIN destruction_request_item i ON i.request_pk = r.id
                        WHERE i.evidence_key = ?
                        ORDER BY r.id
                        """,
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询与给定证物集合相交的全部 PENDING 申请并锁定申请行（SELECT ... FOR UPDATE）。
     * 用于建立冻结时按提交顺序裁决：命中的待审申请在同一事务转 HOLD_BLOCKED。
     */
    public List<DestructionRequest> findPendingByEvidenceKeysForUpdate(java.util.Collection<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", evidenceKeys.stream().map(k -> "?").toList());
        return jdbc.query("""
                        SELECT r.* FROM destruction_request r
                        WHERE r.status = ?
                          AND r.id IN (
                              SELECT request_pk FROM destruction_request_item
                              WHERE evidence_key IN (%s))
                        ORDER BY r.id
                        FOR UPDATE
                        """.formatted(placeholders),
                ps -> {
                    ps.setString(1, DestructionStatus.PENDING.name());
                    int idx = 2;
                    for (String key : evidenceKeys) {
                        ps.setString(idx++, key);
                    }
                },
                ROW_MAPPER);
    }

    /**
     * 阻断快照写入值（取自阻断瞬间的冻结行）。
     */
    public record DestructionBlockSnapshotView(
            String holdId,
            int holdVersion,
            String caseKey,
            LocalDateTime effectiveAt,
            LocalDateTime expireAt,
            String reason) {
    }

    private static final class DestructionRowMapper implements RowMapper<DestructionRequest> {
        @Override
        public DestructionRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionRequest(
                    rs.getLong("id"),
                    rs.getString("request_key"),
                    DestructionStatus.valueOf(rs.getString("status")),
                    rs.getString("requested_by"),
                    rs.getString("blocked_reason"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("blocked_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class));
        }
    }

    private static final class BlockSnapshotRowMapper implements RowMapper<DestructionBlockSnapshot> {
        @Override
        public DestructionBlockSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionBlockSnapshot(
                    rs.getLong("id"),
                    rs.getLong("request_pk"),
                    rs.getString("hold_id"),
                    rs.getInt("hold_version"),
                    rs.getString("case_key"),
                    rs.getObject("effective_at", LocalDateTime.class),
                    rs.getObject("expire_at", LocalDateTime.class),
                    rs.getString("reason"),
                    rs.getInt("snapshot_order"));
        }
    }
}
