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
 * 库位表访问。库存版本变更（放入/迁出）与停用通过行锁或条件更新保证并发按提交顺序生效。
 */
@Repository
public class StorageLocationRepository {

    private static final LocationRowMapper ROW_MAPPER = new LocationRowMapper();

    private final JdbcTemplate jdbc;

    public StorageLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建库位，初始状态 ACTIVE、库存版本 0。
     */
    public void insert(String locationCode, String label, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO storage_location
                            (location_code, label, status, version, created_at, updated_at)
                        VALUES (?, ?, ?, 0, ?, ?)
                        """,
                locationCode, label, LocationStatus.ACTIVE.name(), now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<StorageLocation> findByCode(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ?", ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定库位行（SELECT ... FOR UPDATE），用于停用与迁移执行时的状态校验。
     */
    public Optional<StorageLocation> findByCodeForUpdate(String locationCode) {
        List<StorageLocation> rows = jdbc.query(
                "SELECT * FROM storage_location WHERE location_code = ? FOR UPDATE",
                ROW_MAPPER, locationCode);
        return rows.stream().findFirst();
    }

    /**
     * 停用库位；仅当仍为 ACTIVE 时生效。
     *
     * @return 是否成功停用（false 表示已被并发事务先行停用）
     */
    public boolean disable(String locationCode, LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE storage_location SET status = ?, updated_at = ? WHERE location_code = ? AND status = ?",
                LocationStatus.DISABLED.name(), now, locationCode, LocationStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 库存版本 +1（证物放入或迁出该库位时调用）。
     */
    public void bumpVersion(String locationCode, LocalDateTime now) {
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
                    rs.getString("label"),
                    LocationStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
