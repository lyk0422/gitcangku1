package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseWave;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 拓扑波次数据访问。波次序号在同一发布单内唯一，领取成功即冻结成员集合。
 */
@Repository
public class WaveRepository {

    private static final RowMapper<ReleaseWave> MAPPER = (rs, rowNum) -> new ReleaseWave(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("wave_seq"), rs.getInt("member_count"));

    private final JdbcTemplate jdbc;

    public WaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, int waveSeq, int memberCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_wave (release_id, wave_seq, member_count) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setInt(2, waveSeq);
            ps.setInt(3, memberCount);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertMember(long waveId, String deviceId) {
        jdbc.update("INSERT INTO release_wave_member (wave_id, device_id) VALUES (?, ?)",
                waveId, deviceId);
    }

    /**
     * 下一波次序号；调用方必须已持有发布单行锁，保证并发领取串行编号。
     */
    public int nextWaveSeq(long releaseId) {
        Integer max = jdbc.queryForObject("SELECT MAX(wave_seq) FROM release_wave WHERE release_id = ?",
                Integer.class, releaseId);
        return max == null ? 1 : max + 1;
    }

    public List<ReleaseWave> findByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, wave_seq, member_count FROM release_wave"
                + " WHERE release_id = ? ORDER BY wave_seq", MAPPER, releaseId);
    }

    public List<String> findMembers(long waveId) {
        return jdbc.query("SELECT device_id FROM release_wave_member WHERE wave_id = ? ORDER BY device_id",
                (rs, rowNum) -> rs.getString("device_id"), waveId);
    }
}
