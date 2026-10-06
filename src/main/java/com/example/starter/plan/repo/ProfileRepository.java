package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TrainProfile;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 列车应急制动档案与区段防护档案的 JDBC 持久化。
 * 参数均为精确十进制（最多3位小数），版本从 1 递增。
 */
@Repository
public class ProfileRepository {

    private static final RowMapper<TrainProfile> TRAIN_MAPPER = (rs, n) -> new TrainProfile(
            rs.getLong("id"),
            rs.getString("train_no"),
            rs.getBigDecimal("reaction_time_s"),
            rs.getBigDecimal("base_deceleration_mps2"),
            rs.getInt("version"));

    private static final RowMapper<SectionProfile> SECTION_MAPPER = (rs, n) -> new SectionProfile(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getBigDecimal("protection_distance_m"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按列车编号查询档案（不加锁）。
     */
    public Optional<TrainProfile> findTrain(String trainNo) {
        return jdbc.query("SELECT id, train_no, reaction_time_s, base_deceleration_mps2, version"
                        + " FROM rail_train_profile WHERE train_no = ?",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 按列车编号查询档案并加行级写锁，须在事务内调用。
     */
    public Optional<TrainProfile> findTrainForUpdate(String trainNo) {
        return jdbc.query("SELECT id, train_no, reaction_time_s, base_deceleration_mps2, version"
                        + " FROM rail_train_profile WHERE train_no = ? FOR UPDATE",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 新建列车档案（版本 1），返回自增主键；列车编号唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertTrain(String trainNo, BigDecimal reactionTime, BigDecimal baseDeceleration,
                            long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_train_profile"
                            + " (train_no, reaction_time_s, base_deceleration_mps2, version,"
                            + " created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, trainNo);
            ps.setBigDecimal(2, reactionTime);
            ps.setBigDecimal(3, baseDeceleration);
            ps.setLong(4, nowMillis);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新列车档案参数并递增版本。
     */
    public void updateTrain(long id, BigDecimal reactionTime, BigDecimal baseDeceleration,
                            int version, long nowMillis) {
        jdbc.update("UPDATE rail_train_profile SET reaction_time_s = ?,"
                        + " base_deceleration_mps2 = ?, version = ?, updated_at = ? WHERE id = ?",
                reactionTime, baseDeceleration, version, nowMillis, id);
    }

    /**
     * 按区段 ID 查询档案（不加锁）。
     */
    public Optional<SectionProfile> findSection(String sectionId) {
        return jdbc.query("SELECT id, section_id, protection_distance_m, version"
                        + " FROM rail_section_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询档案并加行级写锁，须在事务内调用。
     */
    public Optional<SectionProfile> findSectionForUpdate(String sectionId) {
        return jdbc.query("SELECT id, section_id, protection_distance_m, version"
                        + " FROM rail_section_profile WHERE section_id = ? FOR UPDATE",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 新建区段档案（版本 1），返回自增主键；区段 ID 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertSection(String sectionId, BigDecimal protectionDistance, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_section_profile"
                            + " (section_id, protection_distance_m, version, created_at, updated_at)"
                            + " VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sectionId);
            ps.setBigDecimal(2, protectionDistance);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新区段档案参数并递增版本。
     */
    public void updateSection(long id, BigDecimal protectionDistance, int version,
                              long nowMillis) {
        jdbc.update("UPDATE rail_section_profile SET protection_distance_m = ?, version = ?,"
                        + " updated_at = ? WHERE id = ?",
                protectionDistance, version, nowMillis, id);
    }
}
