package me.heartalborada.state

import org.h2.jdbcx.JdbcDataSource
import java.io.File
import javax.sql.DataSource

internal data class TestStateDatabases(
    val host: DataSource,
    val comic: DataSource,
    val permissions: DataSource,
) {
    fun store() = BotStateStore(host, comic, permissions)
}

internal fun testStateDatabases(root: File): TestStateDatabases = TestStateDatabases(
    host = testDataSource(root.resolve("data/data")),
    comic = testDataSource(root.resolve("plugins/comic/comic")),
    permissions = testDataSource(root.resolve("plugins/permissions/permissions")),
)

internal fun testStateStore(root: File): BotStateStore = testStateDatabases(root).store()

private fun testDataSource(databaseFile: File): DataSource {
    databaseFile.parentFile.mkdirs()
    return JdbcDataSource().apply {
        setURL("jdbc:h2:file:${databaseFile.canonicalFile.invariantSeparatorsPath};LOCK_TIMEOUT=10000")
        user = "sa"
        password = ""
    }
}
