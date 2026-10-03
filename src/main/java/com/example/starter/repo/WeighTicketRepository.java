package com.example.starter.repo;

import com.example.starter.domain.WeighTicket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 称量单持久化。 */
@Repository
public class WeighTicketRepository {

    private static final RowMapper<WeighTicket> MAPPER = (rs, n) -> new WeighTicket(
            rs.getLong("id"),
            rs.getString("ticket_no"),
            rs.getString("transport_order_no"),
            rs.getString("vehicle_no"),
            (Integer) rs.getObject("tare_kg"),
            (Integer) rs.getObject("gross_kg"),
            toInstant(rs.getTimestamp("tare_time_utc")),
            toInstant(rs.getTimestamp("gross_time_utc")),
            rs.getString("status"),
            rs.getString("create_request_id"),
            rs.getString("create_request_hash"),
            rs.getString("tare_request_id"),
            rs.getString("tare_request_hash"),
            rs.getString("gross_request_id"),
            rs.getString("gross_request_hash"),
            toInstant(rs.getTimestamp("created_at_utc")));

    private final JdbcClient jdbc;

    public WeighTicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 插入称量单并返回自增ID。 */
    public long insert(WeighTicket t) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO weigh_ticket (ticket_no, transport_order_no, vehicle_no, status,
                    create_request_id, create_request_hash, created_at_utc)
                VALUES (:no, :ord, :veh, :status, :reqId, :reqHash, :created)
                """)
                .param("no", t.ticketNo())
                .param("ord", t.transportOrderNo())
                .param("veh", t.vehicleNo())
                .param("status", t.status())
                .param("reqId", t.createRequestId())
                .param("reqHash", t.createRequestHash())
                .param("created", Timestamp.from(t.createdAtUtc()))
                .update(kh);
        return kh.getKey().longValue();
    }

    public Optional<WeighTicket> findById(long id) {
        return jdbc.sql("SELECT * FROM weigh_ticket WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    /** 行锁读取，用于串行化复称提交与净重冻结。 */
    public Optional<WeighTicket> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT * FROM weigh_ticket WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<WeighTicket> findByCreateRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM weigh_ticket WHERE create_request_id = :r")
                .param("r", requestId).query(MAPPER).optional();
    }

    public List<WeighTicket> findByVehicleNo(String vehicleNo) {
        return jdbc.sql("SELECT * FROM weigh_ticket WHERE vehicle_no = :v ORDER BY id")
                .param("v", vehicleNo).query(MAPPER).list();
    }

    /** 登记入场皮重。 */
    public int updateTare(long id, int tareKg, Instant tareTime, String requestId, String requestHash) {
        return jdbc.sql("""
                UPDATE weigh_ticket SET tare_kg = :kg, tare_time_utc = :t,
                    tare_request_id = :rid, tare_request_hash = :rh WHERE id = :id
                """)
                .param("kg", tareKg).param("t", Timestamp.from(tareTime))
                .param("rid", requestId).param("rh", requestHash).param("id", id)
                .update();
    }

    /** 登记出场毛重并置为已出场。 */
    public int updateGross(long id, int grossKg, Instant grossTime, String requestId, String requestHash) {
        return jdbc.sql("""
                UPDATE weigh_ticket SET gross_kg = :kg, gross_time_utc = :t, status = :st,
                    gross_request_id = :rid, gross_request_hash = :rh WHERE id = :id
                """)
                .param("kg", grossKg).param("t", Timestamp.from(grossTime))
                .param("st", WeighTicket.STATUS_GROSSED)
                .param("rid", requestId).param("rh", requestHash).param("id", id)
                .update();
    }

    /** 条件更新状态，用于冻结时防止并发重复冻结。 */
    public int updateStatusIfNot(long id, String newStatus, String excludedStatus) {
        return jdbc.sql("UPDATE weigh_ticket SET status = :ns WHERE id = :id AND status <> :ex")
                .param("ns", newStatus).param("id", id).param("ex", excludedStatus)
                .update();
    }
}