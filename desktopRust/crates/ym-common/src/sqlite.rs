//! How the JVM app's SQLDelight drivers open a database, so either app opens the other's files:
//! `PRAGMA user_version` 0 creates the schema, an older version runs the same migrations, and a
//! newer one is left as it is. Both steps stamp the version in the same transaction.

use rusqlite::Connection;

/// `create` runs on a file at version 0, which may already hold tables made before the version was
/// stamped, so it is written with `IF NOT EXISTS`. `migrate` takes the file's version.
pub fn open_schema(
    connection: &Connection,
    version: i64,
    create: impl FnOnce(&Connection) -> rusqlite::Result<()>,
    migrate: impl FnOnce(&Connection, i64) -> rusqlite::Result<()>,
) -> rusqlite::Result<()> {
    let transaction = connection.unchecked_transaction()?;
    let found: i64 = transaction.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if found == 0 {
        create(&transaction)?;
        transaction.pragma_update(None, "user_version", version)?;
    } else if found < version {
        migrate(&transaction, found)?;
        transaction.pragma_update(None, "user_version", version)?;
    }
    transaction.commit()
}

/// Runs `steps[n - 1]` for every version `n` from `found` up, as SQLDelight runs `n.sqm` files.
pub fn run_migrations(connection: &Connection, found: i64, steps: &[&str]) -> rusqlite::Result<()> {
    for step in steps.iter().skip((found - 1).max(0) as usize) {
        connection.execute_batch(step)?;
    }
    Ok(())
}
