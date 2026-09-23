package com.example.starter.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 母样耗用与子样映射表访问，只追加、不可变。
 * 同时提供按母样聚合的预留/耗用数量查询：预留与耗用均由取样单状态 + 明细实时推导，
 * 拒绝/取消后明细保留但不再计入任何数量。
 */
@Repository
public class AliquotConsumptionRepository {

    private static final ConsumptionRowMapper ROW_MAPPER = new ConsumptionRowMapper();

    private final JdbcTemplate jdbc;

    public AliquotConsumptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条不可变的“母样-数量-子样”映射。
     */
    public void insert(long requestId, String sampleKey, long quantity, String childEvidenceKey,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO aliquot_consumption
                            (request_id, sample_key, quantity, child_evidence_key, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                requestId, sampleKey, quantity, childEvidenceKey, now);
    }

    /**
     * 按取样单 id 查询全部映射（按写入顺序）。
     */
    public List<AliquotConsumption> findByRequestId(long requestId) {
        return jdbc.query(
                "SELECT * FROM aliquot_consumption WHERE request_id = ? ORDER BY id",
                ROW_MAPPER, requestId);
    }

    /**
     * 聚合母样当前预留（RESERVED 单据）与累计耗用（CONSUMED 单据）数量。
     * 必须在持有母样证物行锁的事务内调用，使预留检查与申请预留串行化。
     */
    public SampleReservation aggregateBySample(String sampleKey) {
        Long reserved = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(i.quantity), 0)
                        FROM aliquot_request_item i
                        JOIN aliquot_request r ON r.id = i.request_id
                        WHERE i.sample_key = ? AND r.status = 'RESERVED'
                        """,
                Long.class, sampleKey);
        Long consumed = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(i.quantity), 0)
                        FROM aliquot_request_item i
                        JOIN aliquot_request r ON r.id = i.request_id
                        WHERE i.sample_key = ? AND r.status = 'CONSUMED'
                        """,
                Long.class, sampleKey);
        return new SampleReservation(sampleKey, reserved == null ? 0 : reserved,
                consumed == null ? 0 : consumed);
    }

    private static final class ConsumptionRowMapper implements RowMapper<AliquotConsumption> {
        @Override
        public AliquotConsumption mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AliquotConsumption(
                    rs.getLong("id"),
                    rs.getLong("request_id"),
                    rs.getString("sample_key"),
                    rs.getLong("quantity"),
                    rs.getString("child_evidence_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
