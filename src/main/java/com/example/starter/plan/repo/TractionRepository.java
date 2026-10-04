package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.model.TractionSnapshot;
import com.example.starter.plan.model.TrainTraction;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 列车牵引档案、区段牵引档案与发布牵引快照的 JDBC 持久化。
 */
@Repository
public class TractionRepository {

    private static final RowMapper<TrainTraction> TRAIN_MAPPER = (rs, n) -> new TrainTraction(
            rs.getString("train_no"),
            TractionMode.valueOf(rs.getString("traction_mode")));

    private static final RowMapper<SectionProfile> SECTION_MAPPER = (rs, n) -> new SectionProfile(
            rs.getString("section_id"),
            rs.getBoolean("electrified"),
            rs.getBoolean("diesel_allowed"),
            rs.getInt("version"));

    private static final RowMapper<TractionSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new TractionSnapshot(
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            TractionMode.valueOf(rs.getString("traction_mode")),
            rs.getString("section_id"),
            rs.getBoolean("electrified"),
            rs.getBoolean("diesel_allowed"),
            rs.getInt("section_version"));

    private final JdbcTemplate jdbc;

    public TractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按列车编号查询牵引档案。
     */
    public Optional<TrainTraction> findTrainTraction(String trainNo) {
        return jdbc.query("SELECT train_no, traction_mode FROM rail_train_traction"
                        + " WHERE train_no = ?",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 插入列车牵引档案；唯一键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertTrainTraction(String trainNo, TractionMode mode, long nowMillis) {
        jdbc.update("INSERT INTO rail_train_traction (train_no, traction_mode, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                trainNo, mode.name(), nowMillis, nowMillis);
    }

    /**
     * 更新列车牵引方式，返回受影响行数（0 表示档案不存在）。
     */
    public int updateTrainTraction(String trainNo, TractionMode mode, long nowMillis) {
        return jdbc.update("UPDATE rail_train_traction SET traction_mode = ?, updated_at = ?"
                        + " WHERE train_no = ?",
                mode.name(), nowMillis, trainNo);
    }

    /**
     * 按区段 ID 查询区段档案。
     */
    public Optional<SectionProfile> findSectionProfile(String sectionId) {
        return jdbc.query("SELECT section_id, electrified, diesel_allowed, version"
                        + " FROM rail_section_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询区段档案并加行级写锁，须在事务内调用，用于串行化档案变更。
     */
    public Optional<SectionProfile> findSectionProfileForUpdate(String sectionId) {
        return jdbc.query("SELECT section_id, electrified, diesel_allowed, version"
                        + " FROM rail_section_profile WHERE section_id = ? FOR UPDATE",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 插入区段档案（初始版本 1）；唯一键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertSectionProfile(String sectionId, boolean electrified, boolean dieselAllowed,
                                     long nowMillis) {
        jdbc.update("INSERT INTO rail_section_profile"
                        + " (section_id, electrified, diesel_allowed, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, ?)",
                sectionId, electrified, dieselAllowed, nowMillis, nowMillis);
    }

    /**
     * 更新区段档案并将版本加一。
     */
    public void updateSectionProfile(String sectionId, boolean electrified, boolean dieselAllowed,
                                     int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_section_profile"
                        + " SET electrified = ?, diesel_allowed = ?, version = ?, updated_at = ?"
                        + " WHERE section_id = ?",
                electrified, dieselAllowed, newVersion, nowMillis, sectionId);
    }

    /**
     * 批量写入发布时的牵引快照（不可变，追加后不修改）。
     */
    public void insertSnapshots(List<TractionSnapshot> snapshots) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_traction_snapshot"
                        + " (plan_id, seq, train_no, traction_mode, section_id, electrified,"
                        + " diesel_allowed, section_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(),
                (ps, s) -> {
                    ps.setLong(1, s.planId());
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.tractionMode().name());
                    ps.setString(5, s.sectionId());
                    ps.setBoolean(6, s.electrified());
                    ps.setBoolean(7, s.dieselAllowed());
                    ps.setInt(8, s.sectionVersion());
                });
    }

    /**
     * 查询计划的牵引快照，按计划内序号升序。
     */
    public List<TractionSnapshot> findSnapshots(long planId) {
        return jdbc.query("SELECT plan_id, seq, train_no, traction_mode, section_id, electrified,"
                        + " diesel_allowed, section_version FROM rail_plan_traction_snapshot"
                        + " WHERE plan_id = ? ORDER BY seq",
                SNAPSHOT_MAPPER, planId);
    }

    /**
     * 已发布计划在指定区段上的占用（scheduleKey 与 trainNo），用于档案变更合法性检查。
     */
    public List<PublishedUsage> findPublishedUsageBySection(String sectionId) {
        return jdbc.query("SELECT p.schedule_key, o.train_no, o.section_id"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?"
                        + " ORDER BY p.schedule_key",
                (rs, n) -> new PublishedUsage(rs.getString("schedule_key"),
                        rs.getString("train_no"), rs.getString("section_id")),
                sectionId);
    }

    /**
     * 已发布计划中指定列车的占用（scheduleKey 与 sectionId），用于牵引方式变更合法性检查。
     */
    public List<PublishedUsage> findPublishedUsageByTrain(String trainNo) {
        return jdbc.query("SELECT p.schedule_key, o.train_no, o.section_id"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.train_no = ?"
                        + " ORDER BY p.schedule_key",
                (rs, n) -> new PublishedUsage(rs.getString("schedule_key"),
                        rs.getString("train_no"), rs.getString("section_id")),
                trainNo);
    }

    /**
     * 已发布计划的一条占用引用，用于档案/牵引方式变更时的合法性检查。
     *
     * @param scheduleKey 所属已发布计划业务键
     * @param trainNo     列车编号
     * @param sectionId   区段 ID
     */
    public record PublishedUsage(String scheduleKey, String trainNo, String sectionId) {
    }
}