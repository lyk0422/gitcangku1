package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 4x4 二值栅格观测持久化：mask_observation_current 当前元数据、mask_observation_version 版本元数据、
 * mask_version_cell 逐格值（每版本固定 16 行）。所有 SQL 使用参数化查询。
 */
@Repository
public class MaskObservationRepository {

    private record CurrentRow(String observationId, int version, boolean deleted) {
    }

    private record VersionRow(int version, Integer baseVersion, String operator, boolean deleted) {
    }

    private static final RowMapper<CurrentRow> CURRENT_MAPPER = (rs, rowNum) -> new CurrentRow(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getBoolean("deleted"));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, rowNum) -> new VersionRow(
            rs.getInt("version"),
            (Integer) rs.getObject("base_version"),
            rs.getString("operator"),
            rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;

    public MaskObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前元数据（不加锁），用于查询接口。
     */
    public Optional<MaskSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, deleted FROM mask_observation_current "
                                + "WHERE observation_id = ?",
                        CURRENT_MAPPER, observationId)
                .stream().findFirst()
                .map(row -> toSnapshot(observationId, row.version(), row.deleted()));
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化并发变更。
     */
    public Optional<MaskSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, deleted FROM mask_observation_current "
                                + "WHERE observation_id = ? FOR UPDATE",
                        CURRENT_MAPPER, observationId)
                .stream().findFirst()
                .map(row -> toSnapshot(observationId, row.version(), row.deleted()));
    }

    /**
     * 读取指定历史版本完整快照（含 16 格）；墓碑版本无格值；版本不存在返回空。
     */
    public Optional<MaskSnapshot> findVersion(String observationId, int version) {
        List<VersionRow> rows = jdbcTemplate.query(
                "SELECT version, base_version, operator, deleted FROM mask_observation_version "
                        + "WHERE observation_id = ? AND version = ?",
                VERSION_MAPPER, observationId, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        VersionRow row = rows.get(0);
        if (row.deleted()) {
            return Optional.of(new MaskSnapshot(observationId, row.version(), row.baseVersion(),
                    row.operator(), true, null));
        }
        return Optional.of(new MaskSnapshot(observationId, row.version(), row.baseVersion(),
                row.operator(), false, findCells(observationId, version)));
    }

    /**
     * 按行优先读取某版本的 16 个格值。
     */
    public List<Integer> findCells(String observationId, int version) {
        return jdbcTemplate.queryForList(
                "SELECT cell_value FROM mask_version_cell "
                        + "WHERE observation_id = ? AND version = ? ORDER BY row_index ASC, col_index ASC",
                Integer.class, observationId, version);
    }

    /**
     * 插入新记录的当前元数据（version = 1，非墓碑）。
     */
    public void insertCurrent(String observationId) {
        jdbcTemplate.update(
                "INSERT INTO mask_observation_current (observation_id, version, deleted, updated_at) "
                        + "VALUES (?, 1, FALSE, CURRENT_TIMESTAMP)",
                observationId);
    }

    /**
     * 当前版本前进（合并后内容变化或删除墓碑）。
     */
    public void updateCurrentVersion(String observationId, int newVersion, boolean deleted) {
        jdbcTemplate.update(
                "UPDATE mask_observation_current SET version = ?, deleted = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, deleted, observationId);
    }

    /**
     * 追加一个非墓碑版本的元数据与 16 行格值（同一事务内调用）。
     */
    public void insertVersionWithCells(MaskSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO mask_observation_version (observation_id, version, base_version, operator, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.baseVersion(), snapshot.operator());
        insertCells(snapshot.observationId(), snapshot.version(), snapshot.cells());
    }

    /**
     * 追加一个删除墓碑版本元数据，不写格值。
     */
    public void insertTombstone(MaskSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO mask_observation_version (observation_id, version, base_version, operator, deleted, created_at) "
                        + "VALUES (?, ?, NULL, ?, TRUE, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.operator());
    }

    private void insertCells(String observationId, int version, List<Integer> cells) {
        for (int index = 0; index < cells.size(); index++) {
            jdbcTemplate.update(
                    "INSERT INTO mask_version_cell (observation_id, version, row_index, col_index, cell_value) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    observationId, version, index / MaskGrid.SIZE, index % MaskGrid.SIZE, cells.get(index));
        }
    }

    private MaskSnapshot toSnapshot(String observationId, int version, boolean deleted) {
        VersionRow meta = jdbcTemplate.queryForObject(
                "SELECT version, base_version, operator, deleted FROM mask_observation_version "
                        + "WHERE observation_id = ? AND version = ?",
                VERSION_MAPPER, observationId, version);
        if (deleted || meta == null) {
            String operator = meta == null ? null : meta.operator();
            return new MaskSnapshot(observationId, version, null, operator, true, null);
        }
        return new MaskSnapshot(observationId, version, meta.baseVersion(), meta.operator(), false,
                findCells(observationId, version));
    }
}
