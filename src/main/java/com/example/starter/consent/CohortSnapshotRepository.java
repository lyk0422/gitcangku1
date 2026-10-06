package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 群体汇总快照持久化访问：汇总成功时与业务、幂等记录同事务保存完整依据，
 * 仅供服务端核验与重放校验，不对外返回逐主体值。
 */
@Repository
public class CohortSnapshotRepository {

    private static final RowMapper<SnapshotRow> MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("request_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("member_count"),
            rs.getLong("total"),
            rs.getString("members_json"));

    private final JdbcTemplate jdbc;

    public CohortSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 群体汇总快照行。
     *
     * @param requestId   幂等请求标识
     * @param purpose     用途，仅 RESEARCH 研究
     * @param memberCount 群体人数（3-10）
     * @param total       群体数值总和
     * @param membersJson 完整汇总依据（每主体 subjectKey/epoch/recordKey/value，JSON），仅服务端留存
     */
    public record SnapshotRow(String requestId, Purpose purpose, int memberCount, long total, String membersJson) {
    }

    Optional<SnapshotRow> find(String requestId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT request_id, purpose, member_count, total, members_json"
                        + " FROM cohort_aggregate_snapshot WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    void insert(String requestId, Purpose purpose, int memberCount, long total, String membersJson) {
        jdbc.update(
                "INSERT INTO cohort_aggregate_snapshot (request_id, purpose, member_count, total, members_json)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, purpose.name(), memberCount, total, membersJson);
    }
}
