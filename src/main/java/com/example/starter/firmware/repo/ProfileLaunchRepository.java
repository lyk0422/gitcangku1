package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ProfileLaunch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 画像投放单数据访问。request_id 全局唯一，同键重放首次目标清单。
 */
@Repository
public class ProfileLaunchRepository {

    private final JdbcTemplate jdbc;

    public ProfileLaunchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, long versionId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO profile_launch (request_id, version_id) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, versionId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ProfileLaunch> findById(long id) {
        return jdbc.query("SELECT id, request_id, version_id FROM profile_launch WHERE id = ?",
                (rs, rowNum) -> new ProfileLaunch(rs.getLong("id"), rs.getString("request_id"),
                        rs.getLong("version_id")),
                id).stream().findFirst();
    }
}
