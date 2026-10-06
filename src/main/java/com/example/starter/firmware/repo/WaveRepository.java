package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Wave;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 拓扑波次数据访问。领取成功即冻结成员集合；(release_id, wave_no) 唯一约束兜底并发领取。
 */
@Repository
public class WaveRepository {

    private static final RowMapper<Wave> MAPPER = (rs, rowNum) -> new Wave(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("wave_no"), rs.getInt("member_count"));

    private final JdbcTemplate jdbc;

    public WaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, int waveNo, int memberCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_wave (release_id, wave_no, member_count) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setInt(2, waveNo);
            ps.setInt(3, memberCount);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertMember(long waveId, String deviceId) {
        jdbc.update("INSERT INTO release_wave_member (wave_id, device_id) VALUES (?, ?)", waveId, deviceId);
    }

    public int countByRelease(long releaseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_wave WHERE release_id = ?",
                Integer.class, releaseId);
        return count == null ? 0 : count;
    }

    public List<Wave> findByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, wave_no, member_count FROM release_wave"
                + " WHERE release_id = ? ORDER BY wave_no", MAPPER, releaseId);
    }

    /**
     * 波次成员设备ID，按设备ID升序（冻结集合，重放不重选）。
     */
    public List<String> findMemberDeviceIds(long waveId) {
        return jdbc.query("SELECT device_id FROM release_wave_member WHERE wave_id = ? ORDER BY device_id",
                (rs, rowNum) -> rs.getString("device_id"), waveId);
    }
}
