package dev.framio.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The launcher reads three root command outputs as text; these pin the formats of Android 6 (toolbox) and toybox. */
class ParsersTest {

    @Test
    fun `ps groups app processes by package and skips system ones`() {
        val toolbox = """
            USER      PID   PPID  VSIZE  RSS   WCHAN              PC  NAME
            root      1     0     8840   756   SyS_epoll_ 00000000 S /init
            system    512   210   1004000 47000 SyS_epoll_ 00000000 S system_server
            u0_a2     901   210   1100000 147000 SyS_epoll_ 00000000 S net.frameo.frame
            u0_a2     955   210   900000 12000 SyS_epoll_ 00000000 S net.frameo.frame:remote
            u0_a61    1200  210   850000 31000 SyS_epoll_ 00000000 S dev.framio.mawaqit
            u0_a70    1300  210   800000 15000 SyS_epoll_ 00000000 S zygote_helper
        """.trimIndent()
        val rss = parsePs(toolbox).groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        assertEquals(mapOf("net.frameo.frame" to 159000L, "dev.framio.mawaqit" to 31000L), rss)
    }

    @Test
    fun `ps columns are found by name in toybox output`() {
        val toybox = """
            USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
            u0_a61        1200   210  850000  31000 0                   0 S dev.framio.mawaqit
        """.trimIndent()
        assertEquals(listOf("dev.framio.mawaqit" to 31000L), parsePs(toybox))
    }

    @Test
    fun `ps without a header gives nothing`() {
        assertEquals(emptyList<Pair<String, Long>>(), parsePs("su: permission denied"))
    }

    @Test
    fun `resumed activity package`() {
        val out = """
              Stack #1:
                mResumedActivity: ActivityRecord{2a1b3c u0 net.frameo.frame/.feature.slideshow.ui.AScreenSlidePager t9}
              mFocusedActivity: ActivityRecord{1 u0 dev.framio.launcher/.LauncherActivity t1}
        """.trimIndent()
        assertEquals("net.frameo.frame", parseResumed(out))
        assertNull(parseResumed("nothing here"))
    }

    @Test
    fun `dumpsys wifi scan table keeps SSIDs with spaces`() {
        val out = """
            mWifiInfo SSID: Home, BSSID: 11:22:33:44:55:66
            Latest scan results:
                BSSID              Frequency  RSSI    Age      SSID                                 Flags
              11:22:33:44:55:66      2437   -48    3.120   Home                             [WPA2-PSK-CCMP][ESS]
              aa:bb:cc:dd:ee:ff      2412   -71   12.004   Cafe Guest Wi-Fi                 [ESS]
              aa:bb:cc:dd:ee:00      2462   -80 >1000.000  Neighbour 2.4                    [WPA-PSK-TKIP][WPS][ESS]

            Locks acquired: 0 full, 0 full high perf
        """.trimIndent()
        assertEquals(
            listOf(
                Triple("Home", -48, "[WPA2-PSK-CCMP][ESS]"),
                Triple("Cafe Guest Wi-Fi", -71, "[ESS]"),
                Triple("Neighbour 2.4", -80, "[WPA-PSK-TKIP][WPS][ESS]"),
            ),
            parseDumpsysScan(out),
        )
    }

    @Test
    fun `root refuses anything but package names`() {
        assertEquals("dev.framio.lab", Root.pkg("dev.framio.lab"))
        listOf("x; reboot", "dev.framio.lab && rm -rf /", "nodots", "\$(id).a").forEach {
            try {
                Root.pkg(it)
                throw AssertionError("accepted $it")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
