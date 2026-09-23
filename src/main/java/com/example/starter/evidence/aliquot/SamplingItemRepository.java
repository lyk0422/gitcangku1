package com.example.starter.evidence.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合取样单明细表访问。明细随申请在同一事务写入，任一母样失败则整单回滚、不留明细。
 */
@Repository
public class SamplingItemRepository {

    private static final SamplingItemRowMapper ROW_MAPPER = new SamplingItemRowMapper();

    private final JdbcTemplate jdbc;

    public SamplingItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条母样取用明细，记录预留时的母样版本、证物版本与保管人、封条快照。
     */
    public void insert(String requestId, String sampleKey, long qty, String unit,
                       long sampleVersion, long evidenceVersion, String custodianSnapshot,
                       String sealStatusSnapshot, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sampling_item
                            (request_id, sample_key, qty, unit, sample_version, evidence_version,
                             custodian_snapshot, seal_status_snapshot, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestId, sampleKey, qty, unit, sampleVersion, evidenceVersion,
                custodianSnapshot, sealStatusSnapshot, now);
    }

    /**
     * 按申请单查询全部明细（按母样键排序，保证返回顺序稳定）。
     */
    public List<SamplingItem> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT * FROM sampling_item WHERE request_id = ? ORDER BY sample_key",
                ROW_MAPPER, requestId);
    }

    private static final class SamplingItemRowMapper implements RowMapper<SamplingItem> {
        @Override
        public SamplingItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SamplingItem(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("sample_key"),
                    rs.getLong("qty"),
                    rs.getString("unit"),
                    rs.getLong("sample_version"),
                    rs.getLong("evidence_version"),
                    rs.getString("custodian_snapshot"),
                    rs.getString("seal_status_snapshot"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
