package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交接异常记录表访问。记录只追加、不可变；取消并重新发起后旧异常保留。
 */
@Repository
public class RegulatedExceptionRepository {

    private static final ExceptionRowMapper ROW_MAPPER = new ExceptionRowMapper();

    private final JdbcTemplate jdbc;

    public RegulatedExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条异常记录。
     */
    public void insert(String transferKey, int checkpointNo, RegulatedExceptionReason reason,
                       String detail, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO regulated_exception
                            (transfer_key, checkpoint_no, reason, detail, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                transferKey, checkpointNo, reason.name(), detail, nowUtc);
    }

    /**
     * 按发生顺序查询交接的全部异常记录。
     */
    public List<RegulatedExceptionRecord> findByTransferKey(String transferKey) {
        return jdbc.query(
                "SELECT * FROM regulated_exception WHERE transfer_key = ? ORDER BY id",
                ROW_MAPPER, transferKey);
    }

    private static final class ExceptionRowMapper implements RowMapper<RegulatedExceptionRecord> {
        @Override
        public RegulatedExceptionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RegulatedExceptionRecord(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getInt("checkpoint_no"),
                    RegulatedExceptionReason.valueOf(rs.getString("reason")),
                    rs.getString("detail"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}