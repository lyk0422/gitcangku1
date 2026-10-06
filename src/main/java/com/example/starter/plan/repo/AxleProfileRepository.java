package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionAxleLimit;
import com.example.starter.plan.model.TrainAxleProfile;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 列车轴重档案、区段轴限档案与发布冻结快照的 JDBC 持久化。
 * 轴重与限值均为正整数，单位千克。
 */
@Repository
public class AxleProfileRepository {

    /**
     * 已发布计划中参与轴限核验的占用引用。
     *
     * @param scheduleKey 所属已发布计划业务键
     * @param trainNo     列车编号
     * @param sectionId   区段 ID
     */
    public record PublishedRef(String scheduleKey, String trainNo, String sectionId) {
    }

    private final JdbcTemplate jdbc;

    public AxleProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 列车轴重档案 ----------

    /**
     * 插入列车轴重档案主记录（版本 1），返回自增主键。
     */
    public long insertTrainProfile(String trainNo, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_train_axle_profile (train_no, version, created_at, updated_at)"
                            + " VALUES (?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, trainNo);
            ps.setLong(2, nowMillis);
            ps.setLong(3, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 整体替换档案的轴序列：先删后插，保持提交顺序。
     */
    public void replaceTrainAxles(long profileId, List<Long> axleWeightsKg) {
        jdbc.update("DELETE FROM rail_train_axle WHERE profile_id = ?", profileId);
        insertTrainAxles(profileId, axleWeightsKg);
    }

    /**
     * 逐条插入轴重明细，序号从 0 开始保持轴序（重复轴重序号仍递增）。
     */
    public void insertTrainAxles(long profileId, List<Long> axleWeightsKg) {
        for (int i = 0; i < axleWeightsKg.size(); i++) {
            jdbc.update("INSERT INTO rail_train_axle (profile_id, seq, weight_kg) VALUES (?, ?, ?)",
                    profileId, i, axleWeightsKg.get(i));
        }
    }

    /**
     * 按列车编号查询档案（含有序轴序列，不加锁）。
     */
    public Optional<TrainAxleProfile> findTrainProfile(String trainNo) {
        return findTrainProfileRow(trainNo, false)
                .map(row -> new TrainAxleProfile(trainNo, row.version, findTrainAxles(row.id)));
    }

    /**
     * 按列车编号查询档案并加行级写锁，须在事务内调用。
     */
    public Optional<TrainAxleProfile> findTrainProfileForUpdate(String trainNo) {
        return findTrainProfileRow(trainNo, true)
                .map(row -> new TrainAxleProfile(trainNo, row.version, findTrainAxles(row.id)));
    }

    /**
     * 更新档案版本（轴序列由 replaceTrainAxles 替换）。
     */
    public void updateTrainProfileVersion(String trainNo, int version, long nowMillis) {
        jdbc.update("UPDATE rail_train_axle_profile SET version = ?, updated_at = ?"
                        + " WHERE train_no = ?",
                version, nowMillis, trainNo);
    }

    /**
     * 查询档案主键（用于轴序列替换），不存在返回空。
     */
    public Optional<Long> findTrainProfileId(String trainNo) {
        return jdbc.query("SELECT id FROM rail_train_axle_profile WHERE train_no = ?",
                (rs, n) -> rs.getLong("id"), trainNo).stream().findFirst();
    }

    // ---------- 区段轴限档案 ----------

    /**
     * 插入区段轴限档案（版本 1）。
     */
    public void insertSectionLimit(String sectionId, long singleAxleLimitKg, long window3LimitKg,
                                   long nowMillis) {
        jdbc.update("INSERT INTO rail_section_axle_limit"
                        + " (section_id, version, single_axle_limit_kg, window3_limit_kg,"
                        + " created_at, updated_at) VALUES (?, 1, ?, ?, ?, ?)",
                sectionId, singleAxleLimitKg, window3LimitKg, nowMillis, nowMillis);
    }

    /**
     * 按区段 ID 查询轴限档案（不加锁）。
     */
    public Optional<SectionAxleLimit> findSectionLimit(String sectionId) {
        return findSectionLimit(sectionId, false);
    }

    /**
     * 按区段 ID 查询轴限档案并加行级写锁，须在事务内调用。
     */
    public Optional<SectionAxleLimit> findSectionLimitForUpdate(String sectionId) {
        return findSectionLimit(sectionId, true);
    }

    /**
     * 更新区段轴限与版本。
     */
    public void updateSectionLimit(String sectionId, int version, long singleAxleLimitKg,
                                   long window3LimitKg, long nowMillis) {
        jdbc.update("UPDATE rail_section_axle_limit SET version = ?, single_axle_limit_kg = ?,"
                        + " window3_limit_kg = ?, updated_at = ? WHERE section_id = ?",
                version, singleAxleLimitKg, window3LimitKg, nowMillis, sectionId);
    }

    // ---------- 发布冻结快照 ----------

    /**
     * 冻结发布时列车轴序列快照（含当时档案版本）。
     */
    public void insertFrozenAxles(long planId, String trainNo, int profileVersion,
                                  List<Long> axleWeightsKg) {
        for (int i = 0; i < axleWeightsKg.size(); i++) {
            jdbc.update("INSERT INTO rail_plan_frozen_axle"
                            + " (plan_id, train_no, profile_version, seq, weight_kg)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    planId, trainNo, profileVersion, i, axleWeightsKg.get(i));
        }
    }

    /**
     * 冻结发布时区段轴限快照（含当时档案版本与限值）。
     */
    public void insertFrozenSection(long planId, String sectionId, int profileVersion,
                                    long singleAxleLimitKg, long window3LimitKg) {
        jdbc.update("INSERT INTO rail_plan_frozen_section"
                        + " (plan_id, section_id, profile_version, single_axle_limit_kg,"
                        + " window3_limit_kg) VALUES (?, ?, ?, ?, ?)",
                planId, sectionId, profileVersion, singleAxleLimitKg, window3LimitKg);
    }

    // ---------- 已发布计划占用引用（档案更新合法性核验用） ----------

    /**
     * 查询包含指定列车的已发布计划占用引用。
     */
    public List<PublishedRef> findPublishedRefsByTrain(String trainNo) {
        return jdbc.query("SELECT p.schedule_key, o.train_no, o.section_id"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.train_no = ?"
                        + " ORDER BY p.schedule_key, o.seq",
                (rs, n) -> new PublishedRef(rs.getString("schedule_key"),
                        rs.getString("train_no"), rs.getString("section_id")), trainNo);
    }

    /**
     * 查询占用指定区段的已发布计划占用引用。
     */
    public List<PublishedRef> findPublishedRefsBySection(String sectionId) {
        return jdbc.query("SELECT p.schedule_key, o.train_no, o.section_id"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?"
                        + " ORDER BY p.schedule_key, o.seq",
                (rs, n) -> new PublishedRef(rs.getString("schedule_key"),
                        rs.getString("train_no"), rs.getString("section_id")), sectionId);
    }

    // ---------- 内部实现 ----------

    private record ProfileRow(long id, int version) {
    }

    private Optional<ProfileRow> findTrainProfileRow(String trainNo, boolean forUpdate) {
        String sql = "SELECT id, version FROM rail_train_axle_profile WHERE train_no = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new ProfileRow(rs.getLong("id"), rs.getInt("version")),
                trainNo).stream().findFirst();
    }

    private List<Long> findTrainAxles(long profileId) {
        return jdbc.query("SELECT weight_kg FROM rail_train_axle WHERE profile_id = ? ORDER BY seq",
                (rs, n) -> rs.getLong("weight_kg"), profileId);
    }

    private Optional<SectionAxleLimit> findSectionLimit(String sectionId, boolean forUpdate) {
        String sql = "SELECT section_id, version, single_axle_limit_kg, window3_limit_kg"
                + " FROM rail_section_axle_limit WHERE section_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new SectionAxleLimit(
                        rs.getString("section_id"), rs.getInt("version"),
                        rs.getLong("single_axle_limit_kg"), rs.getLong("window3_limit_kg")),
                sectionId).stream().findFirst();
    }
}
