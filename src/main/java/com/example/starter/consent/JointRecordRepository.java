package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 共同拥有记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 * 确认标记按主体分列更新，避免并发确认互相覆盖；版本与修订上限由 SQL 条件兜底。
 */
@Repository
public class JointRecordRepository {

    private static final RowMapper<JointRecordRow> MAPPER = (rs, rowNum) -> new JointRecordRow(
            rs.getString("record_key"),
            rs.getString("subject_a"),
            rs.getString("subject_b"),
            rs.getInt("epoch_a"),
            rs.getInt("epoch_b"),
            rs.getInt("version"),
            rs.getInt("revision_count"),
            rs.getString("payload"),
            rs.getBoolean("confirm_a"),
            rs.getBoolean("confirm_b"),
            JointStatus.valueOf(rs.getString("status")));

    private static final String SELECT_COLUMNS =
            "record_key, subject_a, subject_b, epoch_a, epoch_b, version, revision_count,"
                    + " payload, confirm_a, confirm_b, status";

    private final JdbcTemplate jdbc;

    public JointRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 共同拥有记录行。
     *
     * @param recordKey     共同记录键，全局唯一
     * @param subjectA      主体A标识（字典序较小者）
     * @param subjectB      主体B标识（字典序较大者）
     * @param epochA        冻结的主体A研究授权代次
     * @param epochB        冻结的主体B研究授权代次
     * @param version       当前提案版本，从 1 开始
     * @param revisionCount 已完成内容修订次数，最多 3 次
     * @param payload       当前提案内容
     * @param confirmA      主体A是否已确认当前提案版本
     * @param confirmB      主体B是否已确认当前提案版本
     * @param status        状态：PENDING 待双确认 / CONFIRMED 已双确认生效
     */
    public record JointRecordRow(String recordKey, String subjectA, String subjectB,
                                 int epochA, int epochB, int version, int revisionCount,
                                 String payload, boolean confirmA, boolean confirmB,
                                 JointStatus status) {
    }

    Optional<JointRecordRow> find(String recordKey) {
        List<JointRecordRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM joint_record WHERE record_key = ?",
                MAPPER, recordKey);
        return rows.stream().findFirst();
    }

    void insert(String recordKey, String subjectA, String subjectB, int epochA, int epochB,
                String payload, String requestId) {
        jdbc.update(
                "INSERT INTO joint_record (record_key, subject_a, subject_b, epoch_a, epoch_b,"
                        + " version, revision_count, payload, confirm_a, confirm_b, status, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, 1, 0, ?, FALSE, FALSE, 'PENDING', ?)",
                recordKey, subjectA, subjectB, epochA, epochB, payload, requestId);
    }

    /**
     * 仅更新确认者本人一侧的确认标记，且仅当版本仍匹配；返回是否实际更新。
     */
    boolean confirm(String recordKey, int expectedVersion, boolean isSubjectA) {
        String column = isSubjectA ? "confirm_a" : "confirm_b";
        int updated = jdbc.update(
                "UPDATE joint_record SET " + column + " = TRUE, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND version = ?",
                recordKey, expectedVersion);
        return updated > 0;
    }

    /**
     * 双方确认标记齐全时将提案置为已生效（一次性冻结），返回是否发生状态跃迁。
     */
    boolean promoteIfConfirmed(String recordKey) {
        int updated = jdbc.update(
                "UPDATE joint_record SET status = 'CONFIRMED', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND confirm_a AND confirm_b AND status = 'PENDING'",
                recordKey);
        return updated > 0;
    }

    /**
     * 内容修订：版本加一、替换内容并清空双方确认；版本不匹配或已达修订上限时不更新。
     */
    boolean revise(String recordKey, int expectedVersion, String payload, int maxRevisions) {
        int updated = jdbc.update(
                "UPDATE joint_record SET version = version + 1, revision_count = revision_count + 1,"
                        + " payload = ?, confirm_a = FALSE, confirm_b = FALSE, status = 'PENDING',"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND version = ? AND revision_count < ?",
                payload, recordKey, expectedVersion, maxRevisions);
        return updated > 0;
    }
}
