package com.example.starter.repo;

import com.example.starter.domain.Inflorescence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 花序仓储。有效操作的互斥通过 active_operation_id 条件更新实现，
 * 在 H2 与 MySQL 上行为一致：同一时刻只允许一个有效操作占据花序。
 */
@Repository
public class InflorescenceRepository {

    private final JdbcTemplate jdbc;

    public InflorescenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建花序；编号重复时抛出 DuplicateKeyException。 */
    public long insert(String code, Instant firstOpenAt, int windowHours, Instant createdAt) {
        jdbc.update("INSERT INTO inflorescence (code, first_open_at, window_hours, created_at) VALUES (?, ?, ?, ?)",
                code, UtcTimes.toDb(firstOpenAt), windowHours, UtcTimes.toDb(createdAt));
        Long id = jdbc.queryForObject("SELECT id FROM inflorescence WHERE code = ?", Long.class, code);
        return id == null ? -1L : id;
    }

    /** 按 ID 查询。 */
    public Optional<Inflorescence> findById(long id) {
        return jdbc.query("SELECT * FROM inflorescence WHERE id = ?", this::map, id).stream().findFirst();
    }

    /** 摘除花序；返回更新行数，0 表示不存在或已摘除（幂等）。 */
    public int markRemoved(long id, Instant removedAt) {
        return jdbc.update("UPDATE inflorescence SET removed_at = ? WHERE id = ? AND removed_at IS NULL",
                UtcTimes.toDb(removedAt), id);
    }

    /** 占据花序：仅当当前无有效操作时生效；返回 1 表示占据成功，0 表示冲突。 */
    public int occupyIfFree(long id, long operationId) {
        return jdbc.update("UPDATE inflorescence SET active_operation_id = ? WHERE id = ? AND active_operation_id IS NULL",
                operationId, id);
    }

    /** 释放花序：仅当当前有效操作确为指定操作时生效。 */
    public int releaseIfHeldBy(long id, long operationId) {
        return jdbc.update("UPDATE inflorescence SET active_operation_id = NULL WHERE id = ? AND active_operation_id = ?",
                id, operationId);
    }

    /** 固化成功结果：写入花粉批号与确认时刻，之后摘除花序也不抹除。 */
    public int fixSuccess(long id, String pollenBatchNo, Instant confirmedAt) {
        return jdbc.update("UPDATE inflorescence SET confirmed_pollen_batch_no = ?, confirmed_at = ? WHERE id = ?",
                pollenBatchNo, UtcTimes.toDb(confirmedAt), id);
    }

    private Inflorescence map(ResultSet rs, int rowNum) throws SQLException {
        long activeOp = rs.getLong("active_operation_id");
        return new Inflorescence(rs.getLong("id"), rs.getString("code"),
                UtcTimes.fromDb(rs.getObject("first_open_at", LocalDateTime.class)), rs.getInt("window_hours"),
                UtcTimes.fromDb(rs.getObject("removed_at", LocalDateTime.class)),
                rs.wasNull() ? null : activeOp,
                rs.getString("confirmed_pollen_batch_no"),
                UtcTimes.fromDb(rs.getObject("confirmed_at", LocalDateTime.class)),
                UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));
    }
}