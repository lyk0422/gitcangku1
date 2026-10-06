package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 公告表数据访问。creative_ids 以 JSON 数组文本存储，避免分隔符拼接歧义。
 */
@Repository
public class CampaignRepository {

    private static final TypeReference<List<String>> CREATIVE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CampaignRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<Campaign> mapper = (rs, rowNum) -> new Campaign(
            rs.getString("campaign_id"),
            rs.getInt("daily_total_cap"),
            rs.getInt("per_visitor_daily_cap"),
            readCreatives(rs.getString("creative_ids")),
            rs.getLong("created_at_utc"));

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, creative_ids, created_at_utc "
                        + "FROM campaign WHERE campaign_id = ?", mapper, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "creative_ids, created_at_utc) VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                writeCreatives(campaign.creativeIds()),
                campaign.createdAtUtc());
    }

    private List<String> readCreatives(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CREATIVE_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt creative_ids json", e);
        }
    }

    private String writeCreatives(List<String> creativeIds) {
        if (creativeIds == null || creativeIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(creativeIds);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize creative_ids", e);
        }
    }
}
