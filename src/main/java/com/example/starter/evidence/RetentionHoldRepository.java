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
 * 保全冻结表访问。冻结与清单只追加；解除仅允许按 holdId + 期望版本条件更新。
 * 重叠判定、有效冻结查询均以 UTC 左闭右开区间 [effective_at, expire_at) 为准。
 */
@Repository
public class RetentionHoldRepository {

    private static final HoldRowMapper HOLD_MAPPER = new HoldRowMapper();

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条冻结（version=1，ACTIVE）。hold_id 冲突由唯一约束拒绝。
     */
    public void insert(String holdId, String holdKey, String caseKey,
                       LocalDateTime effectiveAt, LocalDateTime expireAt, String reason,
                       String createdBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (hold_id, hold_key, case_key, effective_at, expire_at, reason,
                             version, status, created_by, created_at, released_by, released_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                        """,
                holdId, holdKey, caseKey, effectiveAt, expireAt, reason,
                1, HoldStatus.ACTIVE.name(), createdBy, now);
    }

    /**
     * 追加冻结清单一行，order 为规范化排序序号。
     */
    public void insertItem(long holdPk, String evidenceKey, int order) {
        jdbc.update("""
                        INSERT INTO retention_hold_item (hold_pk, evidence_key, item_order)
                        VALUES (?, ?, ?)
                        """,
                holdPk, evidenceKey, order);
    }

    /**
     * 按冻结业务键查询（不加锁）。
     */
    public Optional<RetentionHold> findByHoldId(String holdId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_id = ?", HOLD_MAPPER, holdId);
        return rows.stream().findFirst();
    }

    /**
     * 按冻结业务键查询并锁定行（SELECT ... FOR UPDATE），用于解除版本裁决。
     */
    public Optional<RetentionHold> findByHoldIdForUpdate(String holdId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_id = ? FOR UPDATE", HOLD_MAPPER, holdId);
        return rows.stream().findFirst();
    }

    /**
     * 查询冻结的规范化证物清单（按 item_order）。
     */
    public List<String> findItems(long holdPk) {
        return jdbc.queryForList(
                "SELECT evidence_key FROM retention_hold_item WHERE hold_pk = ? ORDER BY item_order",
                String.class, holdPk);
    }

    /**
     * 查询一件证物在指定 UTC 时刻与之重叠、且状态/区间相交的全部冻结行（调用方须自行持证物行锁）。
     * 区间相交条件：existing.effective_at &lt; newExpire AND existing.expire_at &gt; newEffective。
     * 仅返回 ACTIVE 行：已解除冻结不再阻止新建。
     */
    public List<RetentionHold> findActiveOverlapping(String evidenceKey,
                                                     LocalDateTime effectiveAt,
                                                     LocalDateTime expireAt) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_pk = h.id
                        WHERE i.evidence_key = ?
                          AND h.status = ?
                          AND h.effective_at < ?
                          AND h.expire_at > ?
                        ORDER BY h.id
                        """,
                HOLD_MAPPER, evidenceKey, HoldStatus.ACTIVE.name(), expireAt, effectiveAt);
    }

    /**
     * 查询一件证物在指定 UTC 时刻的全部“有效”冻结（ACTIVE 且 left &lt;= now &lt; right）。
     */
    public List<RetentionHold> findEffectiveAt(String evidenceKey, LocalDateTime nowUtc) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_pk = h.id
                        WHERE i.evidence_key = ?
                          AND h.status = ?
                          AND h.effective_at <= ?
                          AND h.expire_at > ?
                        ORDER BY h.hold_id
                        """,
                HOLD_MAPPER, evidenceKey, HoldStatus.ACTIVE.name(), nowUtc, nowUtc);
    }

    /**
     * 条件解除：仅当冻结仍为 ACTIVE 且版本等于期望版本时，写入新版本与解除信息。
     *
     * @return 是否解除成功（false 表示版本不符或已解除）
     */
    public boolean release(String holdId, int expectedVersion, String releasedBy,
                           LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE retention_hold
                        SET status = ?, version = version + 1, released_by = ?, released_at = ?
                        WHERE hold_id = ? AND status = ? AND version = ?
                        """,
                HoldStatus.RELEASED.name(), releasedBy, now,
                holdId, HoldStatus.ACTIVE.name(), expectedVersion);
        return updated == 1;
    }

    /**
     * 查询一件证物的全部冻结（含已解除/过期），按创建顺序，用于历史快照。
     */
    public List<RetentionHold> findAllByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_pk = h.id
                        WHERE i.evidence_key = ?
                        ORDER BY h.id
                        """,
                HOLD_MAPPER, evidenceKey);
    }

    private static final class HoldRowMapper implements RowMapper<RetentionHold> {
        @Override
        public RetentionHold mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RetentionHold(
                    rs.getLong("id"),
                    rs.getString("hold_id"),
                    rs.getString("hold_key"),
                    rs.getString("case_key"),
                    rs.getObject("effective_at", LocalDateTime.class),
                    rs.getObject("expire_at", LocalDateTime.class),
                    rs.getString("reason"),
                    rs.getInt("version"),
                    HoldStatus.valueOf(rs.getString("status")),
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getString("released_by"),
                    rs.getObject("released_at", LocalDateTime.class));
        }
    }
}
