package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 区组随机表版本数据访问；版本只增不改，旧版本仅允许 ACTIVE -> CLOSED。
 * 版本内序号映射保存在 seat 表，版本链顺序由 version_no 固化。
 */
@Repository
public class RandomTableVersionRepository {

    /** 随机表版本行；createdAt/closedAt 为 Unix 毫秒 UTC。 */
    public record RandomTableVersionRow(
            long id,
            String experimentId,
            int blockNo,
            int versionNo,
            Long parentId,
            int capacity,
            String treatmentCodes,
            String status,
            long createdAt,
            Long closedAt) {
    }

    private static final String COLUMNS =
            "id, experiment_id, block_no, version_no, parent_id, capacity, treatment_codes, "
                    + "status, created_at, closed_at";

    private static final RowMapper<RandomTableVersionRow> MAPPER = (rs, n) -> new RandomTableVersionRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("version_no"),
            (Long) rs.getObject("parent_id"),
            rs.getInt("capacity"),
            rs.getString("treatment_codes"),
            rs.getString("status"),
            rs.getLong("created_at"),
            (Long) rs.getObject("closed_at"));

    private final JdbcTemplate jdbc;

    public RandomTableVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新版本并回填自增主键。 */
    public RandomTableVersionRow insert(RandomTableVersionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO random_table_version ("
                            + "experiment_id, block_no, version_no, parent_id, capacity, "
                            + "treatment_codes, status, created_at, closed_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setInt(2, row.blockNo());
            ps.setInt(3, row.versionNo());
            ps.setObject(4, row.parentId());
            ps.setInt(5, row.capacity());
            ps.setString(6, row.treatmentCodes());
            ps.setString(7, row.status());
            ps.setLong(8, row.createdAt());
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey(), "版本主键必须回填").longValue();
        return new RandomTableVersionRow(id, row.experimentId(), row.blockNo(), row.versionNo(),
                row.parentId(), row.capacity(), row.treatmentCodes(), row.status(), row.createdAt(),
                null);
    }

    public RandomTableVersionRow findById(long id) {
        List<RandomTableVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定版本；封存、扩容、关闭版本时串行化同一版本的并发写。 */
    public RandomTableVersionRow lockById(long id) {
        List<RandomTableVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 区组全部版本，按版本号升序。 */
    public List<RandomTableVersionRow> findByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY version_no",
                MAPPER, experimentId, blockNo);
    }

    /** 行级锁定区组整条版本链（按版本号升序），扩容时裁决并发。 */
    public List<RandomTableVersionRow> lockByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY version_no FOR UPDATE",
                MAPPER, experimentId, blockNo);
    }

    /**
     * 关闭版本未分配余量；仅 ACTIVE 可关闭。
     *
     * @return 受影响行数；0 表示不存在或已关闭
     */
    public int markClosed(long id, long closedAt) {
        return jdbc.update("UPDATE random_table_version SET status = 'CLOSED', closed_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE'",
                closedAt, id);
    }

    /** 实验的全部版本，按区组号、版本号升序（诊断查询）。 */
    public List<RandomTableVersionRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? ORDER BY block_no, version_no",
                MAPPER, experimentId);
    }
}
