package com.example.starter.incident.plan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 三方合并证据的 JDBC 仓储。
 * merge_key 与 request_id 全局唯一；仅在合并发布成功的事务内写入，
 * 失败事务回滚不占键；并发同键插入由唯一约束串行化。
 */
@Repository
public class PlanMergeRepository {

    private final JdbcTemplate jdbc;

    public PlanMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanMerge> MAPPER = (rs, n) -> map(rs);

    private static PlanMerge map(ResultSet rs) throws SQLException {
        return new PlanMerge(rs.getLong("id"), rs.getLong("plan_id"), rs.getString("merge_key"),
                rs.getString("request_id"), rs.getString("request_hash"),
                rs.getLong("base_version_id"), rs.getLong("left_version_id"),
                rs.getLong("right_version_id"), rs.getInt("left_expected"),
                rs.getInt("right_expected"), rs.getLong("result_version_id"),
                rs.getString("diff_json"), rs.getString("resolutions_json"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 按幂等键查询合并记录（普通读），用于同键重放检测。
     */
    public Optional<PlanMerge> findByRequestId(String requestId) {
        List<PlanMerge> rows = jdbc.query("SELECT * FROM plan_merges WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按合并业务键查询合并记录，用于证据查询与 mergeKey 唯一性预检。
     */
    public Optional<PlanMerge> findByMergeKey(String mergeKey) {
        List<PlanMerge> rows = jdbc.query("SELECT * FROM plan_merges WHERE merge_key = ?",
                MAPPER, mergeKey);
        return rows.stream().findFirst();
    }

    /**
     * 写入合并证据（与合并发布同事务提交），返回生成主键。
     */
    public long insert(PlanMerge merge) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO plan_merges (plan_id, merge_key, request_id, request_hash,"
                            + " base_version_id, left_version_id, right_version_id,"
                            + " left_expected, right_expected, result_version_id,"
                            + " diff_json, resolutions_json, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, merge.planId());
            ps.setString(2, merge.mergeKey());
            ps.setString(3, merge.requestId());
            ps.setString(4, merge.requestHash());
            ps.setLong(5, merge.baseVersionId());
            ps.setLong(6, merge.leftVersionId());
            ps.setLong(7, merge.rightVersionId());
            ps.setInt(8, merge.leftExpected());
            ps.setInt(9, merge.rightExpected());
            ps.setLong(10, merge.resultVersionId());
            ps.setString(11, merge.diffJson());
            ps.setString(12, merge.resolutionsJson());
            ps.setString(13, merge.createdBy());
            ps.setTimestamp(14, Timestamp.from(merge.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
