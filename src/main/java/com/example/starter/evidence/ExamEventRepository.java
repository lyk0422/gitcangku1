package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检验事件表访问。事件只追加、不可变，不提供任何更新语句；
 * 与封签版本共同构成检验相关的不可变保管链。
 */
@Repository
public class ExamEventRepository {

    private static final EventRowMapper ROW_MAPPER = new EventRowMapper();

    private final JdbcTemplate jdbc;

    public ExamEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条检验事件；createdAt 为 UTC 时刻。
     */
    public void insert(String reservationKey, String evidenceKey, String eventType, String actorId,
                       Integer sealVersion, String detail, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO exam_event
                            (reservation_key, evidence_key, event_type, actor_id, seal_version, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                reservationKey, evidenceKey, eventType, actorId, sealVersion, detail, createdAt);
    }

    /**
     * 按证物查询全部检验事件（按发生顺序）。
     */
    public List<ExamEvent> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM exam_event WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按预约查询全部检验事件（按发生顺序）。
     */
    public List<ExamEvent> findByReservationKey(String reservationKey) {
        return jdbc.query(
                "SELECT * FROM exam_event WHERE reservation_key = ? ORDER BY id",
                ROW_MAPPER, reservationKey);
    }

    private static final class EventRowMapper implements RowMapper<ExamEvent> {
        @Override
        public ExamEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamEvent(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    rs.getString("event_type"),
                    rs.getString("actor_id"),
                    rs.getObject("seal_version", Integer.class),
                    rs.getString("detail"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
