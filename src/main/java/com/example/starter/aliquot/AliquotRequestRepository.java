package com.example.starter.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 联合取样单表访问。取样单状态机仅允许 RESERVED -> CONSUMED/REJECTED/CANCELLED；
 * 所有状态推进均为条件更新，并发重复推进时只有一行受影响。
 */
@Repository
public class AliquotRequestRepository {

    private static final RequestRowMapper ROW_MAPPER = new RequestRowMapper();

    private final JdbcTemplate jdbc;

    public AliquotRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建取样单（RESERVED、版本 0）；aliquot_key 冲突由唯一约束拒绝。
     */
    public void insert(String aliquotKey, String applicantId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO aliquot_request
                            (aliquot_key, applicant_id, status, version, created_at, updated_at)
                        VALUES (?, ?, ?, 0, ?, ?)
                        """,
                aliquotKey, applicantId, AliquotStatus.RESERVED.name(), now, now);
    }

    /**
     * 按业务键查询取样单（不加锁）。
     */
    public Optional<AliquotRequest> findByKey(String aliquotKey) {
        List<AliquotRequest> rows = jdbc.query(
                "SELECT * FROM aliquot_request WHERE aliquot_key = ?", ROW_MAPPER, aliquotKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定取样单行（SELECT ... FOR UPDATE），审核/拒绝/取消前必须持锁。
     */
    public Optional<AliquotRequest> findByKeyForUpdate(String aliquotKey) {
        List<AliquotRequest> rows = jdbc.query(
                "SELECT * FROM aliquot_request WHERE aliquot_key = ? FOR UPDATE", ROW_MAPPER,
                aliquotKey);
        return rows.stream().findFirst();
    }

    /**
     * 写入第一次确认并将申请版本加 1；仅当仍 RESERVED 且尚无第一审核人时生效。
     *
     * @return 是否写入成功（false 表示已被并发审核或单据已终结）
     */
    public boolean markFirstReview(long id, String reviewerId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE aliquot_request
                        SET first_reviewer = ?, first_confirmed_at = ?,
                            version = version + 1, updated_at = ?
                        WHERE id = ? AND status = ? AND first_reviewer IS NULL
                        """,
                reviewerId, now, now, id, AliquotStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 第二次确认并将单据置为 CONSUMED；仅当仍 RESERVED、已完成第一次确认且无第二审核人时生效。
     *
     * @return 是否耗用成功（false 表示已被并发终结或第一次确认缺失）
     */
    public boolean markConsumed(long id, String reviewerId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE aliquot_request
                        SET status = ?, second_reviewer = ?, second_confirmed_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                          AND first_reviewer IS NOT NULL AND second_reviewer IS NULL
                        """,
                AliquotStatus.CONSUMED.name(), reviewerId, now, now, id,
                AliquotStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 拒绝单据；仅当仍 RESERVED 时生效（第一次确认后也允许第二审核人拒绝）。
     *
     * @return 是否拒绝成功
     */
    public boolean markRejected(long id, String reviewerId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE aliquot_request
                        SET status = ?, rejected_by = ?, rejected_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                AliquotStatus.REJECTED.name(), reviewerId, now, now, id,
                AliquotStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 审核前取消；仅当仍 RESERVED 且尚无第一审核人时生效。
     *
     * @return 是否取消成功
     */
    public boolean markCancelled(long id, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE aliquot_request
                        SET status = ?, cancelled_at = ?, updated_at = ?
                        WHERE id = ? AND status = ? AND first_reviewer IS NULL
                        """,
                AliquotStatus.CANCELLED.name(), now, now, id, AliquotStatus.RESERVED.name());
        return updated == 1;
    }

    private static final class RequestRowMapper implements RowMapper<AliquotRequest> {
        @Override
        public AliquotRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AliquotRequest(
                    rs.getLong("id"),
                    rs.getString("aliquot_key"),
                    rs.getString("applicant_id"),
                    AliquotStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getString("first_reviewer"),
                    rs.getString("second_reviewer"),
                    rs.getObject("first_confirmed_at", LocalDateTime.class),
                    rs.getObject("second_confirmed_at", LocalDateTime.class),
                    rs.getObject("rejected_at", LocalDateTime.class),
                    rs.getString("rejected_by"),
                    rs.getObject("cancelled_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
