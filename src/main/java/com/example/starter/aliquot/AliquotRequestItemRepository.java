package com.example.starter.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合取样单母样明细表访问。明细申请时一次写入，之后不可变，不提供更新/删除语句。
 */
@Repository
public class AliquotRequestItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public AliquotRequestItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条母样明细（含申请时母样版本快照）。
     */
    public void insert(long requestId, String sampleKey, long quantity, long sampleVersion,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO aliquot_request_item
                            (request_id, sample_key, quantity, sample_version, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                requestId, sampleKey, quantity, sampleVersion, now);
    }

    /**
     * 按取样单 id 查询全部母样明细（按写入顺序）。
     */
    public List<AliquotRequestItem> findByRequestId(long requestId) {
        return jdbc.query(
                "SELECT * FROM aliquot_request_item WHERE request_id = ? ORDER BY id",
                ROW_MAPPER, requestId);
    }

    private static final class ItemRowMapper implements RowMapper<AliquotRequestItem> {
        @Override
        public AliquotRequestItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AliquotRequestItem(
                    rs.getLong("id"),
                    rs.getLong("request_id"),
                    rs.getString("sample_key"),
                    rs.getLong("quantity"),
                    rs.getLong("sample_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
