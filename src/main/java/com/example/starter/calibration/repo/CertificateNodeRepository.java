package com.example.starter.calibration.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CertificateNode;

/**
 * 曲线证书节点持久化。节点随证书创建写入，之后不可修改、不可删除（证书仅可撤销）。
 */
@Repository
public class CertificateNodeRepository {

    private static final RowMapper<CertificateNode> MAPPER = (rs, rowNum) -> new CertificateNode(
            rs.getLong("certificate_id"),
            rs.getInt("node_index"),
            rs.getBigDecimal("raw_value"),
            rs.getBigDecimal("corrected_value"));

    private final JdbcTemplate jdbc;

    public CertificateNodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个节点（须在证书创建事务内调用）。
     */
    public void insert(long certificateId, int nodeIndex,
                       java.math.BigDecimal raw, java.math.BigDecimal corrected) {
        jdbc.update("INSERT INTO certificate_node (certificate_id, node_index, raw_value, corrected_value) "
                        + "VALUES (?, ?, ?, ?)",
                certificateId, nodeIndex, raw, corrected);
    }

    /**
     * 按证书 ID 查询全部节点（按节点序号升序，即原始值严格递增序）。
     */
    public List<CertificateNode> findByCertificateId(long certificateId) {
        return jdbc.query("SELECT * FROM certificate_node WHERE certificate_id = ? ORDER BY node_index",
                MAPPER, certificateId);
    }
}
