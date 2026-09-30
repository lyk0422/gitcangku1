package com.example.starter.plan.repo;

import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformCapabilityHistory;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 站台当前能力与不可变能力版本历史的 JDBC 持久化。
 * 供电制式集合以逗号分隔的归一化字符串（去空白、去重、字典序）存储。
 */
@Repository
public class PlatformRepository {

    private static final RowMapper<Platform> PLATFORM_MAPPER = (rs, n) -> new Platform(
            rs.getString("platform_id"),
            rs.getString("platform_name"),
            rs.getInt("max_length_meters"),
            splitPowerSystems(rs.getString("power_systems")),
            rs.getBoolean("accessible"),
            rs.getInt("version"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<PlatformCapabilityHistory> HISTORY_MAPPER = (rs, n) ->
            new PlatformCapabilityHistory(
                    rs.getLong("id"),
                    rs.getString("platform_id"),
                    rs.getInt("version"),
                    rs.getInt("max_length_meters"),
                    splitPowerSystems(rs.getString("power_systems")),
                    rs.getBoolean("accessible"),
                    rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供电制式集合归一化：去空白、去重、字典序，逗号连接。 */
    public static String normalizePowerSystems(List<String> powerSystems) {
        return powerSystems.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static List<String> splitPowerSystems(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(stored.split(","));
    }

    /**
     * 按站台业务键查询当前能力（不加锁）。
     */
    public Optional<Platform> findById(String platformId) {
        return jdbc.query("SELECT platform_id, platform_name, max_length_meters, power_systems,"
                        + " accessible, version, created_at, updated_at FROM rail_platform"
                        + " WHERE platform_id = ?",
                PLATFORM_MAPPER, platformId).stream().findFirst();
    }

    /**
     * 按站台业务键查询当前能力并加行级写锁，须在事务内调用，
     * 用于串行化同一站台的能力登记与占用发布裁决。
     */
    public Optional<Platform> findByIdForUpdate(String platformId) {
        return jdbc.query("SELECT platform_id, platform_name, max_length_meters, power_systems,"
                        + " accessible, version, created_at, updated_at FROM rail_platform"
                        + " WHERE platform_id = ? FOR UPDATE",
                PLATFORM_MAPPER, platformId).stream().findFirst();
    }

    /**
     * 查询全部站台当前能力，按业务键升序，用于候选站台枚举。
     */
    public List<Platform> findAll() {
        return jdbc.query("SELECT platform_id, platform_name, max_length_meters, power_systems,"
                + " accessible, version, created_at, updated_at FROM rail_platform"
                + " ORDER BY platform_id", PLATFORM_MAPPER);
    }

    /**
     * 首次登记站台能力（版本 1），并写入首条不可变历史快照。
     */
    public Platform insert(String platformId, String platformName, int maxLengthMeters,
                           String normalizedPowerSystems, boolean accessible, long nowMillis) {
        jdbc.update("INSERT INTO rail_platform (platform_id, platform_name, max_length_meters,"
                        + " power_systems, accessible, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                platformId, platformName, maxLengthMeters, normalizedPowerSystems, accessible,
                nowMillis, nowMillis);
        jdbc.update("INSERT INTO rail_platform_capability_history (platform_id, version,"
                        + " max_length_meters, power_systems, accessible, created_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?)",
                platformId, maxLengthMeters, normalizedPowerSystems, accessible, nowMillis);
        return new Platform(platformId, platformName, maxLengthMeters,
                splitPowerSystems(normalizedPowerSystems), accessible, 1, nowMillis, nowMillis);
    }

    /**
     * 能力变更：当前表升级到新版本并追加不可变历史快照。调用方须先持行锁。
     */
    public Platform appendVersion(Platform current, String platformName, int maxLengthMeters,
                                  String normalizedPowerSystems, boolean accessible,
                                  long nowMillis) {
        int newVersion = current.version() + 1;
        jdbc.update("UPDATE rail_platform SET platform_name = ?, max_length_meters = ?,"
                        + " power_systems = ?, accessible = ?, version = ?, updated_at = ?"
                        + " WHERE platform_id = ?",
                platformName, maxLengthMeters, normalizedPowerSystems, accessible, newVersion,
                nowMillis, current.platformId());
        jdbc.update("INSERT INTO rail_platform_capability_history (platform_id, version,"
                        + " max_length_meters, power_systems, accessible, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                current.platformId(), newVersion, maxLengthMeters, normalizedPowerSystems,
                accessible, nowMillis);
        return new Platform(current.platformId(), platformName, maxLengthMeters,
                splitPowerSystems(normalizedPowerSystems), accessible, newVersion,
                current.createdAt(), nowMillis);
    }

    /**
     * 按版本查询站台能力历史快照；版本不存在返回空（历史发布保留的版本必能回溯）。
     */
    public Optional<PlatformCapabilityHistory> findHistoryVersion(String platformId, int version) {
        return jdbc.query("SELECT id, platform_id, version, max_length_meters, power_systems,"
                        + " accessible, created_at FROM rail_platform_capability_history"
                        + " WHERE platform_id = ? AND version = ?",
                HISTORY_MAPPER, platformId, version).stream().findFirst();
    }
}
