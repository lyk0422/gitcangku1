package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 外部通报、机构清单与回执的 JDBC 仓储。
 * 写路径均处于先锁定事件行的事务内，同一事件的通报创建、回执写入与降级判定按提交顺序串行；
 * uk_receipt_agency 保证每机构只落首份有效回执，uk_receipt_request 保证报文 requestId 幂等。
 */
@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Notification> NOTIFICATION_MAPPER = (rs, n) -> new Notification(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("escalation_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<NotificationAgency> AGENCY_MAPPER = (rs, n) -> new NotificationAgency(
            rs.getLong("id"), rs.getLong("notification_id"), rs.getLong("incident_id"),
            rs.getString("agency_code"), rs.getBoolean("critical"),
            rs.getTimestamp("ack_deadline").toInstant(), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<NotificationReceipt> RECEIPT_MAPPER = (rs, n) -> mapReceipt(rs);

    private static NotificationReceipt mapReceipt(ResultSet rs) throws SQLException {
        return new NotificationReceipt(
                rs.getLong("id"), rs.getLong("agency_id"), rs.getLong("incident_id"),
                rs.getString("agency_code"), rs.getString("request_id"), rs.getString("signer"),
                rs.getString("content"), rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入通报单，返回生成主键。每事件唯一约束兜底并发重复创建。
     */
    public long insertNotification(Notification notification) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_notifications (incident_id, escalation_id, created_at)"
                            + " VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, notification.incidentId());
            ps.setLong(2, notification.escalationId());
            ps.setTimestamp(3, Timestamp.from(notification.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件查询通报单（不存在返回空）。
     */
    public Optional<Notification> findByIncident(long incidentId) {
        List<Notification> rows = jdbc.query(
                "SELECT * FROM incident_notifications WHERE incident_id = ?",
                NOTIFICATION_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 追加一个通报机构，返回生成主键（即稳定排序键）。
     * (notification_id, agency_code) 唯一约束兜底同一通报重复机构。
     */
    public long insertAgency(NotificationAgency agency) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_notification_agencies (notification_id, incident_id,"
                            + " agency_code, critical, ack_deadline, created_at)"
                            + " VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, agency.notificationId());
            ps.setLong(2, agency.incidentId());
            ps.setString(3, agency.agencyCode());
            ps.setBoolean(4, agency.critical());
            ps.setTimestamp(5, Timestamp.from(agency.ackDeadline()));
            ps.setTimestamp(6, Timestamp.from(agency.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件查询全部通报机构，按 id（创建请求顺序）升序，排序稳定。
     */
    public List<NotificationAgency> listAgenciesByIncident(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_notification_agencies WHERE incident_id = ? ORDER BY id",
                AGENCY_MAPPER, incidentId);
    }

    /**
     * 按事件与机构标识查询机构行。
     */
    public Optional<NotificationAgency> findAgency(long incidentId, String agencyCode) {
        List<NotificationAgency> rows = jdbc.query(
                "SELECT * FROM incident_notification_agencies WHERE incident_id = ? AND agency_code = ?",
                AGENCY_MAPPER, incidentId, agencyCode);
        return rows.stream().findFirst();
    }

    /**
     * 插入回执，返回生成主键。
     * agency_id 唯一（重复机构回执）与 request_id 唯一（报文重放/改参）冲突均以
     * DuplicateKeyException 抛出，由上层在已锁事件行的事务内先行判定，跨事件竞争时兜底。
     */
    public long insertReceipt(NotificationReceipt receipt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_notification_receipts (agency_id, incident_id, agency_code,"
                            + " request_id, signer, content, received_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, receipt.agencyId());
            ps.setLong(2, receipt.incidentId());
            ps.setString(3, receipt.agencyCode());
            ps.setString(4, receipt.requestId());
            ps.setString(5, receipt.signer());
            ps.setString(6, receipt.content());
            ps.setTimestamp(7, Timestamp.from(receipt.receivedAt()));
            ps.setTimestamp(8, Timestamp.from(receipt.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按报文幂等键查询回执（普通读），用于同键重放与改参判定。
     */
    public Optional<NotificationReceipt> findReceiptByRequest(String requestId) {
        List<NotificationReceipt> rows = jdbc.query(
                "SELECT * FROM incident_notification_receipts WHERE request_id = ?",
                RECEIPT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按报文幂等键锁定查询回执（SELECT ... FOR UPDATE），用于跨事件唯一冲突后的重查。
     */
    public Optional<NotificationReceipt> findReceiptByRequestForUpdate(String requestId) {
        List<NotificationReceipt> rows = jdbc.query(
                "SELECT * FROM incident_notification_receipts WHERE request_id = ? FOR UPDATE",
                RECEIPT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按机构行 id 查询其首份有效回执。
     */
    public Optional<NotificationReceipt> findReceiptByAgency(long agencyId) {
        List<NotificationReceipt> rows = jdbc.query(
                "SELECT * FROM incident_notification_receipts WHERE agency_id = ?",
                RECEIPT_MAPPER, agencyId);
        return rows.stream().findFirst();
    }

    /**
     * 按事件查询全部回执，按机构行 id 升序，与机构稳定排序一致。
     */
    public List<NotificationReceipt> listReceiptsByIncident(long incidentId) {
        return jdbc.query(
                "SELECT r.* FROM incident_notification_receipts r"
                        + " JOIN incident_notification_agencies a ON a.id = r.agency_id"
                        + " WHERE r.incident_id = ? ORDER BY a.id",
                RECEIPT_MAPPER, incidentId);
    }
}
