package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ChannelConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 渠道总量频控配置数据访问。修改采用版本号乐观锁：
 * UPDATE ... WHERE channel_key = ? AND version = ?，冲突由服务层映射为 409。
 */
@Repository
public class ChannelConfigRepository {

    private final JdbcTemplate jdbc;

    public ChannelConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChannelConfig> MAPPER = (rs, rowNum) -> new ChannelConfig(
            rs.getString("channel_key"),
            rs.getInt("daily_total_cap"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"),
            rs.getLong("updated_at_utc"));

    private static final String COLUMNS =
            "channel_key, daily_total_cap, version, created_at_utc, updated_at_utc";

    /** 按编号查询渠道配置；未配置返回 empty（申请时不受渠道规则限制）。 */
    public Optional<ChannelConfig> findById(String channelKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_config WHERE channel_key = ?",
                        MAPPER, channelKey)
                .stream()
                .findFirst();
    }

    /** 行锁读取渠道配置。 */
    public Optional<ChannelConfig> lockById(String channelKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_config WHERE channel_key = ? FOR UPDATE",
                        MAPPER, channelKey)
                .stream()
                .findFirst();
    }

    /** 插入渠道配置，初始版本为 0。 */
    public void insert(ChannelConfig config) {
        jdbc.update("INSERT INTO channel_config "
                        + "(channel_key, daily_total_cap, version, created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                config.channelKey(),
                config.dailyTotalCap(),
                config.version(),
                config.createdAtUtc(),
                config.updatedAtUtc());
    }

    /**
     * 乐观锁修改渠道日额度：仅当版本号等于 expectedVersion 时生效并把版本 +1。
     *
     * @return 是否更新成功；false 表示版本冲突（409）
     */
    public boolean compareAndUpdateCap(String channelKey, int newDailyTotalCap,
                                       int expectedVersion, long updatedAtUtc) {
        int rows = jdbc.update("UPDATE channel_config SET daily_total_cap = ?, version = version + 1, "
                        + "updated_at_utc = ? WHERE channel_key = ? AND version = ?",
                newDailyTotalCap, updatedAtUtc, channelKey, expectedVersion);
        return rows == 1;
    }
}
