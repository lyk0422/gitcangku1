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
 * 根箱交接记录表访问。记录只追加；进行中的交接通过根箱行锁保证同一根箱最多一条 PENDING。
 */
@Repository
public class ContainerTransferRepository {

    private static final TransferRowMapper ROW_MAPPER = new TransferRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条待接收交接记录。
     */
    public void insert(String containerKey, String fromCustodian, String toCustodian,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_transfer
                            (container_key, from_custodian, to_custodian, status, initiated_by,
                             created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL)
                        """,
                containerKey, fromCustodian, toCustodian,
                TransferStatus.PENDING.name(), fromCustodian, now);
    }

    /**
     * 查询根箱当前待接收的交接（调用前必须已锁定根箱行）。
     */
    public Optional<ContainerTransfer> findPendingByContainerKey(String containerKey) {
        List<ContainerTransfer> rows = jdbc.query(
                "SELECT * FROM container_transfer WHERE container_key = ? AND status = ?",
                ROW_MAPPER, containerKey, TransferStatus.PENDING.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询根箱最近一次交接记录（含已决定），用于权限判定与幂等重放前的当前权限校验。
     */
    public Optional<ContainerTransfer> findLatestByContainerKey(String containerKey) {
        List<ContainerTransfer> rows = jdbc.query(
                "SELECT * FROM container_transfer WHERE container_key = ? ORDER BY id DESC",
                ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 将待接收交接决定为 ACCEPTED 或 CANCELLED；仅当仍为 PENDING 时生效。
     *
     * @return 是否成功决定（false 表示已被并发事务先行决定）
     */
    public boolean decide(long id, TransferStatus decided, LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "UPDATE container_transfer SET status = ?, decided_at = ? WHERE id = ? AND status = ?",
                decided.name(), decidedAt, id, TransferStatus.PENDING.name());
        return updated == 1;
    }

    private static final class TransferRowMapper implements RowMapper<ContainerTransfer> {
        @Override
        public ContainerTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerTransfer(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("from_custodian"),
                    rs.getString("to_custodian"),
                    TransferStatus.valueOf(rs.getString("status")),
                    rs.getString("initiated_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
