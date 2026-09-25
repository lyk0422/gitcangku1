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
 * 迁移单表访问。证物集合以规范化（排序去重）逗号分隔串存储；
 * 状态流转必须先通过 {@link #findByKeyForUpdate} 锁定迁移单行，保证并发确认/撤销按提交顺序裁决。
 */
@Repository
public class MoveOrderRepository {

    private static final MoveOrderRowMapper ROW_MAPPER = new MoveOrderRowMapper();

    private final JdbcTemplate jdbc;

    public MoveOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入迁移单，初始状态 PENDING。
     */
    public void insert(String moveKey, List<String> evidenceKeys, String sourceLocation,
                       String targetLocation, int expectedVersion, String createdBy,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO move_request
                            (move_key, evidence_keys, source_location, target_location, expected_version,
                             status, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, String.join(",", evidenceKeys), sourceLocation, targetLocation,
                expectedVersion, MoveStatus.PENDING.name(), createdBy, now, now);
    }

    /**
     * 按迁移键查询（不加锁），用于只读场景与加锁前的路径判断。
     */
    public Optional<MoveOrder> findByKey(String moveKey) {
        List<MoveOrder> rows = jdbc.query(
                "SELECT * FROM move_request WHERE move_key = ?", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 按迁移键查询并锁定迁移单行（SELECT ... FOR UPDATE），用于确认/撤销状态流转。
     */
    public Optional<MoveOrder> findByKeyForUpdate(String moveKey) {
        List<MoveOrder> rows = jdbc.query(
                "SELECT * FROM move_request WHERE move_key = ? FOR UPDATE", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 首人确认：仅当前仍为 PENDING 时生效，返回是否更新成功。
     */
    public boolean confirmFirst(String moveKey, String firstConfirmer, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE move_request
                        SET status = ?, first_confirmer = ?, updated_at = ?
                        WHERE move_key = ? AND status = ?
                        """,
                MoveStatus.FIRST_CONFIRMED.name(), firstConfirmer, now,
                moveKey, MoveStatus.PENDING.name()) == 1;
    }

    /**
     * 第二人确认完成：仅当前仍为 FIRST_CONFIRMED 时生效，返回是否更新成功。
     */
    public boolean complete(String moveKey, String secondConfirmer, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE move_request
                        SET status = ?, second_confirmer = ?, updated_at = ?, decided_at = ?
                        WHERE move_key = ? AND status = ?
                        """,
                MoveStatus.COMPLETED.name(), secondConfirmer, now, now,
                moveKey, MoveStatus.FIRST_CONFIRMED.name()) == 1;
    }

    /**
     * 撤销：仅当前仍为 PENDING 或 FIRST_CONFIRMED 时生效，返回是否更新成功。
     */
    public boolean cancel(String moveKey, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE move_request
                        SET status = ?, updated_at = ?, decided_at = ?
                        WHERE move_key = ? AND status IN (?, ?)
                        """,
                MoveStatus.CANCELLED.name(), now, now,
                moveKey, MoveStatus.PENDING.name(), MoveStatus.FIRST_CONFIRMED.name()) == 1;
    }

    static List<String> parseKeys(String joined) {
        return Arrays.asList(joined.split(","));
    }

    private static final class MoveOrderRowMapper implements RowMapper<MoveOrder> {
        @Override
        public MoveOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MoveOrder(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    parseKeys(rs.getString("evidence_keys")),
                    rs.getString("source_location"),
                    rs.getString("target_location"),
                    rs.getInt("expected_version"),
                    MoveStatus.valueOf(rs.getString("status")),
                    rs.getString("created_by"),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
