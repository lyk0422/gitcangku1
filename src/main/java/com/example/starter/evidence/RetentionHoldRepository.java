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
import java.util.StringJoiner;

/**
 * 保全冻结表访问。冻结与冻结-证物关联只追加；解除为一次性状态迁移（ACTIVE → RELEASED），
 * 历史冻结（含已解除）保留用于快照查询，不得改写。
 */
@Repository
public class RetentionHoldRepository {

    private static final HoldRowMapper ROW_MAPPER = new HoldRowMapper();

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新冻结（状态 ACTIVE、版本 1）及其证物关联。
     */
    public void insert(String holdKey, String caseKey, List<String> evidenceKeys,
                       LocalDateTime effectiveFrom, LocalDateTime effectiveTo, String reason,
                       String createdBy, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (hold_key, case_key, evidence_keys, effective_from, effective_to,
                             reason, version, status, created_by, created_at, released_by, released_at)
                        VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, NULL, NULL)
                        """,
                holdKey, caseKey, join(evidenceKeys), effectiveFrom, effectiveTo,
                reason, HoldStatus.ACTIVE.name(), createdBy, createdAt);
        long holdId = findByKey(holdKey).orElseThrow().id();
        for (String evidenceKey : evidenceKeys) {
            jdbc.update("INSERT INTO retention_hold_item (hold_id, evidence_key) VALUES (?, ?)",
                    holdId, evidenceKey);
        }
    }

    /**
     * 按冻结业务键查询（不加锁）。
     */
    public Optional<RetentionHold> findByKey(String holdKey) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_key = ?", ROW_MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 按冻结业务键查询并锁定冻结行（SELECT ... FOR UPDATE），用于批量解除的校验与状态迁移。
     */
    public Optional<RetentionHold> findByKeyForUpdate(String holdKey) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_key = ? FOR UPDATE", ROW_MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询覆盖指定证物的全部 ACTIVE 冻结（用于重叠校验，调用前必须已锁定证物行）。
     */
    public List<RetentionHold> findActiveByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_id = h.id
                        WHERE i.evidence_key = ? AND h.status = ?
                        ORDER BY h.id
                        """,
                ROW_MAPPER, evidenceKey, HoldStatus.ACTIVE.name());
    }

    /**
     * 查询覆盖指定证物、当前时刻有效的冻结（ACTIVE 且区间覆盖 nowUtc，左闭右开）。
     */
    public List<RetentionHold> findEffectiveByEvidenceKey(String evidenceKey, LocalDateTime nowUtc) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_id = h.id
                        WHERE i.evidence_key = ? AND h.status = ?
                          AND h.effective_from <= ? AND h.effective_to > ?
                        ORDER BY h.hold_key
                        """,
                ROW_MAPPER, evidenceKey, HoldStatus.ACTIVE.name(), nowUtc, nowUtc);
    }

    /**
     * 查询覆盖指定证物的全部冻结（含已解除，按创建顺序），用于历史快照查询。
     */
    public List<RetentionHold> findHistoryByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT h.* FROM retention_hold h
                        JOIN retention_hold_item i ON i.hold_id = h.id
                        WHERE i.evidence_key = ?
                        ORDER BY h.id
                        """,
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 解除冻结：仅当仍处于 ACTIVE 且版本匹配时迁移为 RELEASED 并递增版本。
     *
     * @return 是否成功解除（false 表示已被并发解除或版本已变化）
     */
    public boolean release(long id, int expectedVersion, String releasedBy, LocalDateTime releasedAt) {
        int updated = jdbc.update("""
                        UPDATE retention_hold
                        SET status = ?, version = version + 1, released_by = ?, released_at = ?
                        WHERE id = ? AND status = ? AND version = ?
                        """,
                HoldStatus.RELEASED.name(), releasedBy, releasedAt,
                id, HoldStatus.ACTIVE.name(), expectedVersion);
        return updated == 1;
    }

    static String join(List<String> evidenceKeys) {
        StringJoiner joiner = new StringJoiner(",");
        evidenceKeys.forEach(joiner::add);
        return joiner.toString();
    }

    static List<String> split(String evidenceKeys) {
        return Arrays.asList(evidenceKeys.split(","));
    }

    private static final class HoldRowMapper implements RowMapper<RetentionHold> {
        @Override
        public RetentionHold mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RetentionHold(
                    rs.getLong("id"),
                    rs.getString("hold_key"),
                    rs.getString("case_key"),
                    split(rs.getString("evidence_keys")),
                    rs.getObject("effective_from", LocalDateTime.class),
                    rs.getObject("effective_to", LocalDateTime.class),
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
