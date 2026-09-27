package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TargetPolicy;
import com.example.starter.firmware.domain.TargetPolicyRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发布单目标标签策略与目标设备快照数据访问。策略与快照仅在发布单创建时写入一次，后续不改。
 */
@Repository
public class TargetPolicyRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TargetPolicyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertPolicy(long releaseId, TargetPolicy policy, long tagDictVersion,
                             String resolvedAtUtc, int targetCount) {
        jdbc.update("INSERT INTO release_target_policy"
                        + " (release_id, include_all, exclude_any, include_any, tag_dict_version,"
                        + " resolved_at_utc, target_count) VALUES (?, ?, ?, ?, ?, ?, ?)",
                releaseId, writeJson(policy.includeAll()), writeJson(policy.excludeAny()),
                writeJson(policy.includeAny()), tagDictVersion, resolvedAtUtc, targetCount);
    }

    public void insertSnapshot(long releaseId, List<String> sortedDeviceIds) {
        for (int seq = 0; seq < sortedDeviceIds.size(); seq++) {
            jdbc.update("INSERT INTO release_target_snapshot (release_id, seq, device_id) VALUES (?, ?, ?)",
                    releaseId, seq, sortedDeviceIds.get(seq));
        }
    }

    public Optional<TargetPolicyRecord> findPolicy(long releaseId) {
        return jdbc.query("SELECT release_id, include_all, exclude_any, include_any, tag_dict_version,"
                        + " resolved_at_utc, target_count FROM release_target_policy WHERE release_id = ?",
                (rs, rowNum) -> new TargetPolicyRecord(rs.getLong("release_id"),
                        new TargetPolicy(readJson(rs.getString("include_all")),
                                readJson(rs.getString("exclude_any")),
                                readJson(rs.getString("include_any"))),
                        rs.getLong("tag_dict_version"), rs.getString("resolved_at_utc"),
                        rs.getInt("target_count")),
                releaseId).stream().findFirst();
    }

    /**
     * 快照成员设备ID，按规范排序序号升序（即 device_id 升序）。
     */
    public List<String> findSnapshotDevices(long releaseId) {
        return jdbc.query("SELECT device_id FROM release_target_snapshot WHERE release_id = ? ORDER BY seq",
                (rs, rowNum) -> rs.getString("device_id"), releaseId);
    }

    /**
     * 拉取准入判断：无策略的发布单对所有设备开放；有策略的仅快照成员可拉取。
     */
    public boolean allows(long releaseId, String deviceId) {
        Long policies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target_policy WHERE release_id = ?", Long.class, releaseId);
        if (policies == null || policies == 0) {
            return true;
        }
        Long members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target_snapshot WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return members != null && members > 0;
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("目标策略序列化失败", e);
        }
    }

    private List<String> readJson(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("目标策略反序列化失败", e);
        }
    }
}
