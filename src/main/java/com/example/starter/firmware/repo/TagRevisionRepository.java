package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TagRevision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 标签字典修订历史数据访问，只增不改。诊断区间按 changed_at_utc 解析为时刻后做左闭右开 [from, to) 过滤。
 */
@Repository
public class TagRevisionRepository {

    private static final RowMapper<TagRevision> MAPPER = (rs, rowNum) -> new TagRevision(
            rs.getLong("revision"), rs.getString("operator"), rs.getInt("requested_count"),
            rs.getInt("changed_count"), rs.getString("changed_at_utc"));

    private static final String SELECT_ALL = "SELECT revision, operator, requested_count, changed_count,"
            + " changed_at_utc FROM tag_revision ORDER BY revision";

    private final JdbcTemplate jdbc;

    public TagRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long revision, String operator, int requestedCount, int changedCount,
                       String changedAtUtc) {
        jdbc.update("INSERT INTO tag_revision (revision, operator, requested_count, changed_count, changed_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                revision, operator, requestedCount, changedCount, changedAtUtc);
    }

    public List<TagRevision> findAll() {
        return jdbc.query(SELECT_ALL, MAPPER);
    }

    /**
     * UTC 左闭右开区间过滤：from 为 null 不设下界，to 为 null 不设上界。
     * 在应用层按 Instant 比较，避免存储文本与带小数秒边界的字典序差异。
     */
    public List<TagRevision> findBetween(Instant from, Instant to) {
        return findAll().stream().filter(record -> {
            Instant at = Instant.parse(record.changedAtUtc());
            if (from != null && at.isBefore(from)) {
                return false;
            }
            return to == null || at.isBefore(to);
        }).toList();
    }
}
