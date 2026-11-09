package com.ansardeo.binancebot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Read-only Positions screen.
 *
 * Four sections:
 * 1. Positions: active trading cycles returned by GET /cycles/active
 * 2. Open Orders: intentionally reports unavailable until a read-only orders API exists
 * 3. Pipeline: currently displays Re-entry records returned by GET /reentries
 * 4. History: intentionally reports unavailable until a closed-cycle/history API exists
 *
 * This screen does not create, modify, cancel, or submit exchange orders.
 */
class PositionsScreen(
    context: Context,
    private val baseUrl: String,
    private val onCycleDetails: (Int) -> Unit
) : ScrollView(context) {

    private val pageContext = context
    private val backgroundColor = Color.rgb(8, 15, 29)
    private val cardColor = Color.rgb(17, 29, 47)
    private val tabColor = Color.rgb(27, 43, 67)
    private val yellow = Color.rgb(240, 185, 11)
    private val white = Color.WHITE
    private val muted = Color.rgb(143, 163, 189)
    private val green = Color.rgb(83, 198, 140)
    private val red = Color.rgb(240, 91, 103)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val tabs = listOf("Positions", "Open Orders", "Pipeline", "History")
    private val counts = mutableMapOf<String, Int>()
    private var selectedTab = "Positions"

    private lateinit var root: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var statusText: TextView

    init {
        setBackgroundColor(backgroundColor)
        isFillViewport = true
        buildLayout()
        showTab("Positions")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun vertical(): LinearLayout =
        LinearLayout(pageContext).apply {
            orientation = LinearLayout.VERTICAL
        }

    private fun label(
        value: String,
        size: Float = 13f,
        color: Int = white,
        bold: Boolean = false
    ): TextView =
        TextView(pageContext).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun space(parent: LinearLayout, height: Int = 10) {
        parent.addView(View(pageContext), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun card(title: String, subtitle: String? = null): LinearLayout {
        val box = vertical()
        box.background = rounded(cardColor)
        box.setPadding(dp(15), dp(14), dp(15), dp(14))
        box.addView(label(title, 14f, white, true))
        if (!subtitle.isNullOrBlank()) {
            space(box, 5)
            box.addView(label(subtitle, 11f, muted))
        }
        return box
    }

    private fun addCard(parent: LinearLayout, box: LinearLayout) {
        parent.addView(
            box,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            }
        )
    }

    private fun addInfoRow(parent: LinearLayout, title: String, value: String) {
        val line = LinearLayout(pageContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(
            label(title, 11f, muted),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        line.addView(label(value, 12f, white, true))
        parent.addView(line)
        space(parent, 8)
    }

    private fun buildLayout() {
        root = vertical()
        root.setPadding(dp(16), dp(16), dp(16), dp(22))
        addView(
            root,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        root.addView(label("Positions", 23f, white, true))
        space(root, 4)
        root.addView(
            label(
                "Active positions, exchange orders, planned pipeline and completed history",
                11f,
                muted
            )
        )

        val safety = card(
            "READ-ONLY VIEW",
            "No order is submitted, modified or cancelled from this screen."
        )
        safety.addView(label("Live trading and order submission remain disabled.", 11f, yellow))
        addCard(root, safety)

        space(root, 12)
        val tabsScroll = HorizontalScrollView(pageContext).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        tabContainer = LinearLayout(pageContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabsScroll.addView(tabContainer)
        root.addView(tabsScroll)

        statusText = label("Loading...", 11f, muted)
        space(root, 10)
        root.addView(statusText)

        contentContainer = vertical()
        root.addView(contentContainer)
    }

    private fun renderTabs() {
        tabContainer.removeAllViews()
        tabs.forEach { tab ->
            val count = counts[tab]
            val caption = if (count == null) tab else "$tab ($count)"
            val button = TextView(pageContext).apply {
                text = caption
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (tab == selectedTab) Color.BLACK else white)
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(12), dp(15), dp(12))
                background = rounded(
                    if (tab == selectedTab) yellow else tabColor,
                    12
                )
                setOnClickListener { showTab(tab) }
            }
            tabContainer.addView(
                button,
                LinearLayout.LayoutParams(-2, dp(44)).apply {
                    marginEnd = dp(7)
                }
            )
        }
    }

    private fun showTab(tab: String) {
        selectedTab = tab
        renderTabs()
        contentContainer.removeAllViews()
        statusText.text = "Loading $tab..."
        when (tab) {
            "Positions" -> loadPositions()
            "Open Orders" -> showOpenOrdersUnavailable()
            "Pipeline" -> loadPipeline()
            "History" -> showHistoryUnavailable()
        }
    }

    private fun loadPositions() {
        getJson("/cycles/active") { json, error ->
            if (selectedTab != "Positions") return@getJson
            contentContainer.removeAllViews()

            if (error != null || json == null) {
                counts["Positions"] = 0
                renderTabs()
                statusText.text = "Could not load active positions."
                addMessageCard(
                    "POSITIONS UNAVAILABLE",
                    "The server did not return active cycle data. Check the backend connection and retry."
                )
                addRetryButton()
                return@getJson
            }

            val cycles = json.optJSONArray("cycles") ?: JSONArray()
            counts["Positions"] = cycles.length()
            renderTabs()
            statusText.text = "${cycles.length()} active trading cycle(s) returned by the server."

            if (cycles.length() == 0) {
                addMessageCard(
                    "NO ACTIVE POSITIONS",
                    "There are currently no active trading cycles."
                )
                return@getJson
            }

            for (index in 0 until cycles.length()) {
                val cycle = cycles.optJSONObject(index) ?: continue
                val id = cycle.optInt("id", -1)
                val symbol = cycle.optString("symbol", "--")
                val direction = cycle.optString("direction", "--").uppercase(Locale.US)
                val isLong = direction == "LONG"
                val box = card(
                    "$symbol  •  $direction",
                    "Trading Cycle #$id"
                )

                val directionColor = if (isLong) green else red
                box.addView(label("● $direction", 11f, directionColor, true))
                space(box, 9)

                addInfoRow(box, "Cycle Status", cycle.optString("status", "--"))
                addInfoRow(
                    box,
                    "Average Entry Price",
                    numberOrDash(cycle, "average_entry_price")
                )
                addInfoRow(
                    box,
                    "Filled Quantity",
                    numberOrDash(cycle, "total_filled_quantity")
                )
                addInfoRow(
                    box,
                    "Created At",
                    cycle.optString("created_at", "--").replace("T", " ")
                )

                box.addView(
                    label(
                        "Unrealized PNL / ROI: --",
                        12f,
                        yellow,
                        true
                    )
                )
                space(box, 4)
                box.addView(
                    label(
                        "ROI is not calculated yet: the current active-cycle API does not provide a reliable mark price and position margin snapshot.",
                        10f,
                        muted
                    )
                )

                val details = makeButton("VIEW CYCLE DETAILS")
                details.setOnClickListener {
                    if (id > 0) onCycleDetails(id)
                }
                box.addView(
                    details,
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                        topMargin = dp(8)
                    }
                )
                addCard(contentContainer, box)
            }
        }
    }

    private fun showOpenOrdersUnavailable() {
        counts["Open Orders"] = 0
        renderTabs()
        statusText.text = "Exchange open-order records are not available from the current backend API."

        addMessageCard(
            "OPEN ORDERS API REQUIRED",
            "The current backend exposes order synchronization, but it does not expose a read-only GET endpoint that returns the current open CycleOrder records. This tab will not show guessed or simulated orders."
        )

        addMessageCard(
            "WHAT WILL APPEAR HERE",
            "Take Profit, Stop Loss and Re-entry orders only after the backend confirms that the order was actually placed on Binance. Each record should include symbol, side, order type, trigger/limit price, quantity and exchange status."
        )
    }

    private fun loadPipeline() {
        getJson("/reentries?limit=100") { json, error ->
            if (selectedTab != "Pipeline") return@getJson
            contentContainer.removeAllViews()

            if (error != null || json == null) {
                counts["Pipeline"] = 0
                renderTabs()
                statusText.text = "Could not load pipeline records."
                addMessageCard(
                    "PIPELINE UNAVAILABLE",
                    "The server did not return re-entry records. Check the backend connection and retry."
                )
                addRetryButton()
                return@getJson
            }

            val records = json.optJSONArray("reentries") ?: JSONArray()
            counts["Pipeline"] = records.length()
            renderTabs()
            statusText.text = "${records.length()} re-entry record(s) returned by the server."

            val intro = card(
                "PIPELINE RULE",
                "These are planned records waiting for their conditions. They are not exchange open orders unless the backend confirms that they have been placed."
            )
            addCard(contentContainer, intro)

            if (records.length() == 0) {
                addMessageCard(
                    "NO RE-ENTRY RECORDS",
                    "There are currently no re-entry records returned by the backend."
                )
            } else {
                for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val id = item.optInt("id", -1)
                    val symbol = item.optString("symbol", "--")
                    val direction = item.optString("direction", "--")
                    val state = item.optString("status", "UNKNOWN")
                    val box = card(
                        "$symbol  •  $direction",
                        "Re-entry #$id  |  Cycle #${item.optString("cycle_id", "--")}"
                    )
                    addInfoRow(box, "Status", state)
                    addInfoRow(
                        box,
                        "Reference Close Price",
                        numberOrDash(item, "reference_close_price")
                    )
                    addInfoRow(
                        box,
                        "Re-entry Trigger Price",
                        numberOrDash(item, "trigger_price")
                    )
                    addInfoRow(
                        box,
                        "Created At",
                        item.optString("created_at", "--").replace("T", " ")
                    )
                    addInfoRow(
                        box,
                        "Triggered At",
                        item.optString("triggered_at", "--").replace("T", " ")
                    )
                    addCard(contentContainer, box)
                }
            }

            val limitation = card(
                "AVERAGING & OPPOSITE-SIDE PIPELINE",
                "The current API does not expose their individual pending/triggered pipeline records or warning state. They are deliberately not inferred from settings alone. A dedicated read-only pipeline endpoint is needed to display them accurately."
            )
            addCard(contentContainer, limitation)
        }
    }

    private fun showHistoryUnavailable() {
        counts["History"] = 0
        renderTabs()
        statusText.text = "Closed-cycle and completed-order history API is not yet available."

        addMessageCard(
            "HISTORY API REQUIRED",
            "The current backend has no dedicated endpoint that returns closed trading cycles and their final execution details. This tab will not fabricate completed trades."
        )

        addMessageCard(
            "HISTORY DETAILS TO BE ADDED",
            "Symbol, Long/Short, initial and average entry, exit price, opened/closed time, closing reason (Take Profit, Stop Loss or manual), realized PNL, fees and final ROI."
        )
    }

    private fun addMessageCard(title: String, message: String) {
        val box = card(title)
        box.addView(label(message, 12f, muted))
        addCard(contentContainer, box)
    }

    private fun addRetryButton() {
        val button = makeButton("RETRY")
        button.setOnClickListener { showTab(selectedTab) }
        contentContainer.addView(
            button,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(10)
            }
        )
    }

    private fun makeButton(caption: String): Button =
        Button(pageContext).apply {
            text = caption
            textSize = 11f
            isAllCaps = false
            setTextColor(white)
            background = rounded(tabColor, 10)
        }

    private fun numberOrDash(json: JSONObject, key: String): String {
        if (!json.has(key) || json.isNull(key)) return "--"
        val value = json.optDouble(key, Double.NaN)
        if (value.isNaN() || value.isInfinite()) return "--"
        return String.format(Locale.US, "%.8f", value)
            .trimEnd('0')
            .trimEnd('.')
            .ifBlank { "0" }
    }

    private fun getJson(path: String, callback: (JSONObject?, String?) -> Unit) {
        val url = baseUrl.trimEnd('/') + path
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (error: Exception) {
            callback(null, error.message ?: "Invalid URL")
            return
        }

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                post {
                    callback(null, error.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() }
                post {
                    if (!response.isSuccessful || body == null) {
                        callback(null, "HTTP ${response.code}")
                    } else {
                        try {
                            callback(JSONObject(body), null)
                        } catch (_: Exception) {
                            callback(null, "Invalid JSON response")
                        }
                    }
                }
            }
        })
    }

    private fun post(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        http.dispatcher.cancelAll()
    }
}
