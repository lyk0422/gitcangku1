package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 受试者数据提交数据访问；每条写入归属提交时活动的授权代次。
 */
@Repository
public class SubjectDataRepository {

    /** 数据提交行。generationNo 为写入归属的授权代次序号。 */
    public record SubjectDataRow(
            long id,
            String experimentId,
            String participantId,
            String collectorActor,
            int generationNo,
            String payload,
            long submittedAt) {
    }

    private final JdbcTemplate jdbc;

    public SubjectDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入数据写入并归属指定代次。
     *
     * @return 自增主键
     */
    public long insert(String experimentId, String participantId, String collectorActor,
                       int generationNo, String payload, long submittedAt) {
        jdbc.update("INSERT INTO subject_data ("
                        + "experiment_id, participant_id, collector_actor, generation_no, "
                        + "payload, submitted_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                experimentId, participantId, collectorActor, generationNo, payload, submittedAt);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM subject_data "
                        + "WHERE experiment_id = ? AND participant_id = ? AND collector_actor = ?",
                Long.class, experimentId, participantId, collectorActor);
        if (id == null) {
            throw new IllegalStateException("数据写入后未找到主键");
        }
        return id;
    }

    public List<SubjectDataRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, collector_actor, generation_no, "
                        + "payload, submitted_at FROM subject_data "
                        + "WHERE experiment_id = ? ORDER BY id",
                (rs, n) -> new SubjectDataRow(
                        rs.getLong("id"),
                        rs.getString("experiment_id"),
                        rs.getString("participant_id"),
                        rs.getString("collector_actor"),
                        rs.getInt("generation_no"),
                        rs.getString("payload"),
                        rs.getLong("submitted_at")),
                experimentId);
    }
}
