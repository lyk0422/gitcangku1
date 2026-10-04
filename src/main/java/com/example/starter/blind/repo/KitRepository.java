package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 处理包数据访问；treatment 为盲底字段，仅库内与 SUPPLY 登记使用，不进入普通响应与日志。
 */
@Repository
public class KitRepository {

    /** 处理包行（含盲底 treatment，仅供服务层内部使用）。 */
    public record KitRow(
            long id,
            String experimentId,
            String kitNo,
            String treatment,
            String packageCode,
            String registeredActor,
            long registeredAt) {
    }

    private static final RowMapper<KitRow> KIT_MAPPER = (rs, n) -> new KitRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("kit_no"),
            rs.getString("treatment"),
            rs.getString("package_code"),
            rs.getString("registered_actor"),
            rs.getLong("registered_at"));

    private final JdbcTemplate jdbc;

    public KitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(KitRow row) {
        jdbc.update("INSERT INTO kit ("
                        + "experiment_id, kit_no, treatment, package_code, "
                        + "registered_actor, registered_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.kitNo(), row.treatment(), row.packageCode(),
                row.registeredActor(), row.registeredAt());
    }

    public KitRow findByKitNo(String experimentId, String kitNo) {
        List<KitRow> rows = jdbc.query(
                "SELECT id, experiment_id, kit_no, treatment, package_code, "
                        + "registered_actor, registered_at "
                        + "FROM kit WHERE experiment_id = ? AND kit_no = ?",
                KIT_MAPPER, experimentId, kitNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按包编号字典序取匹配处理代码的第一个未发包。
     * 调用方已持有实验行级锁串行化并发领取，kit_pickup(kit_id) 唯一索引兜底。
     *
     * @return 第一个未发包；无匹配包时返回 null
     */
    public KitRow findFirstUnissued(String experimentId, String treatment) {
        List<KitRow> rows = jdbc.query(
                "SELECT k.id, k.experiment_id, k.kit_no, k.treatment, k.package_code, "
                        + "k.registered_actor, k.registered_at "
                        + "FROM kit k LEFT JOIN kit_pickup p ON p.kit_id = k.id "
                        + "WHERE k.experiment_id = ? AND k.treatment = ? AND p.id IS NULL "
                        + "ORDER BY k.kit_no LIMIT 1",
                KIT_MAPPER, experimentId, treatment);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同实验包编号重复。 */
    public boolean isDuplicateKitNo(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_kit_no");
    }

    /** 包装码全局重复（随机冲突，应重试）。 */
    public boolean isDuplicatePackageCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_kit_package_code");
    }
}