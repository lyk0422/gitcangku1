package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 共同研究记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class JointRecordRepository {

    private static final String SELECT_COLUMNS =
            "SELECT record_key, subject_a, epoch_a, subject_b, epoch_b, payload,"
                    + " version, revision_count, confirm_a, confirm_b, status FROM joint_record";

    private static final RowMapper<JointRow> MAPPER = (rs, rowNum) -> new JointRow(
            rs.getString("record_key"),
            rs.getString("subject_a"),
            rs.getInt("epoch_a"),
            rs.getString("subject_b"),
            rs.getInt("epoch_b"),
            rs.getString("payload"),
            rs.getInt("version"),
            rs.getInt("revision_count"),
            rs.getString("confirm_a"),
            rs.getString("confirm_b"),
            JointStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public JointRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 共同记录行。
     *
     * @param recordKey     共同记录键
     * @param subjectA      主体A标识
     * @param epochA        主体A授权代次，创建时冻结，重新授权不替换
     * @param subjectB      主体B标识
     * @param epochB        主体B授权代次，创建时冻结，重新授权不替换
     * @param payload       当前提案内容
     * @param version       提案版本，从 1 开始
     * @param revisionCount 已修订次数，最多 3 次
     * @param confirmA      主体A对当前版本确认的操作者标识，未确认或修订后为 NULL
     * @param confirmB      主体B对当前版本确认的操作者标识，未确认或修订后为 NULL
     * @param status        状态：PROPOSED 待双确认 / FROZEN 当前版本已双确认冻结
     */
    public record JointRow(String recordKey, String subjectA, int epochA, String subjectB, int epochB,
                           String payload, int version, int revisionCount,
                           String confirmA, String confirmB, JointStatus status) {
    }

    Optional<JointRow> find(String recordKey) {
        List<JointRow> rows = jdbc.query(SELECT_COLUMNS + " WHERE record_key = ?", MAPPER, recordKey);
        return rows.stream().findFirst();
    }

    void insert(String recordKey, String subjectA, int epochA, String subjectB, int epochB,
                String payload, String requestId) {
        jdbc.update(
                "INSERT INTO joint_record (record_key, purpose, subject_a, epoch_a, subject_b, epoch_b,"
                        + " payload, version, revision_count, status, request_id)"
                        + " VALUES (?, 'RESEARCH', ?, ?, ?, ?, ?, 1, 0, 'PROPOSED', ?)",
                recordKey, subjectA, epochA, subjectB, epochB, payload, requestId);
    }

    /**
     * 记录一侧确认：仅当提案版本匹配且该侧尚未确认时生效，返回是否实际写入。
     */
    int confirmSide(String recordKey, boolean sideA, String actorId, int version) {
        String column = sideA ? "confirm_a" : "confirm_b";
        return jdbc.update(
                "UPDATE joint_record SET " + column + " = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND version = ? AND " + column + " IS NULL",
                actorId, recordKey, version);
    }

    /**
     * 两票齐时一次冻结：双方对当前版本确认齐全后把状态置为 FROZEN。
     */
    void freezeIfBothConfirmed(String recordKey) {
        jdbc.update(
                "UPDATE joint_record SET status = 'FROZEN', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND status = 'PROPOSED'"
                        + " AND confirm_a IS NOT NULL AND confirm_b IS NOT NULL",
                recordKey);
    }

    /**
     * 内容修订：版本加一并清空旧确认，仅当期望版本匹配且修订次数未超限时生效。
     */
    int revise(String recordKey, String payload, int expectedVersion, int maxRevisions) {
        return jdbc.update(
                "UPDATE joint_record SET payload = ?, version = version + 1,"
                        + " revision_count = revision_count + 1, confirm_a = NULL, confirm_b = NULL,"
                        + " status = 'PROPOSED', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE record_key = ? AND version = ? AND revision_count < ?",
                payload, recordKey, expectedVersion, maxRevisions);
    }
}
