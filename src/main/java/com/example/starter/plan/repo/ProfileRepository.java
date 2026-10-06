package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionLimitProfile;
import com.example.starter.plan.model.TrainAxleProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 列车轴重档案、区段限值档案与发布轴重快照的 JDBC 持久化。
 * 轴重列表以 JSON 数组文本存储，顺序即轴序；所有时刻为 UTC 毫秒。
 */
@Repository
public class ProfileRepository {

    private static final RowMapper<TrainAxleProfile> TRAIN_MAPPER = (rs, n) -> new TrainAxleProfile(
            rs.getString("train_no"),
            rs.getInt("version"),
            parseWeights(rs.getString("axle_weights_json")));

    private static final RowMapper<SectionLimitProfile> SECTION_MAPPER =
            (rs, n) -> new SectionLimitProfile(
                    rs.getString("section_id"),
                    rs.getInt("version"),
                    rs.getLong("single_axle_limit_kg"),
                    rs.getLong("window3_limit_kg"));

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入列车轴重档案（版本 1）；train_no 唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertTrainProfile(String trainNo, String axleWeightsJson, long nowMillis) {
        jdbc.update("INSERT INTO rail_train_axle_profile"
                        + " (train_no, version, axle_weights_json, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?, ?)",
                trainNo, axleWeightsJson, nowMillis, nowMillis);
    }

    /**
     * 按列车编号查询轴重档案（不加锁）。
     */
    public Optional<TrainAxleProfile> findTrainProfile(String trainNo) {
        return jdbc.query("SELECT train_no, version, axle_weights_json FROM rail_train_axle_profile"
                        + " WHERE train_no = ?",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 按列车编号查询轴重档案并加行级写锁，须在事务内调用。
     */
    public Optional<TrainAxleProfile> findTrainProfileForUpdate(String trainNo) {
        return jdbc.query("SELECT train_no, version, axle_weights_json FROM rail_train_axle_profile"
                        + " WHERE train_no = ? FOR UPDATE",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 更新列车轴重档案的版本与轴序列。
     */
    public void updateTrainProfile(String trainNo, int version, String axleWeightsJson,
                                   long nowMillis) {
        jdbc.update("UPDATE rail_train_axle_profile SET version = ?, axle_weights_json = ?,"
                        + " updated_at = ? WHERE train_no = ?",
                version, axleWeightsJson, nowMillis, trainNo);
    }

    /**
     * 插入区段限值档案（版本 1）；section_id 唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertSectionProfile(String sectionId, long singleAxleLimitKg, long window3LimitKg,
                                     long nowMillis) {
        jdbc.update("INSERT INTO rail_section_limit_profile"
                        + " (section_id, version, single_axle_limit_kg, window3_limit_kg,"
                        + " created_at, updated_at) VALUES (?, 1, ?, ?, ?, ?)",
                sectionId, singleAxleLimitKg, window3LimitKg, nowMillis, nowMillis);
    }

    /**
     * 按区段 ID 查询限值档案（不加锁）。
     */
    public Optional<SectionLimitProfile> findSectionProfile(String sectionId) {
        return jdbc.query("SELECT section_id, version, single_axle_limit_kg, window3_limit_kg"
                        + " FROM rail_section_limit_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询限值档案并加行级写锁，须在事务内调用。
     */
    public Optional<SectionLimitProfile> findSectionProfileForUpdate(String sectionId) {
        return jdbc.query("SELECT section_id, version, single_axle_limit_kg, window3_limit_kg"
                        + " FROM rail_section_limit_profile WHERE section_id = ? FOR UPDATE",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 更新区段限值档案的版本与限值。
     */
    public void updateSectionProfile(String sectionId, int version, long singleAxleLimitKg,
                                     long window3LimitKg, long nowMillis) {
        jdbc.update("UPDATE rail_section_limit_profile SET version = ?, single_axle_limit_kg = ?,"
                        + " window3_limit_kg = ?, updated_at = ? WHERE section_id = ?",
                version, singleAxleLimitKg, window3LimitKg, nowMillis, sectionId);
    }

    /**
     * 追加发布时冻结的轴重包络快照（不可变，随发布事务提交）。
     */
    public void insertAxleSnapshot(long planId, int occSeq, String trainNo, String sectionId,
                                   String axleWeightsJson, int trainProfileVersion,
                                   int sectionProfileVersion, long singleAxleLimitKg,
                                   long window3LimitKg, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_axle_snapshot"
                        + " (plan_id, occ_seq, train_no, section_id, axle_weights_json,"
                        + " train_profile_version, section_profile_version, single_axle_limit_kg,"
                        + " window3_limit_kg, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                planId, occSeq, trainNo, sectionId, axleWeightsJson, trainProfileVersion,
                sectionProfileVersion, singleAxleLimitKg, window3LimitKg, nowMillis);
    }

    /**
     * 解析轴重 JSON 数组文本为有序列表。
     */
    private static List<Integer> parseWeights(String json) {
        String trimmed = json.trim();
        if (trimmed.length() < 2) {
            return List.of();
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .map(Integer::valueOf)
                .toList();
    }
}
