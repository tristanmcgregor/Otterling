package app.otterling.data

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs the exact MIGRATION_18_19 SQL string from AppDatabase against a real SQLite engine (via
 * JDBC, not Room/Robolectric) -- a typo or type mismatch in a Room migration only ever fails on a
 * real device's first launch after an update, silently for whoever's holding it, so this is worth
 * verifying directly rather than trusting it by inspection alone.
 */
class SmsOutboxMigration18to19Test {
    @Test
    fun `adding recipientOverride preserves existing rows and defaults them to null`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.use { conn ->
            conn.createStatement().use { stmt ->
                // Exact v18 shape, from MIGRATION_17_18 in AppDatabase.kt.
                stmt.execute(
                    """
                    CREATE TABLE sms_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        body TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        lastAttemptMillis INTEGER NOT NULL,
                        sent INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "INSERT INTO sms_outbox (createdAtMillis, body, attemptCount, lastAttemptMillis, sent) " +
                        "VALUES (1000, 'pre-migration guardian alert', 0, 0, 0)",
                )
            }

            // The exact string from MIGRATION_18_19.
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE sms_outbox ADD COLUMN recipientOverride TEXT")
            }

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT body, recipientOverride FROM sms_outbox WHERE id = 1")
                assertEquals(true, rs.next())
                assertEquals("pre-migration guardian alert", rs.getString("body"))
                rs.getString("recipientOverride")
                assertEquals(true, rs.wasNull())
            }

            // A post-migration insert can now target a specific recipient (the accountability
            // partner case) while an omitted value still defaults to null (the guardian case).
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO sms_outbox (createdAtMillis, body, attemptCount, lastAttemptMillis, sent, recipientOverride) " +
                        "VALUES (2000, 'partner alert', 0, 0, 0, '+61400000000')",
                )
                stmt.execute(
                    "INSERT INTO sms_outbox (createdAtMillis, body, attemptCount, lastAttemptMillis, sent) " +
                        "VALUES (3000, 'guardian alert', 0, 0, 0)",
                )
            }

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT recipientOverride FROM sms_outbox WHERE id = 2")
                assertEquals(true, rs.next())
                assertEquals("+61400000000", rs.getString("recipientOverride"))
            }
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT recipientOverride FROM sms_outbox WHERE id = 3")
                assertEquals(true, rs.next())
                assertNull(rs.getString("recipientOverride"))
            }
        }
    }
}
