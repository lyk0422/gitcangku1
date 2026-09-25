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
 * 机构回执幂等键仓储。ack_key 全局唯一，与指挥侧 command_key 使用独立表与命名空间。
 * 先占位插入、同事务内补写响应；业务校验失败时事务整体回滚，占位行一并回滚（失败不占键）。
 */
@Repository
public class AgencyAckKeyRepository {

    private final JdbcTemplate jdbc;

    public AgencyAckKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ackKey 幂等记录：commandKey 表结构同构，但不含 operation（回执仅一种操作）。 */
    public record AckKeyRecord(long id, String ackKey, String requestHash,
                               Integer responseStatus, String responseBody, Instant createdAt) {
    }

    private static final RowMapper<AckKeyRecord> MAPPER = (rs, n) -> map(rs);

    private static AckKeyRecord map(ResultSet rs) throws SQLException {
        int status = rs.getInt("response_status");
        return new AckKeyRecord(rs.getLong("id"), rs.getString("ack_key"),
                rs.getString("request_hash"), rs.wasNull() ? null : status,
                rs.getString("response_body"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 按回执键查询（普通读）。
     */
    public Optional<AckKeyRecord> find(String ackKey) {
        List<AckKeyRecord> rows = jdbc.query(
                "SELECT * FROM agency_ack_keys WHERE ack_key = ?", MAPPER, ackKey);
        return rows.stream().findFirst();
    }

    /**
     * 按回执键锁定读（SELECT ... FOR UPDATE），用于唯一冲突后的重查。
     */
    public Optional<AckKeyRecord> findForUpdate(String ackKey) {
        List<AckKeyRecord> rows = jdbc.query(
                "SELECT * FROM agency_ack_keys WHERE ack_key = ? FOR UPDATE", MAPPER, ackKey);
        return rows.stream().findFirst();
    }

    /**
     * 占位插入回执幂等键（响应列为空，同事务内随后补写）。
     */
    public void insertPlaceholder(String ackKey, String requestHash, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO agency_ack_keys (ack_key, request_hash, response_status,"
                            + " response_body, created_at) VALUES (?,?,NULL,NULL,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, ackKey);
            ps.setString(2, requestHash);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
    }

    /**
     * 补写首次成功响应，与业务写入同事务提交。
     */
    public void fillResponse(String ackKey, int responseStatus, String responseBody) {
        jdbc.update("UPDATE agency_ack_keys SET response_status = ?, response_body = ?"
                + " WHERE ack_key = ?", responseStatus, responseBody, ackKey);
    }
}
