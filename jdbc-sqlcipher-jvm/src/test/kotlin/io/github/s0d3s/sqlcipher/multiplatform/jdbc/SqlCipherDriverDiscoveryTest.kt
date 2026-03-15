package io.github.s0d3s.sqlcipher.multiplatform.jdbc

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlCipherDriverDiscoveryTest {

    @Test
    fun driver_shouldBeDiscoverable_viaJdbcSpi_withoutExplicitClassLoading() {
        val driver = DriverManager.getDriver("jdbc:sqlcipher:spi-discovery-check.db")

        assertEquals(
            "io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver",
            driver::class.qualifiedName
        )
        assertTrue(driver.acceptsURL("jdbc:sqlcipher:spi-discovery-check.db"))
    }
}