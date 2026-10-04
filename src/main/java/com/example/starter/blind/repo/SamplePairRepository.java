package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 样本配对锁定数据访问；锁定后冻结摘要，试验关闭不撤销已锁定对。
 * 同一参与者至多一个配对由唯一索引 uq_pair_participant 保证。
 */
@Repository
public class SamplePairRepository {

    /** 配对行（含参与者编号，仅供服务层内部使用，禁止透出给分析人）。 */
    public record SamplePairRow(
            long id,
            String pairCode,
            String experimentId,
            String participantId,
            long preSampleId,
            long postSampleId,
            String preDigest,
            String postDigest,
            long preCollectedAt,
            long postCollectedAt,
            String lockedActor,
            long lockedAt) {
    }

    private static final String COLUMNS =
            "id, pair_code, experiment_id, participant_id, pre_sample_id, post_sample_id, "
                    + "pre_digest, post_digest, pre_collected_at, post_collected_at, "
                    + "locked_actor, locked_at";

    private static final RowMapper<SamplePairRow> MAPPER = (rs, n) -> new SamplePairRow(
            rs.getLong("id"),
            rs.getString("pair_code"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("pre_sample_id"),
            rs.getLong("post_sample_id"),
            rs.getString("pre_digest"),
            rs.getString("post_digest"),
            rs.getLong("pre_collected_at"),
            rs.getLong("post_collected_at"),
            rs.getString("locked_actor"),
            rs.getLong("locked_at"));

    private final JdbcTemplate jdbc;

    public SamplePairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SamplePairRow row) {
        jdbc.update("INSERT INTO sample_pair ("
                        + "pair_code, experiment_id, participant_id, pre_sample_id, post_sample_id, "
                        + "pre_digest, post_digest, pre_collected_at, post_collected_at, "
                        + "locked_actor, locked_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.pairCode(), row.experimentId(), row.participantId(),
                row.preSampleId(), row.postSampleId(), row.preDigest(), row.postDigest(),
                row.preCollectedAt(), row.postCollectedAt(), row.lockedActor(), row.lockedAt());
    }

    public SamplePairRow findByParticipant(String experimentId, String participantId) {
        List<SamplePairRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM sample_pair "
                        + "WHERE experiment_id = ? AND participant_id = ?",
                MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 分析查询：只取配对码与两份冻结摘要，不读取参与者编号、盲码或处理代码。 */
    public List<AnonymousPair> findAnonymousByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT pair_code, pre_digest, post_digest FROM sample_pair "
                        + "WHERE experiment_id = ? ORDER BY id",
                (rs, n) -> new AnonymousPair(
                        rs.getString("pair_code"),
                        rs.getString("pre_digest"),
                        rs.getString("post_digest")),
                experimentId);
    }

    /** 匿名配对投影：只有配对码与两份冻结摘要。 */
    public record AnonymousPair(String pairCode, String preDigest, String postDigest) {
    }

    public long countByExperiment(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /** 配对码全局唯一冲突。 */
    public boolean isDuplicatePairCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pair_code");
    }

    /** 同参与者重复锁定冲突。 */
    public boolean isDuplicateParticipant(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pair_participant");
    }
}