-- 占位文件：默认部署 profile 不初始化任何合成数据。
-- 本地 H2 的合成种子数据位于 data-local.sql，仅在 local profile 经 data-locations 显式加载。
-- 保留一条双方言合法的空操作语句，避免脚本加载器将本文件视为空脚本。
SELECT 1;
