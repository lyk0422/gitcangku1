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
 * 位置迁移单与明细的表访问。单据只追加；状态流转 AWAITING_FIRST → AWAITING_SECOND → COMPLETED，
 * 或在首人确认后 CANCELLED；COMPLETED/CANCELLED 不可再变。明细在申请时按字典序冻结，不提供更新。
 */
@Repository
public class LocationMoveRepository {

    private static final MoveRowMapper ROW_MAPPER = new MoveRowMapper();

    private final JdbcTemplate jdbc;

    public LocationMoveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 申请迁移单：写入单据（AWAITING_FIRST）与规范化明细。
     */
    public void insert(String moveKey, List<String> evidenceKeys, String sourceLocationCode,
                       String targetLocationCode, long expectedVersion, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO location_move
                            (move_key, source_location_code, target_location_code, expected_version,
                             status, first_confirmer, second_confirmer,
                             created_at, first_confirmed_at, completed_at, cancelled_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, NULL, NULL, NULL)
                        """,
                moveKey, sourceLocationCode, targetLocationCode, expectedVersion,
                MoveStatus.AWAITING_FIRST.name(), now);
        for (int i = 0; i < evidenceKeys.size(); i++) {
            jdbc.update("""
                            INSERT INTO location_move_item (move_key, evidence_key, ordinal)
                            VALUES (?, ?, ?)
                            """,
                    moveKey, evidenceKeys.get(i), i);
        }
    }

    /**
     * 按业务键查询迁移单（不加锁），用于只读场景。
     */
    public Optional<LocationMove> findByKey(String moveKey) {
        List<LocationMove> rows = jdbc.query(
                "SELECT * FROM location_move WHERE move_key = ?", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定迁移单行（SELECT ... FOR UPDATE），用于确认与撤销。
     */
    public Optional<LocationMove> findByKeyForUpdate(String moveKey) {
        List<LocationMove> rows = jdbc.query(
                "SELECT * FROM location_move WHERE move_key = ? FOR UPDATE", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 首人确认：仅当 AWAITING_FIRST 时写入首人并推进到 AWAITING_SECOND。
     *
     * @return 是否成功（false 表示已被并发处理）
     */
    public boolean markFirstConfirmed(long id, String confirmer, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE location_move
                        SET status = ?, first_confirmer = ?, first_confirmed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.AWAITING_SECOND.name(), confirmer, now, id,
                MoveStatus.AWAITING_FIRST.name());
        return updated == 1;
    }

    /**
     * 第二人确认完成：仅当 AWAITING_SECOND 时写入第二人并置为 COMPLETED。
     *
     * @return 是否成功（false 表示已被并发处理）
     */
    public boolean markCompleted(long id, String confirmer, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE location_move
                        SET status = ?, second_confirmer = ?, completed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.COMPLETED.name(), confirmer, now, id,
                MoveStatus.AWAITING_SECOND.name());
        return updated == 1;
    }

    /**
     * 撤销：仅当已首人确认（AWAITING_SECOND）时可撤销。
     *
     * @return 是否成功（false 表示状态已变化）
     */
    public boolean markCancelledAfterFirst(long id, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE location_move
                        SET status = ?, cancelled_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MoveStatus.CANCELLED.name(), now, id, MoveStatus.AWAITING_SECOND.name());
        return updated == 1;
    }

    /**
     * 查询迁移单冻结的规范化证物集合（按 ordinal 升序）。
     */
    public List<String> findItemKeys(String moveKey) {
        return jdbc.queryForList(
                "SELECT evidence_key FROM location_move_item WHERE move_key = ? ORDER BY ordinal",
                String.class, moveKey);
    }

    private static final class MoveRowMapper implements RowMapper<LocationMove> {
        @Override
        public LocationMove mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LocationMove(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("source_location_code"),
                    rs.getString("target_location_code"),
                    rs.getLong("expected_version"),
                    MoveStatus.valueOf(rs.getString("status")),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("first_confirmed_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getObject("cancelled_at", LocalDateTime.class));
        }
    }
}
