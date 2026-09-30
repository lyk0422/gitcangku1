package com.example.starter.film.repo;

import com.example.starter.film.model.DeliveryReceipt;
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
 * 交付回执数据访问；只插入，不更新不删除。
 */
@Repository
public class DeliveryRepository {

    private static final RowMapper<DeliveryReceipt> MAPPER = (rs, n) -> new DeliveryReceipt(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("film_id"),
            rs.getLong("master_id"),
            rs.getString("master_no"),
            rs.getString("snapshot_digest"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入回执；requestId 唯一冲突时由调用方转重放。
     */
    public DeliveryReceipt insert(String requestId, long filmId, long masterId, String masterNo,
                                  String snapshotDigest, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO delivery_receipt(request_id, film_id, master_id, master_no, "
                        + "snapshot_digest, created_at) "
                        + "VALUES (:requestId, :filmId, :masterId, :masterNo, :snapshotDigest, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("requestId", requestId)
                        .addValue("filmId", filmId)
                        .addValue("masterId", masterId)
                        .addValue("masterNo", masterNo)
                        .addValue("snapshotDigest", snapshotDigest)
                        .addValue("createdAt", Timestamp.valueOf(now)),
                keyHolder,
                new String[]{"id"}
        );
        return new DeliveryReceipt(keyHolder.getKey().longValue(), requestId, filmId,
                masterId, masterNo, snapshotDigest, now);
    }

    public Optional<DeliveryReceipt> findByRequestId(String requestId) {
        List<DeliveryReceipt> list = jdbc.query(
                "SELECT id, request_id, film_id, master_id, master_no, snapshot_digest, created_at "
                        + "FROM delivery_receipt WHERE request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId),
                MAPPER
        );
        return list.stream().findFirst();
    }

    public List<DeliveryReceipt> findByFilm(long filmId) {
        return jdbc.query(
                "SELECT id, request_id, film_id, master_id, master_no, snapshot_digest, created_at "
                        + "FROM delivery_receipt WHERE film_id = :filmId ORDER BY id ASC",
                new MapSqlParameterSource("filmId", filmId),
                MAPPER
        );
    }
}
