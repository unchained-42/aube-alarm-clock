package com.reveil.aube.permissions

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Covers the reliability-gate logic every "turn the alarm on" decision goes through:
 * [missingBlockingChecks] (what actually blocks enabling the alarm) and
 * [PermissionsHelper.oemAutostartIntent] (the per-manufacturer advisory, deliberately kept
 * out of the blocking list — see its doc comment for why, and [oemAutostartAdvisoryCheck]).
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsHelperTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetManufacturer() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "unknown")
    }

    private fun resolveInfoFor(pkg: String, cls: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = cls
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    @Test
    fun `oemAutostartIntent is null for a manufacturer with no known autostart screen`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "google")
        assertNull(PermissionsHelper.oemAutostartIntent(context))
    }

    @Test
    fun `oemAutostartIntent is null for a known manufacturer when the target screen isn't resolvable`() {
        // Known manufacturer (samsung), but nothing registered a matching activity — same as a
        // real device where that OEM app isn't installed or was renamed in a firmware update.
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
        assertNull(PermissionsHelper.oemAutostartIntent(context))
    }

    @Test
    fun `oemAutostartIntent returns the matching intent once the target screen is resolvable`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "xiaomi")
        val expected = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            expected,
            resolveInfoFor("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        )

        val intent = PermissionsHelper.oemAutostartIntent(context)
        assertNotNull(intent)
        assertEquals("com.miui.securitycenter", intent!!.component?.packageName)
        assertEquals(context.packageName, intent.getStringExtra("extra_pkgname"))
    }

    @Test
    fun `oemAutostartIntent manufacturer matching is case-insensitive`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "XIAOMI")
        val expected = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            expected,
            resolveInfoFor("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        )
        assertNotNull(PermissionsHelper.oemAutostartIntent(context))
    }

    @Test
    fun `oemAutostartAdvisoryCheck mirrors oemAutostartIntent`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "google")
        assertNull(oemAutostartAdvisoryCheck(context))
    }

    // --- missingBlockingChecks ---

    @Config(sdk = [33]) // below UPSIDE_DOWN_CAKE: canUseFullScreenIntent short-circuits to true
    @Test
    fun `missingBlockingChecks is empty once every check is satisfied`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)

        assertTrue(missingBlockingChecks(context).isEmpty())
    }

    @Config(sdk = [33])
    @Test
    fun `missingBlockingChecks lists every check that is not satisfied`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)

        // Full-screen intent excluded here (sdk-gated to always-true), so exactly the other
        // four should show up as missing.
        assertEquals(4, missingBlockingChecks(context).size)
    }

    @Config(sdk = [33])
    @Test
    fun `canUseFullScreenIntent is always true below API 34`() {
        assertTrue(PermissionsHelper.canUseFullScreenIntent(context))
    }
}
