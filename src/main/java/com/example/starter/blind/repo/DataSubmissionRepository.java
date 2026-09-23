package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 数据提交记录数据访问；每条提交按事务提交顺序归属提交时活动代次。
 */
@Repository
public class DataSubmissionRepository {

    /** 数据提交行。 */
    public record DataSubmissionRow(
            long id,
            String experimentId,
            String participantId,
            String actorId,
            long generationId,
            String payload,
            long submittedAt) {
    }

    private static final RowMapper<DataSubmissionRow> MAPPER = (rs, n) -> new DataSubmissionRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("actor_id"),
            rs.getLong("generation_id"),
            rs.getString("payload"),
            rs.getLong("submitted_at"));

    private final JdbcTemplate jdbc;

    public DataSubmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DataSubmissionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO data_submission (experiment_id, participant_id, actor_id, "
                            + "generation_id, payload, submitted_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setString(2, row.participantId());
            ps.setString(3, row.actorId());
            ps.setLong(4, row.generationId());
            ps.setString(5, row.payload());
            ps.setLong(6, row.submittedAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 data_submission 未返回主键");
        }
        return key.longValue();
    }

    public DataSubmissionRow findById(long id) {
        List<DataSubmissionRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, actor_id, generation_id, payload, "
                        + "submitted_at FROM data_submission WHERE id = ?",
                MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
