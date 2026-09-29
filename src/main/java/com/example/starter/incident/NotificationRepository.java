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
 * 外部通报及机构回执的 JDBC 仓储。
 * 所有调用均处于先锁定事件行的写事务内，回执的“首份有效”由
 * incident_notification_agencies 上 received_at IS NULL 的条件更新原子保证；
 * request_id 全局唯一约束兜底并发重复回执。
 */
@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Notification> NOTIFICATION_MAPPER = (rs, n) -> new Notification(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("escalation_id"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<AgencyNotification> AGENCY_MAPPER = (rs, n) -> mapAgency(rs);

    private static final RowMapper<NotificationReceipt> RECEIPT_MAPPER = (rs, n) -> new NotificationReceipt(
            rs.getLong("id"), rs.getLong("agency_notification_id"), rs.getString("request_id"),
            rs.getString("signer"), rs.getString("content"),
            rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("created_at").toInstant());

    private static AgencyNotification mapAgency(ResultSet rs) throws SQLException {
        Timestamp receivedAt = rs.getTimestamp("received_at");
        return new AgencyNotification(
                rs.getLong("id"), rs.getLong("notification_id"), rs.getString("agency_key"),
                rs.getBoolean("critical"), rs.getTimestamp("ack_deadline").toInstant(),
                rs.getString("first_request_id"), rs.getString("signer"),
                receivedAt == null ? null : receivedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入通报主记录，返回生成主键。(incident_id)、(escalation_id) 唯一约束兜底并发重复创建。
     */
    public long insertNotification(Notification notification) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_notifications (incident_id, escalation_id, created_at,"
                            + " updated_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, notification.incidentId());
            ps.setLong(2, notification.escalationId());
            ps.setTimestamp(3, Timestamp.from(notification.createdAt()));
            ps.setTimestamp(4, Timestamp.from(notification.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按事件查询通报（不存在返回空）。 */
    public Optional<Notification> findByIncident(long incidentId) {
        List<Notification> rows = jdbc.query(
                "SELECT * FROM incident_notifications WHERE incident_id = ?",
                NOTIFICATION_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /** 按主键查询通报。 */
    public Optional<Notification> findById(long id) {
        List<Notification> rows = jdbc.query(
                "SELECT * FROM incident_notifications WHERE id = ?", NOTIFICATION_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 插入通报机构行，返回生成主键。(notification_id, agency_key) 唯一约束兜底重复机构。
     */
    public long insertAgency(AgencyNotification agency) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_notification_agencies (notification_id, agency_key,"
                            + " critical, ack_deadline, first_request_id, signer, received_at,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, agency.notificationId());
            ps.setString(2, agency.agencyKey());
            ps.setBoolean(3, agency.critical());
            ps.setTimestamp(4, Timestamp.from(agency.ackDeadline()));
            ps.setString(5, agency.firstRequestId());
            ps.setString(6, agency.signer());
            ps.setTimestamp(7, agency.receivedAt() == null ? null : Timestamp.from(agency.receivedAt()));
            ps.setTimestamp(8, Timestamp.from(agency.createdAt()));
            ps.setTimestamp(9, Timestamp.from(agency.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询通报下全部机构行，按机构键稳定升序返回。 */
    public List<AgencyNotification> listAgencies(long notificationId) {
        return jdbc.query("SELECT * FROM incident_notification_agencies WHERE notification_id = ?"
                + " ORDER BY agency_key ASC, id ASC", AGENCY_MAPPER, notificationId);
    }

    /** 按通报 id 与机构键查询机构行。 */
    public Optional<AgencyNotification> findAgency(long notificationId, String agencyKey) {
        List<AgencyNotification> rows = jdbc.query(
                "SELECT * FROM incident_notification_agencies WHERE notification_id = ? AND agency_key = ?",
                AGENCY_MAPPER, notificationId, agencyKey);
        return rows.stream().findFirst();
    }

    /**
     * 原子记录某机构的首份有效回执：仅当 received_at IS NULL 时写入。
     * 返回 1 表示本次为首份（计入完成数）；返回 0 表示该机构已有回执（重复报文，不改写）。
     */
    public int markFirstAck(long agencyNotificationId, String requestId, String signer,
                            Instant receivedAt) {
        return jdbc.update("UPDATE incident_notification_agencies SET first_request_id = ?,"
                        + " signer = ?, received_at = ?, updated_at = ?"
                        + " WHERE id = ? AND received_at IS NULL",
                requestId, signer, Timestamp.from(receivedAt), Timestamp.from(receivedAt),
                agencyNotificationId);
    }

    /**
     * 插入回执原始报文。request_id 全局唯一，重复插入抛 DuplicateKeyException。
     */
    public void insertReceipt(NotificationReceipt receipt) {
        jdbc.update("INSERT INTO incident_notification_acks (agency_notification_id, request_id,"
                        + " signer, content, received_at, created_at) VALUES (?,?,?,?,?,?)",
                receipt.agencyNotificationId(), receipt.requestId(), receipt.signer(), receipt.content(),
                Timestamp.from(receipt.receivedAt()), Timestamp.from(receipt.createdAt()));
    }

    /** 按 requestId 查询原始回执（幂等重放/改参检测用）。 */
    public Optional<NotificationReceipt> findReceipt(String requestId) {
        List<NotificationReceipt> rows = jdbc.query(
                "SELECT * FROM incident_notification_acks WHERE request_id = ?",
                RECEIPT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 按 requestId 查询原始回执并锁定行（SELECT ... FOR UPDATE），并发冲突后重查。 */
    public Optional<NotificationReceipt> findReceiptForUpdate(String requestId) {
        List<NotificationReceipt> rows = jdbc.query(
                "SELECT * FROM incident_notification_acks WHERE request_id = ? FOR UPDATE",
                RECEIPT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 按机构行 id 查询机构行（回执重放后回查机构与通报归属用）。 */
    public Optional<AgencyNotification> findAgencyById(long agencyNotificationId) {
        List<AgencyNotification> rows = jdbc.query(
                "SELECT * FROM incident_notification_agencies WHERE id = ?",
                AGENCY_MAPPER, agencyNotificationId);
        return rows.stream().findFirst();
    }
}
