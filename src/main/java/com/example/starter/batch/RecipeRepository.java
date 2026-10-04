package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * 配方与称量域持久化层：所有 SQL 参数化；十进制量以字符串原样存取以保留精度。
 */
@Repository
public class RecipeRepository {

    /**
     * recipe 表行记录。
     */
    public record RecipeRow(long id, String recipeKey, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录；percentage 为十进制字符串，最多 4 位小数。
     */
    public record IngredientRow(long id, String recipeKey, String ingredient,
                                String percentage, int seq) {
    }

    /**
     * batch_recipe_binding 表行记录；targetMass 单位克，十进制字符串，最多 6 位小数。
     */
    public record BindingRow(long id, String batchKey, String recipeKey, String targetMass,
                             String boundBy, String createdAt) {
    }

    /**
     * weighing 表行记录；mass 单位克，十进制字符串，最多 6 位小数。
     */
    public record WeighingRow(long id, String batchKey, String ingredient, String mass,
                              String kind, Long replacesId, Long supersededBy,
                              String actorId, int chainVersion, String createdAt) {
    }

    private static final RowMapper<RecipeRow> RECIPE_MAPPER = (rs, n) -> new RecipeRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("ingredient"),
            rs.getString("percentage"), rs.getInt("seq"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("recipe_key"),
            rs.getString("target_mass"), rs.getString("bound_by"), rs.getString("created_at"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("ingredient"),
            rs.getString("mass"), rs.getString("kind"),
            rs.getObject("replaces_id") == null ? null : rs.getLong("replaces_id"),
            rs.getObject("superseded_by") == null ? null : rs.getLong("superseded_by"),
            rs.getString("actor_id"), rs.getInt("chain_version"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public RecipeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecipeRow> findRecipe(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe WHERE recipe_key = ?", RECIPE_MAPPER, recipeKey)
                .stream().findFirst();
    }

    public void insertRecipe(String recipeKey, String createdAt) {
        jdbc.update("INSERT INTO recipe (recipe_key, created_at) VALUES (?, ?)",
                recipeKey, createdAt);
    }

    public void insertIngredient(String recipeKey, String ingredient, String percentage, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?)", recipeKey, ingredient, percentage, seq);
    }

    /**
     * 配方成分，按创建顺序返回。
     */
    public List<IngredientRow> findIngredients(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_ingredient WHERE recipe_key = ? ORDER BY seq",
                INGREDIENT_MAPPER, recipeKey);
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_recipe_binding WHERE batch_key = ?",
                        BINDING_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_recipe_binding (batch_key, recipe_key, target_mass,"
                        + " bound_by, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.recipeKey(), row.targetMass(), row.boundBy(), row.createdAt());
    }

    /**
     * 批次全部称量记录（含已作废），按称量ID升序。
     */
    public List<WeighingRow> findWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? ORDER BY id",
                WEIGHING_MAPPER, batchKey);
    }

    /**
     * 批次有效称量记录（未被更正作废），按称量ID升序。
     */
    public List<WeighingRow> findValidWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                        + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    public Optional<WeighingRow> findWeighing(long id) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批次称量链当前版本（记录总数）。
     */
    public int countWeighings(String batchKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM weighing WHERE batch_key = ?",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    /**
     * 插入称量记录并返回称量ID。
     */
    public long insertWeighing(WeighingRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO weighing (batch_key, ingredient, mass, kind, replaces_id,"
                            + " superseded_by, actor_id, chain_version, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.ingredient());
            ps.setString(3, row.mass());
            ps.setString(4, row.kind());
            if (row.replacesId() == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, row.replacesId());
            }
            if (row.supersededBy() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, row.supersededBy());
            }
            ps.setString(7, row.actorId());
            ps.setInt(8, row.chainVersion());
            ps.setString(9, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
    }

    /**
     * 标记称量记录被更正作废。
     */
    public void markSuperseded(long id, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?", supersededBy, id);
    }
}