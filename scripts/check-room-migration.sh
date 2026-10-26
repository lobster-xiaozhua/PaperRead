#!/usr/bin/env bash
#
# Room 迁移登记检查（T-006 · R-06 / G6）。
#
# 背景（v1.1 事故口径）：exportSchema=false + 零迁移，动一次数据模型
# 就让升级用户崩在启动。本脚本把「schema 演进必须显式登记」固化为 CI
# 断言，任一项失败即非零退出：
#   1. exportSchema = true（KSP room.schemaLocation 已配，schema 随代码入库）；
#   2. @Database version = N 时，schemas/ 目录必须恰有 N 份 JSON
#      （1.json .. N.json 齐全，不多不少）；
#   3. 每个版本递增都必须登记迁移：autoMigrations 中存在
#      `AutoMigration(from = ?, to = v)`，或 MIGRATIONS 数组非空且包含
#      覆盖该版本的手写迁移（version+1 无登记 = 升级必崩，零容忍）；
#   4. fallbackToDestructiveMigration 在全库源码中禁止出现（用户数据
#      宁可升级报错也不许静默清库，G6⑤）——匹配方法名本身，
#      覆盖 () / From(vararg) / (dropAllTables) 三种入口；
#   5. 迁移测试夹具（src/debug/assets/<数据库类全名>/ 下的 schema JSON，
#      Robolectric 的 MigrationTestHelper 只读变体合并 assets，故测试
#      夹具不能放 src/test/assets）与 app/schemas/ 导出一一一致，防止
#      只改 schema 忘了同步夹具、迁移测试空跑；
#   6. version >= 2 时，src/test 下必须存在同时使用 MigrationTestHelper 与
#      runMigrationsAndValidate 的迁移测试文件（删掉**全部**迁移测试文件不能
#      只剩绿灯——存在性断言，单份被移走仍绿，详见第 6 项注释）。
#
# 用法：./scripts/check-room-migration.sh（在仓库任意目录执行均可）。

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DB_FILE="app/src/main/java/io/github/lunalight/paperread/data/local/AppDatabase.kt"
SCHEMA_DIR="app/schemas/io.github.lunalight.paperread.data.local.AppDatabase"
FIXTURE_DIR="app/src/debug/assets/io.github.lunalight.paperread.data.local.AppDatabase"

fail=0

if [ ! -f "$DB_FILE" ]; then
  echo "✗ 找不到 $DB_FILE" >&2
  exit 1
fi

# ---- 1) exportSchema = true ----

if grep -q 'exportSchema = true' "$DB_FILE"; then
  echo "✓ exportSchema = true（schema 随代码入库）"
else
  echo "✗ exportSchema 未启用：schema JSON 是迁移登记与编译期校验的依据，必须为 true" >&2
  fail=1
fi

# ---- 2) version 与 schemas/ 目录一致 ----

version="$(grep -oE 'version = [0-9]+' "$DB_FILE" | grep -oE '[0-9]+' | head -1)"
if [ -z "$version" ]; then
  echo "✗ 未能解析 @Database version" >&2
  exit 1
fi

schema_files="$(ls "$SCHEMA_DIR"/*.json 2>/dev/null | wc -l | tr -d ' ')"
missing=""
for v in $(seq 1 "$version"); do
  if [ ! -f "$SCHEMA_DIR/$v.json" ]; then
    missing="$missing $v.json"
  fi
done

if [ -n "$missing" ]; then
  echo "✗ schemas/ 目录缺文件：$missing（version=$version 必须有 1..$version 全部 schema）" >&2
  fail=1
elif [ "$schema_files" -ne "$version" ]; then
  echo "✗ schemas/ 目录共 $schema_files 份 JSON，与 version=$version 不一致" >&2
  fail=1
else
  echo "✓ schemas/ 目录 $schema_files 份 JSON，与 version=$version 一致"
fi

# ---- 3) 每个版本递增都已登记迁移 ----
#
# autoMigrations 注解可能跨行，先把注解体压成单行再解析
# `from = X, to = Y` 对。手写迁移（MIGRATIONS 数组）无法静态解析版本
# 配对，采用宽松口径：数组非空即视为已登记（人工评审兜底），并在输出
# 中显式提示，避免误报阻塞。

auto_body="$(tr '\n' ' ' < "$DB_FILE" | grep -oE 'autoMigrations = \[[^]]*\]' || true)"
registered_auto=""
if [ -n "$auto_body" ]; then
  registered_auto="$(printf '%s' "$auto_body" \
    | grep -oE 'to = [0-9]+' | grep -oE '[0-9]+' | sort -un | tr '\n' ' ')"
fi

migrations_body="$(tr '\n' ' ' < "$DB_FILE" \
  | sed -n 's/.*MIGRATIONS: Array<Migration> = \(\[[^]]*\]\).*/\1/p')"
manual_registered=false
if [ -n "$migrations_body" ] && [ "$migrations_body" != "emptyArray()" ]; then
  manual_registered=true
fi

unregistered=""
for v in $(seq 2 "$version"); do
  if printf '%s' " $registered_auto " | grep -q " $v "; then
    continue
  fi
  if [ "$manual_registered" = true ]; then
    echo "  提示：v$v 未见 autoMigrations 登记，依赖 MIGRATIONS 手写迁移（请人工确认覆盖 v$((v-1))→v$v）"
    continue
  fi
  unregistered="$unregistered v$v"
done

if [ -n "$unregistered" ]; then
  echo "✗ 以下版本递增未登记迁移：$unregistered" >&2
  echo "  升 version 必须同时在 autoMigrations / MIGRATIONS 登记，否则升级用户崩在启动。" >&2
  fail=1
else
  echo "✓ version 1→$version 的全部递增均已登记迁移"
fi

# ---- 4) fallbackToDestructiveMigration 全库禁用（G6⑤）----
#
# 匹配面刻意放宽到 `fallbackToDestructiveMigration` 这个**方法名本身**
# （不要求紧跟左括号），因为 Room 的破坏性清库有三个等价入口：
#   .fallbackToDestructiveMigration()
#   .fallbackToDestructiveMigrationFrom(1, 2)   // vararg
#   .fallbackToDestructiveMigration(dropAllTables = true)
# 只匹配 `(` 会漏掉后两种（QA 批次 3 发现的脚本漏洞）。
#
# 为了不误伤注释：先剥掉行尾 `//` 注释，再排除块注释行（`/*` / ` *` 开头）——
# AppDatabase.kt 的 KDoc 里正文明写「不启用 fallbackToDestructiveMigration()」，
# 那不是调用。

fallback_hits="$(grep -rn "fallbackToDestructiveMigration" app/src/main --include="*.kt" 2>/dev/null \
  | sed -E 's#//.*$##' \
  | grep "fallbackToDestructiveMigration" \
  | grep -vE ':[0-9]+:[[:space:]]*(/\*|\*)' || true)"

if [ -n "$fallback_hits" ]; then
  echo "✗ 源码中出现了 fallbackToDestructiveMigration：书架/进度/书签是用户数据，" >&2
  echo "  禁止清库重建式迁移（G6⑤）。命中的三种变体均在内：" >&2
  echo "  fallbackToDestructiveMigration() / ...From(vararg) / ...(dropAllTables)" >&2
  echo "$fallback_hits" >&2
  fail=1
else
  echo "✓ 未启用 fallbackToDestructiveMigration（含 From(vararg) / dropAllTables 变体）"
fi

# ---- 5) 迁移测试夹具与导出一致 ----
#
# src/debug/assets 下的副本只进 debug 变体（release 不打包），
# 供 Robolectric 的 MigrationTestHelper 读取；两处不一致会让
# 迁移测试校验的对象不是当前 schema，等于空跑。

if [ ! -d "$FIXTURE_DIR" ]; then
  echo "✗ 迁移测试夹具目录缺失：$FIXTURE_DIR" >&2
  fail=1
else
  drift=""
  for f in "$SCHEMA_DIR"/*.json; do
    name="$(basename "$f")"
    if [ ! -f "$FIXTURE_DIR/$name" ]; then
      drift="$drift $name(缺失)"
    elif ! diff -q "$f" "$FIXTURE_DIR/$name" >/dev/null; then
      drift="$drift $name(不一致)"
    fi
  done
  if [ -n "$drift" ]; then
    echo "✗ 迁移测试夹具与 app/schemas 导出一致性检查失败：$drift" >&2
    echo "  请重新拷贝：cp app/schemas/.../<db>/*.json $FIXTURE_DIR/" >&2
    fail=1
  else
    echo "✓ 迁移测试夹具与 app/schemas 导出一致（$schema_files 份）"
  fi
fi

# ---- 6) 迁移测试存在性（version >= 2 时必须真有迁移测试）----
#
# 前 5 项只保证「迁移登记过、夹具同步过」，但**删掉迁移测试文件**时它们全绿——
# 迁移链路（schema v1 建库 → 灌存量数据 → runMigrationsAndValidate 走真实迁移）
# 就没有任何自动化覆盖（QA 批次 3 发现的脚本漏洞）。
#
# 断言口径：src/test 下至少存在一个 **同时** 使用 MigrationTestHelper 与
# runMigrationsAndValidate 的测试文件。只 `new MigrationTestHelper(...)` 而不跑
# validate（或反之）不算——那样的"迁移测试"是空跑。
#
# 复验边界（勿误判为脚本失效）：本断言是「存在性」断言，不是「完整性」断言。
# 当前有两份文件同时满足条件——`BookmarkMigrationTest` 与
# `MigrationDataIntegrityTest`——因此**只移走其中一份，本项仍绿**（另一份还在）。
# 要让本项变红，必须把**两份都**移走 / 都去掉 runMigrationsAndValidate。
# 当初"移走迁移测试文件 → 脚本必红"的说法只在「移走全部」时成立；
# 若将来新增第三份迁移测试，同样要全部移走才会红。

if [ "$version" -ge 2 ]; then
  migration_test_file=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if grep -q "MigrationTestHelper" "$f" && grep -q "runMigrationsAndValidate" "$f"; then
      migration_test_file="$f"
      break
    fi
  done <<EOF
$(grep -rl "MigrationTestHelper" app/src/test --include="*.kt" 2>/dev/null || true)
EOF

  if [ -z "$migration_test_file" ]; then
    echo "✗ version=$version 但未找到迁移测试：src/test 下需存在同时使用" >&2
    echo "  MigrationTestHelper 与 runMigrationsAndValidate 的测试文件" >&2
    echo "  （仅建库或仅 validate 都不算；grep 口径见本脚本第 6 项）。" >&2
    fail=1
  else
    echo "✓ 迁移测试就位：$migration_test_file（MigrationTestHelper + runMigrationsAndValidate）"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "Room 迁移登记检查未通过（详见上方输出）。" >&2
  exit 1
fi
echo "Room 迁移登记检查通过（version=$version，迁移已登记，destructive fallback 禁用）。"
