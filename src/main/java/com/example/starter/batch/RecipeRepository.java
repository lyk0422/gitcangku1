package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 配方与称量域持久化层：所有 SQL 参数化；质量以 DECIMAL 存取，时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class RecipeRepository {

    /**
     * recipe_version 表行记录。
     */
    public record RecipeVersionRow(long id, String recipeKey, int version, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录。
     */
    public record IngredientRow(long id, String recipeKey, int recipeVersion, String ingredient,
                                BigDecimal percentage, int seq) {
    }

    /**
     * batch_recipe 表行记录：批次配方绑定。
     */
    public record BatchRecipeRow(long id, String batchKey, String recipeKey, int recipeVersion,
                                 BigDecimal targetMass, long stateVersion, String createdAt) {
    }

    /**
     * weighing 表行记录；supersedesId/supersededBy 为 null 含义见 schema COMMENT。
     */
    public record WeighingRow(long id, String batchKey, String ingredient, BigDecimal mass,
                              Long supersedesId, Long supersededBy, String operator, String createdAt) {
    }

    private static final RowMapper<RecipeVersionRow> VERSION_MAPPER = (rs, n) -> new RecipeVersionRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getInt("version"),
            rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getInt("recipe_version"),
            rs.getString("ingredient"), rs.getBigDecimal("percentage"), rs.getInt("seq"));

    private static final RowMapper<BatchRecipeRow> BATCH_RECIPE_MAPPER = (rs, n) -> new BatchRecipeRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("recipe_key"),
            rs.getInt("recipe_version"), rs.getBigDecimal("target_mass"),
            rs.getLong("state_version"), rs.getString("created_at"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("ingredient"),
            rs.getBigDecimal("mass"),
            rs.getObject("supersedes_id") == null ? null : rs.getLong("supersedes_id"),
            rs.getObject("superseded_by") == null ? null : rs.getLong("superseded_by"),
            rs.getString("operator"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecipeVersionRow> findRecipeVersion(String recipeKey, int version) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_key = ? AND version = ?",
                        VERSION_MAPPER, recipeKey, version)
                .stream().findFirst();
    }

    public void insertRecipeVersion(RecipeVersionRow row) {
        jdbc.update("INSERT INTO recipe_version (recipe_key, version, created_at) VALUES (?, ?, ?)",
                row.recipeKey(), row.version(), row.createdAt());
    }

    public List<IngredientRow> findIngredients(String recipeKey, int version) {
        return jdbc.query(
                "SELECT * FROM recipe_ingredient WHERE recipe_key = ? AND recipe_version = ? ORDER BY seq",
                INGREDIENT_MAPPER, recipeKey, version);
    }

    public void insertIngredient(IngredientRow row) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, recipe_version, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.recipeKey(), row.recipeVersion(), row.ingredient(), row.percentage(), row.seq());
    }

    public Optional<BatchRecipeRow> findBatchRecipe(String batchKey) {
        return jdbc.query("SELECT * FROM batch_recipe WHERE batch_key = ?",
                        BATCH_RECIPE_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatchRecipe(BatchRecipeRow row) {
        jdbc.update("INSERT INTO batch_recipe (batch_key, recipe_key, recipe_version, target_mass,"
                        + " state_version, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.recipeKey(), row.recipeVersion(), row.targetMass(),
                row.stateVersion(), row.createdAt());
    }

    /**
     * 称量链版本号加 1（称量或更正落定后调用）。
     */
    public void incrementStateVersion(String batchKey) {
        jdbc.update("UPDATE batch_recipe SET state_version = state_version + 1 WHERE batch_key = ?",
                batchKey);
    }

    public Optional<WeighingRow> findWeighing(long id) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批次当前有效（未被替代）的称量记录，按提交顺序。
     */
    public List<WeighingRow> findEffectiveWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    /**
     * 插入称量记录并返回自增主键。
     */
    public long insertWeighing(WeighingRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO weighing (batch_key, ingredient, mass, supersedes_id, superseded_by,"
                            + " operator, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.ingredient());
            ps.setBigDecimal(3, row.mass());
            if (row.supersedesId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, row.supersedesId());
            }
            ps.setNull(5, java.sql.Types.BIGINT);
            ps.setString(6, row.operator());
            ps.setString(7, row.createdAt());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "称量记录未返回自增主键").longValue();
    }

    /**
     * 标记某条称量记录已被更正记录替代（不再有效）。
     */
    public void markSuperseded(long weighingId, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?", supersededBy, weighingId);
    }
}
