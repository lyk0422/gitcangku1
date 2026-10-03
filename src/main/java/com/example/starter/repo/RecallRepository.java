package com.example.starter.repo;

import java.sql.Statement;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 召回记录表访问：登记批号召回及本次隔离瓶数。
 */
@Repository
public class RecallRepository {

    private final JdbcTemplate jdbc;

    public RecallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条召回记录，返回生成主键。
     */
    public long insert(String batchNo, String reason, int quarantinedCount, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO recall_record (batch_no, reason, quarantined_count, recalled_at)"
                    + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, reason);
            ps.setInt(3, quarantinedCount);
            ps.setObject(4, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}