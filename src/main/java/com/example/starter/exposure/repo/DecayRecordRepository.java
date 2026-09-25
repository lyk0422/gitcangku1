package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.DecayRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 频次衰减记录数据访问。记录只增不改，与确认操作同事务写入。
 */
@Repository
public class DecayRecordRepository {

    private static final RowMapper<DecayRecord> MAPPER = (rs, rowNum) -> new DecayRecord(
            rs.getLong("id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            rs.getInt("seq_no"),
            rs.getBigDecimal("decay_weight"),
            rs.getString("reservation_id"),
            rs.getLong("confirmed_at_utc"));

    private static final String COLUMNS =
            "id, campaign_id, visitor_id, utc_date, seq_no, decay_weight, reservation_id, confirmed_at_utc";

    private final JdbcTemplate jdbc;

    public DecayRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 统计某访客对某公告在指定 UTC 日已写入的衰减记录数，用于确定下一次确认的序号 N。
     * 调用方须已持有该 (campaign_id, visitor_id) 冷却行锁，保证并发确认序号唯一。
     */
    public int countForDay(String campaignId, String visitorId, LocalDate utcDate) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_decay_record "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                Integer.class, campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /**
     * 写入一条衰减记录（同事务，不可篡改）。
     */
    public void insert(DecayRecord record) {
        jdbc.update("INSERT INTO exposure_decay_record "
                        + "(campaign_id, visitor_id, utc_date, seq_no, decay_weight, reservation_id, confirmed_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                record.campaignId(),
                record.visitorId(),
                record.utcDate(),
                record.seqNo(),
                record.decayWeight(),
                record.reservationId(),
                record.confirmedAtUtc());
    }

    /**
     * 按访客、公告、UTC 日查询衰减权重明细，按确认序号升序。
     */
    public List<DecayRecord> listForDay(String campaignId, String visitorId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_decay_record "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ? ORDER BY seq_no",
                MAPPER, campaignId, visitorId, java.sql.Date.valueOf(utcDate));
    }
}
