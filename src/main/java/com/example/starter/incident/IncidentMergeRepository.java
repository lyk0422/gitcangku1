package com.example.starter.incident;

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
 * 重复事件合并记录仓储。merge_key 全局唯一；记录落库后不可变（无更新路径），
 * 合并失败事务回滚不占键。所有写调用均处于先锁定双方事件行的写事务内。
 */
@Repository
public class IncidentMergeRepository {

    private final JdbcTemplate jdbc;

    public IncidentMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentMerge> MAPPER = (rs, n) -> map(rs);

    private static IncidentMerge map(ResultSet rs) throws SQLException {
        return new IncidentMerge(rs.getLong("id"), rs.getString("merge_key"),
                rs.getLong("surviving_incident_id"), rs.getLong("merged_incident_id"),
                rs.getString("actor"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入不可变合并记录，返回生成主键。merge_key 唯一约束兜底并发重复合并。
     *
     * @throws org.springframework.dao.DuplicateKeyException mergeKey 已被使用时抛出
     */
    public long insert(IncidentMerge merge) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_merges (merge_key, surviving_incident_id,"
                            + " merged_incident_id, actor, created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, merge.mergeKey());
            ps.setLong(2, merge.survivingIncidentId());
            ps.setLong(3, merge.mergedIncidentId());
            ps.setString(4, merge.actor());
            ps.setTimestamp(5, Timestamp.from(merge.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按合并业务键查询（不存在返回空）。
     */
    public Optional<IncidentMerge> findByMergeKey(String mergeKey) {
        List<IncidentMerge> rows = jdbc.query("SELECT * FROM incident_merges WHERE merge_key = ?",
                MAPPER, mergeKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部合并记录，按落库顺序（id 升序）稳定返回。
     */
    public List<IncidentMerge> listAll() {
        return jdbc.query("SELECT * FROM incident_merges ORDER BY id", MAPPER);
    }
}
