package com.example.starter.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库位迁移单表访问。迁移单只追加一次；完成（第二人确认）与撤销只允许
 * 在 AWAITING_SECOND 状态下各成功一次条件更新，历史字段不可改写。
 */
@Repository
public class LocationMoveOrderRepository {

    private static final TypeReference<List<String>> KEY_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocationMoveOrderRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条迁移单（首人确认即创建），初始状态 AWAITING_SECOND；
     * move_key 冲突由唯一约束拒绝。
     */
    public void insert(String moveKey, List<String> evidenceKeys, String sourceLocationCode,
                       String targetLocationCode, long expectedVersion, String actorId,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO location_move_order
                            (move_key, evidence_keys, source_location_code, target_location_code,
                             expected_version, requested_by, status,
                             first_confirmed_by, first_confirmed_at,
                             second_confirmed_by, second_confirmed_at,
                             cancelled_by, cancelled_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?)
                        """,
                moveKey, toJson(evidenceKeys), sourceLocationCode, targetLocationCode,
                expectedVersion, actorId, LocationMoveStatus.AWAITING_SECOND.name(),
                actorId, now, now);
    }

    /**
     * 按迁移键查询（不加锁），用于只读场景。
     */
    public Optional<LocationMoveOrder> findByKey(String moveKey) {
        List<LocationMoveOrder> rows = jdbc.query(
                "SELECT * FROM location_move_order WHERE move_key = ?", new MoveRowMapper(), moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 按迁移键查询并锁定迁移单行（SELECT ... FOR UPDATE），用于确认/撤销的并发裁决。
     */
    public Optional<LocationMoveOrder> findByKeyForUpdate(String moveKey) {
        List<LocationMoveOrder> rows = jdbc.query(
                "SELECT * FROM location_move_order WHERE move_key = ? FOR UPDATE",
                new MoveRowMapper(), moveKey);
        return rows.stream().findFirst();
    }

    /**
     * 第二人确认完成：仅当仍为 AWAITING_SECOND 时写入第二确认人与完成时间。
     *
     * @return 是否成功完成（false 表示已被并发完成或撤销）
     */
    public boolean complete(String moveKey, String secondConfirmedBy, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE location_move_order
                        SET status = ?, second_confirmed_by = ?, second_confirmed_at = ?
                        WHERE move_key = ? AND status = ?
                        """,
                LocationMoveStatus.COMPLETED.name(), secondConfirmedBy, now,
                moveKey, LocationMoveStatus.AWAITING_SECOND.name());
        return updated == 1;
    }

    /**
     * 撤销迁移单：仅当仍为 AWAITING_SECOND 时写入撤销人与撤销时间。
     *
     * @return 是否成功撤销（false 表示已被并发完成或撤销）
     */
    public boolean cancel(String moveKey, String cancelledBy, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE location_move_order
                        SET status = ?, cancelled_by = ?, cancelled_at = ?
                        WHERE move_key = ? AND status = ?
                        """,
                LocationMoveStatus.CANCELLED.name(), cancelledBy, now,
                moveKey, LocationMoveStatus.AWAITING_SECOND.name());
        return updated == 1;
    }

    /**
     * 查询全部迁移单（按创建顺序）。
     */
    public List<LocationMoveOrder> findAll() {
        return jdbc.query("SELECT * FROM location_move_order ORDER BY id", new MoveRowMapper());
    }

    private String toJson(List<String> evidenceKeys) {
        try {
            return objectMapper.writeValueAsString(evidenceKeys);
        } catch (Exception e) {
            throw new IllegalStateException("证物集合序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, KEY_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("证物集合反序列化失败", e);
        }
    }

    private final class MoveRowMapper implements RowMapper<LocationMoveOrder> {
        @Override
        public LocationMoveOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LocationMoveOrder(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    fromJson(rs.getString("evidence_keys")),
                    rs.getString("source_location_code"),
                    rs.getString("target_location_code"),
                    rs.getLong("expected_version"),
                    rs.getString("requested_by"),
                    LocationMoveStatus.valueOf(rs.getString("status")),
                    rs.getString("first_confirmed_by"),
                    rs.getObject("first_confirmed_at", LocalDateTime.class),
                    rs.getString("second_confirmed_by"),
                    rs.getObject("second_confirmed_at", LocalDateTime.class),
                    rs.getString("cancelled_by"),
                    rs.getObject("cancelled_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
