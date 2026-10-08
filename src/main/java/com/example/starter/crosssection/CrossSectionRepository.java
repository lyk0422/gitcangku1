package com.example.starter.crosssection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 断面观测点持久化：cross_section_current 当前状态、cross_section_version 完整历史、
 * cross_section_pending_conflict 未决冲突标记。所有 SQL 使用参数化查询。
 */
@Repository
public class CrossSectionRepository {

    private static final TypeReference<List<String>> FIELD_LIST_TYPE = new TypeReference<>() {
    };

    private static final RowMapper<CrossSectionSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new CrossSectionSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("section_no"),
            (Integer) rs.getObject("x_mm"),
            (Integer) rs.getObject("z_mm"),
            rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CrossSectionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<CrossSectionSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, section_no, x_mm, z_mm, deleted "
                                + "FROM cross_section_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），写事务内串行化同一观测点并发变更。
     */
    public Optional<CrossSectionSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, section_no, x_mm, z_mm, deleted "
                                + "FROM cross_section_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<CrossSectionSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, section_no, x_mm, z_mm, deleted "
                                + "FROM cross_section_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新观测点的当前状态（version = 1）。
     */
    public void insertCurrent(CrossSectionSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO cross_section_current (observation_id, section_no, x_mm, z_mm, version, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.sectionNo(), snapshot.xMm(), snapshot.zMm(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本的完整版本组。
     */
    public void updateCurrent(CrossSectionSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE cross_section_current SET section_no = ?, x_mm = ?, z_mm = ?, version = ?, deleted = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.sectionNo(), snapshot.xMm(), snapshot.zMm(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE cross_section_current SET version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(CrossSectionSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO cross_section_version (observation_id, version, section_no, x_mm, z_mm, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.sectionNo(),
                snapshot.xMm(), snapshot.zMm(), snapshot.deleted());
    }

    /**
     * 读取未解决冲突标记（不加锁）；无标记返回空。
     */
    public Optional<PendingConflict> findPendingConflict(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, base_version, conflict_version, candidate_section_no, "
                                + "candidate_x_mm, candidate_z_mm, conflict_fields, request_id "
                                + "FROM cross_section_pending_conflict WHERE observation_id = ?",
                        PENDING_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 写入或覆盖未解决冲突标记（每点至多一行）：同一基线的新冲突以最新候选覆盖。
     */
    public void upsertPendingConflict(PendingConflict conflict) {
        jdbcTemplate.update(
                "MERGE INTO cross_section_pending_conflict (observation_id, base_version, conflict_version, "
                        + "candidate_section_no, candidate_x_mm, candidate_z_mm, conflict_fields, request_id, created_at) "
                        + "KEY (observation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                conflict.observationId(), conflict.baseVersion(), conflict.conflictVersion(),
                conflict.candidateSectionNo(), conflict.candidateXMm(), conflict.candidateZMm(),
                writeFieldList(conflict.conflictFields()), conflict.requestId());
    }

    /**
     * 清除未解决冲突标记（裁决成功或后续无冲突内容变更后）；无标记时不报错。
     */
    public void deletePendingConflict(String observationId) {
        jdbcTemplate.update(
                "DELETE FROM cross_section_pending_conflict WHERE observation_id = ?", observationId);
    }

    private String writeFieldList(List<String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize conflict fields", e);
        }
    }

    private List<String> readFieldList(String json) {
        try {
            return objectMapper.readValue(json, FIELD_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict fields", e);
        }
    }

    private final RowMapper<PendingConflict> PENDING_MAPPER = (rs, rowNum) -> new PendingConflict(
            rs.getString("observation_id"),
            rs.getInt("base_version"),
            rs.getInt("conflict_version"),
            rs.getString("candidate_section_no"),
            rs.getInt("candidate_x_mm"),
            rs.getInt("candidate_z_mm"),
            readFieldList(rs.getString("conflict_fields")),
            rs.getString("request_id"));
}
