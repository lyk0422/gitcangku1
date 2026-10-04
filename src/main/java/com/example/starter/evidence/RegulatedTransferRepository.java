package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 受监管交接表访问。记录只追加；状态流转通过条件更新完成，
 * 同一证物的在途/冻结交接唯一性由证物行锁与状态机保证。
 */
@Repository
public class RegulatedTransferRepository {

    private static final RegulatedTransferRowMapper ROW_MAPPER = new RegulatedTransferRowMapper();

    private final JdbcTemplate jdbc;

    public RegulatedTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条在途受监管交接，初始版本 0。
     */
    public void insert(String transferKey, String evidenceKey, String fromCustodian,
                       String toCustodian, String transporterId, String sealNo,
                       int checkpointCount, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO regulated_transfer
                            (transfer_key, evidence_key, from_custodian, to_custodian,
                             transporter_id, seal_no, checkpoint_count, version, status,
                             created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL)
                        """,
                transferKey, evidenceKey, fromCustodian, toCustodian, transporterId, sealNo,
                checkpointCount, RegulatedTransferStatus.IN_TRANSIT.name(), nowUtc);
    }

    /**
     * 按业务键查询（不加锁）。
     */
    public Optional<RegulatedTransfer> findByKey(String transferKey) {
        List<RegulatedTransfer> rows = jdbc.query(
                "SELECT * FROM regulated_transfer WHERE transfer_key = ?",
                ROW_MAPPER, transferKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前未决的受监管交接（IN_TRANSIT 或 FROZEN；调用前必须已锁定证物行）。
     */
    public Optional<RegulatedTransfer> findOpenByEvidenceKey(String evidenceKey) {
        List<RegulatedTransfer> rows = jdbc.query("""
                        SELECT * FROM regulated_transfer
                        WHERE evidence_key = ? AND status IN (?, ?)
                        ORDER BY id
                        """,
                ROW_MAPPER, evidenceKey,
                RegulatedTransferStatus.IN_TRANSIT.name(), RegulatedTransferStatus.FROZEN.name());
        return rows.stream().findFirst();
    }

    /**
     * 按发生顺序查询证物全部受监管交接（含已取消与已接收的历史链）。
     */
    public List<RegulatedTransfer> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM regulated_transfer WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 递增版本并流转状态；仅当当前状态与版本均匹配时生效。
     *
     * @return 是否成功流转（false 表示已被并发事务先行变更）
     */
    public boolean transition(long id, RegulatedTransferStatus from, RegulatedTransferStatus to,
                              long expectedVersion, LocalDateTime decidedAtUtc) {
        int updated = jdbc.update("""
                        UPDATE regulated_transfer
                        SET status = ?, version = version + 1, decided_at = ?
                        WHERE id = ? AND status = ? AND version = ?
                        """,
                to.name(), decidedAtUtc, id, from.name(), expectedVersion);
        return updated == 1;
    }

    /**
     * 仅递增版本（签到留痕时）；仅当仍处于在途且版本匹配时生效。
     *
     * @return 是否成功递增
     */
    public boolean bumpVersion(long id, long expectedVersion) {
        int updated = jdbc.update("""
                        UPDATE regulated_transfer
                        SET version = version + 1
                        WHERE id = ? AND status = ? AND version = ?
                        """,
                id, RegulatedTransferStatus.IN_TRANSIT.name(), expectedVersion);
        return updated == 1;
    }

    private static final class RegulatedTransferRowMapper implements RowMapper<RegulatedTransfer> {
        @Override
        public RegulatedTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RegulatedTransfer(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getString("evidence_key"),
                    rs.getString("from_custodian"),
                    rs.getString("to_custodian"),
                    rs.getString("transporter_id"),
                    rs.getString("seal_no"),
                    rs.getInt("checkpoint_count"),
                    rs.getLong("version"),
                    RegulatedTransferStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}