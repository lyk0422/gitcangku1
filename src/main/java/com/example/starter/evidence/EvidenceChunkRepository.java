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
 * 分块表访问。块只追加、不可修改；(intake_key, chunk_offset, chunk_length) 唯一约束兜底，
 * 重叠校验由服务层在接收行锁内完成。payload 为原始字节，任何查询结果不得写入日志。
 */
@Repository
public class EvidenceChunkRepository {

    private final JdbcTemplate jdbc;

    public EvidenceChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个块；同范围唯一约束冲突时抛出 DuplicateKeyException。
     */
    public void insert(String intakeKey, int offset, int length, byte[] payload,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_chunk
                            (intake_key, chunk_offset, chunk_length, payload, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                intakeKey, offset, length, payload, now);
    }

    /**
     * 列出接收下全部块（按偏移升序），用于重叠校验、覆盖校验与字节拼装。
     */
    public List<EvidenceChunk> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM evidence_chunk WHERE intake_key = ? ORDER BY chunk_offset",
                new ChunkRowMapper(), intakeKey);
    }

    /**
     * 精确查找同范围块（偏移与长度都相同）。
     */
    public Optional<EvidenceChunk> findByRange(String intakeKey, int offset, int length) {
        List<EvidenceChunk> rows = jdbc.query("""
                        SELECT * FROM evidence_chunk
                        WHERE intake_key = ? AND chunk_offset = ? AND chunk_length = ?
                        """,
                new ChunkRowMapper(), intakeKey, offset, length);
        return rows.stream().findFirst();
    }

    private static final class ChunkRowMapper implements RowMapper<EvidenceChunk> {
        @Override
        public EvidenceChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidenceChunk(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getInt("chunk_offset"),
                    rs.getInt("chunk_length"),
                    rs.getBytes("payload"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
