package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CanaryLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 金丝雀分级级别数据访问。样本计数与推进均在发布单行锁保护下串行，无需额外锁。
 */
@Repository
public class CanaryLevelRepository {

    private static final RowMapper<CanaryLevel> MAPPER = (rs, rowNum) -> new CanaryLevel(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("level_no"),
            rs.getInt("ratio"), rs.getInt("min_samples"), rs.getInt("max_failure_rate"),
            rs.getInt("sample_count"), rs.getInt("failed_count"));

    private static final String COLUMNS = "id, release_id, level_no, ratio, min_samples, max_failure_rate,"
            + " sample_count, failed_count";

    private final JdbcTemplate jdbc;

    public CanaryLevelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int levelNo, int ratio, int minSamples, int maxFailureRate) {
        jdbc.update("INSERT INTO canary_level (release_id, level_no, ratio, min_samples, max_failure_rate)"
                + " VALUES (?, ?, ?, ?, ?)", releaseId, levelNo, ratio, minSamples, maxFailureRate);
    }

    public List<CanaryLevel> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM canary_level WHERE release_id = ? ORDER BY level_no",
                MAPPER, releaseId);
    }

    public Optional<CanaryLevel> findByReleaseAndLevel(long releaseId, int levelNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM canary_level WHERE release_id = ? AND level_no = ?",
                MAPPER, releaseId, levelNo).stream().findFirst();
    }

    /**
     * 首次终结回执计入当前解锁级别样本。
     */
    public void recordSample(long releaseId, int levelNo, boolean failed) {
        jdbc.update("UPDATE canary_level SET sample_count = sample_count + 1,"
                        + " failed_count = failed_count + ? WHERE release_id = ? AND level_no = ?",
                failed ? 1 : 0, releaseId, levelNo);
    }
}
