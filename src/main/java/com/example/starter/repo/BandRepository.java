package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 区域高度带数据访问。高度带为左闭右开区间（米），主键 (zone_id, band_lower)。
 */
@Repository
public class BandRepository {

    private final JdbcTemplate jdbc;

    public BandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final org.springframework.jdbc.core.RowMapper<BandPo> BAND_MAPPER =
            (rs, n) -> new BandPo(rs.getString("zone_id"), rs.getInt("band_lower"),
                    rs.getInt("band_upper"), rs.getInt("capacity"));

    /** 查询某区域全部高度带（按下限升序）。 */
    public List<BandPo> findBands(String zoneId) {
        return jdbc.query(
                "SELECT zone_id, band_lower, band_upper, capacity FROM altitude_band "
                        + "WHERE zone_id = ? ORDER BY band_lower",
                BAND_MAPPER, zoneId);
    }

    /**
     * 在当前事务内以 SELECT ... FOR UPDATE 锁定指定高度带行并读取之。
     *
     * @return 高度带；不存在返回 null
     */
    public BandPo findBandForUpdate(String zoneId, int bandLower) {
        try {
            return jdbc.queryForObject(
                    "SELECT zone_id, band_lower, band_upper, capacity FROM altitude_band "
                            + "WHERE zone_id = ? AND band_lower = ? FOR UPDATE",
                    BAND_MAPPER, zoneId, bandLower);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 新增高度带（调用方负责事务与不重叠/不退化校验）。 */
    public void insertBand(BandPo band) {
        jdbc.update("INSERT INTO altitude_band (zone_id, band_lower, band_upper, capacity) "
                        + "VALUES (?, ?, ?, ?)",
                band.zoneId(), band.bandLower(), band.bandUpper(), band.capacity());
    }

    /** 上调容量（调用方负责事务与“只能上调”校验）。 */
    public int updateCapacity(String zoneId, int bandLower, int newCapacity) {
        return jdbc.update(
                "UPDATE altitude_band SET capacity = ? WHERE zone_id = ? AND band_lower = ?",
                newCapacity, zoneId, bandLower);
    }
}
