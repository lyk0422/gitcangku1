package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.model.TractionSnapshot;
import com.example.starter.plan.model.TrainProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 列车/区段牵引档案与冻结牵引快照的 JDBC 持久化。
 */
@Repository
public class TractionRepository {

    private static final RowMapper<TrainProfile> TRAIN_MAPPER = (rs, n) -> new TrainProfile(
            rs.getLong("id"),
            rs.getString("train_no"),
            TractionMode.valueOf(rs.getString("traction_mode")),
            rs.getInt("version"));

    private static final RowMapper<SectionProfile> SECTION_MAPPER = (rs, n) -> new SectionProfile(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getBoolean("electrified"),
            rs.getBoolean("diesel_allowed"),
            rs.getInt("version"));

    private static final RowMapper<TractionSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new TractionSnapshot(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("train_no"),
            TractionMode.valueOf(rs.getString("traction_mode")),
            rs.getString("section_id"),
            rs.getBoolean("electrified"),
            rs.getBoolean("diesel_allowed"),
            rs.getInt("section_version"),
            rs.getLong("created_at"));

    private static final String TRAIN_COLUMNS = "id, train_no, traction_mode, version";
    private static final String SECTION_COLUMNS =
            "id, section_id, electrified, diesel_allowed, version";

    private final JdbcTemplate jdbc;

    public TractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新增列车牵引档案，版本从 1 开始；列车编号唯一冲突时抛出 DuplicateKeyException。
     */
    public void insertTrainProfile(String trainNo, TractionMode mode, long nowMillis) {
        jdbc.update("INSERT INTO rail_train_profile"
                        + " (train_no, traction_mode, version, created_at, updated_at)"
                        + " VALUES (?, ?, 1, ?, ?)",
                trainNo, mode.name(), nowMillis, nowMillis);
    }

    /**
     * 变更列车牵引方式并将版本加一。
     */
    public void updateTrainProfile(String trainNo, TractionMode mode, int newVersion,
                                   long nowMillis) {
        jdbc.update("UPDATE rail_train_profile SET traction_mode = ?, version = ?, updated_at = ?"
                        + " WHERE train_no = ?",
                mode.name(), newVersion, nowMillis, trainNo);
    }

    /**
     * 按列车编号查询牵引档案（不加锁）。
     */
    public Optional<TrainProfile> findTrainProfile(String trainNo) {
        return jdbc.query("SELECT " + TRAIN_COLUMNS + " FROM rail_train_profile WHERE train_no = ?",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 按列车编号查询牵引档案并加行级写锁，须在事务内调用。
     */
    public Optional<TrainProfile> findTrainProfileForUpdate(String trainNo) {
        return jdbc.query("SELECT " + TRAIN_COLUMNS
                        + " FROM rail_train_profile WHERE train_no = ? FOR UPDATE",
                TRAIN_MAPPER, trainNo).stream().findFirst();
    }

    /**
     * 新增区段牵引档案，版本从 1 开始；区段唯一冲突时抛出 DuplicateKeyException。
     */
    public void insertSectionProfile(String sectionId, boolean electrified, boolean dieselAllowed,
                                     long nowMillis) {
        jdbc.update("INSERT INTO rail_section_profile"
                        + " (section_id, electrified, diesel_allowed, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, ?)",
                sectionId, electrified, dieselAllowed, nowMillis, nowMillis);
    }

    /**
     * 变更区段牵引档案并将版本加一。
     */
    public void updateSectionProfile(String sectionId, boolean electrified, boolean dieselAllowed,
                                     int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_section_profile"
                        + " SET electrified = ?, diesel_allowed = ?, version = ?, updated_at = ?"
                        + " WHERE section_id = ?",
                electrified, dieselAllowed, newVersion, nowMillis, sectionId);
    }

    /**
     * 按区段 ID 查询牵引档案（不加锁）。
     */
    public Optional<SectionProfile> findSectionProfile(String sectionId) {
        return jdbc.query("SELECT " + SECTION_COLUMNS
                        + " FROM rail_section_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询牵引档案并加行级写锁，须在事务内调用。
     */
    public Optional<SectionProfile> findSectionProfileForUpdate(String sectionId) {
        return jdbc.query("SELECT " + SECTION_COLUMNS
                        + " FROM rail_section_profile WHERE section_id = ? FOR UPDATE",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 追加冻结牵引快照（不可变）；同一计划同一列车同一区段仅一行。
     */
    public void insertSnapshot(long planId, String trainNo, TractionMode mode, String sectionId,
                               boolean electrified, boolean dieselAllowed, int sectionVersion,
                               long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_traction_snapshot"
                        + " (plan_id, train_no, traction_mode, section_id, electrified,"
                        + " diesel_allowed, section_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                planId, trainNo, mode.name(), sectionId, electrified, dieselAllowed,
                sectionVersion, nowMillis);
    }

    /**
     * 查询计划的冻结牵引快照，按快照 id 升序（即冻结顺序）。
     */
    public List<TractionSnapshot> findSnapshots(long planId) {
        return jdbc.query("SELECT id, plan_id, train_no, traction_mode, section_id, electrified,"
                        + " diesel_allowed, section_version, created_at"
                        + " FROM rail_plan_traction_snapshot WHERE plan_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, planId);
    }
}