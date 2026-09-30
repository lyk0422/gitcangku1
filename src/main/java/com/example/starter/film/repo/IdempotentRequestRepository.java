package com.example.starter.film.repo;

import com.example.starter.film.model.IdempotentRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求记录数据访问。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, n) ->
            new IdempotentRequest(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getObject("film_id", Long.class),
                    rs.getString("payload_hash"),
                    rs.getString("result_ref"),
                    rs.getTimestamp("created_at").toLocalDateTime()
            );

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotentRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试登记首次请求。
     *
     * @return true 首次插入成功；false 同 (requestId, operation) 已存在（重试或异参）
     */
    public boolean tryInsert(String requestId, String operation, Long filmId,
                             String payloadHash, LocalDateTime now) {
        try {
            jdbc.update(
                    "INSERT INTO idempotent_request(request_id, operation, film_id, payload_hash, "
                            + "result_ref, created_at) "
                            + "VALUES (:requestId, :operation, :filmId, :payloadHash, NULL, :createdAt)",
                    new MapSqlParameterSource()
                            .addValue("requestId", requestId)
                            .addValue("operation", operation)
                            .addValue("filmId", filmId)
                            .addValue("payloadHash", payloadHash)
                            .addValue("createdAt", Timestamp.valueOf(now))
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<IdempotentRequest> find(String requestId, String operation) {
        List<IdempotentRequest> list = jdbc.query(
                "SELECT request_id, operation, film_id, payload_hash, result_ref, created_at "
                        + "FROM idempotent_request WHERE request_id = :requestId AND operation = :operation",
                new MapSqlParameterSource()
                        .addValue("requestId", requestId)
                        .addValue("operation", operation),
                MAPPER
        );
        return list.stream().findFirst();
    }

    /**
     * 首次业务处理完成后回填结果引用。
     */
    public void updateResultRef(String requestId, String operation, String resultRef) {
        jdbc.update(
                "UPDATE idempotent_request SET result_ref = :resultRef "
                        + "WHERE request_id = :requestId AND operation = :operation",
                new MapSqlParameterSource()
                        .addValue("resultRef", resultRef)
                        .addValue("requestId", requestId)
                        .addValue("operation", operation)
        );
    }
}
