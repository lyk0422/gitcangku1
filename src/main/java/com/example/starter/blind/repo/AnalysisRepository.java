package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 置换分析冻结记录数据访问；记录一旦写入不可改，basis_json 仅库内留存，禁止透出。
 */
@Repository
public class AnalysisRepository {

    /** 分析记录行。participantIds 已按字典序排序逗号连接；observedNum 为观察统计量分子。 */
    public record AnalysisRow(
            String id,
            String experimentId,
            int resultVersion,
            String participantIds,
            int groupACount,
            int groupBCount,
            long k,
            long n,
            long observedNum,
            String basisJson,
            String actorId,
            long createdAt) {
    }

    private final JdbcTemplate jdbc;

    public AnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnalysisRow row) {
        jdbc.update("INSERT INTO analysis ("
                        + "id, experiment_id, result_version, participant_ids, group_a_count, group_b_count, "
                        + "k, n, observed_num, basis_json, actor_id, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.experimentId(), row.resultVersion(), row.participantIds(),
                row.groupACount(), row.groupBCount(), row.k(), row.n(), row.observedNum(),
                row.basisJson(), row.actorId(), row.createdAt());
    }

    public long countByExperiment(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }
}
