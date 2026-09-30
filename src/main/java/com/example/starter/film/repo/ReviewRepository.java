package com.example.starter.film.repo;

import com.example.starter.film.model.ReviewRequest;
import com.example.starter.film.model.ReviewSegmentBaseline;
import com.example.starter.film.model.ReviewStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 整片审核请求与基线数据访问。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<ReviewRequest> MAPPER = (rs, n) -> {
        Long masterId = rs.getObject("master_id", Long.class);
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ReviewRequest(
                rs.getLong("id"),
                rs.getLong("film_id"),
                rs.getString("request_id"),
                ReviewStatus.valueOf(rs.getString("status")),
                masterId,
                rs.getTimestamp("created_at").toLocalDateTime(),
                decided == null ? null : decided.toLocalDateTime()
        );
    };

    private static final RowMapper<ReviewSegmentBaseline> BASELINE_MAPPER = (rs, n) ->
            new ReviewSegmentBaseline(
                    rs.getLong("id"),
                    rs.getLong("review_id"),
                    rs.getInt("seq"),
                    rs.getInt("version_no"),
                    rs.getString("digest"),
                    rs.getTimestamp("fixed_at").toLocalDateTime()
            );

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建审核请求（PENDING）。
     */
    public ReviewRequest insert(long filmId, String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO review_request(film_id, request_id, status, master_id, created_at, decided_at) "
                        + "VALUES (:filmId, :requestId, 'PENDING', NULL, :createdAt, NULL)",
                new MapSqlParameterSource()
                        .addValue("filmId", filmId)
                        .addValue("requestId", requestId)
                        .addValue("createdAt", Timestamp.valueOf(now)),
                keyHolder,
                new String[]{"id"}
        );
        return new ReviewRequest(keyHolder.getKey().longValue(), filmId, requestId,
                ReviewStatus.PENDING, null, now, null);
    }

    public void insertBaseline(long reviewId, int seq, int versionNo, String digest, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO review_segment_baseline(review_id, seq, version_no, digest, fixed_at) "
                        + "VALUES (:reviewId, :seq, :versionNo, :digest, :fixedAt)",
                new MapSqlParameterSource()
                        .addValue("reviewId", reviewId)
                        .addValue("seq", seq)
                        .addValue("versionNo", versionNo)
                        .addValue("digest", digest)
                        .addValue("fixedAt", Timestamp.valueOf(now))
        );
    }

    public List<ReviewSegmentBaseline> findBaselines(long reviewId) {
        return jdbc.query(
                "SELECT id, review_id, seq, version_no, digest, fixed_at "
                        + "FROM review_segment_baseline WHERE review_id = :reviewId ORDER BY seq ASC",
                new MapSqlParameterSource("reviewId", reviewId),
                BASELINE_MAPPER
        );
    }

    public Optional<ReviewRequest> findById(long reviewId) {
        return queryOne(
                "SELECT id, film_id, request_id, status, master_id, created_at, decided_at "
                        + "FROM review_request WHERE id = :id",
                new MapSqlParameterSource("id", reviewId)
        );
    }

    public Optional<ReviewRequest> findByRequestId(String requestId) {
        return queryOne(
                "SELECT id, film_id, request_id, status, master_id, created_at, decided_at "
                        + "FROM review_request WHERE request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId)
        );
    }

    /**
     * 将审核标记为通过并挂接母版（条件更新，只作用于 PENDING 行）。
     *
     * @return 是否更新到一行；false 表示状态已被并发改变
     */
    public boolean markApproved(long reviewId, long masterId, LocalDateTime now) {
        int rows = jdbc.update(
                "UPDATE review_request SET status = 'APPROVED', master_id = :masterId, decided_at = :now "
                        + "WHERE id = :reviewId AND status = 'PENDING'",
                new MapSqlParameterSource()
                        .addValue("masterId", masterId)
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("reviewId", reviewId)
        );
        return rows == 1;
    }

    /**
     * 将未决审核标记为过期（片段在审核后发生变化）。
     */
    public int markPendingExpired(long filmId) {
        return jdbc.update(
                "UPDATE review_request SET status = 'EXPIRED' "
                        + "WHERE film_id = :filmId AND status = 'PENDING'",
                new MapSqlParameterSource("filmId", filmId)
        );
    }

    /**
     * 将指定的未决审核标记为过期（通过时发现版本漂移）。
     *
     * @return 是否更新到一行
     */
    public boolean markExpired(long reviewId) {
        return jdbc.update(
                "UPDATE review_request SET status = 'EXPIRED' WHERE id = :reviewId AND status = 'PENDING'",
                new MapSqlParameterSource("reviewId", reviewId)
        ) == 1;
    }

    /**
     * 某影片全部审核请求，按ID升序。
     */
    public List<ReviewRequest> findByFilm(long filmId) {
        return jdbc.query(
                "SELECT id, film_id, request_id, status, master_id, created_at, decided_at "
                        + "FROM review_request WHERE film_id = :filmId ORDER BY id ASC",
                new MapSqlParameterSource("filmId", filmId),
                MAPPER
        );
    }

    /**
     * 撤销已通过审核（只阻止新交付）。
     */
    public boolean markRevoked(long reviewId, LocalDateTime now) {
        int rows = jdbc.update(
                "UPDATE review_request SET status = 'REVOKED', decided_at = :now "
                        + "WHERE id = :reviewId AND status = 'APPROVED'",
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.valueOf(now))
                        .addValue("reviewId", reviewId)
        );
        return rows == 1;
    }

    private Optional<ReviewRequest> queryOne(String sql, MapSqlParameterSource params) {
        List<ReviewRequest> list = jdbc.query(sql, params, MAPPER);
        return list.stream().findFirst();
    }
}
