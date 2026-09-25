package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 坐标基准版本持久化：coordinate_frame 登记公开的固定偏移参数，只增不改。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class CoordinateFrameRepository {

    private static final RowMapper<CoordinateFrame> FRAME_MAPPER = (rs, rowNum) -> new CoordinateFrame(
            rs.getString("frame_version"),
            rs.getDouble("offset_lat_deg"),
            rs.getDouble("offset_lon_deg"));

    private final JdbcTemplate jdbcTemplate;

    public CoordinateFrameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按版本标识查询坐标基准；不存在时返回空。
     */
    public Optional<CoordinateFrame> find(String frameVersion) {
        return jdbcTemplate.query(
                        "SELECT frame_version, offset_lat_deg, offset_lon_deg "
                                + "FROM coordinate_frame WHERE frame_version = ?",
                        FRAME_MAPPER, frameVersion)
                .stream().findFirst();
    }

    /**
     * 登记新坐标基准版本；同版本重复登记由主键约束拒绝。
     */
    public void insert(CoordinateFrame frame) {
        jdbcTemplate.update(
                "INSERT INTO coordinate_frame (frame_version, offset_lat_deg, offset_lon_deg, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                frame.frameVersion(), frame.offsetLatDeg(), frame.offsetLonDeg());
    }
}
