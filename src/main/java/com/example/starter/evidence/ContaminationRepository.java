package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 污染事件与传播路径表访问。记录只追加，污染不可解除；
 * 传播路径（受影响证物及其来源批次）随污染事件同事务冻结。
 */
@Repository
public class ContaminationRepository {

    private static final EventRowMapper EVENT_MAPPER = new EventRowMapper();
    private static final MemberRowMapper MEMBER_MAPPER = new MemberRowMapper();

    private final JdbcTemplate jdbc;

    public ContaminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一次污染事件，返回生成的污染事件主键。
     */
    public long insertEvent(String originEvidenceKey, long originEventSeq, String registeredBy,
                            LocalDateTime createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO contamination_event
                                (origin_evidence_key, origin_event_seq, registered_by, created_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, originEvidenceKey);
            ps.setLong(2, originEventSeq);
            ps.setString(3, registeredBy);
            ps.setTimestamp(4, Timestamp.valueOf(createdAtUtc));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("污染事件主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 追加一条传播路径记录：受影响证物及其加入污染集合的来源批次事件序号。
     */
    public void insertMember(long contaminationId, String evidenceKey, long sourceEventSeq) {
        jdbc.update("""
                        INSERT INTO contamination_member (contamination_id, evidence_key, source_event_seq)
                        VALUES (?, ?, ?)
                        """,
                contaminationId, evidenceKey, sourceEventSeq);
    }

    /**
     * 按主键查询污染事件。
     */
    public Optional<ContaminationEvent> findEventById(long contaminationId) {
        List<ContaminationEvent> rows = jdbc.query(
                "SELECT * FROM contamination_event WHERE id = ?", EVENT_MAPPER, contaminationId);
        return rows.stream().findFirst();
    }

    /**
     * 查询污染事件的全部传播路径（按证物键排序）。
     */
    public List<ContaminationMember> findMembers(long contaminationId) {
        return jdbc.query("""
                        SELECT * FROM contamination_member
                        WHERE contamination_id = ? ORDER BY evidence_key
                        """,
                MEMBER_MAPPER, contaminationId);
    }

    /**
     * 污染事件记录。
     *
     * @param id                污染事件主键
     * @param originEvidenceKey 污染起点证物业务键
     * @param originEventSeq    污染起点事件序号
     * @param registeredBy      登记操作人
     * @param createdAt         污染登记时刻（UTC）
     */
    public record ContaminationEvent(long id, String originEvidenceKey, long originEventSeq,
                                     String registeredBy, LocalDateTime createdAt) {
    }

    /**
     * 污染传播路径记录。
     *
     * @param contaminationId 所属污染事件
     * @param evidenceKey     被隔离的证物业务键
     * @param sourceEventSeq  使该证物加入污染集合的接触批次事件序号
     */
    public record ContaminationMember(long contaminationId, String evidenceKey, long sourceEventSeq) {
    }

    private static final class EventRowMapper implements RowMapper<ContaminationEvent> {
        @Override
        public ContaminationEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContaminationEvent(
                    rs.getLong("id"),
                    rs.getString("origin_evidence_key"),
                    rs.getLong("origin_event_seq"),
                    rs.getString("registered_by"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class MemberRowMapper implements RowMapper<ContaminationMember> {
        @Override
        public ContaminationMember mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContaminationMember(
                    rs.getLong("contamination_id"),
                    rs.getString("evidence_key"),
                    rs.getLong("source_event_seq"));
        }
    }
}
