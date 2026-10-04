package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 盲态样本链数据访问；只保存摘要与采集 UTC 时刻，不保存真实样本。
 */
@Repository
public class SampleRepository {

    /** 样本记录行。status 取值 ACTIVE/SUPERSEDED；时间均为 Unix 毫秒 UTC。 */
    public record SampleRow(
            long id,
            String experimentId,
            String participantId,
            String phase,
            String sampleNo,
            String digest,
            long collectedAt,
            Long supersedesId,
            String status,
            String actor,
            long createdAt) {
    }

    /** 配对锁定行（含冻结摘要）。 */
    public record PairLockRow(
            long id,
            String experimentId,
            String participantId,
            String pairCode,
            long preSampleId,
            long postSampleId,
            String preDigest,
            String postDigest,
            long preCollectedAt,
            long postCollectedAt,
            int version,
            String lockedActor,
            long lockedAt) {
    }

    private static final String SAMPLE_COLUMNS =
            "id, experiment_id, participant_id, phase, sample_no, digest, collected_at, "
                    + "supersedes_id, status, actor, created_at";

    private static final String LOCK_COLUMNS =
            "id, experiment_id, participant_id, pair_code, pre_sample_id, post_sample_id, "
                    + "pre_digest, post_digest, pre_collected_at, post_collected_at, "
                    + "version, locked_actor, locked_at";

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("phase"),
            rs.getString("sample_no"),
            rs.getString("digest"),
            rs.getLong("collected_at"),
            (Long) rs.getObject("supersedes_id"),
            rs.getString("status"),
            rs.getString("actor"),
            rs.getLong("created_at"));

    private static final RowMapper<PairLockRow> LOCK_MAPPER = (rs, n) -> new PairLockRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("pair_code"),
            rs.getLong("pre_sample_id"),
            rs.getLong("post_sample_id"),
            rs.getString("pre_digest"),
            rs.getString("post_digest"),
            rs.getLong("pre_collected_at"),
            rs.getLong("post_collected_at"),
            rs.getInt("version"),
            rs.getString("locked_actor"),
            rs.getLong("locked_at"));

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSample(SampleRow row) {
        jdbc.update("INSERT INTO sample_record ("
                        + "experiment_id, participant_id, phase, sample_no, digest, collected_at, "
                        + "supersedes_id, status, actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.phase(), row.sampleNo(), row.digest(),
                row.collectedAt(), row.supersedesId(), row.status(), row.actor(), row.createdAt());
    }

    /** 当前有效样本；无则返回 null。 */
    public SampleRow findActiveSample(String experimentId, String participantId, String phase) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + SAMPLE_COLUMNS + " FROM sample_record "
                        + "WHERE experiment_id = ? AND participant_id = ? AND phase = ? "
                        + "AND status = 'ACTIVE'",
                SAMPLE_MAPPER, experimentId, participantId, phase);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 更正时把最近有效记录置为被替代，返回受影响行数。 */
    public int markSuperseded(long sampleId) {
        return jdbc.update(
                "UPDATE sample_record SET status = 'SUPERSEDED' WHERE id = ? AND status = 'ACTIVE'",
                sampleId);
    }

    /** 参与者样本链全部记录（含被替代），按登记时间升序。 */
    public List<SampleRow> findChain(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + SAMPLE_COLUMNS + " FROM sample_record "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY id",
                SAMPLE_MAPPER, experimentId, participantId);
    }

    /** 当前链版本号：记录总数，每次登记/更正递增。 */
    public int chainVersion(String experimentId, String participantId) {
        Integer version = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = ? AND participant_id = ?",
                Integer.class, experimentId, participantId);
        return version == null ? 0 : version;
    }

    public void insertLock(PairLockRow row) {
        jdbc.update("INSERT INTO sample_pair_lock ("
                        + "experiment_id, participant_id, pair_code, pre_sample_id, post_sample_id, "
                        + "pre_digest, post_digest, pre_collected_at, post_collected_at, "
                        + "version, locked_actor, locked_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.pairCode(), row.preSampleId(),
                row.postSampleId(), row.preDigest(), row.postDigest(), row.preCollectedAt(),
                row.postCollectedAt(), row.version(), row.lockedActor(), row.lockedAt());
    }

    public PairLockRow findLock(String experimentId, String participantId) {
        List<PairLockRow> rows = jdbc.query(
                "SELECT " + LOCK_COLUMNS + " FROM sample_pair_lock "
                        + "WHERE experiment_id = ? AND participant_id = ?",
                LOCK_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实验内全部已锁定配对，供分析人匿名查询。 */
    public List<PairLockRow> findLocksByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + LOCK_COLUMNS + " FROM sample_pair_lock "
                        + "WHERE experiment_id = ? ORDER BY id",
                LOCK_MAPPER, experimentId);
    }

    public boolean isDuplicateSampleNo(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_no");
    }

    public boolean isDuplicatePairLock(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pair_lock_participant");
    }

    public boolean isDuplicatePairCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pair_code");
    }
}