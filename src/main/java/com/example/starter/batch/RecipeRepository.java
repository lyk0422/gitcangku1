package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 配方与称量域持久化层：所有 SQL 参数化；质量为 DECIMAL（克），时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class RecipeRepository {

    /**
     * recipe_version 表行记录。
     */
    public record RecipeRow(long id, String recipeKey, int version, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录；percentage 为质量百分比（%），最多 4 位小数。
     */
    public record IngredientRow(long id, String recipeKey, int recipeVersion, String ingredient,
                                BigDecimal percentage, int seq) {
    }

    /**
     * batch_recipe_binding 表行记录；targetMass 单位克，最多 6 位小数。
     */
    public record BindingRow(long id, String batchKey, String recipeKey, int recipeVersion,
                             BigDecimal targetMass, long weighingVersion, String createdAt) {
    }

    /**
     * weighing 表行记录；amount 单位克；supersededBy 为 NULL 时记录当前有效。
     */
    public record WeighingRow(long id, String batchKey, String ingredient, BigDecimal amount,
                              String source, Long supersedesId, Long supersededBy,
                              String operatorId, String createdAt) {
    }

    /**
     * request_log 表行记录：新增写请求幂等快照，身份为结构化 JSON。
     */
    public record RequestRow(String requestId, String identityJson,
                             int responseStatus, String responseBody) {
    }

    private static final RowMapper<RecipeRow> RECIPE_MAPPER = (rs, n) -> new RecipeRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getInt("version"),
            rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getInt("recipe_version"),
            rs.getString("ingredient"), rs.getBigDecimal("percentage"), rs.getInt("seq"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("recipe_key"),
            rs.getInt("recipe_version"), rs.getBigDecimal("target_mass"),
            rs.getLong("weighing_version"), rs.getString("created_at"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("ingredient"),
            rs.getBigDecimal("amount"), rs.getString("source"),
            (Long) rs.getObject("supersedes_id"), (Long) rs.getObject("superseded_by"),
            rs.getString("operator_id"), rs.getString("created_at"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getString("request_id"), rs.getString("identity_json"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecipeRow> findRecipe(String recipeKey, int version) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_key = ? AND version = ?",
                        RECIPE_MAPPER, recipeKey, version)
                .stream().findFirst();
    }

    public void insertRecipe(RecipeRow row) {
        jdbc.update("INSERT INTO recipe_version (recipe_key, version, created_at) VALUES (?, ?, ?)",
                row.recipeKey(), row.version(), row.createdAt());
    }

    public void insertIngredient(String recipeKey, int version, String ingredient,
                                 BigDecimal percentage, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, recipe_version, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?, ?)",
                recipeKey, version, ingredient, percentage, seq);
    }

    /**
     * 配方版本的全部成分，按创建顺序。
     */
    public List<IngredientRow> findIngredients(String recipeKey, int version) {
        return jdbc.query("SELECT * FROM recipe_ingredient WHERE recipe_key = ? AND recipe_version = ?"
                        + " ORDER BY seq", INGREDIENT_MAPPER, recipeKey, version);
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_recipe_binding WHERE batch_key = ?",
                        BINDING_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_recipe_binding"
                        + " (batch_key, recipe_key, recipe_version, target_mass, weighing_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.recipeKey(), row.recipeVersion(), row.targetMass(),
                row.weighingVersion(), row.createdAt());
    }

    /**
     * 称量链版本 +1（在批次行锁内调用，串行化）。
     */
    public void incrementWeighingVersion(String batchKey) {
        jdbc.update("UPDATE batch_recipe_binding SET weighing_version = weighing_version + 1"
                + " WHERE batch_key = ?", batchKey);
    }

    /**
     * 插入称量记录并返回自增主键。
     */
    public long insertWeighing(WeighingRow row) {
        jdbc.update("INSERT INTO weighing (batch_key, ingredient, amount, source, supersedes_id,"
                        + " superseded_by, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.ingredient(), row.amount(), row.source(), row.supersedesId(),
                row.supersededBy(), row.operatorId(), row.createdAt());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM weighing WHERE batch_key = ?",
                Long.class, row.batchKey());
        return id == null ? -1L : id;
    }

    public Optional<WeighingRow> findWeighing(long id) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批次的全部称量记录（含已失效），按提交顺序。
     */
    public List<WeighingRow> findWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? ORDER BY id",
                WEIGHING_MAPPER, batchKey);
    }

    /**
     * 批次的当前有效称量记录（未被更正替代），按提交顺序。
     */
    public List<WeighingRow> findEffectiveWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                        + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    /**
     * 标记称量记录被某条更正记录替代。
     */
    public void markSuperseded(long weighingId, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?",
                supersededBy, weighingId);
    }

    public Optional<RequestRow> findRequest(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?",
                        REQUEST_MAPPER, requestId)
                .stream().findFirst();
    }

    public void insertRequest(RequestRow row, String createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, identity_json, response_status,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                row.requestId(), row.identityJson(), row.responseStatus(),
                row.responseBody(), createdAt);
    }
}
