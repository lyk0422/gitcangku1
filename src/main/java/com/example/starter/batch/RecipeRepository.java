package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 配方与称量域持久化层：所有 SQL 参数化；百分比与质量以十进制纯数字字符串存取，避免精度漂移。
 */
@Repository
public class RecipeRepository {

    /**
     * recipe_version 表行记录。
     */
    public record RecipeRow(long id, String recipeKey, String createdBy, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录；percentage 为十进制字符串（最多 4 位小数）。
     */
    public record IngredientRow(long id, String recipeKey, String ingredient,
                                String percentage, int seq) {
    }

    /**
     * batch_recipe 表行记录；targetMassG 为十进制字符串（最多 6 位小数，单位克）。
     */
    public record BatchRecipeRow(long id, String batchKey, String recipeKey,
                                 String targetMassG, String createdAt) {
    }

    /**
     * weighing 表行记录；supersededBy 非空表示该记录已被更正替代而失效。
     */
    public record WeighingRow(long id, String batchKey, String requestId, String operator,
                              String ingredient, String massG, Long supersedesId,
                              Long supersededBy, String createdAt) {
    }

    private static final RowMapper<RecipeRow> RECIPE_MAPPER = (rs, n) -> new RecipeRow(
            rs.getLong("id"), rs.getString("recipe_key"),
            rs.getString("created_by"), rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("ingredient"),
            rs.getString("percentage"), rs.getInt("seq"));

    private static final RowMapper<BatchRecipeRow> BATCH_RECIPE_MAPPER = (rs, n) -> new BatchRecipeRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("recipe_key"),
            rs.getString("target_mass_g"), rs.getString("created_at"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("request_id"),
            rs.getString("operator"), rs.getString("ingredient"), rs.getString("mass_g"),
            (Long) rs.getObject("supersedes_id"), (Long) rs.getObject("superseded_by"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRecipe(RecipeRow row) {
        jdbc.update("INSERT INTO recipe_version (recipe_key, created_by, created_at) VALUES (?, ?, ?)",
                row.recipeKey(), row.createdBy(), row.createdAt());
    }

    public Optional<RecipeRow> findRecipe(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_key = ?",
                        RECIPE_MAPPER, recipeKey)
                .stream().findFirst();
    }

    public void insertIngredient(String recipeKey, String ingredient, String percentage, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?)",
                recipeKey, ingredient, percentage, seq);
    }

    public List<IngredientRow> findIngredients(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_ingredient WHERE recipe_key = ? ORDER BY seq",
                INGREDIENT_MAPPER, recipeKey);
    }

    public void insertBatchRecipe(BatchRecipeRow row) {
        jdbc.update("INSERT INTO batch_recipe (batch_key, recipe_key, target_mass_g, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.batchKey(), row.recipeKey(), row.targetMassG(), row.createdAt());
    }

    public Optional<BatchRecipeRow> findBatchRecipe(String batchKey) {
        return jdbc.query("SELECT * FROM batch_recipe WHERE batch_key = ?",
                        BATCH_RECIPE_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 插入称量记录并返回自增主键（即称量记录ID）。
     */
    public long insertWeighing(WeighingRow row) {
        jdbc.update("INSERT INTO weighing (batch_key, request_id, operator, ingredient, mass_g,"
                        + " supersedes_id, superseded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.requestId(), row.operator(), row.ingredient(), row.massG(),
                row.supersedesId(), row.supersededBy(), row.createdAt());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM weighing WHERE batch_key = ?",
                Long.class, row.batchKey());
        return id;
    }

    public Optional<WeighingRow> findWeighing(long weighingId) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, weighingId)
                .stream().findFirst();
    }

    /**
     * 批次全部称量记录（含已失效），按记录ID升序。
     */
    public List<WeighingRow> findWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? ORDER BY id",
                WEIGHING_MAPPER, batchKey);
    }

    /**
     * 批次当前有效称量记录（未被更正替代），按记录ID升序。
     */
    public List<WeighingRow> findValidWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                        + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    public void markSuperseded(long weighingId, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?",
                supersededBy, weighingId);
    }

    /**
     * 批次称量链版本：称量记录总数（每次称量/更正/拆批继承递增）。
     */
    public long weighingVersion(String batchKey) {
        Long version = jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Long.class, batchKey);
        return version == null ? 0L : version;
    }
}
