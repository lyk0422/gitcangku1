package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CanaryPromotion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 金丝雀推进历史数据访问，只增不改。
 */
@Repository
public class CanaryPromotionRepository {

    private static final RowMapper<CanaryPromotion> MAPPER = (rs, rowNum) -> {
        Integer toLevel = rs.getObject("to_level", Integer.class);
        return new CanaryPromotion(rs.getLong("id"), rs.getLong("release_id"), rs.getInt("from_level"),
                toLevel, rs.getString("action"), rs.getInt("sample_count"), rs.getInt("failed_count"),
                rs.getString("promote_key"));
    };

    private static final String COLUMNS = "id, release_id, from_level, to_level, action, sample_count,"
            + " failed_count, promote_key";

    private final JdbcTemplate jdbc;

    public CanaryPromotionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int fromLevel, Integer toLevel, String action,
                       int sampleCount, int failedCount, String promoteKey) {
        jdbc.update("INSERT INTO canary_promotion (release_id, from_level, to_level, action, sample_count,"
                        + " failed_count, promote_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                releaseId, fromLevel, toLevel, action, sampleCount, failedCount, promoteKey);
    }

    public List<CanaryPromotion> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM canary_promotion WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }
}
