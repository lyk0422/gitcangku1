package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.HardwareRequirement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 固件版本硬件兼容要求数据访问。要求与型号集合、标签集合在同一事务写入，只增不改。
 */
@Repository
public class HardwareRequirementRepository {

    private final JdbcTemplate jdbc;

    public HardwareRequirementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String firmwareVersion, Integer minMemoryMb, List<String> models, List<String> tags) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO hardware_requirement (firmware_version, min_memory_mb) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, firmwareVersion);
            if (minMemoryMb == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, minMemoryMb);
            }
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        for (String model : models) {
            jdbc.update("INSERT INTO hardware_requirement_model (requirement_id, model) VALUES (?, ?)", id, model);
        }
        for (String tag : tags) {
            jdbc.update("INSERT INTO hardware_requirement_tag (requirement_id, capability_tag) VALUES (?, ?)",
                    id, tag);
        }
        return id;
    }

    public Optional<HardwareRequirement> findByVersion(String firmwareVersion) {
        List<HardwareRequirement> heads = jdbc.query(
                "SELECT id, firmware_version, min_memory_mb FROM hardware_requirement"
                        + " WHERE firmware_version = ?",
                (rs, rowNum) -> new HardwareRequirement(rs.getLong("id"), rs.getString("firmware_version"),
                        (Integer) rs.getObject("min_memory_mb"), List.of(), List.of()),
                firmwareVersion);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        HardwareRequirement head = heads.get(0);
        List<String> models = jdbc.queryForList(
                "SELECT model FROM hardware_requirement_model WHERE requirement_id = ? ORDER BY model",
                String.class, head.id());
        List<String> tags = jdbc.queryForList(
                "SELECT capability_tag FROM hardware_requirement_tag WHERE requirement_id = ? ORDER BY capability_tag",
                String.class, head.id());
        return Optional.of(new HardwareRequirement(head.id(), head.firmwareVersion(), head.minMemoryMb(),
                List.copyOf(models), List.copyOf(tags)));
    }
}
