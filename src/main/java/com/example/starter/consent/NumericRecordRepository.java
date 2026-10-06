package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 研究数值/派生记录及其溯源的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 * 用途固定为 RESEARCH 研究，故表内不冗余存储用途列。
 */
@Repository
public class NumericRecordRepository {

    private static final RowMapper<NumericRow> NUMERIC_MAPPER = (rs, rowNum) -> new NumericRow(
            rs.getString("subject_key"),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            RecordKind.valueOf(rs.getString("kind")),
            rs.getLong("record_value"),
            rs.getInt("depth"));

    private static final RowMapper<AncestorRow> ANCESTOR_MAPPER = (rs, rowNum) -> new AncestorRow(
            rs.getString("ancestor_subject_key"),
            rs.getInt("ancestor_epoch"),
            rs.getString("ancestor_record_key"));

    private final JdbcTemplate jdbc;

    public NumericRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 研究记录行。
     *
     * @param subjectKey 主体标识（合成字符串）
     * @param epoch      记录所属授权代次
     * @param recordKey  记录键
     * @param kind       类型：NUMERIC 数值 / DERIVED 派生
     * @param value      数值或派生求和结果，0 至 long 最大值
     * @param depth      派生层级：数值为 0，派生为来源最大层级加 1，上限 3
     */
    public record NumericRow(String subjectKey, int epoch, String recordKey,
                             RecordKind kind, long value, int depth) {
    }

    /**
     * 祖先引用行：完整祖先代次集合中的一员，按“主体＋代次＋记录键”固化，不按当前同名键替换。
     *
     * @param subjectKey 祖先主体标识
     * @param epoch      祖先授权代次
     * @param recordKey  祖先记录键
     */
    public record AncestorRow(String subjectKey, int epoch, String recordKey) {
    }

    Optional<NumericRow> find(String subjectKey, int epoch, String recordKey) {
        List<NumericRow> rows = jdbc.query(
                "SELECT subject_key, epoch, record_key, kind, record_value, depth FROM numeric_record"
                        + " WHERE subject_key = ? AND epoch = ? AND record_key = ?",
                NUMERIC_MAPPER, subjectKey, epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insert(String subjectKey, int epoch, String recordKey, RecordKind kind,
                long value, int depth, String requestId) {
        jdbc.update(
                "INSERT INTO numeric_record (subject_key, epoch, record_key, kind, record_value, depth, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, epoch, recordKey, kind.name(), value, depth, requestId);
    }

    void insertSource(String targetSubjectKey, int targetEpoch, String targetRecordKey, int seq,
                      String sourceSubjectKey, int sourceEpoch, String sourceRecordKey) {
        jdbc.update(
                "INSERT INTO derived_source (target_subject_key, target_epoch, target_record_key, seq,"
                        + " source_subject_key, source_epoch, source_record_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                targetSubjectKey, targetEpoch, targetRecordKey, seq,
                sourceSubjectKey, sourceEpoch, sourceRecordKey);
    }

    void insertAncestor(String targetSubjectKey, int targetEpoch, String targetRecordKey,
                        String ancestorSubjectKey, int ancestorEpoch, String ancestorRecordKey) {
        jdbc.update(
                "INSERT INTO derived_ancestor (target_subject_key, target_epoch, target_record_key,"
                        + " ancestor_subject_key, ancestor_epoch, ancestor_record_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                targetSubjectKey, targetEpoch, targetRecordKey,
                ancestorSubjectKey, ancestorEpoch, ancestorRecordKey);
    }

    List<AncestorRow> findAncestors(String subjectKey, int epoch, String recordKey) {
        return jdbc.query(
                "SELECT ancestor_subject_key, ancestor_epoch, ancestor_record_key FROM derived_ancestor"
                        + " WHERE target_subject_key = ? AND target_epoch = ? AND target_record_key = ?",
                ANCESTOR_MAPPER, subjectKey, epoch, recordKey);
    }
}
