package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 观测记录持久化：维护 observation_current 当前状态与 observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationRepository {

    private static final String CURRENT_COLUMNS =
            "observation_id, survey_id, location, reading, note, version, deleted, open_bundle_key";

    private static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getString("survey_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, survey_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, survey_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 一次性锁定簇内全部观测当前行并按观测标识升序返回，保证联合裁决与单条解决/删除/其他裁决之间按提交顺序串行，
     * 不会出现半个簇已裁决。调用方必须保证 observationIds 已去重并排序。
     */
    public List<ObservationSnapshot> findCurrentsForUpdateOrdered(List<String> observationIds) {
        if (observationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", observationIds.stream().map(id -> "?").toList());
        return jdbcTemplate.query(
                "SELECT observation_id, survey_id, version, location, reading, note, deleted "
                        + "FROM observation_current WHERE observation_id IN (" + placeholders + ") "
                        + "ORDER BY observation_id ASC FOR UPDATE",
                SNAPSHOT_MAPPER, observationIds.toArray());
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, survey_id, version, location, reading, note, deleted "
                                + "FROM observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, survey_id, location, reading, note, version, "
                        + "deleted, open_bundle_key, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.surveyId(), snapshot.location(), snapshot.reading(),
                snapshot.note(), snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，业务字段保留原值（查询时不返回）。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE observation_current SET version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 把观测挂到未结关联簇上（建簇冻结时调用）；已在其他未结簇上的观测由唯一写入语义拒绝。
     * 返回受影响行数：0 表示该观测已挂在某个未结簇上，调用方据此重读归属并拒绝。
     */
    public int attachOpenBundleIfFree(String observationId, String bundleKey) {
        return jdbcTemplate.update(
                "UPDATE observation_current SET open_bundle_key = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ? AND open_bundle_key IS NULL",
                bundleKey, observationId);
    }

    /**
     * 在持有行锁的前提下读取观测当前挂接的未结簇标识；null 表示未入簇。
     */
    public String findOpenBundleKeyForUpdate(String observationId) {
        List<String> keys = jdbcTemplate.query(
                "SELECT open_bundle_key FROM observation_current WHERE observation_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("open_bundle_key"), observationId);
        // NULL 挂接会被 RowMapper 映射为 null 元素，findFirst 不接受 null，需要先过滤
        return keys.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * 簇关闭后批量解除其全部成员的未结簇挂接。
     */
    public void clearOpenBundle(String bundleKey) {
        jdbcTemplate.update(
                "UPDATE observation_current SET open_bundle_key = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE open_bundle_key = ?",
                bundleKey);
    }

    /**
     * 联合裁决恢复墓碑：写回业务字段并解除墓碑状态，版本前进到恢复版本。
     */
    public void restoreCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = FALSE, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.observationId());
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, survey_id, version, location, reading, note, "
                        + "deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.surveyId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted());
    }
}
