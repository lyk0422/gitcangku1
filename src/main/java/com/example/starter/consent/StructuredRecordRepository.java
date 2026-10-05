package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 结构化记录与字段级限制清单的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class StructuredRecordRepository {

    private static final RowMapper<StructuredRecordRow> RECORD_MAPPER = (rs, rowNum) -> new StructuredRecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("fields_json"));

    private static final RowMapper<RestrictionRow> RESTRICTION_MAPPER = (rs, rowNum) -> new RestrictionRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("version"),
            rs.getString("forbidden_fields"));

    private final JdbcTemplate jdbc;

    public StructuredRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 结构化记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param fieldsJson 字段集合 JSON 对象（按字段名排序）
     */
    public record StructuredRecordRow(String subjectKey, Purpose purpose, int epoch,
                                      String recordKey, String fieldsJson) {
    }

    /**
     * 字段级限制清单行。
     *
     * @param subjectKey          主体标识
     * @param purpose             用途
     * @param epoch               所属代次
     * @param version             限制清单版本，从 1 开始递增
     * @param forbiddenFieldsJson 禁止字段名 JSON 数组，空数组表示不限制
     */
    public record RestrictionRow(String subjectKey, Purpose purpose, int epoch,
                                 int version, String forbiddenFieldsJson) {
    }

    Optional<StructuredRecordRow> findStructuredRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<StructuredRecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, fields_json FROM structured_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertStructuredRecord(String subjectKey, Purpose purpose, int epoch,
                                String recordKey, String fieldsJson, String requestId) {
        jdbc.update(
                "INSERT INTO structured_record (subject_key, purpose, epoch, record_key, fields_json, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, fieldsJson, requestId);
    }

    /**
     * 查询指定代次当前最新版本的限制清单；从未设置过时返回空。
     */
    Optional<RestrictionRow> findLatestRestriction(String subjectKey, Purpose purpose, int epoch) {
        List<RestrictionRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, version, forbidden_fields FROM field_restriction"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " ORDER BY version DESC LIMIT 1",
                RESTRICTION_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    void insertRestriction(String subjectKey, Purpose purpose, int epoch,
                           int version, String forbiddenFieldsJson, String requestId) {
        jdbc.update(
                "INSERT INTO field_restriction (subject_key, purpose, epoch, version, forbidden_fields, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, version, forbiddenFieldsJson, requestId);
    }
}
