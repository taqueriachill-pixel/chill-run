package com.chill.run

import com.chill.run.health.Health
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * アプリが求めるヘルスコネクトの許可が、すべて AndroidManifest.xml に書かれているかを確かめる。
 * 書き忘れがあると、許可の画面にその項目が出ず、書き込みや読み込みができない。
 */
class ManifestPermissionTest {
    @Test
    fun every_health_permission_is_declared_in_manifest() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }.readText()
        val declared = Regex("""uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = Health.allPermissions - declared
        assertTrue("AndroidManifest.xml に足りない許可: $missing", missing.isEmpty())
        assertTrue(Health.allPermissions.size >= 16)
    }
}
