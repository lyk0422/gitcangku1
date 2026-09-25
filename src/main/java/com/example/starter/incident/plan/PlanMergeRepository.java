package com.example.starter.incident.plan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 方案合并证据仓储。merge_key 与 request_id 均全局唯一；
 * 证据行随合并成功同事务写入，之后不可变，只读查询按 merge_key 定位。
 */
@Repository
public class PlanMergeRepository {

    private final JdbcTemplate jdbc;

    public PlanMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanMergeRecord> MAPPER = (rs, n) -> map(rs);

    private static PlanMergeRecord map(ResultSet rs) throws SQLException {
        return new PlanMergeRecord(rs.getLong("id"), rs.getString("merge_key"),
                rs.getString("request_id"), rs.getString("incident_key"),
                rs.getLong("base_version_id"), rs.getLong("left_version_id"),
                rs.getLong("right_version_id"), rs.getLong("result_version_id"),
                rs.getString("request_hash"), rs.getString("diff_json"),
                rs.getString("resolutions_json"), rs.getString("final_tasks_json"),
                rs.getString("final_edges_json"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 写入合并证据，返回生成主键。merge_key/request_id 唯一约束兜底并发重复。
     */
    public long insert(PlanMergeRecord record) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_merges (merge_key, request_id, incident_key, base_version_id,"
                            + " left_version_id, right_version_id, result_version_id, request_hash,"
                            + " diff_json, resolutions_json, final_tasks_json, final_edges_json,"
                            + " created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.mergeKey());
            ps.setString(2, record.requestId());
            ps.setString(3, record.incidentKey());
            ps.setLong(4, record.baseVersionId());
            ps.setLong(5, record.leftVersionId());
            ps.setLong(6, record.rightVersionId());
            ps.setLong(7, record.resultVersionId());
            ps.setString(8, record.requestHash());
            ps.setString(9, record.diffJson());
            ps.setString(10, record.resolutionsJson());
            ps.setString(11, record.finalTasksJson());
            ps.setString(12, record.finalEdgesJson());
            ps.setString(13, record.createdBy());
            ps.setTimestamp(14, Timestamp.from(record.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按合并业务键查询证据。
     */
    public Optional<PlanMergeRecord> findByMergeKey(String mergeKey) {
        List<PlanMergeRecord> rows = jdbc.query(
                "SELECT * FROM plan_merges WHERE merge_key = ?", MAPPER, mergeKey);
        return rows.stream().findFirst();
    }
}
