package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库位表访问。location_code 全局唯一；停用只允许 ACTIVE → DISABLED 一次条件更新。
 */
@Repository
public class StorageLocationRepository {

    private static final LocationRowMapper ROW_MAPPER = new LocationRowMapper();

    private final JdbcTemplate jdbc;

    public StorageLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建库位，初始状态 ACTIVE；location_code 冲突由唯一约束拒绝。
     */
    public void insert(String locationCode, String locationName, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO storage_location
                            (location_code, location_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                locationCode, locationName, LocationStatus.ACTIVE.name(), now, now);
    }

    /**
     * 按编码查询（不加锁）。
     */
    public Optional<StorageLocation> findByCode(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ?", ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 按编码查询并锁定库位行（SELECT ... FOR UPDATE），用于停用与迁移目标校验的并发裁决。
     */
    public Optional<StorageLocation> findByCodeForUpdate(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ? FOR UPDATE",
                ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 停用库位：仅当仍为 ACTIVE 时生效。
     *
     * @return 是否成功停用（false 表示已停用或不存在）
     */
    public boolean disable(String locationCode, LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE storage_location SET status = ?, updated_at = ? WHERE location_code = ? AND status = ?",
                LocationStatus.DISABLED.name(), now, locationCode, LocationStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 查询全部库位（按编码排序）。
     */
    public List<StorageLocation> findAll() {
        return jdbc.query("SELECT * FROM storage_location ORDER BY location_code", ROW_MAPPER);
    }

    private static final class LocationRowMapper implements RowMapper<StorageLocation> {
        @Override
        public StorageLocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StorageLocation(
                    rs.getLong("id"),
                    rs.getString("location_code"),
                    rs.getString("location_name"),
                    LocationStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
