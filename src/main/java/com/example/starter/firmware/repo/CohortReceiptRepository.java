package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CohortReceipt;
import com.example.starter.firmware.domain.ReceiptDisposition;
import com.example.starter.firmware.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 队列回执入账记录数据访问。同设备同代次由 uk_receipt_device_generation 保证只入账一次。
 */
@Repository
public class CohortReceiptRepository {

    private static final RowMapper<CohortReceipt> MAPPER = (rs, rowNum) -> new CohortReceipt(
            rs.getLong("id"), rs.getLong("campaign_id"), rs.getString("device_id"),
            rs.getLong("cohort_id"), rs.getInt("generation"),
            ReceiptResult.valueOf(rs.getString("result")),
            ReceiptDisposition.valueOf(rs.getString("disposition")),
            rs.getString("received_at_utc"));

    private static final String COLUMNS = "id, campaign_id, device_id, cohort_id, generation,"
            + " result, disposition, received_at_utc";

    private final JdbcTemplate jdbc;

    public CohortReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long campaignId, String deviceId, long cohortId, int generation,
                       ReceiptResult result, ReceiptDisposition disposition, String receivedAtUtc) {
        jdbc.update("INSERT INTO cohort_receipt (campaign_id, device_id, cohort_id, generation,"
                        + " result, disposition, received_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                campaignId, deviceId, cohortId, generation, result.name(), disposition.name(),
                receivedAtUtc);
    }

    public Optional<CohortReceipt> findByGeneration(long campaignId, String deviceId, int generation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cohort_receipt"
                        + " WHERE campaign_id = ? AND device_id = ? AND generation = ?",
                MAPPER, campaignId, deviceId, generation).stream().findFirst();
    }

    /**
     * 迟到回执证据：指定设备在不超过给定代次上的 LATE 入账记录。
     */
    public List<CohortReceipt> findLateByDeviceUpToGeneration(long campaignId, String deviceId,
                                                              int generation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cohort_receipt"
                        + " WHERE campaign_id = ? AND device_id = ? AND generation <= ?"
                        + " AND disposition = 'LATE' ORDER BY id",
                MAPPER, campaignId, deviceId, generation);
    }
}
