package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 受限分析数据访问；snapshot 为盲底内部依据，禁止透出到接口响应。
 */
@Repository
public class AnalysisRepository {

    /** 分析行。favorableCount/totalCount 为精确 k/n；snapshot 含个体映射，仅供内部审计。 */
    public record AnalysisRow(
            String id,
            String experimentId,
            int resultVersion,
            int participantCount,
            int groupACount,
            int groupBCount,
            long favorableCount,
            long totalCount,
            String snapshot,
            String analystActor,
            long createdAt) {
    }

    private static final RowMapper<AnalysisRow> MAPPER = (rs, n) -> new AnalysisRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getInt("result_version"),
            rs.getInt("participant_count"),
            rs.getInt("group_a_count"),
            rs.getInt("group_b_count"),
            rs.getLong("favorable_count"),
            rs.getLong("total_count"),
            rs.getString("snapshot"),
            rs.getString("analyst_actor"),
            rs.getLong("created_at"));

    private static final String COLUMNS =
            "id, experiment_id, result_version, participant_count, group_a_count, group_b_count, "
                    + "favorable_count, total_count, snapshot, analyst_actor, created_at";

    private final JdbcTemplate jdbc;

    public AnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnalysisRow row) {
        jdbc.update("INSERT INTO analysis ("
                        + "id, experiment_id, result_version, participant_count, "
                        + "group_a_count, group_b_count, favorable_count, total_count, "
                        + "snapshot, analyst_actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.experimentId(), row.resultVersion(), row.participantCount(),
                row.groupACount(), row.groupBCount(), row.favorableCount(), row.totalCount(),
                row.snapshot(), row.analystActor(), row.createdAt());
    }

    public AnalysisRow findById(String analysisId) {
        List<AnalysisRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM analysis WHERE id = ?", MAPPER, analysisId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
