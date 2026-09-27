package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 许可撤销复核快照持久化：permit_review_snapshot 只追加、不更新。所有 SQL 使用参数化查询。
 */
@Repository
public class PermitReviewSnapshotRepository {

    private static final String SNAPSHOT_COLUMNS =
            "id, observation_id, permit_id, permit_version, observation_version, revoked_at_utc";

    private static final RowMapper<PermitReviewSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new PermitReviewSnapshot(
            rs.getLong("id"),
            rs.getString("observation_id"),
            rs.getString("permit_id"),
            rs.getInt("permit_version"),
            rs.getInt("observation_version"),
            rs.getTimestamp("revoked_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PermitReviewSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条复核快照（许可撤销时与复核标记同事务写入）。
     */
    public void insert(PermitReviewSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO permit_review_snapshot (observation_id, permit_id, permit_version, "
                        + "observation_version, revoked_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.permitId(), snapshot.permitVersion(),
                snapshot.observationVersion(), Timestamp.from(snapshot.revokedAtUtc()));
    }

    /**
     * 按许可标识查询全部复核快照，按落库先后排序。
     */
    public List<PermitReviewSnapshot> findByPermitId(String permitId) {
        return jdbcTemplate.query(
                "SELECT " + SNAPSHOT_COLUMNS + " FROM permit_review_snapshot WHERE permit_id = ? "
                        + "ORDER BY id ASC",
                SNAPSHOT_MAPPER, permitId);
    }
}
