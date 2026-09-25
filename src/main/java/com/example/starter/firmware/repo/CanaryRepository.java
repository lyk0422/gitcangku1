package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CanaryLevel;
import com.example.starter.firmware.domain.CanaryPromotion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 金丝雀级别与推进历史数据访问。样本统计的读改写在发布单行锁保护下与推进串行化。
 */
@Repository
public class CanaryRepository {

    private static final RowMapper<CanaryLevel> LEVEL_MAPPER = (rs, rowNum) -> new CanaryLevel(
            rs.getLong("release_id"), rs.getInt("level_no"), rs.getInt("ratio"),
            rs.getInt("min_samples"), rs.getInt("max_failure_rate"),
            rs.getInt("sample_count"), rs.getInt("fail_count"), rs.getBoolean("unlocked"));

    private static final String LEVEL_COLUMNS =
            "release_id, level_no, ratio, min_samples, max_failure_rate, sample_count, fail_count, unlocked";

    private static final RowMapper<CanaryPromotion> PROMOTION_MAPPER = (rs, rowNum) -> new CanaryPromotion(
            rs.getLong("release_id"), rs.getInt("from_level"), rs.getInt("to_level"),
            rs.getString("promote_key"), rs.getInt("sample_count"), rs.getInt("fail_count"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final String PROMOTION_COLUMNS =
            "release_id, from_level, to_level, promote_key, sample_count, fail_count, created_at";

    private final JdbcTemplate jdbc;

    public CanaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建发布单的所有级别，初始仅第1级解锁。
     */
    public void insertLevels(long releaseId, List<LevelSpec> levels) {
        for (int i = 0; i < levels.size(); i++) {
            LevelSpec spec = levels.get(i);
            boolean unlocked = i == 0;
            jdbc.update("INSERT INTO canary_level (release_id, level_no, ratio, min_samples, max_failure_rate,"
                            + " unlocked, unlocked_at) VALUES (?, ?, ?, ?, ?, ?,"
                            + (unlocked ? " CURRENT_TIMESTAMP)" : " NULL)"),
                    releaseId, i + 1, spec.ratio(), spec.minSamples(), spec.maxFailureRate(), unlocked);
        }
    }

    public List<CanaryLevel> findLevels(long releaseId) {
        return jdbc.query("SELECT " + LEVEL_COLUMNS + " FROM canary_level WHERE release_id = ? ORDER BY level_no",
                LEVEL_MAPPER, releaseId);
    }

    public Optional<CanaryLevel> findLevel(long releaseId, int levelNo) {
        return jdbc.query("SELECT " + LEVEL_COLUMNS + " FROM canary_level WHERE release_id = ? AND level_no = ?",
                LEVEL_MAPPER, releaseId, levelNo).stream().findFirst();
    }

    /**
     * 设备回执首次进入终态时累计当前解锁级别样本；failed 为 true 时同时累计失败数。
     */
    public void incrementSample(long releaseId, int levelNo, boolean failed) {
        jdbc.update("UPDATE canary_level SET sample_count = sample_count + 1,"
                        + " fail_count = fail_count + ? WHERE release_id = ? AND level_no = ?",
                failed ? 1 : 0, releaseId, levelNo);
    }

    public void unlock(long releaseId, int levelNo) {
        jdbc.update("UPDATE canary_level SET unlocked = TRUE, unlocked_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND level_no = ?", releaseId, levelNo);
    }

    public void insertPromotion(long releaseId, int fromLevel, int toLevel, String promoteKey,
                                int sampleCount, int failCount) {
        jdbc.update("INSERT INTO canary_promotion (release_id, from_level, to_level, promote_key,"
                        + " sample_count, fail_count) VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, fromLevel, toLevel, promoteKey, sampleCount, failCount);
    }

    public List<CanaryPromotion> findPromotions(long releaseId) {
        return jdbc.query("SELECT " + PROMOTION_COLUMNS + " FROM canary_promotion WHERE release_id = ? ORDER BY id",
                PROMOTION_MAPPER, releaseId);
    }

    /**
     * 创建发布单时声明的级别参数。
     */
    public record LevelSpec(int ratio, int minSamples, int maxFailureRate) {
    }
}
