package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.DecayRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 频次衰减权重明细数据访问。仅确认成功事务内写入（与确认同事务），
 * 序号互斥由调用方借助 visitor_last_confirmation 行锁保证，
 * (campaign_id, visitor_id, utc_date, sequence_no) 唯一约束兜底。
 */
@Repository
public class DecayRecordRepository {

    private static final String COLUMNS =
            "id, campaign_id, visitor_id, utc_date, sequence_no, decay_weight, "
                    + "reservation_id, confirmed_at_utc";

    private static final RowMapper<DecayRecord> MAPPER = (rs, rowNum) -> new DecayRecord(
            rs.getLong("id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            rs.getInt("sequence_no"),
            rs.getBigDecimal("decay_weight"),
            rs.getString("reservation_id"),
            rs.getLong("confirmed_at_utc"));

    private final JdbcTemplate jdbc;

    public DecayRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条衰减明细；同日序号唯一冲突由调用方按并发裁决处理。 */
    public void insert(DecayRecord record) {
        jdbc.update("INSERT INTO exposure_decay_record "
                        + "(campaign_id, visitor_id, utc_date, sequence_no, decay_weight, "
                        + "reservation_id, confirmed_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                record.campaignId(),
                record.visitorId(),
                record.utcDate(),
                record.sequenceNo(),
                record.decayWeight(),
                record.reservationId(),
                record.confirmedAtUtc());
    }

    /**
     * 统计某访客某公告某 UTC 日已写入的衰减明细条数（即当日已确认次数）。
     * 调用方须持有 visitor_last_confirmation 行锁以保证并发确认序号互斥。
     */
    public int countByDay(String campaignId, String visitorId, LocalDate utcDate) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_decay_record "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                Integer.class, campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 查询某访客某公告在指定 UTC 日的全部衰减权重明细，按确认次序升序。 */
    public List<DecayRecord> findByVisitorDay(String campaignId, String visitorId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_decay_record "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ? ORDER BY sequence_no ASC",
                MAPPER, campaignId, visitorId, java.sql.Date.valueOf(utcDate));
    }
}
