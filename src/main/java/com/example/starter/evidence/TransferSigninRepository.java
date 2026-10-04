package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检查点签到表访问。记录只追加、不可变；(transfer_id, checkpoint_seq) 唯一约束
 * 保证同一检查点不会重复签到。
 */
@Repository
public class TransferSigninRepository {

    private static final SigninRowMapper ROW_MAPPER = new SigninRowMapper();

    private final JdbcTemplate jdbc;

    public TransferSigninRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条正常签到记录。
     */
    public void insert(long transferId, String evidenceKey, int checkpointSeq,
                       String sealNo, LocalDateTime arrivedAtUtc, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transfer_signin
                            (transfer_id, evidence_key, checkpoint_seq, seal_no, arrived_at_utc, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, checkpointSeq, sealNo, arrivedAtUtc, now);
    }

    /**
     * 按检查点序号升序查询某次交接的全部正常签到。
     */
    public List<TransferSignin> findByTransferId(long transferId) {
        return jdbc.query(
                "SELECT * FROM transfer_signin WHERE transfer_id = ? ORDER BY checkpoint_seq",
                ROW_MAPPER, transferId);
    }

    /**
     * 按发生顺序查询证物全部历史交接的签到记录（含已取消/已冻结交接的旧链）。
     */
    public List<TransferSignin> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM transfer_signin WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SigninRowMapper implements RowMapper<TransferSignin> {
        @Override
        public TransferSignin mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferSignin(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("checkpoint_seq"),
                    rs.getString("seal_no"),
                    rs.getObject("arrived_at_utc", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}