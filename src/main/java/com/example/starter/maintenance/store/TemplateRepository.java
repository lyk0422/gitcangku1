package com.example.starter.maintenance.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.MaintenanceTemplate;
import com.example.starter.maintenance.domain.TemplateItem;

/**
 * 保养模板数据访问层：模板版本与必检项均只追加、不可变；SQL 全部参数化。
 */
@Repository
public class TemplateRepository {

    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    public int nextVersion(String equipmentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(template_version), 0) FROM maintenance_template WHERE equipment_id = ?",
                Integer.class, equipmentId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertTemplate(MaintenanceTemplate template, String requestId) {
        jdbc.update("INSERT INTO maintenance_template (equipment_id, template_version,"
                        + " overdue_threshold_minutes, request_id, created_at) VALUES (?, ?, ?, ?, ?)",
                template.equipmentId(), template.templateVersion(),
                template.overdueThresholdMinutes(), requestId, utc(template.createdAt()));
    }

    public void insertItem(TemplateItem item) {
        jdbc.update("INSERT INTO maintenance_template_item (equipment_id, template_version, ordinal,"
                        + " item_code, item_title) VALUES (?, ?, ?, ?, ?)",
                item.equipmentId(), item.templateVersion(), item.ordinal(),
                item.itemCode(), item.itemTitle());
    }

    public Optional<MaintenanceTemplate> findTemplate(String equipmentId, int templateVersion) {
        List<MaintenanceTemplate> rows = jdbc.query(
                "SELECT equipment_id, template_version, overdue_threshold_minutes, created_at"
                        + " FROM maintenance_template WHERE equipment_id = ? AND template_version = ?",
                (rs, n) -> new MaintenanceTemplate(
                        rs.getString("equipment_id"),
                        rs.getInt("template_version"),
                        rs.getLong("overdue_threshold_minutes"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                equipmentId, templateVersion);
        return rows.stream().findFirst();
    }

    /** 设备最新模板版本；无模板时为空。 */
    public Optional<MaintenanceTemplate> findLatestTemplate(String equipmentId) {
        List<MaintenanceTemplate> rows = jdbc.query(
                "SELECT equipment_id, template_version, overdue_threshold_minutes, created_at"
                        + " FROM maintenance_template WHERE equipment_id = ?"
                        + " ORDER BY template_version DESC LIMIT 1",
                (rs, n) -> new MaintenanceTemplate(
                        rs.getString("equipment_id"),
                        rs.getInt("template_version"),
                        rs.getLong("overdue_threshold_minutes"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
                equipmentId);
        return rows.stream().findFirst();
    }

    /** 指定模板版本的必检项，按 ordinal 升序返回规范排序。 */
    public List<TemplateItem> listItems(String equipmentId, int templateVersion) {
        return jdbc.query(
                "SELECT equipment_id, template_version, ordinal, item_code, item_title"
                        + " FROM maintenance_template_item"
                        + " WHERE equipment_id = ? AND template_version = ? ORDER BY ordinal ASC",
                (rs, n) -> new TemplateItem(
                        rs.getString("equipment_id"),
                        rs.getInt("template_version"),
                        rs.getInt("ordinal"),
                        rs.getString("item_code"),
                        rs.getString("item_title")),
                equipmentId, templateVersion);
    }
}
