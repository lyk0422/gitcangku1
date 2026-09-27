package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布单目标设备快照数据访问。快照仅在发布单创建时写入一次（同事务），之后只读不改，
 * 标签修订与设备标签变更不会增删快照成员。明细查询按 ordinal（device_id 字典序）稳定排序。
 */
@Repository
public class ReleaseTargetRepository {

    private final JdbcTemplate jdbc;

    public ReleaseTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 事务内一次性写入快照；deviceIds 必须已按字典序排序，ordinal 从 0 开始。
     */
    public void insertSnapshot(long releaseId, List<String> orderedDeviceIds) {
        int ordinal = 0;
        for (String deviceId : orderedDeviceIds) {
            jdbc.update("INSERT INTO release_target (release_id, device_id, ordinal) VALUES (?, ?, ?)",
                    releaseId, deviceId, ordinal++);
        }
    }

    public boolean isMember(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count != null && count > 0;
    }

    /**
     * 快照成员，按 ordinal 升序（即 device_id 字典序）。
     */
    public List<String> findMembers(long releaseId) {
        return jdbc.queryForList(
                "SELECT device_id FROM release_target WHERE release_id = ? ORDER BY ordinal",
                String.class, releaseId);
    }

    public long countMembers(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0L : count;
    }
}
