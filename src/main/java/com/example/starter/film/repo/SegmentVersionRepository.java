package com.example.starter.film.repo;

import com.example.starter.film.model.SegmentVersion;
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
 * 片段工作版数据访问。工作版只追加，不提供 update。
 */
@Repository
public class SegmentVersionRepository {

    private static final RowMapper<SegmentVersion> MAPPER = (rs, n) -> new SegmentVersion(
            rs.getLong("id"),
            rs.getLong("film_id"),
            rs.getInt("seq"),
            rs.getInt("version_no"),
            rs.getString("digest"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public SegmentVersionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条新版次（append-only）。
     */
    public SegmentVersion insert(long filmId, int seq, int versionNo, String digest,
                                 String requestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO segment_version(film_id, seq, version_no, digest, request_id, created_at) "
                        + "VALUES (:filmId, :seq, :versionNo, :digest, :requestId, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("filmId", filmId)
                        .addValue("seq", seq)
                        .addValue("versionNo", versionNo)
                        .addValue("digest", digest)
                        .addValue("requestId", requestId)
                        .addValue("createdAt", Timestamp.valueOf(now)),
                keyHolder,
                new String[]{"id"}
        );
        return new SegmentVersion(keyHolder.getKey().longValue(), filmId, seq, versionNo,
                digest, requestId, now);
    }

    /**
     * 取某片段当前最新版次；无提交时为空。
     */
    public Optional<SegmentVersion> findLatest(long filmId, int seq) {
        List<SegmentVersion> list = jdbc.query(
                "SELECT id, film_id, seq, version_no, digest, request_id, created_at "
                        + "FROM segment_version WHERE film_id = :filmId AND seq = :seq "
                        + "ORDER BY version_no DESC LIMIT 1",
                new MapSqlParameterSource().addValue("filmId", filmId).addValue("seq", seq),
                MAPPER
        );
        return list.stream().findFirst();
    }

    /**
     * 取指定版次；乐观冲突后回溯原结果使用。
     */
    public Optional<SegmentVersion> findByVersion(long filmId, int seq, int versionNo) {
        List<SegmentVersion> list = jdbc.query(
                "SELECT id, film_id, seq, version_no, digest, request_id, created_at "
                        + "FROM segment_version WHERE film_id = :filmId AND seq = :seq AND version_no = :versionNo",
                new MapSqlParameterSource()
                        .addValue("filmId", filmId)
                        .addValue("seq", seq)
                        .addValue("versionNo", versionNo),
                MAPPER
        );
        return list.stream().findFirst();
    }

    /**
     * 按版次行ID查询（幂等重放用）。
     */
    public Optional<SegmentVersion> findById(long id) {
        List<SegmentVersion> list = jdbc.query(
                "SELECT id, film_id, seq, version_no, digest, request_id, created_at "
                        + "FROM segment_version WHERE id = :id",
                new MapSqlParameterSource("id", id),
                MAPPER
        );
        return list.stream().findFirst();
    }

    /**
     * 某影片所有片段的版次链，按序号、版次升序，用于查询展示。
     */
    public List<SegmentVersion> findChainByFilm(long filmId) {
        return jdbc.query(
                "SELECT id, film_id, seq, version_no, digest, request_id, created_at "
                        + "FROM segment_version WHERE film_id = :filmId "
                        + "ORDER BY seq ASC, version_no ASC",
                new MapSqlParameterSource("filmId", filmId),
                MAPPER
        );
    }

    /**
     * 每个序号当前最新版次，按序号升序（须在持有影片行锁后调用）。
     */
    public List<SegmentVersion> findLatestEachByFilm(long filmId) {
        return jdbc.query(
                "SELECT v.id, v.film_id, v.seq, v.version_no, v.digest, v.request_id, v.created_at "
                        + "FROM segment_version v "
                        + "JOIN (SELECT seq, MAX(version_no) AS max_no FROM segment_version "
                        + "      WHERE film_id = :filmId GROUP BY seq) m "
                        + "  ON v.seq = m.seq AND v.version_no = m.max_no "
                        + "WHERE v.film_id = :filmId ORDER BY v.seq ASC",
                new MapSqlParameterSource("filmId", filmId),
                MAPPER
        );
    }

    public Optional<SegmentVersion> findByRequestId(String requestId) {
        List<SegmentVersion> list = jdbc.query(
                "SELECT id, film_id, seq, version_no, digest, request_id, created_at "
                        + "FROM segment_version WHERE request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId),
                MAPPER
        );
        return list.stream().findFirst();
    }
}
