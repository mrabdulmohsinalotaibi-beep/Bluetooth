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
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
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
    private var player: MediaPlayer? = null
    private var selectedAudio: Uri? = null
    private lateinit var audioInfo: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var count: TextView
    private lateinit var page: ScrollView
    private val cards = linkedMapOf<String, DeviceCard>()

    private data class DeviceEntry(
        val device: BluetoothDevice,
        var name: String,
        var category: String,
        var source: String,
        var paired: Boolean,
        var rssi: Int? = null,
        var appearance: Int? = null
    )
    private data class DeviceCard(
        val name: TextView,
        val category: TextView,
        val source: TextView,
        val proximity: TextView,
        val pair: Button
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
                    if (device != null) {
                        addDevice(device, null)
                        val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        status.text = when (bond) {
                            BluetoothDevice.BOND_BONDED -> "اكتمل الاقتران. حدد السماعة كمخرج صوت من إعدادات الهاتف."
                            BluetoothDevice.BOND_BONDING -> "جارٍ الاقتران. أكمل الموافقة على الجهازين."
                            else -> "لم يكتمل الاقتران. ضع الجهاز في وضع الاقتران وحاول مجددًا."
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON)
                        status.text = "البلوتوث يعمل الآن؛ اضغط البحث عن الأجهزة."
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { addDevice(result.device, result.scanRecord?.deviceName, result.rssi, appearanceFromAdvertisement(result.scanRecord?.bytes)) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            runOnUiThread { results.forEach { addDevice(it.device, it.scanRecord?.deviceName, it.rssi, appearanceFromAdvertisement(it.scanRecord?.bytes)) } }
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
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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
            text = "اختيار ملف صوتي من الجهاز"
            setOnClickListener { selectAudio() }
        })
        audioInfo = TextView(this).apply {
            text = "لم تختر ملفًا صوتيًا"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(7), 0, dp(6))
        }
        root.addView(audioInfo)
        root.addView(Button(this).apply {
            text = "تشغيل / إيقاف الصوت"
            setOnClickListener { toggleAudio() }
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
        // One scroll surface for the whole page: no nested scrolling or zero-height list.
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "المسافة ليست قياسًا دقيقًا؛ يُقدَّر القرب من إشارة RSSI التي تتأثر بالجدران والعوائق. لا نرفع نتائج البحث لأي خادم. الاقتران يحتاج موافقة."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        page = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        page.addView(root, ScrollView.LayoutParams(-1, -2))
        setContentView(page)
        updateList()
    }

    private fun selectAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, 201)
    }

    @Deprecated("Framework-compatible file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 201 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
        selectedAudio = uri
        player?.release()
        player = null
        var filename = "ملف صوتي محدد"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) filename = cursor.getString(0) ?: filename
            }
        } catch (_: Exception) { }
        audioInfo.text = "الملف: " + filename
        status.text = "تم اختيار الملف؛ الصوت يعمل عبر مخرج الصوت المختار في نظام أندرويد."
    }

    private fun toggleAudio() {
        if (player?.isPlaying == true) {
            player?.pause()
            status.text = "تم إيقاف الموسيقى مؤقتًا."
            return
        }
        if (player != null) {
            player?.start()
            status.text = "جارٍ تشغيل الموسيقى."
            return
        }
        val uri = selectedAudio ?: run {
            status.text = "اختر ملفًا صوتيًا أولًا."
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnCompletionListener { status.text = "انتهى تشغيل الملف." }
                prepare()
                start()
            }
            status.text = "جارٍ التشغيل على مخرج الصوت المختار في أندرويد."
        } catch (_: Exception) {
            player?.release()
            player = null
            status.text = "تعذر تشغيل الملف؛ جرّب صيغة MP3."
        }
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
            cards.clear()
            list.removeAllViews()
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


    // Appearance (AD type 0x19) is a standardized BLE device category.
    // Some devices do not advertise it; missing data must not be invented.
    private fun appearanceFromAdvertisement(data: ByteArray?): Int? {
        if (data == null) return null
        var pos = 0
        while (pos < data.size) {
            val length = data[pos].toInt() and 0xff
            if (length == 0 || pos + 1 + length > data.size) break
            if (length >= 3 && (data[pos + 1].toInt() and 0xff) == 0x19) {
                return (data[pos + 2].toInt() and 0xff) or
                    ((data[pos + 3].toInt() and 0xff) shl 8)
            }
            pos += 1 + length
        }
        return null
    }

    private fun appearanceCategory(value: Int?): String? {
        if (value == null || value == 0) return null
        val group = value ushr 6
        val variant = value and 0x3f
        return when (group) {
            0x001 -> "📱 هاتف"
            0x002 -> if (variant == 0x07) "📱 تابلت" else "💻 كمبيوتر"
            0x003 -> "⌚ ساعة ذكية"
            0x005 -> "🖥️ شاشة"
            0x00D -> "⌚ جهاز قياس نبض"
            0x00F -> when (variant) {
                0x01 -> "⌨️ لوحة مفاتيح"
                0x02 -> "🖱️ فأرة"
                0x04 -> "🎮 يد ألعاب"
                else -> "⌨️ ملحق حاسوب"
            }
            0x014 -> "📡 جهاز شبكة"
            0x016 -> "💡 إضاءة ذكية"
            0x01A -> "💧 جهاز ترطيب"
            0x021 -> when (variant) {
                0x02 -> "🔊 ساوندبار"
                else -> "🔊 سماعة أو مخرج صوت"
            }
            0x022 -> "🎤 جهاز إدخال صوت"
            0x024 -> "🏠 جهاز منزلي"
            0x025 -> "🎧 سماعة قابلة للارتداء"
            0x029 -> "🦻 سماعة طبية"
            else -> null
        }
    }

    private fun category(device: BluetoothDevice, name: String, appearance: Int?): Pair<String, String> {
        // Specific Bluetooth Classic device class is more meaningful than a brand guess.
        val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        val major = btClass?.majorDeviceClass
        val minor = btClass?.deviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            val exact = when (minor) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "🎧 سماعة رأس"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "🔊 سماعة أو نظام صوتي"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "🚗 نظام صوت سيارة"
                BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> "🎤 ميكروفون"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "🖥️ شاشة"
                else -> null
            }
            if (exact != null) return exact to "نوع معلن في تصنيف Bluetooth Classic"
        }
        if (major == BluetoothClass.Device.Major.COMPUTER && minor == BluetoothClass.Device.COMPUTER_LAPTOP)
            return "💻 لابتوب" to "نوع معلن في تصنيف Bluetooth Classic"
        if (major == BluetoothClass.Device.Major.PHONE)
            return "📱 هاتف" to "نوع معلن في تصنيف Bluetooth Classic"

        val bleType = appearanceCategory(appearance)
        if (bleType != null) return bleType to "نوع معلن في بيانات Bluetooth LE"

        // Generic Classic classes may be too broad for a useful category.
        val n = name.trim().lowercase()
        val nameType: String? = when {
            Regex("""(le[_ -]?)?(wh|wf)[-_ ]?1000xm[4-9]|airpods|headphone|headset|earbud|earphone|buds|soundbar|speaker|jbl|bose|soundcore|marshall|beats|سماعة""").containsMatchIn(n) ->
                "🎧 سماعة / جهاز صوتي"
            Regex("""ipad|tablet|xiaomi\s*pad|redmi\s*pad|galaxy\s*tab|lenovo\s*tab|pixel\s*tablet|surface\s*pro|تابلت|جهاز لوحي""").containsMatchIn(n) ->
                "📱 تابلت"
            Regex("""whoop|watch|fitbit|amazfit|garmin|smartband|smart\s*band|ساعة""").containsMatchIn(n) ->
                "⌚ ساعة أو سوار ذكي"
            Regex("""iphone|galaxy\s*s\d|pixel\s*\d|redmi\s*note|\bphone\b|هاتف""").containsMatchIn(n) ->
                "📱 هاتف"
            Regex("""macbook|laptop|thinkpad|desktop|computer|\bpc\b|كمبيوتر|حاسوب""").containsMatchIn(n) ->
                "💻 كمبيوتر"
            Regex("""keyboard|logitech\s*k|لوحة مفاتيح""").containsMatchIn(n) -> "⌨️ لوحة مفاتيح"
            Regex("""\bmouse\b|فأرة""").containsMatchIn(n) -> "🖱️ فأرة"
            Regex("""printer|طابعة""").containsMatchIn(n) -> "🖨️ طابعة"
            Regex("""smart\s*tv|television|android\s*tv|تلفاز""").containsMatchIn(n) -> "📺 تلفاز"
            else -> null
        }
        if (nameType != null) return nameType to "تقدير من اسم الجهاز فقط؛ غير مؤكد"

        val broad: String? = when (major) {
            BluetoothClass.Device.Major.COMPUTER -> "💻 كمبيوتر"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "🔊 جهاز صوتي أو مرئي"
            BluetoothClass.Device.Major.WEARABLE -> "⌚ جهاز قابل للارتداء"
            BluetoothClass.Device.Major.PERIPHERAL -> "⌨️ ملحق حاسوب"
            BluetoothClass.Device.Major.IMAGING -> "🖨️ جهاز طباعة أو تصوير"
            BluetoothClass.Device.Major.HEALTH -> "🩺 جهاز صحي"
            else -> null
        }
        if (broad != null) return broad to "فئة عامة معلنة عبر Bluetooth Classic"
        return "❔ نوع الجهاز غير معروف" to "لم يعلن الجهاز نوعًا يمكن التحقق منه"
    }

    private fun addDevice(
        device: BluetoothDevice,
        announcedName: String?,
        rssi: Int? = null,
        appearance: Int? = null
    ) {
        if (!hasPermissions()) return
        try {
            val address = device.address
            val prior = entries[address]
            val resolvedName = announcedName?.trim()?.takeIf { it.isNotEmpty() }
                ?: device.name?.trim()?.takeIf { it.isNotEmpty() }
            val displayName = resolvedName ?: prior?.name ?: "جهاز دون اسم معلن"
            val measured = rssi?.takeIf { it in -127..-1 } ?: prior?.rssi
            val advertisedAppearance = appearance ?: prior?.appearance
            val (type, evidence) = category(device, displayName, advertisedAppearance)
            val paired = device.bondState == BluetoothDevice.BOND_BONDED
            if (prior == null) {
                entries[address] = DeviceEntry(
                    device, displayName, type, evidence, paired, measured, advertisedAppearance
                )
            } else {
                prior.name = displayName
                prior.category = type
                prior.source = evidence
                prior.paired = paired
                prior.rssi = measured
                prior.appearance = advertisedAppearance
            }
            // Update an existing row in place; never rebuild 100+ rows on every BLE packet.
            updateList(address)
        } catch (_: SecurityException) {
            status.text = "لم يسمح النظام بقراءة معلومات الجهاز."
        }
    }

    private fun updateList(changedAddress: String? = null) {
        count.text = "الأجهزة (${entries.size}) — اسحب لأعلى أو لأسفل"
        if (entries.isEmpty()) {
            if (list.childCount == 0) {
                list.addView(TextView(this).apply {
                    text = "لم تظهر أجهزة بعد. تأكد من جعل الأجهزة القريبة قابلة للاكتشاف ثم اضغط البحث."
                    textSize = 14f
                    setTextColor(Color.GRAY)
                    setPadding(dp(8), dp(18), dp(8), dp(18))
                })
            }
            return
        }
        if (changedAddress == null) return
        val entry = entries[changedAddress] ?: return
        var display = cards[changedAddress]
        if (display == null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                setBackgroundColor(Color.WHITE)
            }
            val nameView = TextView(this).apply {
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
            }
            val typeView = TextView(this).apply {
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(40, 65, 89))
                setPadding(0, dp(4), 0, 0)
            }
            val sourceView = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, 0)
            }
            val proximityView = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.rgb(63, 83, 108))
                setPadding(0, dp(5), 0, 0)
            }
            val pairView = Button(this).apply {
                text = "طلب اقتران"
                setOnClickListener { entries[changedAddress]?.device?.let { pairDevice(it) } }
            }
            card.addView(nameView)
            card.addView(typeView)
            card.addView(sourceView)
            card.addView(proximityView)
            card.addView(pairView)
            val params = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            list.addView(card, params)
            display = DeviceCard(nameView, typeView, sourceView, proximityView, pairView)
            cards[changedAddress] = display
        }
        display.name.text = entry.name
        display.category.text = entry.category + if (entry.paired) " · مقترن" else " · غير مقترن"
        display.source.text = entry.source
        display.proximity.text = "القرب: " + proximity(entry.rssi)
        display.pair.visibility = if (entry.paired) View.GONE else View.VISIBLE
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
        player?.release()
        player = null
        super.onDestroy()
    }
}
