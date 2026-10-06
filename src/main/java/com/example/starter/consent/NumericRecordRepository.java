package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 数值记录、派生直接来源与祖先代次集合的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class NumericRecordRepository {

    private static final RowMapper<NumericRecordRow> RECORD_MAPPER = (rs, rowNum) -> new NumericRecordRow(
            rs.getString("subject_key"),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            RecordKind.valueOf(rs.getString("record_kind")),
            rs.getLong("numeric_value"),
            rs.getInt("depth"));

    private static final RowMapper<AncestorRef> ANCESTOR_MAPPER = (rs, rowNum) -> new AncestorRef(
            rs.getString("ancestor_subject_key"),
            rs.getInt("ancestor_epoch"));

    private final JdbcTemplate jdbc;

    public NumericRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 数值记录行。
     *
     * @param subjectKey 主体标识
     * @param epoch      所属研究授权代次
     * @param recordKey  记录键
     * @param kind       类型：VALUE 数值 / DERIVED 派生求和
     * @param value      数值（0 至 long 最大值），派生记录为精确求和结果
     * @param depth      派生层数：数值记录为 0，最大 3
     */
    public record NumericRecordRow(String subjectKey, int epoch, String recordKey,
                                   RecordKind kind, long value, int depth) {
    }

    /**
     * 祖先授权代次引用。
     *
     * @param subjectKey 祖先授权主体标识
     * @param epoch      祖先授权代次
     */
    public record AncestorRef(String subjectKey, int epoch) {
    }

    Optional<NumericRecordRow> findRecord(String subjectKey, int epoch, String recordKey) {
        List<NumericRecordRow> rows = jdbc.query(
                "SELECT subject_key, epoch, record_key, record_kind, numeric_value, depth FROM numeric_record"
                        + " WHERE subject_key = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询记录创建时的幂等请求标识，用于并发同 requestId 时识别已提交的同一请求。
     */
    Optional<String> findRecordRequestId(String subjectKey, int epoch, String recordKey) {
        List<String> rows = jdbc.query(
                "SELECT request_id FROM numeric_record"
                        + " WHERE subject_key = ? AND epoch = ? AND record_key = ?",
                (rs, rowNum) -> rs.getString(1), subjectKey, epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertRecord(String subjectKey, int epoch, String recordKey, RecordKind kind,
                      long value, int depth, String operator, String requestId) {
        jdbc.update(
                "INSERT INTO numeric_record"
                        + " (subject_key, epoch, record_key, record_kind, numeric_value, depth, operator, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                subjectKey, epoch, recordKey, kind.name(), value, depth, operator, requestId);
    }

    void insertSource(String targetSubjectKey, int targetEpoch, String targetRecordKey, int ordinal,
                      String sourceSubjectKey, int sourceEpoch, String sourceRecordKey) {
        jdbc.update(
                "INSERT INTO numeric_record_source"
                        + " (target_subject_key, target_epoch, target_record_key, source_ordinal,"
                        + " source_subject_key, source_epoch, source_record_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                targetSubjectKey, targetEpoch, targetRecordKey, ordinal,
                sourceSubjectKey, sourceEpoch, sourceRecordKey);
    }

    void insertAncestor(String targetSubjectKey, int targetEpoch, String targetRecordKey,
                        String ancestorSubjectKey, int ancestorEpoch) {
        jdbc.update(
                "INSERT INTO numeric_record_ancestor"
                        + " (target_subject_key, target_epoch, target_record_key,"
                        + " ancestor_subject_key, ancestor_epoch)"
                        + " VALUES (?, ?, ?, ?, ?)",
                targetSubjectKey, targetEpoch, targetRecordKey, ancestorSubjectKey, ancestorEpoch);
    }

    /**
     * 读取记录的完整祖先代次集合（含记录自身代次），创建时固化，不按同名键重解析。
     */
    List<AncestorRef> findAncestors(String subjectKey, int epoch, String recordKey) {
        return jdbc.query(
                "SELECT ancestor_subject_key, ancestor_epoch FROM numeric_record_ancestor"
                        + " WHERE target_subject_key = ? AND target_epoch = ? AND target_record_key = ?",
                ANCESTOR_MAPPER, subjectKey, epoch, recordKey);
    }
}
