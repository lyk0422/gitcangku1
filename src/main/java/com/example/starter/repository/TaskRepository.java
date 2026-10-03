package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.ReceiveTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接收任务仓库：负责任务登记与查询。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<ReceiveTask> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ReceiveTask map(ResultSet rs) throws SQLException {
        return new ReceiveTask(
                rs.getLong("id"),
                rs.getString("task_code"),
                rs.getLong("antenna_id"),
                JdbcTimes.read(rs, "start_at"),
                JdbcTimes.read(rs, "end_at"),
                JdbcTimes.read(rs, "created_at"));
    }

    /** 插入任务并返回自增主键。 */
    public long insert(String taskCode, long antennaId, Instant startAt, Instant endAt, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO receive_task (task_code, antenna_id, start_at, end_at, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskCode);
            ps.setLong(2, antennaId);
            ps.setTimestamp(3, JdbcTimes.toSql(startAt));
            ps.setTimestamp(4, JdbcTimes.toSql(endAt));
            ps.setTimestamp(5, JdbcTimes.toSql(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ReceiveTask> findById(long id) {
        List<ReceiveTask> rows = jdbc.query("SELECT * FROM receive_task WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }
}