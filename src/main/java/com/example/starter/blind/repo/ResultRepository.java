package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 参与者结果数据访问；结果按版本追加，历史版本保留供分析冻结引用。
 */
@Repository
public class ResultRepository {

    /** 结果行。version 从 1 起每次修订加一；resultValue 范围 0~100。 */
    public record ResultRow(
            String experimentId,
            String participantId,
            int version,
            int value,
            String updatedActor,
            long updatedAt) {
    }

    private static final RowMapper<ResultRow> MAPPER = (rs, n) -> new ResultRow(
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("version"),
            rs.getInt("result_value"),
            rs.getString("updated_actor"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public ResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResultRow row) {
        jdbc.update("INSERT INTO participant_result ("
                        + "experiment_id, participant_id, version, result_value, updated_actor, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.version(), row.value(),
                row.updatedActor(), row.updatedAt());
    }

    /** 当前最新版本；无结果时返回 null。 */
    public ResultRow findLatest(String experimentId, String participantId) {
        List<ResultRow> rows = jdbc.query(
                "SELECT experiment_id, participant_id, version, result_value, updated_actor, updated_at "
                        + "FROM participant_result WHERE experiment_id = ? AND participant_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 指定版本的冻结结果；该版本无值时返回 null。 */
    public ResultRow findByVersion(String experimentId, String participantId, int version) {
        List<ResultRow> rows = jdbc.query(
                "SELECT experiment_id, participant_id, version, result_value, updated_actor, updated_at "
                        + "FROM participant_result "
                        + "WHERE experiment_id = ? AND participant_id = ? AND version = ?",
                MAPPER, experimentId, participantId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
