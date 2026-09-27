package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 驻留区域登记持久化访问：区域代码必须先登记，授权 epoch 才能引用。
 */
@Repository
public class RegionRepository {

    private static final RowMapper<RegionRow> MAPPER = (rs, rowNum) -> new RegionRow(
            rs.getString("region_code"),
            rs.getString("region_name"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RegionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 驻留区域行。
     *
     * @param regionCode 区域代码
     * @param regionName 区域可读名称
     * @param createdAt  登记时间（UTC）
     */
    public record RegionRow(String regionCode, String regionName, Instant createdAt) {
    }

    Optional<RegionRow> find(String regionCode) {
        List<RegionRow> rows = jdbc.query(
                "SELECT region_code, region_name, created_at FROM residency_region WHERE region_code = ?",
                MAPPER, regionCode);
        return rows.stream().findFirst();
    }

    List<RegionRow> listAll() {
        return jdbc.query(
                "SELECT region_code, region_name, created_at FROM residency_region ORDER BY region_code ASC",
                MAPPER);
    }

    void insert(String regionCode, String regionName, String requestId) {
        jdbc.update(
                "INSERT INTO residency_region (region_code, region_name, request_id) VALUES (?, ?, ?)",
                regionCode, regionName, requestId);
    }
}
