package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 关联观测簇持久化：簇、冻结成员、字段冲突登记与不可变联合裁决记录。
 *
 * <p>所有 SQL 使用参数化查询；一致字段列表与簇级前后快照以 JSON 原文存储。
 * 写操作均在调用方事务内执行，失败随事务整体回滚。
 */
@Repository
public class BundleRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BundleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------- 簇 ----------

    /**
     * 插入新簇；bundleKey 主键冲突时抛出重复键异常。
     */
    public void insertBundle(BundleRecord bundle) {
        jdbcTemplate.update(
                "INSERT INTO observation_bundle (bundle_key, survey_id, consistent_fields, status, operator, "
                        + "closed_at, created_at) VALUES (?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                bundle.bundleKey(), bundle.surveyId(), writeJson(bundle.consistentFields()),
                bundle.status(), bundle.operator());
    }

    /**
     * 读取簇（不加锁），用于查询接口。
     */
    public Optional<BundleRecord> findBundleByKey(String bundleKey) {
        return queryBundle(
                "SELECT bundle_key, survey_id, consistent_fields, status, operator, closed_at "
                        + "FROM observation_bundle WHERE bundle_key = ?", bundleKey);
    }

    /**
     * 读取簇并对簇行加锁（SELECT ... FOR UPDATE），在联合裁决事务内串行化针对同一簇的并发裁决。
     */
    public Optional<BundleRecord> findBundleByKeyForUpdate(String bundleKey) {
        return queryBundle(
                "SELECT bundle_key, survey_id, consistent_fields, status, operator, closed_at "
                        + "FROM observation_bundle WHERE bundle_key = ? FOR UPDATE", bundleKey);
    }

    /**
     * 联合裁决成功后关闭簇。
     */
    public void closeBundle(String bundleKey, Instant closedAtUtc) {
        jdbcTemplate.update(
                "UPDATE observation_bundle SET status = ?, closed_at = ? WHERE bundle_key = ?",
                BundleRecord.CLOSED, Timestamp.from(closedAtUtc), bundleKey);
    }

    private Optional<BundleRecord> queryBundle(String sql, String bundleKey) {
        return jdbcTemplate.query(sql, BUNDLE_MAPPER, bundleKey).stream().findFirst();
    }

    private final RowMapper<BundleRecord> BUNDLE_MAPPER = (rs, rowNum) -> {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new BundleRecord(
                rs.getString("bundle_key"),
                rs.getString("survey_id"),
                readStringList(rs.getString("consistent_fields")),
                rs.getString("status"),
                rs.getString("operator"),
                closedAt == null ? null : closedAt.toInstant());
    };

    // ---------- 冻结成员 ----------

    /**
     * 插入一条冻结成员。
     */
    public void insertMember(BundleMemberRecord member) {
        jdbcTemplate.update(
                "INSERT INTO observation_bundle_member (bundle_key, observation_id, survey_id, frozen_version, "
                        + "tombstone_at_freeze, base_version, remote_location, remote_reading, remote_note, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                member.bundleKey(), member.observationId(), member.surveyId(), member.frozenVersion(),
                member.tombstoneAtFreeze(), member.baseVersion(), member.remoteLocation(),
                member.remoteReading(), member.remoteNote());
    }

    /**
     * 按观测标识升序读取簇内全部冻结成员。
     */
    public List<BundleMemberRecord> findMembersByKey(String bundleKey) {
        return jdbcTemplate.query(
                "SELECT bundle_key, observation_id, survey_id, frozen_version, tombstone_at_freeze, "
                        + "base_version, remote_location, remote_reading, remote_note "
                        + "FROM observation_bundle_member WHERE bundle_key = ? ORDER BY observation_id ASC",
                MEMBER_MAPPER, bundleKey);
    }

    private final RowMapper<BundleMemberRecord> MEMBER_MAPPER = (rs, rowNum) -> new BundleMemberRecord(
            rs.getString("bundle_key"),
            rs.getString("observation_id"),
            rs.getString("survey_id"),
            rs.getInt("frozen_version"),
            rs.getBoolean("tombstone_at_freeze"),
            (Integer) rs.getObject("base_version"),
            rs.getString("remote_location"),
            rs.getString("remote_reading"),
            rs.getString("remote_note"));

    // ---------- 字段冲突 ----------

    /**
     * 登记一条字段冲突（FIELD 或 RESTORE）；(bundleKey, observationId, fieldName) 唯一键冲突时抛出重复键异常。
     */
    public void insertConflict(FieldConflictRecord conflict) {
        jdbcTemplate.update(
                "INSERT INTO field_conflict (bundle_key, observation_id, field_name, conflict_type, base_version, "
                        + "base_value, local_value, remote_value, status, chosen_source, chosen_value, "
                        + "restore_basis, arbitration_request_id, resolved_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP)",
                conflict.bundleKey(), conflict.observationId(), conflict.fieldName(), conflict.conflictType(),
                conflict.baseVersion(), conflict.baseValue(), conflict.localValue(), conflict.remoteValue(),
                FieldConflictRecord.OPEN);
    }

    /**
     * 按观测标识、字段名稳定排序读取簇内全部冲突（证据查询与裁决校验共用）。
     */
    public List<FieldConflictRecord> findConflictsByKey(String bundleKey) {
        return queryConflicts(
                "SELECT id, bundle_key, observation_id, field_name, conflict_type, base_version, base_value, "
                        + "local_value, remote_value, status, chosen_source, chosen_value, restore_basis, "
                        + "arbitration_request_id, resolved_at_utc FROM field_conflict WHERE bundle_key = ? "
                        + "ORDER BY observation_id ASC, field_name ASC",
                bundleKey);
    }

    /**
     * 读取簇内全部未决冲突，按观测标识、字段名稳定排序。
     */
    public List<FieldConflictRecord> findOpenConflictsByKey(String bundleKey) {
        return queryConflicts(
                "SELECT id, bundle_key, observation_id, field_name, conflict_type, base_version, base_value, "
                        + "local_value, remote_value, status, chosen_source, chosen_value, restore_basis, "
                        + "arbitration_request_id, resolved_at_utc FROM field_conflict "
                        + "WHERE bundle_key = ? AND status = ? ORDER BY observation_id ASC, field_name ASC",
                bundleKey, FieldConflictRecord.OPEN);
    }

    /**
     * 按裁决请求标识读取被该次裁决关闭的冲突（证据查询），按观测标识、字段名稳定排序。
     */
    public List<FieldConflictRecord> findConflictsByArbitration(String requestId) {
        return jdbcTemplate.query(
                "SELECT id, bundle_key, observation_id, field_name, conflict_type, base_version, base_value, "
                        + "local_value, remote_value, status, chosen_source, chosen_value, restore_basis, "
                        + "arbitration_request_id, resolved_at_utc FROM field_conflict "
                        + "WHERE arbitration_request_id = ? ORDER BY observation_id ASC, field_name ASC",
                CONFLICT_MAPPER, requestId);
    }

    private List<FieldConflictRecord> queryConflicts(String sql, Object... args) {
        return jdbcTemplate.query(sql, CONFLICT_MAPPER, args);
    }

    /**
     * 关闭单个字段冲突，写入逐字段来源、最终值、恢复依据与关闭时刻。返回受影响行数（成功应为 1）。
     */
    public int resolveConflict(String bundleKey, String observationId, String fieldName,
                               String chosenSource, String chosenValue, String restoreBasis,
                               String arbitrationRequestId, Instant resolvedAtUtc) {
        return jdbcTemplate.update(
                "UPDATE field_conflict SET status = ?, chosen_source = ?, chosen_value = ?, restore_basis = ?, "
                        + "arbitration_request_id = ?, resolved_at_utc = ? "
                        + "WHERE bundle_key = ? AND observation_id = ? AND field_name = ? AND status = ?",
                FieldConflictRecord.RESOLVED, chosenSource, chosenValue, restoreBasis,
                arbitrationRequestId, Timestamp.from(resolvedAtUtc),
                bundleKey, observationId, fieldName, FieldConflictRecord.OPEN);
    }

    private final RowMapper<FieldConflictRecord> CONFLICT_MAPPER = (rs, rowNum) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at_utc");
        return new FieldConflictRecord(
                rs.getLong("id"),
                rs.getString("bundle_key"),
                rs.getString("observation_id"),
                rs.getString("field_name"),
                rs.getString("conflict_type"),
                (Integer) rs.getObject("base_version"),
                rs.getString("base_value"),
                rs.getString("local_value"),
                rs.getString("remote_value"),
                rs.getString("status"),
                rs.getString("chosen_source"),
                rs.getString("chosen_value"),
                rs.getString("restore_basis"),
                rs.getString("arbitration_request_id"),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    // ---------- 联合裁决记录 ----------

    /**
     * 插入不可变联合裁决记录；requestId 主键或 bundleKey 唯一键冲突时抛出重复键异常。
     */
    public void insertArbitration(String requestId, String bundleKey, String operator,
                                  String beforeSnapshotJson, String afterSnapshotJson,
                                  Instant arbitratedAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO bundle_arbitration (request_id, bundle_key, operator, before_snapshot, "
                        + "after_snapshot, arbitrated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                requestId, bundleKey, operator, beforeSnapshotJson, afterSnapshotJson,
                Timestamp.from(arbitratedAtUtc));
    }

    /**
     * 按 requestId 查询联合裁决记录；不存在返回空。
     */
    public Optional<ArbitrationRecordView> findArbitrationByRequestId(String requestId) {
        return jdbcTemplate.query(
                        "SELECT request_id, bundle_key, operator, before_snapshot, after_snapshot, arbitrated_at_utc "
                                + "FROM bundle_arbitration WHERE request_id = ?",
                        ARBITRATION_MAPPER, requestId)
                .stream().findFirst();
    }

    private final RowMapper<ArbitrationRecordView> ARBITRATION_MAPPER = (rs, rowNum) -> new ArbitrationRecordView(
            rs.getString("request_id"),
            rs.getString("bundle_key"),
            rs.getString("operator"),
            rs.getString("before_snapshot"),
            rs.getString("after_snapshot"),
            rs.getTimestamp("arbitrated_at_utc").toInstant());

    /**
     * 仅供插入方区分 requestId 主键冲突与 bundleKey 唯一键冲突使用。
     */
    public boolean arbitrationExistsForBundle(String bundleKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = ?", Integer.class, bundleKey);
        return count != null && count > 0;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize bundle payload", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize consistent fields", e);
        }
    }
}
