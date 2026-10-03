package com.example.starter.repo;

import com.example.starter.domain.Dispute;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 争议裁决持久化。 */
@Repository
public class DisputeRepository {

    private static final RowMapper<Dispute> MAPPER = (rs, n) -> new Dispute(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("status"),
            rs.getString("adopted_source"),
            (Long) rs.getObject("adopted_reweigh_id"),
            rs.getString("adjudication_reason"),
            rs.getString("adjudicator"),
            toInstant(rs.getTimestamp("adjudicated_at_utc")),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcClient jdbc;

    public DisputeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public long insert(Dispute d) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO dispute (ticket_id, status, created_at_utc) VALUES (:tid, :status, :created)
                """)
                .param("tid", d.ticketId()).param("status", d.status())
                .param("created", Timestamp.from(d.createdAtUtc()))
                .update(kh);
        return kh.getKey().longValue();
    }

    public Optional<Dispute> findById(long id) {
        return jdbc.sql("SELECT * FROM dispute WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<Dispute> findByTicketId(long ticketId) {
        return jdbc.sql("SELECT * FROM dispute WHERE ticket_id = :t")
                .param("t", ticketId).query(MAPPER).optional();
    }

    public Optional<Dispute> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM dispute WHERE request_id = :r")
                .param("r", requestId).query(MAPPER).optional();
    }

    /**
     * 条件裁决更新：仅当争议仍为 OPEN 时生效，返回 0 表示已被并发裁决。
     */
    public int resolveIfOpen(long id, String adoptedSource, Long adoptedReweighId, String reason,
                             String adjudicator, Instant at, String requestId, String requestHash) {
        return jdbc.sql("""
                UPDATE dispute SET status = 'RESOLVED', adopted_source = :src, adopted_reweigh_id = :rw,
                    adjudication_reason = :reason, adjudicator = :adj, adjudicated_at_utc = :at,
                    request_id = :rid, request_hash = :rh
                WHERE id = :id AND status = 'OPEN'
                """)
                .param("src", adoptedSource).param("rw", adoptedReweighId).param("reason", reason)
                .param("adj", adjudicator).param("at", Timestamp.from(at))
                .param("rid", requestId).param("rh", requestHash).param("id", id)
                .update();
    }
}