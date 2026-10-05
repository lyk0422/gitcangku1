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
import java.util.Optional;

/**
 * 配方称量域持久化层：所有 SQL 参数化；质量以 DECIMAL 存取（BigDecimal），
 * 时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class RecipeRepository {

    /**
     * recipe_version 表行记录。
     */
    public record RecipeRow(long id, String recipeKey, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录；percentage 为质量百分比（正值，最多 4 位小数）。
     */
    public record IngredientRow(long id, String recipeKey, String ingredient,
                                BigDecimal percentage, int seq) {
    }

    /**
     * batch_recipe_binding 表行记录；targetMass 单位克（正值，最多 6 位小数）。
     */
    public record BindingRow(long id, String batchKey, String recipeKey,
                             BigDecimal targetMass, String boundAt) {
    }

    /**
     * weighing 表行记录。correctsId 非空表示更正记录；supersededBy 非空表示已被替代失效。
     */
    public record WeighingRow(long id, String batchKey, String requestId, String ingredient,
                              BigDecimal mass, Long correctsId, Long supersededBy,
                              String source, String operatorId, String createdAt) {
    }

    private static final RowMapper<RecipeRow> RECIPE_MAPPER = (rs, n) -> new RecipeRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("ingredient"),
            rs.getBigDecimal("percentage"), rs.getInt("seq"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("recipe_key"),
            rs.getBigDecimal("target_mass"), rs.getString("bound_at"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("request_id"),
            rs.getString("ingredient"), rs.getBigDecimal("mass"),
            (Long) rs.getObject("corrects_id"), (Long) rs.getObject("superseded_by"),
            rs.getString("source"), rs.getString("operator_id"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRecipe(String recipeKey, String createdAt) {
        jdbc.update("INSERT INTO recipe_version (recipe_key, created_at) VALUES (?, ?)",
                recipeKey, createdAt);
    }

    public Optional<RecipeRow> findRecipe(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_key = ?",
                        RECIPE_MAPPER, recipeKey)
                .stream().findFirst();
    }

    public void insertIngredient(String recipeKey, String ingredient, BigDecimal percentage, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?)",
                recipeKey, ingredient, percentage, seq);
    }

    /**
     * 配方成分，按创建顺序返回。
     */
    public List<IngredientRow> findIngredients(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_ingredient WHERE recipe_key = ? ORDER BY seq",
                INGREDIENT_MAPPER, recipeKey);
    }

    public void insertBinding(String batchKey, String recipeKey, BigDecimal targetMass, String boundAt) {
        jdbc.update("INSERT INTO batch_recipe_binding (batch_key, recipe_key, target_mass, bound_at)"
                        + " VALUES (?, ?, ?, ?)",
                batchKey, recipeKey, targetMass, boundAt);
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_recipe_binding WHERE batch_key = ?",
                        BINDING_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 插入称量记录并返回自增主键。
     */
    public long insertWeighing(WeighingRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO weighing (batch_key, request_id, ingredient, mass, corrects_id,"
                            + " superseded_by, source, operator_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.requestId());
            ps.setString(3, row.ingredient());
            ps.setBigDecimal(4, row.mass());
            if (row.correctsId() == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, row.correctsId());
            }
            if (row.supersededBy() == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, row.supersededBy());
            }
            ps.setString(7, row.source());
            ps.setString(8, row.operatorId());
            ps.setString(9, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<WeighingRow> findWeighing(long id) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批次全部称量记录（含已失效），按 id 升序。
     */
    public List<WeighingRow> findWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? ORDER BY id",
                WEIGHING_MAPPER, batchKey);
    }

    /**
     * 批次当前有效称量记录（未被更正替代），按 id 升序。
     */
    public List<WeighingRow> findEffectiveWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                        + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    /**
     * 称量链版本：批次称量记录总数（含更正与已失效记录）。
     */
    public long countWeighings(String batchKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Long.class, batchKey);
        return count == null ? 0L : count;
    }

    /**
     * 标记称量记录被某条更正替代。
     */
    public void markSuperseded(long id, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?", supersededBy, id);
    }
}