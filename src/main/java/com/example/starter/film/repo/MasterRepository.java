package com.example.starter.film.repo;

import com.example.starter.film.model.Master;
import com.example.starter.film.model.MasterSegment;
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
 * 不可变母版及片段快照数据访问；只插入，不更新。
 */
@Repository
public class MasterRepository {

    private static final RowMapper<Master> MASTER_MAPPER = (rs, n) -> new Master(
            rs.getLong("id"),
            rs.getString("master_no"),
            rs.getLong("film_id"),
            rs.getLong("review_id"),
            rs.getInt("segment_count"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private static final RowMapper<MasterSegment> SEGMENT_MAPPER = (rs, n) -> new MasterSegment(
            rs.getLong("id"),
            rs.getLong("master_id"),
            rs.getInt("seq"),
            rs.getInt("version_no"),
            rs.getString("digest")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public MasterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Master insert(String masterNo, long filmId, long reviewId,
                         int segmentCount, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO master(master_no, film_id, review_id, segment_count, created_at) "
                        + "VALUES (:masterNo, :filmId, :reviewId, :segmentCount, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("masterNo", masterNo)
                        .addValue("filmId", filmId)
                        .addValue("reviewId", reviewId)
                        .addValue("segmentCount", segmentCount)
                        .addValue("createdAt", Timestamp.valueOf(now)),
                keyHolder,
                new String[]{"id"}
        );
        return new Master(keyHolder.getKey().longValue(), masterNo, filmId, reviewId,
                segmentCount, now);
    }

    public void insertSegment(long masterId, int seq, int versionNo, String digest) {
        jdbc.update(
                "INSERT INTO master_segment(master_id, seq, version_no, digest) "
                        + "VALUES (:masterId, :seq, :versionNo, :digest)",
                new MapSqlParameterSource()
                        .addValue("masterId", masterId)
                        .addValue("seq", seq)
                        .addValue("versionNo", versionNo)
                        .addValue("digest", digest)
        );
    }

    public Optional<Master> findById(long id) {
        return queryOne(
                "SELECT id, master_no, film_id, review_id, segment_count, created_at "
                        + "FROM master WHERE id = :id",
                new MapSqlParameterSource("id", id)
        );
    }

    public Optional<Master> findByNo(String masterNo) {
        return queryOne(
                "SELECT id, master_no, film_id, review_id, segment_count, created_at "
                        + "FROM master WHERE master_no = :masterNo",
                new MapSqlParameterSource("masterNo", masterNo)
        );
    }

    public List<MasterSegment> findSegments(long masterId) {
        return jdbc.query(
                "SELECT id, master_id, seq, version_no, digest "
                        + "FROM master_segment WHERE master_id = :masterId ORDER BY seq ASC",
                new MapSqlParameterSource("masterId", masterId),
                SEGMENT_MAPPER
        );
    }

    public List<Master> findByFilm(long filmId) {
        return jdbc.query(
                "SELECT id, master_no, film_id, review_id, segment_count, created_at "
                        + "FROM master WHERE film_id = :filmId ORDER BY id ASC",
                new MapSqlParameterSource("filmId", filmId),
                MASTER_MAPPER
        );
    }

    private Optional<Master> queryOne(String sql, MapSqlParameterSource params) {
        List<Master> list = jdbc.query(sql, params, MASTER_MAPPER);
        return list.stream().findFirst();
    }
}
