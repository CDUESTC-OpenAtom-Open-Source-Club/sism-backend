#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const SCRIPT_DIR = __dirname;
const ROOT_DIR = path.resolve(SCRIPT_DIR, "..", "..");
const SEED_DIR = path.join(ROOT_DIR, "database", "seeds");
const RESET_FILE = path.join(SEED_DIR, "reset-and-load-clean-seeds.sql");
const ENV_FILE = path.join(ROOT_DIR, ".env");

function fail(message) {
  console.error(`[FAIL] ${message}`);
  process.exit(1);
}

function parseEnvFile(filePath) {
  if (!fs.existsSync(filePath)) {
    return {};
  }

  return Object.fromEntries(
    fs
      .readFileSync(filePath, "utf8")
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#") && line.includes("="))
      .map((line) => {
        const index = line.indexOf("=");
        return [line.slice(0, index), line.slice(index + 1)];
      }),
  );
}

function parseJdbcUrl(jdbcUrl) {
  const match = jdbcUrl.match(
    /^jdbc:postgresql:\/\/(?<host>[^/:?]+)(?::(?<port>\d+))?\/(?<dbname>[^?]+)/,
  );

  if (!match || !match.groups) {
    fail(`Unsupported DB_URL. Expected PostgreSQL-wire JDBC URL, got: ${jdbcUrl}`);
  }

  return {
    host: match.groups.host,
    port: match.groups.port || "5432",
    dbname: match.groups.dbname,
  };
}

function loadDbConfig() {
  const envFileValues = parseEnvFile(ENV_FILE);
  const dbUrl = process.env.DB_URL || envFileValues.DB_URL;
  const username = process.env.DB_USERNAME || envFileValues.DB_USERNAME;
  const password = process.env.DB_PASSWORD || envFileValues.DB_PASSWORD;

  if (!dbUrl || !username || !password) {
    fail(
      `Missing DB_URL / DB_USERNAME / DB_PASSWORD from environment or ${ENV_FILE}`,
    );
  }

  return {
    ...parseJdbcUrl(dbUrl),
    username,
    password,
  };
}

function loadSeedEntries() {
  const resetText = fs.readFileSync(RESET_FILE, "utf8");
  const includeFiles = [...resetText.matchAll(/^\\i\s+(.+)$/gm)].map((match) =>
    match[1].trim(),
  );

  return includeFiles.map((fileName) => parseSeedFile(fileName));
}

function normalizeColumnName(rawColumn) {
  return rawColumn.trim().replace(/^"|"$/g, "");
}

function parseSeedFile(fileName) {
  const fullPath = path.join(SEED_DIR, fileName);
  const text = fs.readFileSync(fullPath, "utf8");
  const insertRegex =
    /INSERT\s+INTO\s+public\.([a-zA-Z0-9_]+)\s*\(([\s\S]*?)\)\s*(?:VALUES|SELECT|WITH)\b/gi;
  const statements = [];
  let match;

  while ((match = insertRegex.exec(text)) !== null) {
    const columns = match[2]
      .split(",")
      .map((column) => column.replace(/--.*$/g, ""))
      .map(normalizeColumnName)
      .filter(Boolean);

    statements.push({
      table: match[1],
      columns,
    });
  }

  const isEmptyBaseline =
    /Intentionally no seed rows/i.test(text) ||
    /SELECT\s+1\s+WHERE\s+FALSE/i.test(text);

  return {
    fileName,
    fullPath,
    statements,
    isEmptyBaseline,
    inferredTableName: fileName.replace(/-(data|clean)\.sql$/, ""),
  };
}

function querySchema(dbConfig, tableNames) {
  const sql = `
WITH target_tables AS (
    SELECT unnest(string_to_array($$${tableNames.join(",")}$$, ',')) AS table_name
)
SELECT
    'TABLE' AS kind,
    t.table_name,
    '' AS column_name,
    '' AS is_nullable,
    '' AS column_default,
    '' AS udt_name,
    '' AS is_identity,
    '' AS is_generated
FROM information_schema.tables t
JOIN target_tables tt ON tt.table_name = t.table_name
WHERE t.table_schema = 'public'
UNION ALL
SELECT
    'COLUMN' AS kind,
    c.table_name,
    c.column_name,
    c.is_nullable,
    COALESCE(c.column_default, ''),
    COALESCE(c.udt_name, c.data_type),
    COALESCE(c.is_identity, 'NO'),
    COALESCE(c.is_generated, 'NEVER')
FROM information_schema.columns c
JOIN target_tables tt ON tt.table_name = c.table_name
WHERE c.table_schema = 'public'
ORDER BY 1, 2, 3;
`;

  const output = execFileSync(
    "psql",
    [
      "--host",
      dbConfig.host,
      "--port",
      dbConfig.port,
      "--username",
      dbConfig.username,
      "--dbname",
      dbConfig.dbname,
      "-AtF",
      "\t",
      "-c",
      sql,
    ],
    {
      env: {
        ...process.env,
        PGPASSWORD: dbConfig.password,
      },
      encoding: "utf8",
    },
  );

  const tableSet = new Set();
  const schemaByTable = new Map();

  for (const line of output.split(/\r?\n/).filter(Boolean)) {
    const [
      kind,
      tableName,
      columnName,
      isNullable,
      columnDefault,
      udtName,
      isIdentity,
      isGenerated,
    ] = line.split("\t");

    if (kind === "TABLE") {
      tableSet.add(tableName);
      continue;
    }

    if (!schemaByTable.has(tableName)) {
      schemaByTable.set(tableName, new Map());
    }

    schemaByTable.get(tableName).set(columnName, {
      isNullable,
      columnDefault,
      udtName,
      isIdentity,
      isGenerated,
    });
  }

  return {
    tableSet,
    schemaByTable,
  };
}

function isRequiredWithoutDefault(column) {
  return (
    column.isNullable === "NO" &&
    !column.columnDefault &&
    column.isIdentity !== "YES" &&
    column.isGenerated === "NEVER"
  );
}

function evaluateSeedEntries(seedEntries, schema) {
  const failures = [];
  const warnings = [];
  const passes = [];

  for (const entry of seedEntries) {
    if (entry.statements.length === 0) {
      const tableExists = schema.tableSet.has(entry.inferredTableName);
      if (!tableExists) {
        failures.push(
          `${entry.fileName}: expected table public.${entry.inferredTableName} does not exist`,
        );
      } else if (!entry.isEmptyBaseline) {
        warnings.push(
          `${entry.fileName}: no INSERT statement parsed; only verified that public.${entry.inferredTableName} exists`,
        );
      } else {
        passes.push(
          `${entry.fileName}: empty baseline file matches public.${entry.inferredTableName}`,
        );
      }
      continue;
    }

    for (const statement of entry.statements) {
      if (!schema.tableSet.has(statement.table)) {
        failures.push(
          `${entry.fileName}: target table public.${statement.table} does not exist`,
        );
        continue;
      }

      const columns = schema.schemaByTable.get(statement.table) || new Map();
      const missingColumns = statement.columns.filter(
        (columnName) => !columns.has(columnName),
      );
      if (missingColumns.length > 0) {
        failures.push(
          `${entry.fileName}: public.${statement.table} is missing referenced column(s): ${missingColumns.join(
            ", ",
          )}`,
        );
      }

      const missingRequiredColumns = [];
      for (const [columnName, metadata] of columns.entries()) {
        if (
          isRequiredWithoutDefault(metadata) &&
          !statement.columns.includes(columnName)
        ) {
          missingRequiredColumns.push(columnName);
        }
      }

      if (missingRequiredColumns.length > 0) {
        failures.push(
          `${entry.fileName}: public.${statement.table} has required column(s) not covered by seed INSERT: ${missingRequiredColumns.join(
            ", ",
          )}`,
        );
      }

      if (missingColumns.length === 0 && missingRequiredColumns.length === 0) {
        passes.push(
          `${entry.fileName}: INSERT columns match public.${statement.table}`,
        );
      }
    }
  }

  return {
    failures,
    warnings,
    passes,
  };
}

function main() {
  if (!fs.existsSync(RESET_FILE)) {
    fail(`Reset entry file not found: ${RESET_FILE}`);
  }

  const dbConfig = loadDbConfig();
  const seedEntries = loadSeedEntries();
  const tableNames = [
    ...new Set(
      seedEntries.flatMap((entry) =>
        entry.statements.length > 0
          ? entry.statements.map((statement) => statement.table)
          : [entry.inferredTableName],
      ),
    ),
  ].sort();

  const schema = querySchema(dbConfig, tableNames);
  const result = evaluateSeedEntries(seedEntries, schema);

  console.log("Seed schema drift check");
  console.log(
    `Database: ${dbConfig.host}:${dbConfig.port}/${dbConfig.dbname} (${dbConfig.username})`,
  );
  console.log(`Seed entries: ${seedEntries.length}`);
  console.log("");

  for (const message of result.passes) {
    console.log(`[PASS] ${message}`);
  }

  for (const message of result.warnings) {
    console.log(`[WARN] ${message}`);
  }

  for (const message of result.failures) {
    console.error(`[FAIL] ${message}`);
  }

  console.log("");
  console.log(
    `Summary: ${result.passes.length} pass, ${result.warnings.length} warn, ${result.failures.length} fail`,
  );

  if (result.failures.length > 0) {
    process.exit(1);
  }
}

main();
