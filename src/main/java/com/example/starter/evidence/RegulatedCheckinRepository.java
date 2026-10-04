package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 签到记录表访问。记录只追加、不可变；(transfer_key, checkpoint_no) 唯一，
 * 每个检查点最多一条签到。
 */
@Repository
public class RegulatedCheckinRepository {

    private static final CheckinRowMapper ROW_MAPPER = new CheckinRowMapper();

    private final JdbcTemplate jdbc;

    public RegulatedCheckinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条签到记录。
     */
    public void insert(String transferKey, int checkpointNo, String transporterId,
                       String sealNo, LocalDateTime arrivedAtUtc, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO regulated_checkin
                            (transfer_key, checkpoint_no, transporter_id, seal_no, arrived_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                transferKey, checkpointNo, transporterId, sealNo, arrivedAtUtc, nowUtc);
    }

    /**
     * 按点号顺序查询交接的全部签到记录。
     */
    public List<RegulatedCheckin> findByTransferKey(String transferKey) {
        return jdbc.query(
                "SELECT * FROM regulated_checkin WHERE transfer_key = ? ORDER BY checkpoint_no",
                ROW_MAPPER, transferKey);
    }

    private static final class CheckinRowMapper implements RowMapper<RegulatedCheckin> {
        @Override
        public RegulatedCheckin mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RegulatedCheckin(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getInt("checkpoint_no"),
                    rs.getString("transporter_id"),
                    rs.getString("seal_no"),
                    rs.getObject("arrived_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}