package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 跨案移交表访问：主单、证物条目、双案封存快照与跨案保管链事件。
 * 全部记录只追加；撤销仅通过条件更新主单状态并追加反向链，不删除原移交与快照。
 */
@Repository
public class CaseTransferRepository {

    private static final CaseTransferRowMapper TRANSFER_MAPPER = new CaseTransferRowMapper();
    private static final ItemRowMapper ITEM_MAPPER = new ItemRowMapper();
    private static final SnapshotRowMapper SNAPSHOT_MAPPER = new SnapshotRowMapper();
    private static final EventRowMapper EVENT_MAPPER = new EventRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入移交主单（初始 COMPLETED），返回生成的 transferId。
     */
    public long insert(String fromCaseKey, String toCaseKey, String orderKey, String orderVersion,
                       String fromCustodian, String toCustodian, String initiatedBy,
                       int evidenceCount, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO case_transfer
                                (from_case_key, to_case_key, order_key, order_version,
                                 from_custodian, to_custodian, status, initiated_by,
                                 evidence_count, created_at, decided_at, revoked_by_from, revoked_by_to)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fromCaseKey);
            ps.setString(2, toCaseKey);
            ps.setString(3, orderKey);
            ps.setString(4, orderVersion);
            ps.setString(5, fromCustodian);
            ps.setString(6, toCustodian);
            ps.setString(7, CaseTransferStatus.COMPLETED.name());
            ps.setString(8, initiatedBy);
            ps.setInt(9, evidenceCount);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 追加一条证物条目，seqNo 固化规范化排序。
     */
    public void insertItem(long transferId, String evidenceKey, int seqNo) {
        jdbc.update("""
                        INSERT INTO case_transfer_item (transfer_id, evidence_key, seq_no)
                        VALUES (?, ?, ?)
                        """,
                transferId, evidenceKey, seqNo);
    }

    /**
     * 追加一行双案封存快照（含移交前后续操作判定水位）。
     */
    public void insertSnapshot(long transferId, String evidenceKey, SnapshotSide side, String caseKey,
                               String location, String sealNo, String orderVersion,
                               Long lastLoanId, Long lastInspectionId, Long lastHandoffId,
                               LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_transfer_snapshot
                            (transfer_id, evidence_key, side, case_key, location, seal_no, order_version,
                             last_loan_id, last_inspection_id, last_handoff_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, side.name(), caseKey, location, sealNo, orderVersion,
                lastLoanId, lastInspectionId, lastHandoffId, now);
    }

    /**
     * 追加一条跨案保管链事件；按插入顺序自增 id 即提交顺序。
     */
    public void insertEvent(long transferId, String evidenceKey, String caseKey,
                            ChainEventType eventType, String actorId, String detail,
                            LocalDateTime eventTime) {
        jdbc.update("""
                        INSERT INTO custody_chain_event
                            (transfer_id, evidence_key, case_key, event_type, actor_id, detail, event_time)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, caseKey, eventType.name(), actorId, detail, eventTime);
    }

    /**
     * 条件撤销：仅当主单仍为 COMPLETED 时置 REVOKED 并固化双方确认人与撤销时刻。
     *
     * @return 是否撤销成功（false 表示不存在或已被并发撤销）
     */
    public boolean markRevoked(long transferId, String fromCustodian, String toCustodian,
                               LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE case_transfer
                        SET status = ?, decided_at = ?, revoked_by_from = ?, revoked_by_to = ?
                        WHERE id = ? AND status = ?
                        """,
                CaseTransferStatus.REVOKED.name(), now, fromCustodian, toCustodian,
                transferId, CaseTransferStatus.COMPLETED.name());
        return updated == 1;
    }

    /**
     * 按 transferId 查询主单。
     */
    public Optional<CaseTransfer> findById(long transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE id = ?", TRANSFER_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 按 seq_no 顺序查询移交条目。
     */
    public List<CaseTransferItem> findItems(long transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE transfer_id = ? ORDER BY seq_no",
                ITEM_MAPPER, transferId);
    }

    /**
     * 查询双案快照：先按证物 seq 顺序、再按 SOURCE/TARGET 顺序。
     */
    public List<CaseTransferSnapshot> findSnapshots(long transferId) {
        return jdbc.query("""
                        SELECT s.* FROM case_transfer_snapshot s
                        JOIN case_transfer_item i
                            ON i.transfer_id = s.transfer_id AND i.evidence_key = s.evidence_key
                        WHERE s.transfer_id = ?
                        ORDER BY i.seq_no, s.side
                        """,
                SNAPSHOT_MAPPER, transferId);
    }

    /**
     * 查询某次移交的全部链事件，按提交顺序。
     */
    public List<CustodyChainEvent> findEventsByTransfer(long transferId) {
        return jdbc.query(
                "SELECT * FROM custody_chain_event WHERE transfer_id = ? ORDER BY id",
                EVENT_MAPPER, transferId);
    }

    /**
     * 查询案件保管链事件（可限定单件证物），按提交顺序；读取不改变任何状态。
     */
    public List<CustodyChainEvent> findEventsByCase(String caseKey, String evidenceKey) {
        if (evidenceKey == null) {
            return jdbc.query(
                    "SELECT * FROM custody_chain_event WHERE case_key = ? ORDER BY id",
                    EVENT_MAPPER, caseKey);
        }
        return jdbc.query(
                "SELECT * FROM custody_chain_event WHERE case_key = ? AND evidence_key = ? ORDER BY id",
                EVENT_MAPPER, caseKey, evidenceKey);
    }

    /**
     * 查询单件证物跨全部案件的保管链事件（移出事件归来源案、移入事件归目标案），按提交顺序。
     */
    public List<CustodyChainEvent> findEventsByEvidence(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM custody_chain_event WHERE evidence_key = ? ORDER BY id",
                EVENT_MAPPER, evidenceKey);
    }

    /**
     * 该证物在指定移交之后是否又发生过其他跨案移交（含其撤销），撤销判定用。
     */
    public boolean existsLaterTransfer(String evidenceKey, long transferId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(1) FROM case_transfer_item i
                        JOIN case_transfer t ON t.id = i.transfer_id
                        WHERE i.evidence_key = ? AND t.id > ?
                        """,
                Integer.class, evidenceKey, transferId);
        return count != null && count > 0;
    }

    private static final class CaseTransferRowMapper implements RowMapper<CaseTransfer> {
        @Override
        public CaseTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransfer(
                    rs.getLong("id"),
                    rs.getString("from_case_key"),
                    rs.getString("to_case_key"),
                    rs.getString("order_key"),
                    rs.getString("order_version"),
                    rs.getString("from_custodian"),
                    rs.getString("to_custodian"),
                    CaseTransferStatus.valueOf(rs.getString("status")),
                    rs.getString("initiated_by"),
                    rs.getInt("evidence_count"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class),
                    rs.getString("revoked_by_from"),
                    rs.getString("revoked_by_to"));
        }
    }

    private static final class ItemRowMapper implements RowMapper<CaseTransferItem> {
        @Override
        public CaseTransferItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferItem(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("seq_no"));
        }
    }

    private static final class SnapshotRowMapper implements RowMapper<CaseTransferSnapshot> {
        @Override
        public CaseTransferSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferSnapshot(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    SnapshotSide.valueOf(rs.getString("side")),
                    rs.getString("case_key"),
                    rs.getString("location"),
                    rs.getString("seal_no"),
                    rs.getString("order_version"),
                    rs.getObject("last_loan_id", Long.class),
                    rs.getObject("last_inspection_id", Long.class),
                    rs.getObject("last_handoff_id", Long.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class EventRowMapper implements RowMapper<CustodyChainEvent> {
        @Override
        public CustodyChainEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CustodyChainEvent(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    ChainEventType.valueOf(rs.getString("event_type")),
                    rs.getString("actor_id"),
                    rs.getString("detail"),
                    rs.getObject("event_time", LocalDateTime.class));
        }
    }
}
