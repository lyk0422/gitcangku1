package com.example.starter.repo;

import com.example.starter.domain.ReweighRequest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 复称申请持久化。 */
@Repository
public class ReweighRepository {

    private static final RowMapper<ReweighRequest> MAPPER = (rs, n) -> new ReweighRequest(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getInt("gross_kg"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcClient jdbc;

    public ReweighRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(ReweighRequest r) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO reweigh_request (ticket_id, gross_kg, reason, status, request_id, request_hash, created_at_utc)
                VALUES (:tid, :kg, :reason, :status, :rid, :rh, :created)
                """)
                .param("tid", r.ticketId()).param("kg", r.grossKg()).param("reason", r.reason())
                .param("status", r.status()).param("rid", r.requestId()).param("rh", r.requestHash())
                .param("created", Timestamp.from(r.createdAtUtc()))
                .update(kh);
        return kh.getKey().longValue();
    }

    public Optional<ReweighRequest> findById(long id) {
        return jdbc.sql("SELECT * FROM reweigh_request WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<ReweighRequest> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM reweigh_request WHERE request_id = :r")
                .param("r", requestId).query(MAPPER).optional();
    }

    /** 按提交顺序返回某称量单的全部复称申请（版本集合）。 */
    public List<ReweighRequest> findByTicketId(long ticketId) {
        return jdbc.sql("SELECT * FROM reweigh_request WHERE ticket_id = :t ORDER BY id")
                .param("t", ticketId).query(MAPPER).list();
    }

    /** 裁决后更新复称状态（采纳/拒绝）。 */
    public int updateStatus(long id, String status) {
        return jdbc.sql("UPDATE reweigh_request SET status = :s WHERE id = :id")
                .param("s", status).param("id", id).update();
    }
}