package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ChannelCapConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 渠道日总量频控配置数据访问。version 为乐观版本号：
 * 修改必须以 expectedVersion 条件更新，冲突由服务层映射为 409。
 */
@Repository
public class ChannelCapConfigRepository {

    private static final RowMapper<ChannelCapConfig> MAPPER = (rs, rowNum) -> new ChannelCapConfig(
            rs.getString("channel_key"),
            rs.getInt("daily_cap"),
            rs.getLong("version"),
            rs.getLong("created_at_utc"),
            rs.getLong("updated_at_utc"));

    private static final String COLUMNS =
            "channel_key, daily_cap, version, created_at_utc, updated_at_utc";

    private final JdbcTemplate jdbc;

    public ChannelCapConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按渠道编号查询配置；未配置返回 empty（不受渠道频控限制）。 */
    public Optional<ChannelCapConfig> findByKey(String channelKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_cap_config WHERE channel_key = ?",
                        MAPPER, channelKey)
                .stream()
                .findFirst();
    }

    /** 行锁读取配置；写路径使用，必须在事务内调用。 */
    public Optional<ChannelCapConfig> lockByKey(String channelKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_cap_config WHERE channel_key = ? FOR UPDATE",
                        MAPPER, channelKey)
                .stream()
                .findFirst();
    }

    /** 插入配置，初始版本为 1；渠道编号冲突由调用方依据唯一约束处理。 */
    public void insert(String channelKey, int dailyCap, long nowUtc) {
        jdbc.update("INSERT INTO channel_cap_config (" + COLUMNS + ") VALUES (?, ?, 1, ?, ?)",
                channelKey, dailyCap, nowUtc, nowUtc);
    }

    /**
     * 条件更新额度并递增版本：仅当当前版本等于 expectedVersion 时生效。
     *
     * @return 是否更新成功；false 表示版本冲突
     */
    public boolean compareAndSetCap(String channelKey, int dailyCap,
                                    long expectedVersion, long nowUtc) {
        int rows = jdbc.update("UPDATE channel_cap_config SET daily_cap = ?, version = version + 1, "
                        + "updated_at_utc = ? WHERE channel_key = ? AND version = ?",
                dailyCap, nowUtc, channelKey, expectedVersion);
        return rows == 1;
    }
}
