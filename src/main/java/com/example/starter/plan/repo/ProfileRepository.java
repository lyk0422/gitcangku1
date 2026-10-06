package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TrainProfile;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 列车/区段制动档案的 JDBC 持久化。配置版本从 1 递增，修改须带 expectedVersion。
 */
@Repository
public class ProfileRepository {

    private static final RowMapper<TrainProfile> TRAIN_MAPPER = (rs, n) -> new TrainProfile(
            rs.getLong("id"),
            rs.getString("train_no"),
            rs.getBigDecimal("reaction_t"),
            rs.getBigDecimal("base_decel_a"),
            rs.getInt("version"));

    private static final RowMapper<SectionProfile> SECTION_MAPPER = (rs, n) -> new SectionProfile(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getBigDecimal("protect_d"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建列车档案（版本 1）；列车编号唯一冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertTrainProfile(String trainNo, BigDecimal reactionT, BigDecimal baseDecelA,
                                   long nowMillis) {
        jdbc.update("INSERT INTO rail_train_profile"
                        + " (train_no, reaction_t, base_decel_a, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, ?)",
                trainNo, reactionT, baseDecelA, nowMillis, nowMillis);
    }

    /**
     * 按列车编号查询档案（不加锁）。
     */
    public Optional<TrainProfile> findTrainProfile(String trainNo) {
        return jdbc.query("SELECT id, train_no, reaction_t, base_decel_a, version"
                        + " FROM rail_train_profile WHERE train_no = ?",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 按列车编号查询档案并加行级写锁，须在事务内调用。
     */
    public Optional<TrainProfile> findTrainProfileForUpdate(String trainNo) {
        return jdbc.query("SELECT id, train_no, reaction_t, base_decel_a, version"
                        + " FROM rail_train_profile WHERE train_no = ? FOR UPDATE",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 更新列车档案参数并递增版本。
     */
    public void updateTrainProfile(long id, BigDecimal reactionT, BigDecimal baseDecelA,
                                   int version, long nowMillis) {
        jdbc.update("UPDATE rail_train_profile SET reaction_t = ?, base_decel_a = ?,"
                        + " version = ?, updated_at = ? WHERE id = ?",
                reactionT, baseDecelA, version, nowMillis, id);
    }

    /**
     * 创建区段档案（版本 1）；区段 ID 唯一冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertSectionProfile(String sectionId, BigDecimal protectD, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_profile"
                        + " (section_id, protect_d, version, created_at, updated_at)"
                        + " VALUES (?, ?, 1, ?, ?)",
                sectionId, protectD, nowMillis, nowMillis);
    }

    /**
     * 按区段 ID 查询档案（不加锁）。
     */
    public Optional<SectionProfile> findSectionProfile(String sectionId) {
        return jdbc.query("SELECT id, section_id, protect_d, version"
                        + " FROM rail_section_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询档案并加行级写锁，须在事务内调用。
     */
    public Optional<SectionProfile> findSectionProfileForUpdate(String sectionId) {
        return jdbc.query("SELECT id, section_id, protect_d, version"
                        + " FROM rail_section_profile WHERE section_id = ? FOR UPDATE",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 更新区段档案防护距离并递增版本。
     */
    public void updateSectionProfile(long id, BigDecimal protectD, int version, long nowMillis) {
        jdbc.update("UPDATE rail_section_profile SET protect_d = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                protectD, version, nowMillis, id);
    }
}
