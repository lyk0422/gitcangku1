-- 合成演示数据：50%/50% 两成分配方（配合 100 克目标总质量可演示放行门禁）。
-- 使用 MERGE INTO 保证重复初始化（如同 JVM 内重建上下文）幂等。
MERGE INTO recipe_version (id, recipe_key, version, created_at) KEY(recipe_key, version)
    VALUES (1, 'RECIPE-DEMO', 1, '2026-01-01T00:00:00Z');
MERGE INTO recipe_ingredient (id, recipe_key, recipe_version, ingredient, percentage, seq) KEY(id)
    VALUES (1, 'RECIPE-DEMO', 1, '成分A', 50.0000, 1);
MERGE INTO recipe_ingredient (id, recipe_key, recipe_version, ingredient, percentage, seq) KEY(id)
    VALUES (2, 'RECIPE-DEMO', 1, '成分B', 50.0000, 2);
