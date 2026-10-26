/**
 * Screen for adjusting CPU cluster (little/big/prime) and GPU frequencies,
 * governors, and related tunables.
 *
 * Why this screen is built the way it is (it fixes "I have to pick the
 * frequency again"):
 *  - ALL root I/O (initial load, periodic refresh, user writes) goes through a
 *    single-thread executor, so writes are applied in the order they were
 *    chosen and a refresh never interleaves with a write.
 *  - The periodic refresh is not allowed to touch the controls while a user
 *    write is pending, nor if the user changed something after the refresh
 *    started (see [uiEpoch] / [writesInFlight]); otherwise a refresh that read
 *    the OLD value would snap the spinner back right after the user's choice.
 *  - If the real value isn't in the list (kernel/thermal clamp), it is added to
 *    the list instead of showing the nearest entry — otherwise the entry the
 *    user wants is already "selected" and re-selecting it fires no event.
 */
package com.thermaloverlay.overlay.freqcontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.thermaloverlay.overlay.R
import com.thermaloverlay.overlay.RootState
import com.thermaloverlay.overlay.metrics.CpuCyclesUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FreqControlActivity : AppCompatActivity() {

    private class ClusterHolder(
        val root: View,
        val paths: FreqControlUtils.ClusterPaths,
        val currentFreqView: TextView,
    ) {
        val governorSpinner: Spinner = root.findViewById(R.id.spinner_governor)
        val minFreqSpinner: Spinner = root.findViewById(R.id.spinner_min_freq)
        val maxFreqSpinner: Spinner = root.findViewById(R.id.spinner_max_freq)

        var minController: SpinnerController<Int>? = null
        var maxController: SpinnerController<Int>? = null
        var govController: SpinnerController<String>? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // The one and only place where root I/O happens for this screen.
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FreqControlIO").apply { isDaemon = true }
    }

    private data class Detection(
        val hasBigCluster: Boolean,
        val bigPolicy: Int,
        val hasPrimeCluster: Boolean,
        val hasGpu: Boolean,
        val hasCpuInputBoostMs: Boolean,
        val hasCpuSchedBoostOnInput: Boolean,
        val coreCount: Int,
        val coreToggleable: List<Boolean>,
    )

    private val clusterHolders = mutableListOf<ClusterHolder>()
    private var gpuRoot: View? = null
    private var cpuBoostRoot: View? = null
    private var coreRoot: View? = null
    private val coreViews = mutableListOf<TextView>()
    private var coreOnlineState: List<Boolean> = emptyList()
    private var coreToggleable: List<Boolean> = emptyList()

    private var gpuMinController: SpinnerController<Int>? = null
    private var gpuMaxController: SpinnerController<Int>? = null
    private var gpuGovController: SpinnerController<String>? = null
    private var gpuBoostController: SpinnerController<Int>? = null
    private var gpuThrottlingController: SwitchController? = null

    private var gpuPwrlevelApplyReal: ((min: Int, max: Int, def: Int) -> Unit)? = null

    private var cpuBoostInputEditRef: EditText? = null
    private var cpuBoostSchedController: SwitchController? = null

    // --- write / refresh coordination (main thread unless noted) ---

    /** Bumped whenever the user starts a write. Thread-safe. */
    private val uiEpoch = AtomicInteger(0)

    /** User writes submitted but not yet fully read back. */
    private var writesInFlight = 0

    private val liveRefreshIntervalMs = 2000L
    private var liveRefreshRunning = false

    /** Bumped on pause/resume so a refresh that finishes late can't start a second chain. */
    private var liveGeneration = 0
    private val liveRefreshRunnable = Runnable { refreshLiveStateAsync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing here works without root.
        if (RootState.granted.value == false) {
            Toast.makeText(this, R.string.root_required_toast, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (RootState.granted.value == null) {
            // Process was restarted while this screen was open.
            Thread {
                if (!RootState.check()) runOnUiThread { finish() }
            }.start()
        }
        setContentView(R.layout.activity_freq_control)

        val clusterContainer = findViewById<LinearLayout>(R.id.cluster_container)
        val cpuBoostContainer = findViewById<LinearLayout>(R.id.cpu_boost_container)
        val coreContainer = findViewById<LinearLayout>(R.id.core_container)
        val gpuContainer = findViewById<LinearLayout>(R.id.gpu_container)

        io.execute {
            val detection = try {
                val n = FreqControlUtils.cpuCoreCount()
                Detection(
                    hasBigCluster = FreqControlUtils.hasBigCluster(),
                    bigPolicy = FreqControlUtils.detectBigPolicy(),
                    hasPrimeCluster = FreqControlUtils.hasPrimeCluster(),
                    hasGpu = FreqControlUtils.hasGpu(),
                    hasCpuInputBoostMs = FreqControlUtils.hasCpuInputBoostMs(),
                    hasCpuSchedBoostOnInput = FreqControlUtils.hasCpuSchedBoostOnInput(),
                    coreCount = n,
                    coreToggleable = (0 until n).map { FreqControlUtils.isCoreToggleable(it) },
                )
            } catch (ex: Exception) {
                Detection(
                    hasBigCluster = false, bigPolicy = 0, hasPrimeCluster = false,
                    hasGpu = false, hasCpuInputBoostMs = false, hasCpuSchedBoostOnInput = false,
                    coreCount = 0, coreToggleable = emptyList(),
                )
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                buildClusterCards(clusterContainer, detection)
                buildCpuBoostCard(cpuBoostContainer, detection)
                buildCoreCard(coreContainer, detection)
                buildGpuCard(gpuContainer, detection)
                loadAllAsync()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        liveRefreshRunning = true
        liveGeneration++
        mainHandler.removeCallbacks(liveRefreshRunnable)
        mainHandler.post(liveRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        liveRefreshRunning = false
        liveGeneration++
        mainHandler.removeCallbacks(liveRefreshRunnable)
    }

    override fun onDestroy() {
        // Queued writes still run to completion; nothing new is accepted.
        io.shutdown()
        super.onDestroy()
    }

    private fun buildClusterCards(container: LinearLayout, detection: Detection) {
        val inflater = LayoutInflater.from(this)

        FreqControlUtils.pathsFor(FreqControlUtils.Cluster.LITTLE)?.let { paths ->
            addClusterCard(container, inflater, paths, getString(R.string.cluster_little_title))
        }

        if (detection.hasBigCluster) {
            FreqControlUtils.pathsFor(FreqControlUtils.Cluster.BIG)?.let { paths ->
                val title = getString(R.string.cluster_big_title, detection.bigPolicy)
                addClusterCard(container, inflater, paths, title)
            }
        }

        if (detection.hasPrimeCluster) {
            FreqControlUtils.pathsFor(FreqControlUtils.Cluster.PRIME)?.let { paths ->
                addClusterCard(container, inflater, paths, getString(R.string.cluster_prime_title))
            }
        }
    }

    private fun addClusterCard(
        container: LinearLayout,
        inflater: LayoutInflater,
        paths: FreqControlUtils.ClusterPaths,
        title: String,
    ) {
        val card = inflater.inflate(R.layout.view_cluster_control, container, false)
        card.findViewById<TextView>(R.id.cluster_title).text = title
        val currentFreqView = card.findViewById<TextView>(R.id.cluster_current_freq)
        container.addView(card)
        clusterHolders.add(ClusterHolder(card, paths, currentFreqView))
    }

    private fun buildGpuCard(container: LinearLayout, detection: Detection) {
        if (!detection.hasGpu) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_gpu_control, container, false)
        container.addView(view)
        gpuRoot = view
    }

    private fun buildCoreCard(container: LinearLayout, detection: Detection) {
        if (detection.coreCount <= 1) return
        coreToggleable = detection.coreToggleable
        val view = LayoutInflater.from(this).inflate(R.layout.view_core_control, container, false)
        container.addView(view)
        coreRoot = view
    }

    private fun buildCpuBoostCard(container: LinearLayout, detection: Detection) {
        if (!detection.hasCpuInputBoostMs && !detection.hasCpuSchedBoostOnInput) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_cpu_boost_control, container, false)
        view.findViewById<View>(R.id.row_input_boost_ms).visibility =
            if (detection.hasCpuInputBoostMs) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.row_sched_boost_on_input).visibility =
            if (detection.hasCpuSchedBoostOnInput) View.VISIBLE else View.GONE
        container.addView(view)
        cpuBoostRoot = view
    }

    // ------------------------------------------------------------------
    // Reading state (one shell round trip per read, whole screen at once)
    // ------------------------------------------------------------------

    private class StateRequest(
        val clusterPaths: List<FreqControlUtils.ClusterPaths>,
        val readGpu: Boolean,
        val readCpuBoost: Boolean,
        val coreOnlinePaths: List<Pair<Int, String>>,
        val includeStatic: Boolean,
    ) {
        fun allPaths(): List<String> {
            val p = LinkedHashSet<String>()
            for (c in clusterPaths) {
                p += c.curFreq; p += c.minFreq; p += c.maxFreq; p += c.gov
                if (includeStatic) {
                    p += c.availFreq; p += c.availGov
                    c.availBoost?.let { p += it }
                }
            }
            if (readGpu) {
                p += FreqControlUtils.CUR_FREQ_GPU; p += FreqControlUtils.MIN_FREQ_GPU
                p += FreqControlUtils.MAX_FREQ_GPU; p += FreqControlUtils.GOV_GPU
                p += FreqControlUtils.ADRENO_BOOST; p += FreqControlUtils.MIN_PWRLEVEL
                p += FreqControlUtils.MAX_PWRLEVEL; p += FreqControlUtils.DEFAULT_PWRLEVEL
                p += FreqControlUtils.GPU_THROTTLING
                if (includeStatic) {
                    p += FreqControlUtils.AVAIL_FREQ_GPU; p += FreqControlUtils.AVAIL_GOV_GPU
                }
            }
            if (readCpuBoost) {
                p += FreqControlUtils.CPU_INPUT_BOOST_MS; p += FreqControlUtils.CPU_SCHED_BOOST_ON_INPUT
            }
            for ((_, path) in coreOnlinePaths) p += path
            return p.toList()
        }
    }

    private fun buildRequest(includeStatic: Boolean) = StateRequest(
        clusterPaths = clusterHolders.map { it.paths },
        readGpu = gpuRoot != null,
        readCpuBoost = cpuBoostRoot != null,
        coreOnlinePaths = if (coreRoot != null) {
            coreToggleable.mapIndexedNotNull { core, t -> if (t) core to FreqControlUtils.coreOnlinePath(core) else null }
        } else {
            emptyList()
        },
        includeStatic = includeStatic,
    )

    private class ClusterRead(
        val curMHz: Int,
        val minKHz: Int,
        val maxKHz: Int,
        val gov: String,
        val availFreq: List<Int>,
        val availGov: List<String>,
    )

    private class GpuRead(
        val curMHz: Int,
        val minMHz: Int,
        val maxMHz: Int,
        val gov: String,
        val adrenoBoost: Int,
        val minPwrlevel: Int?,
        val maxPwrlevel: Int?,
        val defaultPwrlevel: Int?,
        val throttling: Boolean,
        val availFreq: List<Int>,
        val availGov: List<String>,
        val rawAdrenoBoost: String,
    )

    private class CpuBoostRead(val inputBoostMs: Int?, val schedBoost: Boolean)

    private class StateRead(
        val clusters: List<ClusterRead>,
        val gpu: GpuRead?,
        val cpuBoost: CpuBoostRead?,
        val coreOnline: List<Boolean>?,
    )

    /** Runs on the io thread. */
    private fun readState(req: StateRequest, coreCount: Int): StateRead {
        val m = FreqControlUtils.readNodes(req.allPaths())
        fun v(path: String): String = m[path]?.trim() ?: ""

        val clusters = req.clusterPaths.map { c ->
            ClusterRead(
                curMHz = FreqControlUtils.parseMHzFromKHz(v(c.curFreq)),
                minKHz = FreqControlUtils.parseKHz(v(c.minFreq)),
                maxKHz = FreqControlUtils.parseKHz(v(c.maxFreq)),
                gov = v(c.gov),
                // kHz end to end: spinner values must round-trip to sysfs exactly.
                availFreq = if (req.includeStatic) {
                    (FreqControlUtils.parseIntList(v(c.availFreq)) +
                        (c.availBoost?.let { FreqControlUtils.parseIntList(v(it)) } ?: emptyList()))
                        .distinct().sorted()
                } else {
                    emptyList()
                },
                availGov = if (req.includeStatic) FreqControlUtils.parseWordList(v(c.availGov)) else emptyList(),
            )
        }

        val gpu = if (req.readGpu) {
            val boostRaw = v(FreqControlUtils.ADRENO_BOOST)
            GpuRead(
                curMHz = FreqControlUtils.parseGpuCurMHz(v(FreqControlUtils.CUR_FREQ_GPU)),
                minMHz = v(FreqControlUtils.MIN_FREQ_GPU).toIntOrNull() ?: 0,
                maxMHz = v(FreqControlUtils.MAX_FREQ_GPU).toIntOrNull() ?: 0,
                gov = v(FreqControlUtils.GOV_GPU),
                adrenoBoost = boostRaw.toIntOrNull()?.coerceIn(0, 3) ?: 0,
                minPwrlevel = v(FreqControlUtils.MIN_PWRLEVEL).toIntOrNull(),
                maxPwrlevel = v(FreqControlUtils.MAX_PWRLEVEL).toIntOrNull(),
                defaultPwrlevel = v(FreqControlUtils.DEFAULT_PWRLEVEL).toIntOrNull(),
                throttling = v(FreqControlUtils.GPU_THROTTLING) == "1",
                availFreq = if (req.includeStatic) {
                    FreqControlUtils.parseIntList(v(FreqControlUtils.AVAIL_FREQ_GPU)).distinct().sorted()
                } else {
                    emptyList()
                },
                availGov = if (req.includeStatic) FreqControlUtils.parseWordList(v(FreqControlUtils.AVAIL_GOV_GPU)) else emptyList(),
                rawAdrenoBoost = boostRaw,
            )
        } else {
            null
        }

        val cpuBoost = if (req.readCpuBoost) {
            CpuBoostRead(
                inputBoostMs = v(FreqControlUtils.CPU_INPUT_BOOST_MS).toIntOrNull(),
                schedBoost = v(FreqControlUtils.CPU_SCHED_BOOST_ON_INPUT) == "1",
            )
        } else {
            null
        }

        val coreOnline = if (coreRootPresentFor(req)) {
            val toggleableByCore = req.coreOnlinePaths.associate { it.first to it.second }
            (0 until coreCount).map { core ->
                val path = toggleableByCore[core]
                if (path == null) true else v(path) == "1"
            }
        } else {
            null
        }

        return StateRead(clusters, gpu, cpuBoost, coreOnline)
    }

    private fun coreRootPresentFor(req: StateRequest) = req.coreOnlinePaths.isNotEmpty()

    private fun loadAllAsync() {
        val req = buildRequest(includeStatic = true)
        val coreCount = coreToggleable.size
        io.execute {
            val state = try {
                readState(req, coreCount)
            } catch (ex: Exception) {
                return@execute
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                state.clusters.forEachIndexed { i, read ->
                    clusterHolders.getOrNull(i)?.let { bindClusterSnapshot(it, read) }
                }
                state.coreOnline?.let { bindCoreSnapshot(it) }
                    ?: run { if (coreRoot != null) bindCoreSnapshot(List(coreCount) { true }) }
                state.gpu?.let { bindGpuSnapshot(it) }
                state.cpuBoost?.let { bindCpuBoostSnapshot(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    /**
     * A Spinner whose list can grow: a value that isn't in the list (clamped by
     * the kernel / thermal) is inserted, so what the spinner shows is always
     * the real value and every other entry can be selected (= fires an event).
     */
    private class SpinnerController<T : Comparable<T>>(
        private val spinner: Spinner,
        private val adapter: ArrayAdapter<String>,
        private val values: MutableList<T>,
        private val labelOf: (T) -> String,
        private val sorted: Boolean,
        initialIndex: Int,
    ) {
        var lastAppliedIndex: Int = initialIndex
            private set

        fun shouldWrite(position: Int): Boolean = position != lastAppliedIndex

        fun valueAt(position: Int): T = values[position]

        /** The user picked [position]; remember it so we don't write it twice. */
        fun onUserSelected(position: Int) {
            lastAppliedIndex = position
        }

        fun applyRealValue(value: T) {
            var idx = values.indexOf(value)
            if (idx < 0) idx = insert(value)
            lastAppliedIndex = idx
            spinner.setSelection(idx, false)
        }

        private fun insert(value: T): Int {
            val pos = if (sorted) {
                val p = values.indexOfFirst { it > value }
                if (p < 0) values.size else p
            } else {
                values.size
            }
            values.add(pos, value)
            adapter.insert(labelOf(value), pos)
            return pos
        }
    }

    private inner class SwitchController(
        private val switch: Switch,
        initialChecked: Boolean,
        private val write: (Boolean) -> FreqControlUtils.WriteResult,
        private val readBack: () -> Boolean,
    ) {
        private var lastApplied = initialChecked

        init {
            switch.isChecked = initialChecked
            installListener()
        }

        private fun installListener() {
            switch.setOnCheckedChangeListener { _, checked ->
                if (checked == lastApplied) return@setOnCheckedChangeListener
                lastApplied = checked
                submitWrite(
                    action = { write(checked) },
                    afterWrite = {
                        val real = readBack()
                        mainHandler.post { applyRealValue(real) }
                    },
                )
            }
        }

        fun applyRealValue(value: Boolean) {
            lastApplied = value
            if (switch.isChecked != value) {
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = value
                installListener()
            }
        }
    }

    private fun bindClusterSnapshot(holder: ClusterHolder, s: ClusterRead) {
        holder.currentFreqView.text = getString(R.string.cluster_current_freq, s.curMHz)

        var minController: SpinnerController<Int>? = null
        var maxController: SpinnerController<Int>? = null
        // After any min/max write BOTH bounds are re-read: setting a max below
        // the min also moves the min (and the other way round).
        val refreshMinMax: () -> Unit = {
            val minKhz = FreqControlUtils.readFreqKHz(holder.paths.minFreq)
            val maxKhz = FreqControlUtils.readFreqKHz(holder.paths.maxFreq)
            mainHandler.post {
                if (minKhz > 0) minController?.applyRealValue(minKhz)
                if (maxKhz > 0) maxController?.applyRealValue(maxKhz)
            }
        }
        minController = setupFreqSpinner(holder.minFreqSpinner, s.availFreq, s.minKHz, ::cpuFreqLabel, refreshMinMax) { khz ->
            FreqControlUtils.setCpuMinKHz(holder.paths, khz)
        }
        maxController = setupFreqSpinner(holder.maxFreqSpinner, s.availFreq, s.maxKHz, ::cpuFreqLabel, refreshMinMax) { khz ->
            FreqControlUtils.setCpuMaxKHz(holder.paths, khz)
        }
        holder.minController = minController
        holder.maxController = maxController

        var govController: SpinnerController<String>? = null
        govController = setupGovernorSpinner(holder.governorSpinner, s.availGov, s.gov, {
            val realGov = FreqControlUtils.readNodes(listOf(holder.paths.gov))[holder.paths.gov]?.trim() ?: ""
            mainHandler.post { if (realGov.isNotEmpty()) govController?.applyRealValue(realGov) }
        }) { gov ->
            FreqControlUtils.writeGovernor(holder.paths.gov, gov)
        }
        holder.govController = govController
    }

    private fun bindCoreSnapshot(online: List<Boolean>) {
        val root = coreRoot ?: return
        val row = root.findViewById<LinearLayout>(R.id.core_toggle_container)
        row.removeAllViews()
        coreViews.clear()

        val density = resources.displayMetrics.density
        val height = (40 * density).toInt()
        val margin = (3 * density).toInt()

        online.indices.forEach { core ->
            val toggleable = coreToggleable.getOrElse(core) { false }
            val tv = TextView(this).apply {
                text = core.toString()
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                // cpu0 (or any core without an "online" node) can't be
                // offlined by the kernel — show it dimmed and non-clickable.
                alpha = if (toggleable) 1f else 0.45f
                isEnabled = toggleable
                if (toggleable) {
                    setOnClickListener { onCoreTapped(core) }
                }
            }
            val lp = LinearLayout.LayoutParams(0, height, 1f)
            lp.setMargins(margin, 0, margin, 0)
            row.addView(tv, lp)
            coreViews.add(tv)
        }
        applyCoreStates(online)
    }

    private fun onCoreTapped(core: Int) {
        val current = coreOnlineState.getOrElse(core) { true }
        val target = !current
        // The kernel refuses to offline the last online core anyway, but
        // guard in the UI so the user gets a clear message instead of a
        // generic write failure.
        if (!target && coreOnlineState.count { it } <= 1) {
            Toast.makeText(this, R.string.freq_core_last_toast, Toast.LENGTH_SHORT).show()
            return
        }
        submitWrite(
            action = { FreqControlUtils.writeCoreOnline(core, target) },
            afterWrite = {
                // simpleperf (per-core MHz in the overlay's detailed view)
                // opens perf events once at launch and never picks up a
                // re-onlined core — restart the stream after any hotplug.
                CpuCyclesUtils.restartAfterCoreHotplug()
                val states = FreqControlUtils.readAllCoresOnline()
                mainHandler.post { applyCoreStates(states) }
            },
        )
    }

    private fun applyCoreStates(states: List<Boolean>) {
        coreOnlineState = states
        coreViews.forEachIndexed { i, tv ->
            val on = states.getOrElse(i) { true }
            tv.setBackgroundResource(if (on) R.drawable.bg_core_on else R.drawable.bg_core_off)
            tv.setTextColor(getColor(if (on) R.color.accent else R.color.text_secondary))
        }
    }

    private fun bindGpuSnapshot(s: GpuRead) {
        val root = gpuRoot ?: return
        root.findViewById<TextView>(R.id.gpu_current_freq).text =
            getString(R.string.cluster_current_freq, s.curMHz)

        var minController: SpinnerController<Int>? = null
        var maxController: SpinnerController<Int>? = null
        val refreshMinMax: () -> Unit = {
            val minMhz = FreqControlUtils.readIntNode(FreqControlUtils.MIN_FREQ_GPU) ?: 0
            val maxMhz = FreqControlUtils.readIntNode(FreqControlUtils.MAX_FREQ_GPU) ?: 0
            mainHandler.post {
                if (minMhz > 0) minController?.applyRealValue(minMhz)
                if (maxMhz > 0) maxController?.applyRealValue(maxMhz)
            }
        }
        minController = setupFreqSpinner(root.findViewById(R.id.gpu_spinner_min_freq), s.availFreq, s.minMHz, ::gpuFreqLabel, refreshMinMax) { mhz ->
            FreqControlUtils.setGpuMinMHz(mhz)
        }
        maxController = setupFreqSpinner(root.findViewById(R.id.gpu_spinner_max_freq), s.availFreq, s.maxMHz, ::gpuFreqLabel, refreshMinMax) { mhz ->
            FreqControlUtils.setGpuMaxMHz(mhz)
        }
        gpuMinController = minController
        gpuMaxController = maxController

        var gpuGovControllerLocal: SpinnerController<String>? = null
        gpuGovControllerLocal = setupGovernorSpinner(root.findViewById(R.id.gpu_spinner_governor), s.availGov, s.gov, {
            val realGov = FreqControlUtils.readNodes(listOf(FreqControlUtils.GOV_GPU))[FreqControlUtils.GOV_GPU]?.trim() ?: ""
            mainHandler.post { if (realGov.isNotEmpty()) gpuGovControllerLocal?.applyRealValue(realGov) }
        }) { gov ->
            FreqControlUtils.writeGovernor(FreqControlUtils.GOV_GPU, gov)
        }
        gpuGovController = gpuGovControllerLocal

        val boostLabels = mutableListOf(
            getString(R.string.freq_adreno_boost_off),
            getString(R.string.freq_adreno_boost_low),
            getString(R.string.freq_adreno_boost_medium),
            getString(R.string.freq_adreno_boost_high),
        )
        val boostSpinner = root.findViewById<Spinner>(R.id.gpu_spinner_adreno_boost)
        val boostAdapter = spinnerAdapter(boostLabels)
        boostSpinner.adapter = boostAdapter
        val curBoostIndex = s.adrenoBoost
        boostSpinner.setSelection(curBoostIndex, false)
        val boostController = SpinnerController(
            boostSpinner, boostAdapter, mutableListOf(0, 1, 2, 3), { it.toString() }, false, curBoostIndex,
        )
        gpuBoostController = boostController
        boostSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!boostController.shouldWrite(position)) return
                boostController.onUserSelected(position)
                submitWrite(
                    action = { FreqControlUtils.writeAdrenoBoost(position) },
                    afterWrite = {
                        val real = FreqControlUtils.readNodes(listOf(FreqControlUtils.ADRENO_BOOST))[FreqControlUtils.ADRENO_BOOST]
                            ?.trim()?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                        mainHandler.post { boostController.applyRealValue(real) }
                    },
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val minPl = s.minPwrlevel
        val maxPl = s.maxPwrlevel
        val defPl = s.defaultPwrlevel
        val seekBar = root.findViewById<SeekBar>(R.id.gpu_seekbar_default_pwrlevel)
        val boundsView = root.findViewById<TextView>(R.id.gpu_pwrlevel_bounds)
        val defaultValueView = root.findViewById<TextView>(R.id.gpu_default_pwrlevel_value)

        if (minPl != null && maxPl != null && defPl != null && maxPl <= minPl) {

            var currentMaxPl = maxPl

            gpuPwrlevelApplyReal = { min, max, def ->
                if (max <= min && !seekBar.isPressed) {
                    currentMaxPl = max
                    boundsView.text = getString(R.string.freq_pwrlevel_bounds, min, max)
                    seekBar.max = min - max
                    seekBar.progress = (def - max).coerceIn(0, min - max)
                    defaultValueView.text = def.toString()
                }
            }
            gpuPwrlevelApplyReal?.invoke(minPl, maxPl, defPl)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) defaultValueView.text = (currentMaxPl + progress).toString()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val value = currentMaxPl + (sb?.progress ?: 0)
                    submitWrite(
                        action = { FreqControlUtils.writeIntNode(FreqControlUtils.DEFAULT_PWRLEVEL, value) },
                        afterWrite = {
                            val m = FreqControlUtils.readNodes(
                                listOf(FreqControlUtils.MIN_PWRLEVEL, FreqControlUtils.MAX_PWRLEVEL, FreqControlUtils.DEFAULT_PWRLEVEL)
                            )
                            val realMin = m[FreqControlUtils.MIN_PWRLEVEL]?.trim()?.toIntOrNull()
                            val realMax = m[FreqControlUtils.MAX_PWRLEVEL]?.trim()?.toIntOrNull()
                            val realDef = m[FreqControlUtils.DEFAULT_PWRLEVEL]?.trim()?.toIntOrNull()
                            mainHandler.post {
                                if (realMin != null && realMax != null && realDef != null) {
                                    gpuPwrlevelApplyReal?.invoke(realMin, realMax, realDef)
                                }
                            }
                        },
                    )
                }
            })
        } else {
            boundsView.text = ""
            defaultValueView.text = "—"
            seekBar.isEnabled = false
            gpuPwrlevelApplyReal = null
        }

        val throttlingSwitch = root.findViewById<Switch>(R.id.switch_gpu_throttling)
        gpuThrottlingController = SwitchController(
            switch = throttlingSwitch,
            initialChecked = s.throttling,
            write = { checked -> FreqControlUtils.writeThrottling(checked) },
            readBack = {
                FreqControlUtils.readNodes(listOf(FreqControlUtils.GPU_THROTTLING))[FreqControlUtils.GPU_THROTTLING]?.trim() == "1"
            },
        )
    }

    private fun bindCpuBoostSnapshot(s: CpuBoostRead) {
        val root = cpuBoostRoot ?: return

        val inputBoostEdit = root.findViewById<EditText>(R.id.edit_input_boost_ms)
        val inputBoostButton = root.findViewById<Button>(R.id.button_apply_input_boost_ms)
        inputBoostEdit.setText(s.inputBoostMs?.toString() ?: "")
        cpuBoostInputEditRef = inputBoostEdit
        inputBoostButton.setOnClickListener {
            val value = inputBoostEdit.text.toString().toIntOrNull()
            if (value != null && value >= 0) {
                submitWrite(
                    action = { FreqControlUtils.writeCpuInputBoostMs(value) },
                    afterWrite = {
                        val real = FreqControlUtils.readIntNode(FreqControlUtils.CPU_INPUT_BOOST_MS)
                        mainHandler.post {
                            if (!inputBoostEdit.hasFocus()) inputBoostEdit.setText(real?.toString() ?: "")
                        }
                    },
                )
            }
        }

        val schedBoostSwitch = root.findViewById<Switch>(R.id.switch_sched_boost_on_input)
        cpuBoostSchedController = SwitchController(
            switch = schedBoostSwitch,
            initialChecked = s.schedBoost,
            write = { checked -> FreqControlUtils.writeCpuSchedBoostOnInput(checked) },
            readBack = {
                FreqControlUtils.readNodes(listOf(FreqControlUtils.CPU_SCHED_BOOST_ON_INPUT))[FreqControlUtils.CPU_SCHED_BOOST_ON_INPUT]?.trim() == "1"
            },
        )
    }

    /** Compact adapter shared by every picker (closed row 13sp, open rows 36dp). */
    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.item_spinner, items.toMutableList()).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

    // CPU spinner values are in kHz (sysfs native unit, written back exactly);
    // the label truncates to whole MHz for display only (1999600 kHz -> 1999 MHz).
    private fun cpuFreqLabel(khz: Int): String = "${khz / 1000} MHz"

    // GPU spinner values are already in MHz (kgsl exposes *_clock_mhz nodes).
    private fun gpuFreqLabel(mhz: Int): String = "$mhz MHz"

    private fun setupFreqSpinner(
        spinner: Spinner,
        available: List<Int>,
        current: Int,
        labelOf: (Int) -> String,
        afterWrite: () -> Unit,
        onSelected: (Int) -> FreqControlUtils.WriteResult,
    ): SpinnerController<Int>? {
        if (available.isEmpty()) {
            spinner.adapter = spinnerAdapter(listOf("—"))
            spinner.isEnabled = false
            return null
        }
        val values = available.toMutableList()
        // Show the real value even if it's not in the table (e.g. clamped).
        if (current > 0 && current !in values) {
            val pos = values.indexOfFirst { it > current }.let { if (it < 0) values.size else it }
            values.add(pos, current)
        }
        val adapter = spinnerAdapter(values.map(labelOf))
        spinner.adapter = adapter
        val initialIndex = values.indexOf(current).let { if (it >= 0) it else 0 }
        spinner.setSelection(initialIndex, false)

        val controller = SpinnerController(spinner, adapter, values, labelOf, true, initialIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!controller.shouldWrite(position)) return
                controller.onUserSelected(position)
                val value = controller.valueAt(position)
                submitWrite(action = { onSelected(value) }, afterWrite = afterWrite)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return controller
    }

    private fun setupGovernorSpinner(
        spinner: Spinner,
        available: List<String>,
        current: String,
        afterWrite: () -> Unit,
        onSelected: (String) -> FreqControlUtils.WriteResult,
    ): SpinnerController<String>? {
        if (available.isEmpty()) {
            spinner.adapter = spinnerAdapter(listOf("—"))
            spinner.isEnabled = false
            return null
        }
        val values = available.toMutableList()
        if (current.isNotEmpty() && current !in values) values.add(current)
        val adapter = spinnerAdapter(values)
        spinner.adapter = adapter
        val initialIndex = values.indexOf(current).let { if (it >= 0) it else 0 }
        spinner.setSelection(initialIndex, false)

        val controller = SpinnerController(spinner, adapter, values, { it }, false, initialIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!controller.shouldWrite(position)) return
                controller.onUserSelected(position)
                val value = controller.valueAt(position)
                submitWrite(action = { onSelected(value) }, afterWrite = afterWrite)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return controller
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Queues a user change. [action] writes (verified) and [afterWrite] reads
     * the real state back and posts it to the UI; both run on the io thread,
     * strictly after every earlier write.
     */
    private fun submitWrite(
        action: () -> FreqControlUtils.WriteResult,
        afterWrite: () -> Unit = {},
    ) {
        uiEpoch.incrementAndGet()
        writesInFlight++
        try {
            io.execute {
                val result = try {
                    action()
                } catch (ex: Exception) {
                    FreqControlUtils.WriteResult.FAILED
                }
                try {
                    afterWrite()
                } catch (ex: Exception) {
                    // the next refresh will resync the UI
                }
                mainHandler.post {
                    writesInFlight--
                    val messageRes = when (result) {
                        FreqControlUtils.WriteResult.OK -> R.string.freq_applied_toast
                        FreqControlUtils.WriteResult.FAILED -> R.string.freq_write_failed_toast
                    }
                    Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            writesInFlight--
        }
    }

    // ------------------------------------------------------------------
    // Periodic refresh
    // ------------------------------------------------------------------

    private fun refreshLiveStateAsync() {
        if (!liveRefreshRunning) return
        if (clusterHolders.isEmpty() && gpuRoot == null && cpuBoostRoot == null && coreRoot == null) {
            scheduleNextLiveRefresh(liveGeneration)
            return
        }
        val generation = liveGeneration
        val epochAtStart = uiEpoch.get()
        val req = buildRequest(includeStatic = false)
        val coreCount = coreToggleable.size
        try {
            io.execute {
                val state = try {
                    readState(req, coreCount)
                } catch (ex: Exception) {
                    null
                }
                mainHandler.post {
                    if (generation != liveGeneration) return@post
                    // Apply only if the user did nothing since this refresh
                    // began and no write is still pending: otherwise it holds
                    // the OLD value and would undo the user's choice.
                    if (state != null && epochAtStart == uiEpoch.get() && writesInFlight == 0) {
                        applyLiveState(state)
                    }
                    scheduleNextLiveRefresh(generation)
                }
            }
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            // activity is going away
        }
    }

    private fun applyLiveState(state: StateRead) {
        state.clusters.forEachIndexed { i, read ->
            val holder = clusterHolders.getOrNull(i) ?: return@forEachIndexed
            holder.currentFreqView.text = getString(R.string.cluster_current_freq, read.curMHz)
            if (read.minKHz > 0) holder.minController?.applyRealValue(read.minKHz)
            if (read.maxKHz > 0) holder.maxController?.applyRealValue(read.maxKHz)
            if (read.gov.isNotEmpty()) holder.govController?.applyRealValue(read.gov)
        }
        state.gpu?.let { g ->
            gpuRoot?.findViewById<TextView>(R.id.gpu_current_freq)?.text =
                getString(R.string.cluster_current_freq, g.curMHz)
            if (g.minMHz > 0) gpuMinController?.applyRealValue(g.minMHz)
            if (g.maxMHz > 0) gpuMaxController?.applyRealValue(g.maxMHz)
            if (g.gov.isNotEmpty()) gpuGovController?.applyRealValue(g.gov)
            gpuBoostController?.applyRealValue(g.adrenoBoost)
            if (g.minPwrlevel != null && g.maxPwrlevel != null && g.defaultPwrlevel != null) {
                gpuPwrlevelApplyReal?.invoke(g.minPwrlevel, g.maxPwrlevel, g.defaultPwrlevel)
            }
            gpuThrottlingController?.applyRealValue(g.throttling)
        }
        state.cpuBoost?.let { b ->
            cpuBoostInputEditRef?.let { edit ->
                if (!edit.hasFocus()) edit.setText(b.inputBoostMs?.toString() ?: "")
            }
            cpuBoostSchedController?.applyRealValue(b.schedBoost)
        }
        state.coreOnline?.let { if (coreViews.isNotEmpty()) applyCoreStates(it) }
    }

    private fun scheduleNextLiveRefresh(generation: Int) {
        if (liveRefreshRunning && generation == liveGeneration) {
            mainHandler.postDelayed(liveRefreshRunnable, liveRefreshIntervalMs)
        }
    }
}
