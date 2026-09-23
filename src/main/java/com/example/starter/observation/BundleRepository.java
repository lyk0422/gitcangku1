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
 * 关联观测簇持久化：observation_bundle / bundle_member / bundle_conflict / bundle_arbitration。
 * 所有 SQL 使用参数化查询；列表均按观测标识（再按字段）稳定排序，保证证据查询与裁决落库顺序确定。
 */
@Repository
public class BundleRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<BundleRecord> bundleMapper = (rs, rowNum) -> new BundleRecord(
            rs.getString("bundle_key"),
            rs.getString("survey_id"),
            BundleStatus.valueOf(rs.getString("status")),
            readStringList(rs.getString("consistent_fields")),
            rs.getString("operator"),
            rs.getString("arbitration_id"),
            toInstant(rs.getTimestamp("closed_at")),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<BundleMemberRecord> MEMBER_MAPPER = (rs, rowNum) -> new BundleMemberRecord(
            rs.getLong("id"),
            rs.getString("bundle_key"),
            rs.getString("observation_id"),
            rs.getString("survey_id"),
            rs.getInt("frozen_version"),
            BundleMemberRole.valueOf(rs.getString("role")));

    private static final RowMapper<BundleConflictRecord> CONFLICT_MAPPER = (rs, rowNum) ->
            new BundleConflictRecord(
                    rs.getLong("id"),
                    rs.getString("bundle_key"),
                    rs.getString("observation_id"),
                    rs.getString("field"),
                    rs.getInt("base_version"),
                    rs.getString("candidate_location"),
                    rs.getString("candidate_reading"),
                    rs.getString("candidate_note"),
                    rs.getString("candidate_token"),
                    BundleConflictStatus.valueOf(rs.getString("status")),
                    rs.getString("resolved_source") == null
                            ? null : BundleSource.valueOf(rs.getString("resolved_source")),
                    rs.getString("resolved_value"),
                    rs.getString("arbitration_id"),
                    toInstant(rs.getTimestamp("resolved_at")),
                    toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<BundleArbitrationRecord> ARBITRATION_MAPPER = (rs, rowNum) ->
            new BundleArbitrationRecord(
                    rs.getString("arbitration_id"),
                    rs.getString("bundle_key"),
                    rs.getString("request_id"),
                    rs.getString("survey_id"),
                    rs.getString("operator"),
                    rs.getString("field_sources"),
                    rs.getString("restore_basis"),
                    rs.getString("snapshot_before"),
                    rs.getString("snapshot_after"),
                    rs.getTimestamp("arbitrated_at_utc").toInstant());

    public BundleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String writeStringList(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize string list", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize string list", e);
        }
    }

    // ---------- observation_bundle ----------

    /**
     * 按簇标识查询簇（不加锁）。
     */
    public Optional<BundleRecord> findBundle(String bundleKey) {
        return jdbcTemplate.query(
                        "SELECT bundle_key, survey_id, status, consistent_fields, operator, arbitration_id, "
                                + "closed_at, created_at FROM observation_bundle WHERE bundle_key = ?",
                        bundleMapper, bundleKey)
                .stream().findFirst();
    }

    /**
     * 按簇标识查询簇并加行锁，用于簇写事务串行化并发的联合裁决与冲突登记。
     */
    public Optional<BundleRecord> findBundleForUpdate(String bundleKey) {
        return jdbcTemplate.query(
                        "SELECT bundle_key, survey_id, status, consistent_fields, operator, arbitration_id, "
                                + "closed_at, created_at FROM observation_bundle WHERE bundle_key = ? FOR UPDATE",
                        bundleMapper, bundleKey)
                .stream().findFirst();
    }

    /**
     * 插入新簇（OPEN）。
     */
    public void insertBundle(BundleRecord bundle) {
        jdbcTemplate.update(
                "INSERT INTO observation_bundle (bundle_key, survey_id, status, consistent_fields, operator, "
                        + "arbitration_id, closed_at, created_at) VALUES (?, ?, 'OPEN', ?, ?, NULL, NULL, ?)",
                bundle.bundleKey(), bundle.surveyId(),
                writeStringList(bundle.consistentFields()), bundle.operator(),
                Timestamp.from(bundle.createdAtUtc()));
    }

    /**
     * 联合裁决成功后一次性关闭簇。
     */
    public void closeBundle(String bundleKey, String arbitrationId, Instant closedAtUtc) {
        jdbcTemplate.update(
                "UPDATE observation_bundle SET status = 'CLOSED', arbitration_id = ?, closed_at = ? "
                        + "WHERE bundle_key = ?",
                arbitrationId, Timestamp.from(closedAtUtc), bundleKey);
    }

    // ---------- bundle_member ----------

    /**
     * 查询簇全部成员，按观测标识升序。
     */
    public List<BundleMemberRecord> findMembers(String bundleKey) {
        return jdbcTemplate.query(
                "SELECT id, bundle_key, observation_id, survey_id, frozen_version, role "
                        + "FROM bundle_member WHERE bundle_key = ? ORDER BY observation_id ASC",
                MEMBER_MAPPER, bundleKey);
    }

    /**
     * 插入簇成员；OPEN 期间 open_observation_key 等于观测标识，占用未结簇名额。
     */
    public void insertMember(BundleMemberRecord member) {
        jdbcTemplate.update(
                "INSERT INTO bundle_member (bundle_key, observation_id, survey_id, frozen_version, role, "
                        + "open_observation_key, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                member.bundleKey(), member.observationId(), member.surveyId(), member.frozenVersion(),
                member.role().name(), member.observationId());
    }

    /**
     * 统计观测在未结簇中的占用数（库级唯一索引之外的事务内预检）。
     */
    public int countOpenOccupation(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_member WHERE open_observation_key = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    /**
     * 查询观测当前所在未结簇的簇标识；未加入未结簇时返回空。
     */
    public Optional<String> findOpenBundleKey(String observationId) {
        return jdbcTemplate.query(
                        "SELECT bundle_key FROM bundle_member WHERE open_observation_key = ?",
                        (rs, rowNum) -> rs.getString("bundle_key"), observationId)
                .stream().findFirst();
    }

    /**
     * 簇关闭后释放全部成员的未结簇占用，使其之后可以加入新簇。
     */
    public void releaseOpenKeys(String bundleKey) {
        jdbcTemplate.update(
                "UPDATE bundle_member SET open_observation_key = NULL WHERE bundle_key = ?", bundleKey);
    }

    // ---------- bundle_conflict ----------

    private static final String CONFLICT_COLUMNS =
            "id, bundle_key, observation_id, field, base_version, candidate_location, candidate_reading, "
                    + "candidate_note, candidate_token, status, resolved_source, resolved_value, "
                    + "arbitration_id, resolved_at, created_at";

    /**
     * 查询簇内冲突（含已解决），按观测标识、字段名升序；供证据查询。
     */
    public List<BundleConflictRecord> findConflicts(String bundleKey) {
        return jdbcTemplate.query(
                "SELECT " + CONFLICT_COLUMNS + " FROM bundle_conflict WHERE bundle_key = ? "
                        + "ORDER BY observation_id ASC, field ASC",
                CONFLICT_MAPPER, bundleKey);
    }

    /**
     * 查询簇内全部未解决冲突并加锁，按观测标识、字段名升序；联合裁决事务内使用。
     */
    public List<BundleConflictRecord> findOpenConflictsForUpdate(String bundleKey) {
        return jdbcTemplate.query(
                "SELECT " + CONFLICT_COLUMNS + " FROM bundle_conflict WHERE bundle_key = ? AND status = 'OPEN' "
                        + "ORDER BY observation_id ASC, field ASC FOR UPDATE",
                CONFLICT_MAPPER, bundleKey);
    }

    /**
     * 登记一条冲突字段；同簇同观测同字段由唯一键约束，重复登记前须先清除该观测的旧 OPEN 组。
     */
    public void insertConflict(BundleConflictRecord conflict) {
        jdbcTemplate.update(
                "INSERT INTO bundle_conflict (bundle_key, observation_id, field, base_version, "
                        + "candidate_location, candidate_reading, candidate_note, candidate_token, status, "
                        + "resolved_source, resolved_value, arbitration_id, resolved_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', NULL, NULL, NULL, NULL, ?)",
                conflict.bundleKey(), conflict.observationId(), conflict.field(), conflict.baseVersion(),
                conflict.candidateLocation(), conflict.candidateReading(), conflict.candidateNote(),
                conflict.candidateToken(), Timestamp.from(conflict.createdAtUtc()));
    }

    /**
     * 查询指定观测在簇内全部未解决冲突，按字段名升序；重新登记候选做差异更新时使用。
     */
    public List<BundleConflictRecord> findOpenConflictsForObservation(String bundleKey, String observationId) {
        return jdbcTemplate.query(
                "SELECT " + CONFLICT_COLUMNS + " FROM bundle_conflict "
                        + "WHERE bundle_key = ? AND observation_id = ? AND status = 'OPEN' "
                        + "ORDER BY field ASC",
                CONFLICT_MAPPER, bundleKey, observationId);
    }

    /**
     * 原地更新持续冲突字段的候选快照与指纹（保持冲突行 id 稳定）：旧裁决请求持有旧指纹时将被拒绝。
     */
    public void updateConflictCandidate(long conflictId, int baseVersion, String location, String reading,
                                        String note, String candidateToken) {
        jdbcTemplate.update(
                "UPDATE bundle_conflict SET base_version = ?, candidate_location = ?, candidate_reading = ?, "
                        + "candidate_note = ?, candidate_token = ? WHERE id = ? AND status = 'OPEN'",
                baseVersion, location, reading, note, candidateToken, conflictId);
    }

    /**
     * 删除单条未解决冲突（重新登记后该字段已不再冲突时调用）。
     */
    public void deleteConflict(long conflictId) {
        jdbcTemplate.update("DELETE FROM bundle_conflict WHERE id = ? AND status = 'OPEN'", conflictId);
    }

    /**
     * 删除指定观测在任意未结簇中的全部未解决冲突。
     * 观测被删除成为墓碑后不能再提供候选值，其旧候选随之失效，由删除事务调用；
     * 已解决（RESOLVED）历史冲突保留不动。
     */
    public void deleteOpenConflictsGlobally(String observationId) {
        jdbcTemplate.update(
                "DELETE FROM bundle_conflict WHERE observation_id = ? AND status = 'OPEN'",
                observationId);
    }

    /**
     * 联合裁决成功后关闭单条冲突并保存逐字段来源与最终值。
     */
    public void resolveConflict(long conflictId, BundleSource source, String value,
                                String arbitrationId, Instant resolvedAtUtc) {
        jdbcTemplate.update(
                "UPDATE bundle_conflict SET status = 'RESOLVED', resolved_source = ?, resolved_value = ?, "
                        + "arbitration_id = ?, resolved_at = ? WHERE id = ? AND status = 'OPEN'",
                source.name(), value, arbitrationId, Timestamp.from(resolvedAtUtc), conflictId);
    }

    // ---------- bundle_arbitration ----------

    /**
     * 插入不可变联合裁决记录。
     */
    public void insertArbitration(BundleArbitrationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO bundle_arbitration (arbitration_id, bundle_key, request_id, survey_id, operator, "
                        + "field_sources, restore_basis, snapshot_before, snapshot_after, arbitrated_at_utc, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.arbitrationId(), record.bundleKey(), record.requestId(), record.surveyId(),
                record.operator(), record.fieldSources(), record.restoreBasis(), record.snapshotBefore(),
                record.snapshotAfter(), Timestamp.from(record.arbitratedAtUtc()));
    }

    /**
     * 按全局裁决标识查询不可变记录。
     */
    public Optional<BundleArbitrationRecord> findArbitration(String arbitrationId) {
        return jdbcTemplate.query(
                        "SELECT arbitration_id, bundle_key, request_id, survey_id, operator, field_sources, "
                                + "restore_basis, snapshot_before, snapshot_after, arbitrated_at_utc "
                                + "FROM bundle_arbitration WHERE arbitration_id = ?",
                        ARBITRATION_MAPPER, arbitrationId)
                .stream().findFirst();
    }

    /**
     * 按簇查询全部裁决记录，按裁决时刻与标识升序。
     */
    public List<BundleArbitrationRecord> findArbitrationsByBundle(String bundleKey) {
        return jdbcTemplate.query(
                "SELECT arbitration_id, bundle_key, request_id, survey_id, operator, field_sources, "
                        + "restore_basis, snapshot_before, snapshot_after, arbitrated_at_utc "
                        + "FROM bundle_arbitration WHERE bundle_key = ? "
                        + "ORDER BY arbitrated_at_utc ASC, arbitration_id ASC",
                ARBITRATION_MAPPER, bundleKey);
    }
}
