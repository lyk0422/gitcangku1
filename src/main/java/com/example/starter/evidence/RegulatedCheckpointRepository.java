package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 受监管交接检查点表访问。名单在发起时一次性写入，之后不改不删。
 */
@Repository
public class RegulatedCheckpointRepository {

    private static final CheckpointRowMapper ROW_MAPPER = new CheckpointRowMapper();

    private final JdbcTemplate jdbc;

    public RegulatedCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一个检查点（点号自 1 起连续）。
     */
    public void insert(String transferKey, int checkpointNo, LocalDateTime deadlineAtUtc) {
        jdbc.update("""
                        INSERT INTO regulated_checkpoint (transfer_key, checkpoint_no, deadline_at)
                        VALUES (?, ?, ?)
                        """,
                transferKey, checkpointNo, deadlineAtUtc);
    }

    /**
     * 按点号顺序查询交接的检查点名单。
     */
    public List<RegulatedCheckpoint> findByTransferKey(String transferKey) {
        return jdbc.query(
                "SELECT * FROM regulated_checkpoint WHERE transfer_key = ? ORDER BY checkpoint_no",
                ROW_MAPPER, transferKey);
    }

    private static final class CheckpointRowMapper implements RowMapper<RegulatedCheckpoint> {
        @Override
        public RegulatedCheckpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RegulatedCheckpoint(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getInt("checkpoint_no"),
                    rs.getObject("deadline_at", LocalDateTime.class));
        }
    }
}