package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检验保管链事件表访问。只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class ExamChainEventRepository {

    private static final ExamChainEventRowMapper ROW_MAPPER = new ExamChainEventRowMapper();

    private final JdbcTemplate jdbc;

    public ExamChainEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条检验保管链事件。
     */
    public void insert(String reservationKey, String evidenceKey, String benchKey,
                       ExamChainEventType eventType, String actorId, String sealNo, int sealVersion,
                       String resultSummary, String newSealNo, String newSealCheck,
                       LocalDateTime eventAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO exam_chain_event
                            (reservation_key, evidence_key, bench_key, event_type, actor_id,
                             seal_no, seal_version, result_summary, new_seal_no, new_seal_check,
                             event_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservationKey, evidenceKey, benchKey, eventType.name(), actorId,
                sealNo, sealVersion, resultSummary, newSealNo, newSealCheck, eventAt, createdAt);
    }

    /**
     * 按证物查询全部检验事件（按写入顺序，即保管链发生顺序）。
     */
    public List<ExamChainEvent> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM exam_chain_event WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按预约查询其全部事件（按写入顺序）。
     */
    public List<ExamChainEvent> findByReservationKey(String reservationKey) {
        return jdbc.query(
                "SELECT * FROM exam_chain_event WHERE reservation_key = ? ORDER BY id",
                ROW_MAPPER, reservationKey);
    }

    private static final class ExamChainEventRowMapper implements RowMapper<ExamChainEvent> {
        @Override
        public ExamChainEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamChainEvent(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    rs.getString("bench_key"),
                    ExamChainEventType.valueOf(rs.getString("event_type")),
                    rs.getString("actor_id"),
                    rs.getString("seal_no"),
                    rs.getInt("seal_version"),
                    rs.getString("result_summary"),
                    rs.getString("new_seal_no"),
                    rs.getString("new_seal_check"),
                    rs.getObject("event_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
