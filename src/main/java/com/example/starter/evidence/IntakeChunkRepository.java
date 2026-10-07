package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 已接收块表访问。块写入后不可改，只追加；重叠检测与插入必须在
 * 持有分块入库记录行锁的事务内完成，保证并发补块串行校验。
 */
@Repository
public class IntakeChunkRepository {

    private static final IntakeChunkRowMapper ROW_MAPPER = new IntakeChunkRowMapper();

    private final JdbcTemplate jdbc;

    public IntakeChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个块（调用前必须已锁定分块入库记录行并完成重叠检测）。
     */
    public void insert(String intakeKey, int offsetBytes, int lengthBytes, byte[] data,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO intake_chunk
                            (intake_key, offset_bytes, length_bytes, data, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                intakeKey, offsetBytes, lengthBytes, data, now);
    }

    /**
     * 按分块入库业务键查询全部块（按偏移升序）。
     */
    public List<IntakeChunk> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM intake_chunk WHERE intake_key = ? ORDER BY offset_bytes",
                ROW_MAPPER, intakeKey);
    }

    /**
     * 查询与 [startInclusive, endExclusive) 重叠的块（调用前必须已锁定分块入库记录行）。
     */
    public List<IntakeChunk> findOverlapping(String intakeKey, int startInclusive, int endExclusive) {
        return jdbc.query("""
                        SELECT * FROM intake_chunk
                        WHERE intake_key = ? AND offset_bytes < ? AND (offset_bytes + length_bytes) > ?
                        """,
                ROW_MAPPER, intakeKey, endExclusive, startInclusive);
    }

    private static final class IntakeChunkRowMapper implements RowMapper<IntakeChunk> {
        @Override
        public IntakeChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IntakeChunk(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getInt("offset_bytes"),
                    rs.getInt("length_bytes"),
                    rs.getBytes("data"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
