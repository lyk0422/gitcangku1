package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 活跃轮换占位数据访问。主键 (campaign_id, visitor_id) 即
 * “每访客每公告至多一条轮换 RESERVED” 的互斥约束；
 * 轮换预占进入任意终态时同事务删除占位。
 */
@Repository
public class RotationActiveRepository {

    private final JdbcTemplate jdbc;

    public RotationActiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入活跃占位；同 (campaign_id, visitor_id) 已存在时抛出
     * {@link org.springframework.dao.DuplicateKeyException}，由调用方转为 409。
     */
    public void insert(String campaignId, String visitorId, String reservationId, long createdAtUtc) {
        jdbc.update("INSERT INTO rotation_active (campaign_id, visitor_id, reservation_id, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                campaignId, visitorId, reservationId, createdAtUtc);
    }

    /** 按预占单精确删除活跃占位（轮换预占进入终态时调用）；编号不匹配不误删他人占位。 */
    public void delete(String campaignId, String visitorId, String reservationId) {
        jdbc.update("DELETE FROM rotation_active "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND reservation_id = ?",
                campaignId, visitorId, reservationId);
    }

    /** 查询当前活跃轮换占位对应的预占单编号；无活跃占位返回 empty。 */
    public Optional<String> findReservationId(String campaignId, String visitorId) {
        List<String> list = jdbc.query(
                "SELECT reservation_id FROM rotation_active WHERE campaign_id = ? AND visitor_id = ?",
                (rs, rowNum) -> rs.getString(1), campaignId, visitorId);
        return list.stream().findFirst();
    }
}
