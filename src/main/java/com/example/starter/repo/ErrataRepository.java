package com.example.starter.repo;

import com.example.starter.domain.ErrataRequest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 勘误申请持久化。 */
@Repository
public class ErrataRepository {

    private static final RowMapper<ErrataRequest> MAPPER = (rs, n) -> new ErrataRequest(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getInt("new_gross_kg"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcClient jdbc;

    public ErrataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(ErrataRequest e) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO errata_request (ticket_id, new_gross_kg, reason, status, request_id, request_hash, created_at_utc)
                VALUES (:tid, :kg, :reason, :status, :rid, :rh, :created)
                """)
                .param("tid", e.ticketId()).param("kg", e.newGrossKg()).param("reason", e.reason())
                .param("status", e.status()).param("rid", e.requestId()).param("rh", e.requestHash())
                .param("created", Timestamp.from(e.createdAtUtc()))
                .update(kh);
        return kh.getKey().longValue();
    }

    public Optional<ErrataRequest> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM errata_request WHERE request_id = :r")
                .param("r", requestId).query(MAPPER).optional();
    }

    public List<ErrataRequest> findByTicketId(long ticketId) {
        return jdbc.sql("SELECT * FROM errata_request WHERE ticket_id = :t ORDER BY id")
                .param("t", ticketId).query(MAPPER).list();
    }
}