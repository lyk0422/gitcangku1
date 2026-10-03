package com.example.coral.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.Review;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 复核记录持久化：只追加不修改，按时间构成复核链。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<Review> MAPPER = (rs, n) -> new Review(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("transplant_id"),
            PlantStatus.valueOf(rs.getString("result")),
            rs.getString("note"),
            rs.getTimestamp("reviewed_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, long transplantId, PlantStatus result, String note,
            Instant reviewedAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review (request_id, transplant_id, result, note, reviewed_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, transplantId);
            ps.setString(3, result.name());
            ps.setString(4, note);
            ps.setTimestamp(5, Timestamp.from(reviewedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Review> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM review WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 某株植株的复核链，按复核顺序。 */
    public List<Review> findByTransplantId(long transplantId) {
        return jdbc.query("SELECT * FROM review WHERE transplant_id = ? ORDER BY id", MAPPER,
                transplantId);
    }
}