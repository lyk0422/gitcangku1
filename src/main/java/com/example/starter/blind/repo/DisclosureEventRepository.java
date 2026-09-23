package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 泄露披露事件数据访问；exposure_key 主键全局唯一，重复登记触发 DuplicateKeyException。
 */
@Repository
public class DisclosureEventRepository {

    /** 披露事件行；源操作者固定为登记人本人。 */
    public record DisclosureEventRow(
            String exposureKey,
            String experimentId,
            String sourceActor,
            int participantCount,
            int receiverCount,
            int newEdges,
            long createdAt) {
    }

    private static final RowMapper<DisclosureEventRow> MAPPER = (rs, n) -> new DisclosureEventRow(
            rs.getString("exposure_key"),
            rs.getString("experiment_id"),
            rs.getString("source_actor"),
            rs.getInt("participant_count"),
            rs.getInt("receiver_count"),
            rs.getInt("new_edges"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public DisclosureEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入披露事件；exposure_key 重复时抛出 DuplicateKeyException（失败回滚不占键）。 */
    public void insert(DisclosureEventRow row) {
        jdbc.update("INSERT INTO disclosure_event ("
                        + "exposure_key, experiment_id, source_actor, participant_count, "
                        + "receiver_count, new_edges, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.exposureKey(), row.experimentId(), row.sourceActor(), row.participantCount(),
                row.receiverCount(), row.newEdges(), row.createdAt());
    }

    public DisclosureEventRow findByExposureKey(String exposureKey) {
        List<DisclosureEventRow> rows = jdbc.query(
                "SELECT exposure_key, experiment_id, source_actor, participant_count, "
                        + "receiver_count, new_edges, created_at "
                        + "FROM disclosure_event WHERE exposure_key = ?",
                MAPPER, exposureKey);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
