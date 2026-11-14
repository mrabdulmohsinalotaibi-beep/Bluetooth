package app.athat.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val entries = linkedMapOf<String, DeviceEntry>()
    private var adapter: BluetoothAdapter? = null
    private var scanning = false
    private var receiverRegistered = false
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var count: TextView

    private data class DeviceEntry(
        val device: BluetoothDevice,
        var name: String,
        var category: String,
        var paired: Boolean,
        var rssi: Int? = null
    )

    private val timeout = Runnable { stopScan("اكتمل البحث. يمكنك إعادة الفحص لاكتشاف أجهزة أخرى.") }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val announced = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeUnless { it == Short.MIN_VALUE.toInt() } else null
                        addDevice(device, announced, rssi)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // BLE may still be running until the timeout.
                    if (scanning) status.text = "انتهى فحص الأجهزة التقليدية؛ جارٍ إكمال فحص BLE."
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) addDevice(device, null)
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { addDevice(result.device, result.scanRecord?.deviceName, result.rssi) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            runOnUiThread { results.forEach { addDevice(it.device, it.scanRecord?.deviceName, it.rssi) } }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                status.text = "فحص BLE غير متاح (رمز: $errorCode). قد يظل فحص الأجهزة التقليدية يعمل."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(246, 245, 242)
        window.navigationBarColor = Color.rgb(246, 245, 242)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildUi()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        receiverRegistered = true
        status.text = "اسم الجهاز المعروض هو الاسم الذي يعلنه فعليًا، وقد لا تعلن بعض الأجهزة أسماءها."
        if (hasPermissions()) showPaired()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(18))
            setBackgroundColor(Color.rgb(246, 245, 242))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "بلوتوث الذات"
            textSize = 27f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(35, 38, 43))
            gravity = Gravity.CENTER
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "الأجهزة القريبة — النوع والقرب التقريبي"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, dp(18))
        }
        root.addView(subtitle)
        scanButton = Button(this).apply {
            text = "البحث عن الأجهزة"
            setOnClickListener { beginScan() }
        }
        root.addView(scanButton)
        root.addView(Button(this).apply {
            text = "الأجهزة المقترنة سابقًا"
            setOnClickListener { if (hasPermissions()) showPaired() else requestNeededPermissions() }
        })
        root.addView(Button(this).apply {
            text = "إعدادات البلوتوث"
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        root.addView(status)
        count = TextView(this).apply {
            text = "الأجهزة (0)"
            setTypeface(null, Typeface.BOLD)
            textSize = 18f
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(count)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "المسافة ليست قياسًا دقيقًا؛ يُقدَّر القرب من إشارة RSSI التي تتأثر بالجدران والعوائق. لا نرفع نتائج البحث لأي خادم. الاقتران يحتاج موافقة."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(root)
        updateList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions(): Boolean =
        neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestNeededPermissions() { requestPermissions(neededPermissions(), 100) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasPermissions()) beginScan()
            else status.text = "يجب السماح بالأجهزة القريبة (أو الموقع لإصدارات أندرويد القديمة) لعرض الأجهزة."
        }
    }

    private fun beginScan() {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        val bt = adapter ?: run { status.text = "هذا الهاتف لا يدعم البلوتوث."; return }
        try {
            if (!bt.isEnabled) {
                status.text = "شغّل البلوتوث أولًا ثم اضغط البحث."
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }
            stopScan(null)
            entries.clear()
            showPaired()
            scanning = true
            scanButton.isEnabled = false
            scanButton.text = "جارٍ البحث..."
            status.text = "جارٍ البحث عن أجهزة Bluetooth Classic وBLE القريبة..."
            val classicStarted = bt.startDiscovery()
            var bleStarted = false
            try {
                bt.bluetoothLeScanner?.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    bleCallback
                )
                bleStarted = bt.bluetoothLeScanner != null
            } catch (_: SecurityException) { }
            if (!classicStarted && !bleStarted) {
                stopScan("تعذر بدء الفحص؛ تحقق من تفعيل البلوتوث والصلاحيات.")
                return
            }
            handler.postDelayed(timeout, 15000L)
        } catch (_: SecurityException) {
            stopScan("الأذونات المطلوبة غير متاحة.")
        }
    }

    private fun stopScan(message: String?) {
        handler.removeCallbacks(timeout)
        if (scanning && hasPermissions()) {
            try {
                adapter?.cancelDiscovery()
                adapter?.bluetoothLeScanner?.stopScan(bleCallback)
            } catch (_: Exception) {}
        }
        scanning = false
        if (::scanButton.isInitialized) {
            scanButton.isEnabled = true
            scanButton.text = "إعادة البحث عن الأجهزة"
        }
        if (message != null && ::status.isInitialized) status.text = message
    }

    private fun showPaired() {
        if (!hasPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { addDevice(it, null) }
        } catch (_: SecurityException) { status.text = "لا توجد صلاحية لقراءة الأجهزة المقترنة." }
    }

    // RSSI is a received signal measurement, not a distance sensor.
    private fun proximity(rssi: Int?): String = when {
        rssi == null -> "المسافة غير متاحة (لم تُرصد إشارة حديثة)"
        rssi >= -55 -> "قريب جدًا (ربما ضمن نحو مترين) · ${rssi} dBm"
        rssi >= -70 -> "قريب (تقريبًا 2–8 أمتار) · ${rssi} dBm"
        rssi >= -85 -> "أبعد نسبيًا (قد يزيد على 8 أمتار) · ${rssi} dBm"
        else -> "إشارة ضعيفة جدًا (المسافة غير مؤكدة) · ${rssi} dBm"
    }

    private fun category(device: BluetoothDevice, name: String): String {
        val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        val major = btClass?.majorDeviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            val minor = btClass.deviceClass
            return when (minor) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "سماعة رأس"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "سماعة أو نظام صوتي"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "نظام صوت سيارة"
                BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> "ميكروفون"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "شاشة أو جهاز مرئي"
                else -> "جهاز صوتي أو مرئي"
            }
        }
        when (major) {
            BluetoothClass.Device.Major.PHONE -> return "هاتف"
            BluetoothClass.Device.Major.COMPUTER -> return "كمبيوتر"
            BluetoothClass.Device.Major.WEARABLE -> return "ساعة أو جهاز قابل للارتداء"
            BluetoothClass.Device.Major.PERIPHERAL -> return "ملحق حاسوب"
            BluetoothClass.Device.Major.IMAGING -> return "طابعة أو جهاز تصوير"
        }
        val n = name.lowercase()
        return when {
            Regex("speaker|headphone|headset|earbud|airpods|buds|soundbar|jbl|bose|soundcore|سماعة").containsMatchIn(n) -> "سماعة (تقدير من الاسم)"
            Regex("watch|fitbit|amazfit|garmin|band|ساعة").containsMatchIn(n) -> "ساعة ذكية (تقدير من الاسم)"
            Regex("iphone|galaxy|pixel|phone|هاتف").containsMatchIn(n) -> "هاتف (تقدير من الاسم)"
            Regex("printer|طابعة").containsMatchIn(n) -> "طابعة (تقدير من الاسم)"
            else -> when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "جهاز Bluetooth Classic · نوعه غير معروف"
                BluetoothDevice.DEVICE_TYPE_LE -> "جهاز Bluetooth Low Energy · نوعه غير معروف"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "جهاز بلوتوث مزدوج · نوعه غير معروف"
                else -> "نوع الجهاز غير معروف"
            }
        }
    }

    private fun addDevice(device: BluetoothDevice, announcedName: String?, rssi: Int? = null) {
        if (!hasPermissions()) return
        try {
            val address = device.address
            val resolvedName = announcedName?.trim()?.takeIf { it.isNotEmpty() }
                ?: device.name?.trim()?.takeIf { it.isNotEmpty() }
            val prior = entries[address]
            val displayName = resolvedName ?: prior?.name ?: "جهاز غير معروف"
            val type = category(device, displayName)
            val paired = device.bondState == BluetoothDevice.BOND_BONDED
            val strength = rssi?.takeIf { it in -127..-1 } ?: prior?.rssi
            entries[address] = DeviceEntry(device, displayName, type, paired, strength)
            updateList()
        } catch (_: SecurityException) {
            status.text = "لم يسمح النظام بقراءة معلومات الجهاز."
        }
    }

    private fun updateList() {
        count.text = "الأجهزة (${entries.size})"
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "لم تظهر أجهزة بعد. تأكد من جعل الأجهزة القريبة قابلة للاكتشاف ثم اضغط البحث."
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(dp(8), dp(18), dp(8), dp(18))
            })
            return
        }
        entries.values.sortedWith(compareByDescending<DeviceEntry> { it.paired }.thenBy { it.name })
            .forEach { entry ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(10))
                    setBackgroundColor(Color.WHITE)
                }
                card.addView(TextView(this).apply {
                    text = entry.name
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                })
                card.addView(TextView(this).apply {
                    text = entry.category + " · " + if (entry.paired) "مقترن" else "غير مقترن"
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, dp(4), 0, 0)
                })
                card.addView(TextView(this).apply {
                    text = "القرب: " + proximity(entry.rssi)
                    textSize = 13f
                    setTextColor(Color.rgb(63, 83, 108))
                    setPadding(0, dp(5), 0, 0)
                })
                if (!entry.paired) {
                    card.addView(Button(this).apply {
                        text = "طلب اقتران"
                        setOnClickListener { pairDevice(entry.device) }
                    })
                }
                val params = LinearLayout.LayoutParams(-1, -2)
                params.bottomMargin = dp(10)
                list.addView(card, params)
            }
    }

    private fun pairDevice(device: BluetoothDevice) {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        stopScan(null)
        try {
            val initiated = device.createBond()
            status.text = if (initiated) "أُرسل طلب الاقتران. وافق من نافذة النظام أو الجهاز." else "لم يبدأ الاقتران؛ استخدم إعدادات البلوتوث."
        } catch (_: Exception) {
            status.text = "تعذر الاقتران؛ جرّب من إعدادات البلوتوث."
        }
    }

    override fun onDestroy() {
        stopScan(null)
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }
}
