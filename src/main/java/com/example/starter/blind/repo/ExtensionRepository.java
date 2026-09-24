package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 区组扩容记录数据访问；extensionKey 全局唯一。
 * 扩容记录与追加席位、实验版本递增在同一事务提交；不保存任何处理映射。
 */
@Repository
public class ExtensionRepository {

    /** 扩容记录行（无盲底字段）。 */
    public record ExtensionRow(
            long id,
            String extensionKey,
            String experimentId,
            int expectedVersion,
            int fromBlockCount,
            int addedBlockCount,
            int fromBlockNo,
            int toBlockNo,
            int resultingVersion,
            String operatorActor,
            long createdAt) {
    }

    private static final RowMapper<ExtensionRow> MAPPER = (rs, n) -> new ExtensionRow(
            rs.getLong("id"),
            rs.getString("extension_key"),
            rs.getString("experiment_id"),
            rs.getInt("expected_version"),
            rs.getInt("from_block_count"),
            rs.getInt("added_block_count"),
            rs.getInt("from_block_no"),
            rs.getInt("to_block_no"),
            rs.getInt("resulting_version"),
            rs.getString("operator_actor"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ExtensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExtensionRow row) {
        jdbc.update("INSERT INTO block_extension ("
                        + "extension_key, experiment_id, expected_version, from_block_count, "
                        + "added_block_count, from_block_no, to_block_no, resulting_version, "
                        + "operator_actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.extensionKey(), row.experimentId(), row.expectedVersion(), row.fromBlockCount(),
                row.addedBlockCount(), row.fromBlockNo(), row.toBlockNo(), row.resultingVersion(),
                row.operatorActor(), row.createdAt());
    }

    /**
     * 按扩容幂等键查找记录；重放与全局唯一冲突判定使用。
     */
    public ExtensionRow findByKey(String extensionKey) {
        List<ExtensionRow> rows = jdbc.query(
                "SELECT id, extension_key, experiment_id, expected_version, from_block_count, "
                        + "added_block_count, from_block_no, to_block_no, resulting_version, "
                        + "operator_actor, created_at "
                        + "FROM block_extension WHERE extension_key = ?",
                MAPPER, extensionKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ExtensionRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT id, extension_key, experiment_id, expected_version, from_block_count, "
                        + "added_block_count, from_block_no, to_block_no, resulting_version, "
                        + "operator_actor, created_at "
                        + "FROM block_extension WHERE experiment_id = ? ORDER BY id",
                MAPPER, experimentId);
    }

    /**
     * 插入命中 extensionKey 唯一约束时返回 true：键已被其他扩容占用。
     */
    public boolean isDuplicateKey(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_block_extension_key");
    }
}
