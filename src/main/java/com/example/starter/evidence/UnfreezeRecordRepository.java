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
 * 解冻记录表访问。记录只追加、不可变，不提供任何更新语句。
 * 解冻后借出人追缴计数以解冻时刻的累计次数为基线重新累计。
 */
@Repository
public class UnfreezeRecordRepository {

    private static final UnfreezeRowMapper ROW_MAPPER = new UnfreezeRowMapper();

    private final JdbcTemplate jdbc;

    public UnfreezeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条解冻记录，固化解冻时刻的累计追缴次数作为重新计数基线。
     */
    public void insert(String borrowerId, String unfrozenBy, String note,
                       int reclaimCountAtUnfreeze, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO unfreeze_record
                            (borrower_id, unfrozen_by, note, reclaim_count_at_unfreeze, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                borrowerId, unfrozenBy, note, reclaimCountAtUnfreeze, createdAt);
    }

    /**
     * 查询借出人最近一次解冻记录；NULL 表示从未解冻（计数基线为 0）。
     */
    public Optional<UnfreezeRecord> findLatestByBorrower(String borrowerId) {
        List<UnfreezeRecord> rows = jdbc.query(
                "SELECT * FROM unfreeze_record WHERE borrower_id = ? ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, borrowerId);
        return rows.stream().findFirst();
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
                    rs.getString("unfrozen_by"),
                    rs.getString("note"),
                    rs.getInt("reclaim_count_at_unfreeze"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
