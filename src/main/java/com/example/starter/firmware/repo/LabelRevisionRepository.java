package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.LabelRevision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 标签字典修订事件数据访问。历史只增不改；诊断查询按修订时刻 UTC 做左闭右开区间过滤。
 */
@Repository
public class LabelRevisionRepository {

    private static final RowMapper<LabelRevision> MAPPER = (rs, rowNum) -> new LabelRevision(
            rs.getLong("id"), rs.getString("event_type"), rs.getString("operator"),
            rs.getInt("device_count"), rs.getInt("new_tag_count"), rs.getLong("dict_version"),
            rs.getString("revised_at_utc"));

    private static final String COLUMNS = "id, event_type, operator, device_count, new_tag_count,"
            + " dict_version, revised_at_utc";

    public static final String EVENT_DEVICE_REGISTER = "DEVICE_REGISTER";
    public static final String EVENT_LABEL_BATCH_UPDATE = "LABEL_BATCH_UPDATE";

    private final JdbcTemplate jdbc;

    public LabelRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String eventType, String operator, int deviceCount, int newTagCount,
                       long dictVersion, String revisedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO label_revision (event_type, operator, device_count, new_tag_count,"
                            + " dict_version, revised_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, eventType);
            ps.setString(2, operator);
            ps.setInt(3, deviceCount);
            ps.setInt(4, newTagCount);
            ps.setLong(5, dictVersion);
            ps.setString(6, revisedAtUtc);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public List<LabelRevision> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM label_revision ORDER BY id", MAPPER);
    }

    /**
     * 按修订时刻过滤：fromUtc 为 null 不限起点，toUtc 为 null 不限终点；区间左闭右开 [from, to)。
     * 边界时刻以 ISO-8601 UTC 字符串字典序与 Instant 序一致。
     */
    public List<LabelRevision> findBetweenUtc(String fromUtc, String toUtc) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM label_revision WHERE 1 = 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (fromUtc != null) {
            sql.append(" AND revised_at_utc >= ?");
            args.add(fromUtc);
        }
        if (toUtc != null) {
            sql.append(" AND revised_at_utc < ?");
            args.add(toUtc);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
