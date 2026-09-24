package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 区组扩容记录数据访问；扩容记录只含区组层面信息，不保存任何处理映射。
 */
@Repository
public class BlockExtensionRepository {

    /** 扩容记录行。 */
    public record ExtensionRow(
            long id,
            String experimentId,
            String extensionKey,
            int expectedVersion,
            int fromVersion,
            int toVersion,
            int blockCountAdded,
            int firstBlockNo,
            int lastBlockNo,
            String operatorActor,
            long createdAt) {
    }

    private static final RowMapper<ExtensionRow> MAPPER = (rs, n) -> new ExtensionRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("extension_key"),
            rs.getInt("expected_version"),
            rs.getInt("from_version"),
            rs.getInt("to_version"),
            rs.getInt("block_count_added"),
            rs.getInt("first_block_no"),
            rs.getInt("last_block_no"),
            rs.getString("operator_actor"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public BlockExtensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExtensionRow row) {
        jdbc.update("INSERT INTO block_extension ("
                        + "experiment_id, extension_key, expected_version, from_version, to_version, "
                        + "block_count_added, first_block_no, last_block_no, operator_actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.extensionKey(), row.expectedVersion(), row.fromVersion(),
                row.toVersion(), row.blockCountAdded(), row.firstBlockNo(), row.lastBlockNo(),
                row.operatorActor(), row.createdAt());
    }

    public ExtensionRow findByExtensionKey(String extensionKey) {
        List<ExtensionRow> rows = jdbc.query(
                "SELECT id, experiment_id, extension_key, expected_version, from_version, to_version, "
                        + "block_count_added, first_block_no, last_block_no, operator_actor, created_at "
                        + "FROM block_extension WHERE extension_key = ?",
                MAPPER, extensionKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ExtensionRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT id, experiment_id, extension_key, expected_version, from_version, to_version, "
                        + "block_count_added, first_block_no, last_block_no, operator_actor, created_at "
                        + "FROM block_extension WHERE experiment_id = ? "
                        + "ORDER BY id, first_block_no",
                MAPPER, experimentId);
    }
}
