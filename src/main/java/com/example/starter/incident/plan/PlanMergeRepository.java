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
 * 合并证据仓储。merge_key 与 request_id 均全局唯一；
 * 仅合并成功时插入（失败事务回滚不占键），行不可变。
 */
@Repository
public class PlanMergeRepository {

    private final JdbcTemplate jdbc;

    public PlanMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanMerge> MAPPER = (rs, n) -> map(rs);

    private static PlanMerge map(ResultSet rs) throws SQLException {
        return new PlanMerge(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("merge_key"), rs.getString("request_id"),
                rs.getString("request_hash"), rs.getInt("base_version_no"),
                rs.getInt("left_version_no"), rs.getInt("right_version_no"),
                rs.getInt("result_version_no"), rs.getString("diff_json"),
                rs.getString("resolutions_json"), rs.getString("tasks_json"),
                rs.getString("edges_json"), rs.getString("response_json"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 合并成功时插入冻结证据，返回生成主键。
     * merge_key / request_id 唯一约束兜底并发重复。
     */
    public long insert(PlanMerge merge) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_merges (incident_id, merge_key, request_id, request_hash,"
                            + " base_version_no, left_version_no, right_version_no,"
                            + " result_version_no, diff_json, resolutions_json, tasks_json,"
                            + " edges_json, response_json, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, merge.incidentId());
            ps.setString(2, merge.mergeKey());
            ps.setString(3, merge.requestId());
            ps.setString(4, merge.requestHash());
            ps.setInt(5, merge.baseVersionNo());
            ps.setInt(6, merge.leftVersionNo());
            ps.setInt(7, merge.rightVersionNo());
            ps.setInt(8, merge.resultVersionNo());
            ps.setString(9, merge.diffJson());
            ps.setString(10, merge.resolutionsJson());
            ps.setString(11, merge.tasksJson());
            ps.setString(12, merge.edgesJson());
            ps.setString(13, merge.responseJson());
            ps.setString(14, merge.createdBy());
            ps.setTimestamp(15, Timestamp.from(merge.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键 requestId 查询（重放判定）。
     */
    public Optional<PlanMerge> findByRequestId(String requestId) {
        List<PlanMerge> rows = jdbc.query("SELECT * FROM plan_merges WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键 mergeKey 查询（证据查询与唯一性预检）。
     */
    public Optional<PlanMerge> findByMergeKey(String mergeKey) {
        List<PlanMerge> rows = jdbc.query("SELECT * FROM plan_merges WHERE merge_key = ?",
                MAPPER, mergeKey);
        return rows.stream().findFirst();
    }
}
