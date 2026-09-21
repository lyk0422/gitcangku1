package com.example.starter.evidence.repository;

import com.example.starter.evidence.domain.Transfer;
import com.example.starter.evidence.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 交接历史表访问。记录只追加，状态仅由 PENDING 单向流转为 ACCEPTED/CANCELLED。
 */
@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> MAPPER = (rs, rowNum) -> new Transfer(
            rs.getLong("id"),
            rs.getLong("evidence_id"),
            rs.getString("from_custodian_id"),
            rs.getString("to_custodian_id"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long evidenceId, String fromCustodianId, String toCustodianId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO evidence_transfer (evidence_id, from_custodian_id, to_custodian_id, status, created_at, decided_at) "
                            + "VALUES (?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, evidenceId);
            ps.setString(2, fromCustodianId);
            ps.setString(3, toCustodianId);
            ps.setString(4, TransferStatus.PENDING.name());
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 查询证物当前待接收的交接；同一证物同一时间至多一条 PENDING（由证据行锁保证）。
     */
    public Optional<Transfer> findPendingByEvidenceId(long evidenceId) {
        List<Transfer> rows = jdbc.query(
                "SELECT * FROM evidence_transfer WHERE evidence_id = ? AND status = ? ORDER BY id",
                MAPPER, evidenceId, TransferStatus.PENDING.name());
        return rows.stream().findFirst();
    }

    public List<Transfer> findByEvidenceId(long evidenceId) {
        return jdbc.query(
                "SELECT * FROM evidence_transfer WHERE evidence_id = ? ORDER BY id",
                MAPPER, evidenceId);
    }

    /**
     * 将待接收交接置为终态（ACCEPTED/CANCELLED）并记录决定时间。
     */
    public void decide(long id, TransferStatus status, LocalDateTime decidedAt) {
        jdbc.update("UPDATE evidence_transfer SET status = ?, decided_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(decidedAt), id);
    }
}
