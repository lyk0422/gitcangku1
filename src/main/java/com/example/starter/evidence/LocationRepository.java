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
 * 库位表访问。库存版本变更（入库/迁入/迁出）与停用必须先通过
 * {@link #findByCodeForUpdate} 锁定库位行，保证并发迁移/入库按事务提交顺序生效。
 */
@Repository
public class LocationRepository {

    private static final LocationRowMapper ROW_MAPPER = new LocationRowMapper();

    private final JdbcTemplate jdbc;

    public LocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建库位，初始状态 ACTIVE、库存版本 0。
     */
    public void insert(String locationCode, String description, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO storage_location
                            (location_code, status, version, description, created_at, updated_at)
                        VALUES (?, ?, 0, ?, ?, ?)
                        """,
                locationCode, LocationStatus.ACTIVE.name(), description, now, now);
    }

    /**
     * 按编码查询（不加锁），用于只读场景。
     */
    public Optional<StorageLocation> findByCode(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ?", ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 按编码查询并锁定库位行（SELECT ... FOR UPDATE），用于停用与库存版本变更。
     */
    public Optional<StorageLocation> findByCodeForUpdate(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ? FOR UPDATE",
                ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 停用库位；仅当前仍为 ACTIVE 时生效，返回是否更新成功。
     */
    public boolean disable(String locationCode, LocalDateTime now) {
        return jdbc.update(
                "UPDATE storage_location SET status = ?, updated_at = ? WHERE location_code = ? AND status = ?",
                LocationStatus.DISABLED.name(), now, locationCode, LocationStatus.ACTIVE.name()) == 1;
    }

    /**
     * 库存版本递增（入库/迁入/迁出时调用，调用前必须已锁定库位行）。
     */
    public void incrementVersion(String locationCode, LocalDateTime now) {
        jdbc.update(
                "UPDATE storage_location SET version = version + 1, updated_at = ? WHERE location_code = ?",
                now, locationCode);
    }

    private static final class LocationRowMapper implements RowMapper<StorageLocation> {
        @Override
        public StorageLocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StorageLocation(
                    rs.getLong("id"),
                    rs.getString("location_code"),
                    LocationStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getString("description"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
