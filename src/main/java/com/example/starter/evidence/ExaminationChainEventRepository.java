package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检验保管链事件表访问。事件只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class ExaminationChainEventRepository {

    private static final EventRowMapper ROW_MAPPER = new EventRowMapper();

    private final JdbcTemplate jdbc;

    public ExaminationChainEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条检验保管链事件。
     */
    public void insert(String reservationKey, String evidenceKey, ExaminationEventType eventType,
                       String actorId, LocalDateTime eventTime,
                       LocalDateTime startAt, LocalDateTime endAt,
                       Integer sealVersion, String resultSummary, String detail) {
        jdbc.update("""
                        INSERT INTO examination_chain_event
                            (reservation_key, evidence_key, event_type, actor_id, event_time,
                             start_at, end_at, seal_version, result_summary, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservationKey, evidenceKey, eventType.name(), actorId, eventTime,
                startAt, endAt, sealVersion, resultSummary, detail);
    }

    /**
     * 按发生顺序查询证物全部检验链事件（历史查询，稳定排序）。
     */
    public List<ExaminationChainEvent> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM examination_chain_event WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class EventRowMapper implements RowMapper<ExaminationChainEvent> {
        @Override
        public ExaminationChainEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExaminationChainEvent(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    ExaminationEventType.valueOf(rs.getString("event_type")),
                    rs.getString("actor_id"),
                    rs.getObject("event_time", LocalDateTime.class),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    rs.getObject("seal_version", Integer.class),
                    rs.getString("result_summary"),
                    rs.getString("detail"));
        }
    }
}
