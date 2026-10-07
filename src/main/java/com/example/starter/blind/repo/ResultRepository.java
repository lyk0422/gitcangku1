package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 结果版本数据访问；每次提交/修订追加一行，历史版本保留供固定版本分析。
 */
@Repository
public class ResultRepository {

    /** 结果版本行。resultValue 取值 0~100；version 从 1 递增。 */
    public record ResultRow(
            String experimentId,
            String participantId,
            int version,
            int resultValue,
            String submittedActor,
            long submittedAt) {
    }

    private static final RowMapper<ResultRow> MAPPER = (rs, n) -> new ResultRow(
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("version"),
            rs.getInt("result_value"),
            rs.getString("submitted_actor"),
            rs.getLong("submitted_at"));

    private static final String COLUMNS =
            "experiment_id, participant_id, version, result_value, submitted_actor, submitted_at";

    private final JdbcTemplate jdbc;

    public ResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResultRow row) {
        jdbc.update("INSERT INTO result_version ("
                        + "experiment_id, participant_id, version, result_value, "
                        + "submitted_actor, submitted_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.version(), row.resultValue(),
                row.submittedActor(), row.submittedAt());
    }

    /** 当前（最大）版本的结果；从未提交时返回 null。 */
    public ResultRow findCurrent(String experimentId, String participantId) {
        List<ResultRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM result_version "
                        + "WHERE experiment_id = ? AND participant_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 指定版本的结果；该版本不存在时返回 null。 */
    public ResultRow findVersion(String experimentId, String participantId, int version) {
        List<ResultRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM result_version "
                        + "WHERE experiment_id = ? AND participant_id = ? AND version = ?",
                MAPPER, experimentId, participantId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
