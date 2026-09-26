package com.procreate.android.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the paywall's shape. A subscription gate fails in two directions and both are expensive: a
 * gate that stops firing gives the paid features away silently, and a second way to grant access
 * means a bug anywhere can hand out entitlements Play never sold.
 *
 * These read source rather than behaviour because the billing classes need a real Play connection
 * and an Android Context; what can be checked without a device is that the wiring is still there
 * and still routes through one place.
 */
class BillingGateTest {

    private val moduleDir: File by lazy {
        var dir = File(".").canonicalFile
        while (!File(dir, "src/main/java").isDirectory) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun source(path: String) = File(moduleDir, "src/main/java/com/procreate/android/$path")

    private fun mainSources(): Sequence<File> =
        File(moduleDir, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }

    @Test
    fun `sources are actually found`() {
        assertTrue("Could not locate src/main/java", File(moduleDir, "src/main/java").isDirectory)
        assertTrue("Entitlements.kt missing", source("billing/Entitlements.kt").isFile)
    }

    @Test
    fun `plan analysis is free, and its paywall seam is still in place`() {
        // Free on purpose: a Play build ships no API key, so analysis runs on a Gemini key the user
        // pays Google for. Charging for it would be selling a feature the buyer funds themselves.
        val entitlements = source("billing/Entitlements.kt").readText()
        val policy = Regex("""Feature\.PLAN_ANALYSIS\s*->\s*(\w+)""").find(entitlements)
        assertEquals(
            "The plan-analysis policy changed. That is only correct once the model is proxied " +
                "through a server on the developer's key - otherwise subscribers pay twice.",
            "true", policy?.groupValues?.get(1)
        )

        // The call site stays even while the answer is always true: it is what makes the decision
        // reversible in one line instead of a hunt through the app.
        val activity = source("ui/canvas/CanvasActivity.kt").readText()
        assertTrue(
            "The seam in startPlanAnalysis is gone; re-gating analysis later would mean finding " +
                "the call site again.",
            activity.contains("Entitlements.Feature.PLAN_ANALYSIS")
        )
    }

    @Test
    fun `the paywall does not advertise a free feature`() {
        // Selling what the user already gets for nothing is the fastest route to a refund request.
        val dialog = source("billing/SubscriptionDialog.kt").readText()
        val featureBlock = dialog.substringAfter("val scroll").substringBefore("statusView =")
        assertTrue(
            "The paywall still lists AI plan analysis as a paid feature, but it is free.",
            !featureBlock.contains("feature(\n            \"تحليل المخططات")
        )
    }

    @Test
    fun `dxf export is gated`() {
        val view = source("canvas/DrawingView.kt").readText()
        assertTrue(
            "exportUrbanDxf no longer checks Entitlements - DXF export would be free.",
            view.contains("Entitlements.Feature.DXF_EXPORT")
        )
    }

    @Test
    fun `only BillingManager can grant entitlement`() {
        // setSubscribed is internal so the compiler already blocks other modules; this catches the
        // case that matters more in a single-module app - another file in this one calling it.
        val callers = mainSources()
            .filter { it.readText().contains("setSubscribed") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "Something other than BillingManager grants entitlement. Access must only ever come " +
                "from what Play reports, never from app logic.",
            sortedSetOf("BillingManager.kt", "Entitlements.kt"),
            callers
        )
    }

    @Test
    fun `purchases are acknowledged`() {
        // Play auto-refunds and revokes any purchase left unacknowledged for three days. Losing
        // this call does not fail anything visibly - it just cancels real subscriptions later.
        val manager = source("billing/BillingManager.kt").readText()
        assertTrue(
            "BillingManager no longer acknowledges purchases - Play will refund them after 3 days.",
            manager.contains("acknowledgePurchase")
        )
        assertTrue(
            "BillingManager no longer queries existing purchases - subscriptions would not restore " +
                "on a new device, and expiry would never revoke access.",
            manager.contains("queryPurchasesAsync")
        )
    }

    @Test
    fun `no price is hardcoded in the paywall`() {
        // Play prices per country. A literal amount would be wrong for most users and would drift
        // the moment the price is edited in the Console, so prices must come from ProductDetails.
        val dialog = source("billing/SubscriptionDialog.kt").readText()
        val currencyLiteral = Regex("""""[^"]*(?:\$|USD|SAR|ريال|درهم|جنيه)\s*\d""")
        assertTrue(
            "A price literal appeared in the paywall; use Plan.formattedPrice from Play instead.",
            !currencyLiteral.containsMatchIn(dialog)
        )
        assertTrue(
            "The paywall no longer reads prices from Play.",
            dialog.contains("formattedPrice")
        )
    }

    @Test
    fun `the paywall has standing entry points, not only blocked features`() {
        // The first cut of this shipped with no subscribe button at all: the paywall opened only
        // when someone walked into a gated feature, so anyone who never opened plan analysis or
        // DXF export never learned Pro existed, and a subscriber had no way to check status or
        // reach Play's cancel page. Two always-visible entry points, asserted here so a layout
        // tidy-up cannot quietly remove the last one.
        val gallery = source("ui/gallery/GalleryActivity.kt").readText()
        assertTrue(
            "The gallery no longer opens the subscription dialog - Pro loses its main entry point.",
            gallery.contains("SubscriptionDialog.show")
        )

        val layout = File(moduleDir, "src/main/res/layout/activity_gallery.xml").readText()
        assertTrue(
            "btn_pro is gone from the gallery toolbar.",
            layout.contains("@+id/btn_pro")
        )

        val actions = source("ui/canvas/ActionsPanel.kt").readText()
        assertTrue(
            "The actions menu no longer offers the subscription row.",
            actions.contains("showSubscriptionFromMenu")
        )
    }

    @Test
    fun `subscribers are given a way to manage and cancel`() {
        // Play expects a subscription app to make management reachable in-app, and an app cannot
        // cancel on the user's behalf - pointing at the right Play page is the most it can do.
        val dialog = source("billing/SubscriptionDialog.kt").readText()
        assertTrue(
            "No link to Play's subscription management remains.",
            dialog.contains("store/account/subscriptions")
        )
        assertTrue(
            "The paywall no longer distinguishes an existing subscriber, so it would try to sell " +
                "Pro to someone who already has it.",
            dialog.contains("Entitlements.isSubscribed")
        )
    }

    @Test
    fun `product ids are declared once`() {
        val manager = source("billing/BillingManager.kt").readText()
        // These three strings must match the Play Console setup exactly; a typo shows up only as
        // an empty plan list on a real device, which is indistinguishable from "not released yet".
        assertTrue(manager.contains("""const val PRODUCT_ID = "artify_pro""""))
        assertTrue(manager.contains("""const val BASE_PLAN_MONTHLY = "pro-monthly""""))
        assertTrue(manager.contains("""const val BASE_PLAN_YEARLY = "pro-yearly""""))

        // Anywhere else spelling the product id by hand would be a second source of truth.
        val others = mainSources()
            .filter { it.name != "BillingManager.kt" && it.readText().contains("\"artify_pro\"") }
            .map { it.name }
            .toList()
        assertTrue(
            "The product id is hardcoded outside BillingManager: $others",
            others.isEmpty()
        )
    }
}
