/**
 * Screen for adjusting CPU cluster (little/big/prime) and GPU frequencies,
 * governors, and related tunables.
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

    private data class Detection(
        val hasBigCluster: Boolean,
        val bigPolicy: Int,
        val hasPrimeCluster: Boolean,
        val hasGpu: Boolean,
        val hasCpuInputBoostMs: Boolean,
        val hasCpuSchedBoostOnInput: Boolean,
    )

    private val clusterHolders = mutableListOf<ClusterHolder>()
    private var gpuRoot: View? = null
    private var cpuBoostRoot: View? = null

    private var gpuMinController: SpinnerController<Int>? = null
    private var gpuMaxController: SpinnerController<Int>? = null
    private var gpuGovController: SpinnerController<String>? = null
    private var gpuBoostController: SpinnerController<Int>? = null
    private var gpuThrottlingController: SwitchController? = null

    private var gpuPwrlevelApplyReal: ((min: Int, max: Int, def: Int) -> Unit)? = null

    private var cpuBoostInputEditRef: EditText? = null
    private var cpuBoostSchedController: SwitchController? = null

    private val liveRefreshIntervalMs = 2000L
    private var liveRefreshRunning = false
    private val liveRefreshRunnable = Runnable { refreshLiveStateAsync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freq_control)

        val clusterContainer = findViewById<LinearLayout>(R.id.cluster_container)
        val cpuBoostContainer = findViewById<LinearLayout>(R.id.cpu_boost_container)
        val gpuContainer = findViewById<LinearLayout>(R.id.gpu_container)

        Thread({

            val detection = try {
                Detection(
                    hasBigCluster = FreqControlUtils.hasBigCluster(),
                    bigPolicy = FreqControlUtils.detectBigPolicy(),
                    hasPrimeCluster = FreqControlUtils.hasPrimeCluster(),
                    hasGpu = FreqControlUtils.hasGpu(),
                    hasCpuInputBoostMs = FreqControlUtils.hasCpuInputBoostMs(),
                    hasCpuSchedBoostOnInput = FreqControlUtils.hasCpuSchedBoostOnInput(),
                )
            } catch (ex: Exception) {
                Detection(
                    hasBigCluster = false, bigPolicy = 0, hasPrimeCluster = false,
                    hasGpu = false, hasCpuInputBoostMs = false, hasCpuSchedBoostOnInput = false,
                )
            }
            mainHandler.post {
                buildClusterCards(clusterContainer, detection)
                buildCpuBoostCard(cpuBoostContainer, detection)
                buildGpuCard(gpuContainer, detection)
                loadAllAsync()
            }
        }).start()
    }

    override fun onResume() {
        super.onResume()
        liveRefreshRunning = true
        mainHandler.post(liveRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        liveRefreshRunning = false
        mainHandler.removeCallbacks(liveRefreshRunnable)
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

    private fun loadAllAsync() {
        Thread({
            val snapshot = clusterHolders.map { holder ->
                ClusterSnapshot(
                    holder = holder,
                    curFreq = FreqControlUtils.readFreqMHz(holder.paths.curFreq),
                    availFreq = FreqControlUtils.readAvailableFreqWithBoost(holder.paths.availFreq, holder.paths.availBoost),
                    availGov = FreqControlUtils.readAvailableGovernors(holder.paths.availGov),
                    curMinFreq = FreqControlUtils.readFreqMHz(holder.paths.minFreq),
                    curMaxFreq = FreqControlUtils.readFreqMHz(holder.paths.maxFreq),
                    curGov = FreqControlUtils.readGovernor(holder.paths.gov),
                )
            }

            val gpuSnapshot = if (gpuRoot != null) {
                GpuSnapshot(
                    curFreq = FreqControlUtils.readGpuFreqMHz(),
                    availFreq = FreqControlUtils.readAvailableFreqGPU(),
                    availGov = FreqControlUtils.readAvailableGovernors(FreqControlUtils.AVAIL_GOV_GPU),
                    curMinFreq = FreqControlUtils.readGpuMinMax(FreqControlUtils.MIN_FREQ_GPU),
                    curMaxFreq = FreqControlUtils.readGpuMinMax(FreqControlUtils.MAX_FREQ_GPU),
                    curGov = FreqControlUtils.readGovernor(FreqControlUtils.GOV_GPU),
                    curAdrenoBoost = FreqControlUtils.readAdrenoBoost(),
                    minPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.MIN_PWRLEVEL),
                    maxPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.MAX_PWRLEVEL),
                    defaultPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.DEFAULT_PWRLEVEL),
                    throttlingEnabled = FreqControlUtils.readThrottlingEnabled(),
                )
            } else {
                null
            }

            val cpuBoostSnapshot = if (cpuBoostRoot != null) {
                CpuBoostSnapshot(
                    inputBoostMs = FreqControlUtils.readIntNode(FreqControlUtils.CPU_INPUT_BOOST_MS),
                    schedBoostEnabled = FreqControlUtils.readCpuSchedBoostOnInputEnabled(),
                )
            } else {
                null
            }

            mainHandler.post {
                snapshot.forEach { bindClusterSnapshot(it) }
                gpuSnapshot?.let { bindGpuSnapshot(it) }
                cpuBoostSnapshot?.let { bindCpuBoostSnapshot(it) }
            }
        }).start()
    }

    private data class ClusterSnapshot(
        val holder: ClusterHolder,
        val curFreq: Int,
        val availFreq: List<Int>,
        val availGov: List<String>,
        val curMinFreq: Int,
        val curMaxFreq: Int,
        val curGov: String,
    )

    private data class GpuSnapshot(
        val curFreq: Int,
        val availFreq: List<Int>,
        val availGov: List<String>,
        val curMinFreq: Int,
        val curMaxFreq: Int,
        val curGov: String,
        val curAdrenoBoost: String,
        val minPwrlevel: Int?,
        val maxPwrlevel: Int?,
        val defaultPwrlevel: Int?,
        val throttlingEnabled: Boolean,
    )

    private data class CpuBoostSnapshot(
        val inputBoostMs: Int?,
        val schedBoostEnabled: Boolean,
    )

    private class SpinnerController<T>(
        private val spinner: Spinner,
        private val values: List<T>,
        initialIndex: Int,
    ) {
        var lastAppliedIndex: Int = initialIndex
            private set

        fun shouldWrite(position: Int): Boolean = position != lastAppliedIndex

        fun applyRealValue(value: T) {
            val idx = values.indexOf(value).let { if (it >= 0) it else lastAppliedIndex }
            lastAppliedIndex = idx
            spinner.setSelection(idx, false)
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
                writeInBackground(
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

    private fun bindClusterSnapshot(s: ClusterSnapshot) {
        s.holder.currentFreqView.text = getString(R.string.cluster_current_freq, s.curFreq)

        var minController: SpinnerController<Int>? = null
        var maxController: SpinnerController<Int>? = null
        val refreshMinMax: () -> Unit = {
            val minMhz = FreqControlUtils.readFreqMHz(s.holder.paths.minFreq)
            val maxMhz = FreqControlUtils.readFreqMHz(s.holder.paths.maxFreq)
            mainHandler.post {
                minController?.applyRealValue(minMhz)
                maxController?.applyRealValue(maxMhz)
            }
        }
        minController = setupFreqSpinner(s.holder.minFreqSpinner, s.availFreq, s.curMinFreq, refreshMinMax) { mhz ->
            FreqControlUtils.writeFreqCPU(s.holder.paths.minFreq, mhz)
        }
        maxController = setupFreqSpinner(s.holder.maxFreqSpinner, s.availFreq, s.curMaxFreq, refreshMinMax) { mhz ->
            FreqControlUtils.writeFreqCPU(s.holder.paths.maxFreq, mhz)
        }
        s.holder.minController = minController
        s.holder.maxController = maxController

        var govController: SpinnerController<String>? = null
        govController = setupGovernorSpinner(s.holder.governorSpinner, s.availGov, s.curGov, {
            val realGov = FreqControlUtils.readGovernor(s.holder.paths.gov)
            mainHandler.post { govController?.applyRealValue(realGov) }
        }) { gov ->
            FreqControlUtils.writeGovernor(s.holder.paths.gov, gov)
        }
        s.holder.govController = govController
    }

    private fun bindGpuSnapshot(s: GpuSnapshot) {
        val root = gpuRoot ?: return
        root.findViewById<TextView>(R.id.gpu_current_freq).text =
            getString(R.string.cluster_current_freq, s.curFreq)

        var minController: SpinnerController<Int>? = null
        var maxController: SpinnerController<Int>? = null
        val refreshMinMax: () -> Unit = {
            val minMhz = FreqControlUtils.readGpuMinMax(FreqControlUtils.MIN_FREQ_GPU)
            val maxMhz = FreqControlUtils.readGpuMinMax(FreqControlUtils.MAX_FREQ_GPU)
            mainHandler.post {
                minController?.applyRealValue(minMhz)
                maxController?.applyRealValue(maxMhz)
            }
        }
        minController = setupFreqSpinner(root.findViewById(R.id.gpu_spinner_min_freq), s.availFreq, s.curMinFreq, refreshMinMax) { mhz ->
            FreqControlUtils.writeFreqGPU(FreqControlUtils.MIN_FREQ_GPU, mhz)
        }
        maxController = setupFreqSpinner(root.findViewById(R.id.gpu_spinner_max_freq), s.availFreq, s.curMaxFreq, refreshMinMax) { mhz ->
            FreqControlUtils.writeFreqGPU(FreqControlUtils.MAX_FREQ_GPU, mhz)
        }
        gpuMinController = minController
        gpuMaxController = maxController

        var gpuGovControllerLocal: SpinnerController<String>? = null
        gpuGovControllerLocal = setupGovernorSpinner(root.findViewById(R.id.gpu_spinner_governor), s.availGov, s.curGov, {
            val realGov = FreqControlUtils.readGovernor(FreqControlUtils.GOV_GPU)
            mainHandler.post { gpuGovControllerLocal?.applyRealValue(realGov) }
        }) { gov ->
            FreqControlUtils.writeGovernor(FreqControlUtils.GOV_GPU, gov)
        }
        gpuGovController = gpuGovControllerLocal

        val boostLabels = listOf(
            getString(R.string.freq_adreno_boost_off),
            getString(R.string.freq_adreno_boost_low),
            getString(R.string.freq_adreno_boost_medium),
            getString(R.string.freq_adreno_boost_high),
        )
        val boostSpinner = root.findViewById<Spinner>(R.id.gpu_spinner_adreno_boost)
        boostSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, boostLabels)
        val curBoostIndex = s.curAdrenoBoost.toIntOrNull()?.coerceIn(0, 3) ?: 0
        boostSpinner.setSelection(curBoostIndex, false)
        val boostValues = listOf(0, 1, 2, 3)
        val boostController = SpinnerController(boostSpinner, boostValues, curBoostIndex)
        gpuBoostController = boostController
        boostSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!boostController.shouldWrite(position)) return
                writeInBackground(
                    action = { FreqControlUtils.writeAdrenoBoost(position) },
                    afterWrite = {
                        val real = FreqControlUtils.readAdrenoBoost().toIntOrNull()?.coerceIn(0, 3) ?: 0
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
                    writeInBackground(
                        action = { FreqControlUtils.writeIntNode(FreqControlUtils.DEFAULT_PWRLEVEL, value) },
                        afterWrite = {

                            val realMin = FreqControlUtils.readIntNode(FreqControlUtils.MIN_PWRLEVEL)
                            val realMax = FreqControlUtils.readIntNode(FreqControlUtils.MAX_PWRLEVEL)
                            val realDef = FreqControlUtils.readIntNode(FreqControlUtils.DEFAULT_PWRLEVEL)
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
            initialChecked = s.throttlingEnabled,
            write = { checked -> FreqControlUtils.writeThrottling(checked) },
            readBack = { FreqControlUtils.readThrottlingEnabled() },
        )
    }

    private fun bindCpuBoostSnapshot(s: CpuBoostSnapshot) {
        val root = cpuBoostRoot ?: return

        val inputBoostEdit = root.findViewById<EditText>(R.id.edit_input_boost_ms)
        val inputBoostButton = root.findViewById<Button>(R.id.button_apply_input_boost_ms)
        inputBoostEdit.setText(s.inputBoostMs?.toString() ?: "")
        cpuBoostInputEditRef = inputBoostEdit
        inputBoostButton.setOnClickListener {
            val value = inputBoostEdit.text.toString().toIntOrNull()
            if (value != null) {
                writeInBackground(
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
            initialChecked = s.schedBoostEnabled,
            write = { checked -> FreqControlUtils.writeCpuSchedBoostOnInput(checked) },
            readBack = { FreqControlUtils.readCpuSchedBoostOnInputEnabled() },
        )
    }

    private fun setupFreqSpinner(
        spinner: Spinner,
        available: List<Int>,
        current: Int,
        afterWrite: () -> Unit,
        onSelected: (Int) -> FreqControlUtils.WriteResult,
    ): SpinnerController<Int>? {
        if (available.isEmpty()) {
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("—"))
            spinner.isEnabled = false
            return null
        }
        val labels = available.map { "$it MHz" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val initialIndex = available.indexOf(current).let { if (it >= 0) it else 0 }
        spinner.setSelection(initialIndex, false)

        val controller = SpinnerController(spinner, available, initialIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!controller.shouldWrite(position)) return
                writeInBackground(action = { onSelected(available[position]) }, afterWrite = afterWrite)
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
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("—"))
            spinner.isEnabled = false
            return null
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, available)
        val initialIndex = available.indexOf(current).let { if (it >= 0) it else 0 }
        spinner.setSelection(initialIndex, false)

        val controller = SpinnerController(spinner, available, initialIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!controller.shouldWrite(position)) return
                writeInBackground(action = { onSelected(available[position]) }, afterWrite = afterWrite)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return controller
    }

    private fun writeInBackground(
        action: () -> FreqControlUtils.WriteResult,
        afterWrite: () -> Unit = {},
    ) {
        Thread({
            val result = action()
            afterWrite()
            mainHandler.post {
                val messageRes = when (result) {
                    FreqControlUtils.WriteResult.OK -> R.string.freq_applied_toast
                    FreqControlUtils.WriteResult.FAILED -> R.string.freq_write_failed_toast
                }
                Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
            }
        }).start()
    }

    private data class ClusterLiveRead(val curFreq: Int, val minFreq: Int, val maxFreq: Int, val gov: String)
    private data class GpuLiveRead(
        val curFreq: Int,
        val minFreq: Int,
        val maxFreq: Int,
        val gov: String,
        val adrenoBoost: Int,
        val minPwrlevel: Int?,
        val maxPwrlevel: Int?,
        val defaultPwrlevel: Int?,
        val throttling: Boolean,
    )
    private data class CpuBoostLiveRead(val inputBoostMs: Int?, val schedBoostEnabled: Boolean)

    private fun refreshLiveStateAsync() {
        if (clusterHolders.isEmpty() && gpuRoot == null && cpuBoostRoot == null) {

            scheduleNextLiveRefresh()
            return
        }
        Thread({
            val clusterReads = clusterHolders.map { holder ->
                holder to ClusterLiveRead(
                    curFreq = FreqControlUtils.readFreqMHz(holder.paths.curFreq),
                    minFreq = FreqControlUtils.readFreqMHz(holder.paths.minFreq),
                    maxFreq = FreqControlUtils.readFreqMHz(holder.paths.maxFreq),
                    gov = FreqControlUtils.readGovernor(holder.paths.gov),
                )
            }
            val gpuRead = if (gpuRoot != null) {
                GpuLiveRead(
                    curFreq = FreqControlUtils.readGpuFreqMHz(),
                    minFreq = FreqControlUtils.readGpuMinMax(FreqControlUtils.MIN_FREQ_GPU),
                    maxFreq = FreqControlUtils.readGpuMinMax(FreqControlUtils.MAX_FREQ_GPU),
                    gov = FreqControlUtils.readGovernor(FreqControlUtils.GOV_GPU),
                    adrenoBoost = FreqControlUtils.readAdrenoBoost().toIntOrNull()?.coerceIn(0, 3) ?: 0,
                    minPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.MIN_PWRLEVEL),
                    maxPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.MAX_PWRLEVEL),
                    defaultPwrlevel = FreqControlUtils.readIntNode(FreqControlUtils.DEFAULT_PWRLEVEL),
                    throttling = FreqControlUtils.readThrottlingEnabled(),
                )
            } else null
            val cpuBoostRead = if (cpuBoostRoot != null) {
                CpuBoostLiveRead(
                    inputBoostMs = FreqControlUtils.readIntNode(FreqControlUtils.CPU_INPUT_BOOST_MS),
                    schedBoostEnabled = FreqControlUtils.readCpuSchedBoostOnInputEnabled(),
                )
            } else null

            mainHandler.post {
                clusterReads.forEach { (holder, read) ->
                    holder.currentFreqView.text = getString(R.string.cluster_current_freq, read.curFreq)
                    holder.minController?.applyRealValue(read.minFreq)
                    holder.maxController?.applyRealValue(read.maxFreq)
                    holder.govController?.applyRealValue(read.gov)
                }
                gpuRead?.let { g ->
                    gpuRoot?.findViewById<TextView>(R.id.gpu_current_freq)?.text =
                        getString(R.string.cluster_current_freq, g.curFreq)
                    gpuMinController?.applyRealValue(g.minFreq)
                    gpuMaxController?.applyRealValue(g.maxFreq)
                    gpuGovController?.applyRealValue(g.gov)
                    gpuBoostController?.applyRealValue(g.adrenoBoost)
                    if (g.minPwrlevel != null && g.maxPwrlevel != null && g.defaultPwrlevel != null) {
                        gpuPwrlevelApplyReal?.invoke(g.minPwrlevel, g.maxPwrlevel, g.defaultPwrlevel)
                    }
                    gpuThrottlingController?.applyRealValue(g.throttling)
                }
                cpuBoostRead?.let { b ->

                    cpuBoostInputEditRef?.let { edit ->
                        if (!edit.hasFocus()) edit.setText(b.inputBoostMs?.toString() ?: "")
                    }
                    cpuBoostSchedController?.applyRealValue(b.schedBoostEnabled)
                }

                scheduleNextLiveRefresh()
            }
        }).start()
    }

    private fun scheduleNextLiveRefresh() {
        if (liveRefreshRunning) mainHandler.postDelayed(liveRefreshRunnable, liveRefreshIntervalMs)
    }
}
