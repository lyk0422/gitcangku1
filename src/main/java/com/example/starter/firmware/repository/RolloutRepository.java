package com.example.starter.firmware.repository;

import com.example.starter.firmware.dto.RolloutResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 灰度发布单数据访问。
 */
@Repository
public class RolloutRepository {

    private static final String COLUMNS = "id, model, from_version, to_version, ratio, status, version";

    private static final RowMapper<RolloutResponse> MAPPER = (rs, rowNum) -> new RolloutResponse(
            rs.getLong("id"),
            rs.getString("model"),
            rs.getString("from_version"),
            rs.getString("to_version"),
            rs.getInt("ratio"),
            rs.getString("status"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public RolloutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新发布单，初始版本号为 1、状态 ACTIVE；同型号 ACTIVE 唯一冲突时抛出 DuplicateKeyException。
     *
     * @return 自增主键
     */
    public long insert(String model, String fromVersion, String toVersion, int ratio) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout (model, from_version, to_version, ratio, status, version)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', 1)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, fromVersion);
            ps.setString(3, toVersion);
            ps.setInt(4, ratio);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（无锁）。
     */
    public Optional<RolloutResponse> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（SELECT ... FOR UPDATE），用于扩量/取消/拉取的串行化。
     */
    public Optional<RolloutResponse> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 更新投放比例并递增版本号。
     */
    public void updateRatio(long id, int ratio, int newVersion) {
        jdbc.update("UPDATE rollout SET ratio = ?, version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                ratio, newVersion, id);
    }

    /**
     * 取消发布单并递增版本号。
     */
    public void cancel(long id, int newVersion) {
        jdbc.update("UPDATE rollout SET status = 'CANCELLED', version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                newVersion, id);
    }
}
