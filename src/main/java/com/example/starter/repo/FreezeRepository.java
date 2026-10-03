package com.example.starter.repo;

import com.example.starter.domain.SettlementFreeze;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 净重冻结快照持久化。 */
@Repository
public class FreezeRepository {

    private static final RowMapper<SettlementFreeze> MAPPER = (rs, n) -> new SettlementFreeze(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getInt("tare_kg"),
            rs.getInt("adopted_gross_kg"),
            rs.getInt("net_kg"),
            rs.getString("adopted_source"),
            (Long) rs.getObject("adopted_reweigh_id"),
            rs.getString("reweigh_version_set"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getTimestamp("frozen_at_utc").toInstant());

    private final JdbcClient jdbc;

    public FreezeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(SettlementFreeze f) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO settlement_freeze (ticket_id, tare_kg, adopted_gross_kg, net_kg, adopted_source,
                    adopted_reweigh_id, reweigh_version_set, request_id, request_hash, frozen_at_utc)
                VALUES (:tid, :tare, :gross, :net, :src, :rw, :vs, :rid, :rh, :at)
                """)
                .param("tid", f.ticketId()).param("tare", f.tareKg()).param("gross", f.adoptedGrossKg())
                .param("net", f.netKg()).param("src", f.adoptedSource()).param("rw", f.adoptedReweighId())
                .param("vs", f.reweighVersionSet()).param("rid", f.requestId()).param("rh", f.requestHash())
                .param("at", Timestamp.from(f.frozenAtUtc()))
                .update(kh);
        return kh.getKey().longValue();
    }

    public Optional<SettlementFreeze> findByTicketId(long ticketId) {
        return jdbc.sql("SELECT * FROM settlement_freeze WHERE ticket_id = :t")
                .param("t", ticketId).query(MAPPER).optional();
    }

    public Optional<SettlementFreeze> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM settlement_freeze WHERE request_id = :r")
                .param("r", requestId).query(MAPPER).optional();
    }
}