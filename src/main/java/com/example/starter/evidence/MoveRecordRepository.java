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
 * 双人迁移记录表访问。记录只追加、不可变，不提供任何更新路径。
 */
@Repository
public class MoveRecordRepository {

    private static final MoveRecordRowMapper ROW_MAPPER = new MoveRecordRowMapper();

    private final JdbcTemplate jdbc;

    public MoveRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入不可变双人迁移记录（第二人确认执行成功时调用）。
     */
    public void insert(String moveKey, List<String> evidenceKeys, String sourceLocation,
                       String targetLocation, int expectedVersion,
                       String firstConfirmer, String secondConfirmer, LocalDateTime completedAt) {
        jdbc.update("""
                        INSERT INTO move_record
                            (move_key, evidence_keys, source_location, target_location, expected_version,
                             first_confirmer, second_confirmer, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, String.join(",", evidenceKeys), sourceLocation, targetLocation,
                expectedVersion, firstConfirmer, secondConfirmer, completedAt);
    }

    /**
     * 按迁移键查询迁移记录。
     */
    public Optional<MoveRecord> findByMoveKey(String moveKey) {
        List<MoveRecord> rows = jdbc.query(
                "SELECT * FROM move_record WHERE move_key = ?", ROW_MAPPER, moveKey);
        return rows.stream().findFirst();
    }

    private static final class MoveRecordRowMapper implements RowMapper<MoveRecord> {
        @Override
        public MoveRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MoveRecord(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    MoveOrderRepository.parseKeys(rs.getString("evidence_keys")),
                    rs.getString("source_location"),
                    rs.getString("target_location"),
                    rs.getInt("expected_version"),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("completed_at", LocalDateTime.class));
        }
    }
}
