package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RegionLimit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发布单区域带宽限流上限配置数据访问。与发布单版本变更在同一事务提交。
 */
@Repository
public class RegionLimitRepository {

    private static final RowMapper<RegionLimit> MAPPER = (rs, rowNum) -> new RegionLimit(
            rs.getLong("release_id"), rs.getString("region"), rs.getInt("max_in_flight"));

    private final JdbcTemplate jdbc;

    public RegionLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findLimit(long releaseId, String region) {
        return jdbc.query("SELECT max_in_flight FROM release_region_limit"
                        + " WHERE release_id = ? AND region = ?",
                (rs, rowNum) -> rs.getInt("max_in_flight"), releaseId, region)
                .stream().findFirst();
    }

    public List<RegionLimit> findByRelease(long releaseId) {
        return jdbc.query("SELECT release_id, region, max_in_flight FROM release_region_limit"
                + " WHERE release_id = ? ORDER BY region", MAPPER, releaseId);
    }

    /**
     * 全量替换发布单的区域上限配置。
     */
    public void replaceAll(long releaseId, List<RegionLimit> limits) {
        jdbc.update("DELETE FROM release_region_limit WHERE release_id = ?", releaseId);
        for (RegionLimit limit : limits) {
            jdbc.update("INSERT INTO release_region_limit (release_id, region, max_in_flight)"
                    + " VALUES (?, ?, ?)", releaseId, limit.region(), limit.maxInFlight());
        }
    }
}
