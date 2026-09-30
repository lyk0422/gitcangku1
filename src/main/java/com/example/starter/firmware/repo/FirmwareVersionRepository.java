package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.domain.ProfileCompatibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.Set;

/**
 * 固件版本兼容声明数据访问。版本名全局唯一。
 */
@Repository
public class FirmwareVersionRepository {

    private static final RowMapper<FirmwareVersion> MAPPER = (rs, rowNum) -> new FirmwareVersion(
            rs.getLong("id"), rs.getString("name"),
            ProfileCompatibility.split(rs.getString("models")),
            rs.getInt("min_memory_mb"),
            ProfileCompatibility.split(rs.getString("required_tags")));

    private static final String COLUMNS = "id, name, models, min_memory_mb, required_tags";

    private final JdbcTemplate jdbc;

    public FirmwareVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, Set<String> models, int minMemoryMb, Set<String> requiredTags) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firmware_version (name, models, min_memory_mb, required_tags)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, ProfileCompatibility.join(models));
            ps.setInt(3, minMemoryMb);
            ps.setString(4, ProfileCompatibility.join(requiredTags));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FirmwareVersion> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
}
