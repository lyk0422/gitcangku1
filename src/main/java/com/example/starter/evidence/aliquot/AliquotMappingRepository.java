package com.example.starter.evidence.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 母样-数量-子样不可变映射表访问。映射仅在二次确认成功的同一事务内一次写入，
 * 之后不提供更新或删除入口。
 */
@Repository
public class AliquotMappingRepository {

    private static final AliquotMappingRowMapper ROW_MAPPER = new AliquotMappingRowMapper();

    private final JdbcTemplate jdbc;

    public AliquotMappingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条母样-数量-子样映射。
     */
    public void insert(String aliquotKey, String requestId, String sampleKey, long qty,
                       String unit, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO aliquot_mapping
                            (aliquot_key, request_id, sample_key, qty, unit, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                aliquotKey, requestId, sampleKey, qty, unit, now);
    }

    /**
     * 按申请单查询全部映射（按母样键排序）。
     */
    public List<AliquotMapping> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT * FROM aliquot_mapping WHERE request_id = ? ORDER BY sample_key",
                ROW_MAPPER, requestId);
    }

    /**
     * 按母样查询其参与生成的全部子样映射（只读）。
     */
    public List<AliquotMapping> findBySampleKey(String sampleKey) {
        return jdbc.query(
                "SELECT * FROM aliquot_mapping WHERE sample_key = ? ORDER BY id",
                ROW_MAPPER, sampleKey);
    }

    private static final class AliquotMappingRowMapper implements RowMapper<AliquotMapping> {
        @Override
        public AliquotMapping mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AliquotMapping(
                    rs.getLong("id"),
                    rs.getString("aliquot_key"),
                    rs.getString("request_id"),
                    rs.getString("sample_key"),
                    rs.getLong("qty"),
                    rs.getString("unit"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
