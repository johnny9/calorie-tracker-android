package com.johnny9.calorietracker.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class TrackerDatabaseMigrationTest {
    @Test
    fun migrationOneToTwoPreservesEveryTableAndMatchesTheCommittedSchema() {
        val versionOne = readSchema(1)
        val versionTwo = readSchema(2)
        Class.forName("org.sqlite.JDBC")

        DriverManager.getConnection("jdbc:sqlite::memory:").use { database ->
            createSchema(database, versionOne)
            val recordKeys = seedVersionOne(database, versionOne)
            val countsBefore = versionOne.associate { entity -> entity.tableName to database.rowCount(entity.tableName) }

            database.autoCommit = false
            try {
                database.createStatement().use { statement ->
                    TrackerDatabase.MIGRATION_1_2_STATEMENTS.forEach(statement::execute)
                }
                database.commit()
            } catch (error: Exception) {
                database.rollback()
                throw error
            } finally {
                database.autoCommit = true
            }

            countsBefore.forEach { (table, count) -> assertEquals("$table row count", count, database.rowCount(table)) }
            recordKeys.forEach { (table, record) ->
                val entity = versionOne.single { it.tableName == table }
                assertEquals("$table record survived", 1, database.recordCount(table, entity.primaryKey, record))
            }

            assertEquals("REFERENCE", database.foodQuality("usda-food"))
            assertEquals("USER_ENTERED", database.foodQuality("custom-food"))
            assertEquals(123_000L, database.foodCalories("usda-food"))

            val expectedFoods = versionTwo.single { it.tableName == "foods" }.fields.associateBy(Field::name)
            val migratedFoods = database.columns("foods").associateBy(Column::name)
            assertEquals(expectedFoods.keys, migratedFoods.keys)
            expectedFoods.forEach { (name, expected) ->
                assertNotNull("Missing migrated column $name", migratedFoods[name])
                val actual = requireNotNull(migratedFoods[name])
                assertEquals("$name affinity", expected.affinity, actual.affinity)
                assertEquals("$name nullability", expected.notNull, actual.notNull)
                assertEquals("$name default", expected.defaultValue, actual.defaultValue)
            }

            database.insertLegacyFood("post-migration-food", "OTHER_SOURCE")
            assertEquals("UNSPECIFIED", database.foodQuality("post-migration-food"))
        }
    }

    private fun readSchema(version: Int): List<SchemaEntity> {
        val relative = "schemas/com.johnny9.calorietracker.data.TrackerDatabase/$version.json"
        val file = sequenceOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)
        assertNotNull("Missing committed Room schema $relative", file)
        val database = Json.parseToJsonElement(requireNotNull(file).readText()).jsonObject.getValue("database").jsonObject
        return database.getValue("entities").jsonArray.map { element ->
            val entity = element.jsonObject
            SchemaEntity(
                tableName = entity.text("tableName"),
                createSql = entity.text("createSql"),
                primaryKey = entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.first().jsonPrimitive.content,
                fields = entity.getValue("fields").jsonArray.map { fieldElement ->
                    val field = fieldElement.jsonObject
                    Field(
                        name = field.text("columnName"),
                        affinity = field.text("affinity"),
                        notNull = field["notNull"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        defaultValue = field["defaultValue"]?.jsonPrimitive?.contentOrNull,
                    )
                },
                indexSql = entity["indices"]?.jsonArray?.map { it.jsonObject.text("createSql") }.orEmpty(),
            )
        }
    }

    private fun createSchema(database: Connection, entities: List<SchemaEntity>) {
        database.createStatement().use { statement ->
            entities.forEach { entity ->
                statement.execute(entity.createSql.forTable(entity.tableName))
                entity.indexSql.forEach { statement.execute(it.forTable(entity.tableName)) }
            }
        }
    }

    private fun seedVersionOne(database: Connection, entities: List<SchemaEntity>): Map<String, String> {
        database.insertLegacyFood("usda-food", "USDA_REFERENCE")
        database.insertLegacyFood("custom-food", "USER_CUSTOM")
        return entities.filterNot { it.tableName == "foods" }.associate { entity ->
            val fields = entity.fields.filter(Field::notNull)
            val recordKey = "${entity.tableName}-${entity.primaryKey}"
            val sql = "INSERT INTO `${entity.tableName}` (${fields.joinToString { "`${it.name}`" }}) VALUES (${fields.joinToString { "?" }})"
            database.prepareStatement(sql).use { statement ->
                fields.forEachIndexed { index, field ->
                    val value: Any = when (field.affinity) {
                        "INTEGER" -> 1L
                        "REAL" -> 1.0
                        else -> if (field.name == entity.primaryKey) recordKey else "${entity.tableName}-${field.name}"
                    }
                    statement.setObject(index + 1, value)
                }
                assertEquals(1, statement.executeUpdate())
            }
            entity.tableName to recordKey
        }
    }

    private fun Connection.insertLegacyFood(id: String, source: String) {
        prepareStatement(
            """
            INSERT INTO foods (
                id, name, servingLabel, caloriesMilliKcal, proteinMilliGram, carbsMilliGram,
                fatMilliGram, fiberMilliGram, source, isArchived, isUserCreated, createdAtEpochMs
            ) VALUES (?, ?, '1 serving', 123000, 10000, 20000, 3000, 1000, ?, 0, 1, 123)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, id)
            statement.setString(3, source)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun Connection.rowCount(table: String): Int = createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows -> rows.getInt(1) }
    }

    private fun Connection.recordCount(table: String, keyColumn: String, key: String): Int = prepareStatement(
        "SELECT COUNT(*) FROM `$table` WHERE `$keyColumn` = ?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows -> rows.getInt(1) }
    }

    private fun Connection.foodQuality(id: String): String = prepareStatement(
        "SELECT dataQuality FROM foods WHERE id = ?",
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> rows.getString(1) }
    }

    private fun Connection.foodCalories(id: String): Long = prepareStatement(
        "SELECT caloriesMilliKcal FROM foods WHERE id = ?",
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> rows.getLong(1) }
    }

    private fun Connection.columns(table: String): List<Column> = createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        Column(
                            name = rows.getString("name"),
                            affinity = rows.getString("type"),
                            notNull = rows.getInt("notnull") == 1,
                            defaultValue = rows.getString("dflt_value"),
                        ),
                    )
                }
            }
        }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun String.forTable(table: String): String = replace("\${TABLE_NAME}", table)

    private data class SchemaEntity(
        val tableName: String,
        val createSql: String,
        val primaryKey: String,
        val fields: List<Field>,
        val indexSql: List<String>,
    )

    private data class Field(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private data class Column(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )
}
