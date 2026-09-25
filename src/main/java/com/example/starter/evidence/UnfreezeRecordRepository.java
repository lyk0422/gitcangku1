package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 解冻记录表访问。记录只追加、不可变。
 */
@Repository
public class UnfreezeRecordRepository {

    private static final UnfreezeRowMapper ROW_MAPPER = new UnfreezeRowMapper();

    private final JdbcTemplate jdbc;

    public UnfreezeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条解冻记录。
     */
    public void insert(String borrowerId, String actorId, String note, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO unfreeze_record (borrower_id, actor_id, note, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                borrowerId, actorId, note, createdAt);
    }

    /**
     * 按借出人查询全部解冻记录（按发生顺序）。
     */
    public List<UnfreezeRecord> findByBorrower(String borrowerId) {
        return jdbc.query(
                "SELECT * FROM unfreeze_record WHERE borrower_id = ? ORDER BY id",
                ROW_MAPPER, borrowerId);
    }

    private static final class UnfreezeRowMapper implements RowMapper<UnfreezeRecord> {
        @Override
        public UnfreezeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UnfreezeRecord(
                    rs.getLong("id"),
                    rs.getString("borrower_id"),
                    rs.getString("actor_id"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
