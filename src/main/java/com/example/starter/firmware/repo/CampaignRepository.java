package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Campaign;
import com.example.starter.firmware.domain.CampaignStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 投放活动数据访问。入组、回执、恢复、结束与迁移共用活动行锁（SELECT ... FOR UPDATE）
 * 形成一致提交顺序，统计与设备归属只在持有活动行锁的事务内变更。
 */
@Repository
public class CampaignRepository {

    private static final RowMapper<Campaign> MAPPER = (rs, rowNum) -> new Campaign(
            rs.getLong("id"), rs.getString("name"), CampaignStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, name, status";

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO campaign (name, status) VALUES (?, 'ACTIVE')",
                    new String[]{"id"});
            ps.setString(1, name);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Campaign> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Campaign> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 结束活动：仅当仍为 ACTIVE 时生效，返回影响行数。
     */
    public int endIfActive(long id) {
        return jdbc.update("UPDATE campaign SET status = 'ENDED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", id);
    }
}
