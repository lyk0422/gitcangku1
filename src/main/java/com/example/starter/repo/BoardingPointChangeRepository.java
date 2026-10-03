package com.example.starter.repo;

import com.example.starter.model.BoardingPointChangeRow;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 登轮点变更历史表访问。
 */
@Repository
public class BoardingPointChangeRepository {

    private static final RowMapper<BoardingPointChangeRow> MAPPER = (rs, rowNum) ->
            new BoardingPointChangeRow(rs.getLong("id"), rs.getLong("segment_id"),
                    rs.getString("old_point"), rs.getString("new_point"),
                    JdbcTimes.toInstant(rs, "changed_at_utc"));

    private final JdbcTemplate jdbc;

    public BoardingPointChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long segmentId, String oldPoint, String newPoint, Instant changedAtUtc) {
        jdbc.update("INSERT INTO boarding_point_change (segment_id, old_point, new_point,"
                        + " changed_at_utc) VALUES (?, ?, ?, ?)",
                segmentId, oldPoint, newPoint, JdbcTimes.toColumn(changedAtUtc));
    }

    public List<BoardingPointChangeRow> findBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM boarding_point_change WHERE segment_id = ? ORDER BY id",
                MAPPER, segmentId);
    }
}