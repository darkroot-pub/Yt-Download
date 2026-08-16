package com.darkroot.ytdownloader

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.darkroot.ytdownloader.databinding.ActivityMainBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    companion object {
        // All downloads (video/music) and saved thumbnail photos live in
        // this subfolder under the public Downloads directory, auto-created
        // on first use.
        private const val DOWNLOAD_SUBFOLDER = "Dark-Download"
        private val RELATIVE_DOWNLOAD_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER/"
    }

    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val qualityOptions = listOf(
        "Best available (video+audio)",
        "1080p",
        "720p",
        "480p",
        "Audio only (m4a)"
    )

    // Working directory: app-specific external storage, always writable,
    // no permission needed on any Android version. Finished files get
    // copied from here into the public Downloads location afterward.
    private lateinit var workDir: File

    private var ytDlInitialized = false

    // ---- Preview state (from the last "Fetch info" call) ----
    private var previewFormats: JSONArray? = null
    private var previewThumbnailUrl: String? = null
    private var previewTitle: String? = null

    // ---- Download queue ----
    private val queueItems = mutableListOf<QueueItem>()
    private lateinit var queueAdapter: QueueAdapter
    private var queueProcessorJob: Job? = null

    // ---- Library (downloaded videos/music/photos in Dark-Download) ----
    private lateinit var libraryAdapter: LibraryAdapter
    private val libraryVideos = mutableListOf<LibraryItem>()
    private val libraryAudio = mutableListOf<LibraryItem>()
    private val libraryPhotos = mutableListOf<LibraryItem>()
    private var currentLibraryTab = LibraryTab.VIDEOS

    private enum class LibraryTab { VIDEOS, MUSIC, PHOTOS }

    // ---- Clipboard auto-detect ----
    private var lastDismissedClip: String? = null
    private val genericUrlPattern = Pattern.compile(
        "https?://[\\w.-]+\\.[a-z]{2,}(/[\\w\\-./?%&=#+]*)?",
        Pattern.CASE_INSENSITIVE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        workDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "yt_work").apply { mkdirs() }

        setupQualitySpinner()
        setupQueueList()
        setupLibraryList()
        binding.folderLabel.text = publicDownloadsLabel()

        binding.fetchInfoButton.setOnClickListener { onFetchInfoClicked() }
        binding.downloadButton.setOnClickListener { onAddToQueueClicked() }
        binding.downloadThumbnailButton.setOnClickListener { onDownloadThumbnailClicked() }
        binding.playPreviewButton.setOnClickListener { onPlayPreviewClicked() }
        binding.refreshButton.setOnClickListener { refreshLibrary() }
        binding.updateEngineButton.setOnClickListener { onUpdateEngineClicked() }
        binding.useClipboardButton.setOnClickListener { onUseClipboardClicked() }
        binding.dismissClipboardButton.setOnClickListener { hideClipboardBanner() }
        binding.tabVideosButton.setOnClickListener { selectLibraryTab(LibraryTab.VIDEOS) }
        binding.tabMusicButton.setOnClickListener { selectLibraryTab(LibraryTab.MUSIC) }
        binding.tabPhotosButton.setOnClickListener { selectLibraryTab(LibraryTab.PHOTOS) }
        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        initYoutubeDL()
        requestLegacyStoragePermissionIfNeeded()
        refreshLibrary()

        animateEntrance()
        animateBlobDrift(binding.blobRed, xRange = 30f, yRange = 40f, duration = 9000)
        animateBlobDrift(binding.blobViolet, xRange = -25f, yRange = -35f, duration = 11000)
        addPressFeedback(
            binding.downloadButton, binding.updateEngineButton, binding.refreshButton,
            binding.fetchInfoButton, binding.downloadThumbnailButton, binding.playPreviewButton
        )

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Handles a link shared in from another app's share sheet (e.g. "Share"
     * on a video in a browser or another app). Extracts the first URL found
     * in the shared text and drops it straight into the URL field. */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val matcher = genericUrlPattern.matcher(sharedText)
        if (!matcher.find()) {
            Toast.makeText(this, "No link found in what was shared", Toast.LENGTH_SHORT).show()
            return
        }

        val link = matcher.group()
        binding.urlInput.setText(link)
        hideClipboardBanner()
        Toast.makeText(this, "Link received - tap Fetch info or Add to queue", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForVideoLink()
    }

    // ---------------- Animations ----------------
    private fun animateEntrance() {
        val views = listOf(binding.downloadCard, binding.queueCard, binding.libraryCard)
        views.forEachIndexed { index, view ->
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L * index)
                .setDuration(420)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun animateBlobDrift(target: View, xRange: Float, yRange: Float, duration: Long) {
        val animatorX = ObjectAnimator.ofFloat(target, "translationX", 0f, xRange, 0f)
        val animatorY = ObjectAnimator.ofFloat(target, "translationY", 0f, yRange, 0f)
        listOf(animatorX, animatorY).forEach {
            it.duration = duration
            it.repeatCount = ValueAnimator.INFINITE
            it.interpolator = AccelerateDecelerateInterpolator()
            it.start()
        }
    }

    private fun addPressFeedback(vararg targets: View) {
        targets.forEach { target ->
            target.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator()).start()
                    }
                }
                false
            }
        }
    }

    private fun publicDownloadsLabel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Downloads/$DOWNLOAD_SUBFOLDER (auto-created, shows in Files/Gallery apps)"
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBFOLDER
            ).absolutePath
        }
    }

    private fun setupQualitySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.qualitySpinner.adapter = adapter
        binding.qualitySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreviewMeta()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupQueueList() {
        queueAdapter = QueueAdapter { item -> onQueueItemAction(item) }
        binding.queueRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.queueRecyclerView.adapter = queueAdapter
    }

    private fun setupLibraryList() {
        libraryAdapter = LibraryAdapter(
            onPlayClick = { item -> playLibraryItem(item) },
            onShareClick = { item -> shareLibraryItem(item) }
        )
        binding.libraryRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.libraryRecyclerView.adapter = libraryAdapter
    }

    private fun selectLibraryTab(tab: LibraryTab) {
        currentLibraryTab = tab

        fun style(button: android.widget.Button, active: Boolean) {
            button.background = ContextCompat.getDrawable(
                this, if (active) R.drawable.bg_button_primary else android.R.color.transparent
            )
            button.setTextColor(ContextCompat.getColor(this, if (active) R.color.white else R.color.text_muted))
        }

        style(binding.tabVideosButton, tab == LibraryTab.VIDEOS)
        style(binding.tabMusicButton, tab == LibraryTab.MUSIC)
        style(binding.tabPhotosButton, tab == LibraryTab.PHOTOS)

        renderLibraryList()
    }

    private fun renderLibraryList() {
        val list = when (currentLibraryTab) {
            LibraryTab.VIDEOS -> libraryVideos
            LibraryTab.MUSIC -> libraryAudio
            LibraryTab.PHOTOS -> libraryPhotos
        }
        libraryAdapter.submitList(list)
        binding.libraryEmptyLabel.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryEmptyLabel.text = when (currentLibraryTab) {
            LibraryTab.VIDEOS -> "No videos downloaded yet"
            LibraryTab.MUSIC -> "No music downloaded yet"
            LibraryTab.PHOTOS -> "No photos saved yet"
        }
    }

    // ---------------- yt-dlp init ----------------
    private fun initYoutubeDL() {
        mainScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                    ytDlInitialized = true
                } catch (e: Exception) {
                    ytDlInitialized = false
                    withContext(Dispatchers.Main) {
                        setStatus("Failed to initialize downloader: ${e.message}", isError = true)
                    }
                    return@withContext
                }
                updateYoutubeDLEngine(showToast = false)
            }
        }
    }

    private fun updateYoutubeDLEngine(showToast: Boolean) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
            if (showToast) {
                mainScope.launch {
                    Toast.makeText(this@MainActivity, "yt-dlp engine: $status", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: YoutubeDLException) {
            if (showToast) {
                mainScope.launch {
                    Toast.makeText(this@MainActivity, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onUpdateEngineClicked() {
        binding.updateEngineButton.isEnabled = false
        binding.updateEngineButton.text = "Updating..."
        setStatus("Updating yt-dlp engine...", isError = false)
        mainScope.launch {
            withContext(Dispatchers.IO) { updateYoutubeDLEngine(showToast = true) }
            binding.updateEngineButton.isEnabled = true
            binding.updateEngineButton.text = "Update engine"
            setStatus("", isError = false)
        }
    }

    // ---------------- Permissions (pre-Android 10 only) ----------------
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }

    // ---------------- Clipboard auto-detect ----------------
    private fun checkClipboardForVideoLink() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return
            val item = clipboard.primaryClip?.getItemAt(0) ?: return
            val text = item.text?.toString() ?: return
            val matcher = genericUrlPattern.matcher(text)
            if (matcher.find()) {
                val link = matcher.group()
                if (link != lastDismissedClip && binding.urlInput.text.toString().trim() != link) {
                    showClipboardBanner(link)
                }
            }
        } catch (e: Exception) {
            // Clipboard access can fail on some OEM ROMs/background states - fail silently
        }
    }

    private fun showClipboardBanner(link: String) {
        binding.clipboardBanner.tag = link
        binding.clipboardBanner.visibility = View.VISIBLE
        binding.clipboardBanner.alpha = 0f
        binding.clipboardBanner.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideClipboardBanner() {
        val link = binding.clipboardBanner.tag as? String
        lastDismissedClip = link
        binding.clipboardBanner.animate().alpha(0f).setDuration(150).withEndAction {
            binding.clipboardBanner.visibility = View.GONE
        }.start()
    }

    private fun onUseClipboardClicked() {
        val link = binding.clipboardBanner.tag as? String ?: return
        binding.urlInput.setText(link)
        hideClipboardBanner()
    }

    // ---------------- Preview (paste-and-preview) ----------------
    private fun onFetchInfoClicked() {
        val url = binding.urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Paste a video or music link first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isLikelyUrl(url)) {
            setStatus("That doesn't look like a link. Paste a full URL starting with http:// or https://", isError = true)
            return
        }
        if (!ytDlInitialized) {
            Toast.makeText(this, "Downloader is still starting up, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }

        binding.fetchInfoButton.isEnabled = false
        binding.fetchInfoButton.text = "Fetching..."
        binding.previewContainer.visibility = View.GONE
        setStatus("Fetching video info...", isError = false)

        mainScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchVideoInfoJson(url) }
                applyPreview(json)
                setStatus("", isError = false)
            } catch (e: Exception) {
                setStatus(friendlyErrorMessage(e.message), isError = true)
            } finally {
                binding.fetchInfoButton.isEnabled = true
                binding.fetchInfoButton.text = "Fetch info"
            }
        }
    }

    private fun isLikelyUrl(text: String): Boolean {
        return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }

    /** Turns yt-dlp's raw (often long, technical) error output into a short,
     * plain-language message. Falls back to a trimmed version of the raw
     * error when nothing matches, so nothing gets silently swallowed. */
    private fun friendlyErrorMessage(raw: String?): String {
        val message = raw ?: return "Something went wrong. Try again."
        val lower = message.lowercase()

        return when {
            lower.contains("unsupported url") || lower.contains("no extractor") ->
                "This platform isn't supported. Try a link from a site like YouTube, Vimeo, SoundCloud, TikTok, or Twitter/X."
            lower.contains("is not a valid url") ->
                "That doesn't look like a valid link. Double-check it and try again."
            lower.contains("unable to download webpage") || lower.contains("network is unreachable")
                || lower.contains("timed out") || lower.contains("failed to establish a new connection") ->
                "Couldn't reach that site. Check your internet connection and try again."
            lower.contains("private video") || lower.contains("this video is private") ->
                "That video is private and can't be accessed."
            lower.contains("video unavailable") ->
                "That video is unavailable - it may have been removed or region-locked."
            lower.contains("sign in") || lower.contains("login required") || lower.contains("age") ->
                "That content requires sign-in or age verification, which this app can't do."
            lower.contains("copyright") ->
                "That content was taken down or blocked for copyright reasons."
            lower.contains("no video info returned") ->
                "Couldn't read any video info from that link. It may not point directly to a video or audio page."
            else -> {
                // Unknown error - still useful, but keep it short and drop
                // internal stack-trace-style noise if present.
                val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() } ?: message
                val trimmed = firstLine.removePrefix("ERROR:").trim()
                if (trimmed.length > 140) trimmed.take(140) + "..." else trimmed
            }
        }
    }

    private fun fetchVideoInfoJson(url: String): JSONObject {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-playlist")
        request.addOption("--skip-download")
        val response = YoutubeDL.getInstance().execute(request) { _, _, _ -> }
        val out = response.out.trim()
        val jsonLine = out.lines().lastOrNull { it.trim().startsWith("{") }
            ?: throw IllegalStateException("No video info returned")
        return JSONObject(jsonLine)
    }

    private fun applyPreview(json: JSONObject) {
        val title = json.optString("title", binding.urlInput.text.toString())
        val thumbnail = json.optString("thumbnail", null)
        val durationSeconds = if (json.has("duration") && !json.isNull("duration")) {
            json.optDouble("duration").toLong()
        } else null
        val formats = json.optJSONArray("formats")

        previewTitle = title
        previewThumbnailUrl = thumbnail
        previewFormats = formats

        binding.previewTitle.text = title
        if (!thumbnail.isNullOrBlank()) {
            binding.previewThumbnail.load(thumbnail)
        }
        binding.previewContainer.visibility = View.VISIBLE
        binding.previewContainer.alpha = 0f
        binding.previewContainer.animate().alpha(1f).setDuration(220).start()

        updatePreviewMeta(durationSeconds)
    }

    private fun updatePreviewMeta(durationOverride: Long? = null) {
        if (binding.previewContainer.visibility != View.VISIBLE && durationOverride == null) return
        val qualityChoice = qualityOptions.getOrNull(binding.qualitySpinner.selectedItemPosition) ?: return
        val estimatedBytes = estimateSizeForQuality(previewFormats, qualityChoice)
        val durationText = formatDuration(durationOverride)
        binding.previewMeta.text = "$durationText  •  ~${formatBytes(estimatedBytes)}"
    }

    private fun estimateSizeForQuality(formats: JSONArray?, qualityChoice: String): Long? {
        if (formats == null) return null
        val entries = (0 until formats.length()).mapNotNull { i -> formats.optJSONObject(i) }

        fun sizeOf(fmt: JSONObject): Long? {
            if (fmt.has("filesize") && !fmt.isNull("filesize")) return fmt.optLong("filesize")
            if (fmt.has("filesize_approx") && !fmt.isNull("filesize_approx")) return fmt.optLong("filesize_approx")
            return null
        }

        if (qualityChoice == "Audio only (m4a)") {
            val audioOnly = entries.filter { it.optString("vcodec", "none") == "none" }
            val best = audioOnly.maxByOrNull { it.optDouble("abr", 0.0) } ?: return null
            return sizeOf(best)
        }

        val targetHeight = when (qualityChoice) {
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            else -> Int.MAX_VALUE
        }

        val videoCandidates = entries.filter {
            it.optString("vcodec", "none") != "none" && it.optInt("height", 0) in 1..targetHeight
        }
        val bestVideo = videoCandidates.maxByOrNull { it.optInt("height", 0) }

        val audioCandidates = entries.filter { it.optString("vcodec", "none") == "none" }
        val bestAudio = audioCandidates.maxByOrNull { it.optDouble("abr", 0.0) }

        val videoSize = bestVideo?.let { sizeOf(it) }
        val audioSize = bestAudio?.let { sizeOf(it) }

        if (videoSize == null && audioSize == null) return null
        return (videoSize ?: 0L) + (audioSize ?: 0L)
    }

    /** Finds a directly playable stream URL for the "Play preview" button -
     * a combined (video+audio in one file) progressive format, or failing
     * that an HLS manifest URL. DASH-only split streams aren't usable here
     * since plain ExoPlayer needs one playable URI, not separately muxed
     * video/audio tracks. */
    private fun findPreviewStreamUrl(formats: JSONArray?): String? {
        if (formats == null) return null
        val entries = (0 until formats.length()).mapNotNull { i -> formats.optJSONObject(i) }

        val combined = entries.filter {
            it.optString("vcodec", "none") != "none" && it.optString("acodec", "none") != "none"
        }.maxByOrNull { it.optInt("height", 0) }
        if (combined != null) {
            val url = combined.optString("url", "")
            if (url.isNotBlank()) return url
        }

        val hls = entries.firstOrNull {
            it.optString("protocol", "").contains("m3u8") || it.optString("url", "").contains(".m3u8")
        }
        val hlsUrl = hls?.optString("url", "")
        if (!hlsUrl.isNullOrBlank()) return hlsUrl

        return null
    }

    private fun onPlayPreviewClicked() {
        val streamUrl = findPreviewStreamUrl(previewFormats)
        if (streamUrl == null) {
            Toast.makeText(this, "No streamable preview available for this video - try downloading instead", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, previewTitle ?: "Preview")
            putExtra(PlayerActivity.EXTRA_MIME_TYPE, "video/*")
        }
        startActivity(intent)
    }

    // ---------------- Thumbnail download ----------------
    private fun onDownloadThumbnailClicked() {
        val thumbnailUrl = previewThumbnailUrl
        if (thumbnailUrl.isNullOrBlank()) {
            Toast.makeText(this, "No thumbnail available for this video", Toast.LENGTH_SHORT).show()
            return
        }
        binding.downloadThumbnailButton.isEnabled = false
        binding.downloadThumbnailButton.text = "Saving..."

        mainScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { downloadThumbnailImage(thumbnailUrl, previewTitle ?: "thumbnail") }
                Toast.makeText(this@MainActivity, "Thumbnail saved: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Couldn't save thumbnail: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.downloadThumbnailButton.isEnabled = true
                binding.downloadThumbnailButton.text = "Save thumbnail"
            }
        }
    }

    private fun downloadThumbnailImage(url: String, suggestedTitle: String): String {
        val safeName = suggestedTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
        val fileName = "$safeName.jpg"

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Server returned ${connection.responseCode}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use the generic Downloads collection (not Images/Pictures) so
            // the photo lands in Downloads/Dark-Download alongside videos
            // and music, matching where the app keeps everything else.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DOWNLOAD_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create image entry")
            resolver.openOutputStream(uri)?.use { out ->
                connection.inputStream.use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val darkDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBFOLDER
            )
            darkDownloadDir.mkdirs()
            val destFile = File(darkDownloadDir, fileName)
            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        }
        connection.disconnect()
        return fileName
    }

    // ---------------- Download queue ----------------
    private fun onAddToQueueClicked() {
        val url = binding.urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Paste a video or music link first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isLikelyUrl(url)) {
            setStatus("That doesn't look like a link. Paste a full URL starting with http:// or https://", isError = true)
            return
        }
        if (!ytDlInitialized) {
            Toast.makeText(this, "Downloader is still starting up, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }

        val qualityChoice = qualityOptions[binding.qualitySpinner.selectedItemPosition]
        val item = QueueItem(
            url = url,
            qualityChoice = qualityChoice,
            title = previewTitle?.takeIf { binding.previewContainer.visibility == View.VISIBLE } ?: url,
            thumbnailUrl = previewThumbnailUrl?.takeIf { binding.previewContainer.visibility == View.VISIBLE },
            estimatedBytes = estimateSizeForQuality(previewFormats, qualityChoice)
        )
        addToQueue(item)

        binding.urlInput.setText("")
        binding.previewContainer.visibility = View.GONE
        previewFormats = null
        previewThumbnailUrl = null
        previewTitle = null

        setStatus("Added to queue", isError = false)
        pulseSuccess(binding.downloadCard)
    }

    private fun addToQueue(item: QueueItem) {
        queueItems.add(item)
        binding.queueEmptyLabel.visibility = View.GONE
        queueAdapter.submitList(queueItems.toList())
        ensureQueueProcessing()
    }

    private fun ensureQueueProcessing() {
        if (queueProcessorJob?.isActive == true) return
        queueProcessorJob = mainScope.launch {
            while (true) {
                val next = queueItems.firstOrNull { it.status == QueueStatus.QUEUED } ?: break
                processQueueItem(next)
            }
        }
    }

    private suspend fun processQueueItem(item: QueueItem) {
        item.status = QueueStatus.DOWNLOADING
        item.progress = 0
        queueAdapter.updateItem(item)

        try {
            val outputFile = withContext(Dispatchers.IO) { runQueuedDownload(item) }
            if (item.status == QueueStatus.CANCELLED) {
                outputFile?.delete()
                return
            }
            if (item.status == QueueStatus.PAUSED) {
                return
            }
            val finalFile = withContext(Dispatchers.IO) { moveToPublicDownloads(outputFile!!) }
            item.status = QueueStatus.DONE
            item.savedFileName = finalFile.name
            item.progress = 100
            queueAdapter.updateItem(item)
            refreshLibrary()
            pulseSuccess(binding.queueCard)

            // Completed items don't need to linger in the active queue box -
            // they're already visible in the library below. Briefly show
            // "Saved" then remove the row.
            delay(1400)
            removeQueueItemSilently(item)
        } catch (e: Exception) {
            if (item.status != QueueStatus.CANCELLED && item.status != QueueStatus.PAUSED) {
                item.status = QueueStatus.ERROR
                item.errorMessage = friendlyErrorMessage(e.message)
                queueAdapter.updateItem(item)
            }
        }
    }

    private fun removeQueueItemSilently(item: QueueItem) {
        queueItems.remove(item)
        queueAdapter.submitList(queueItems.toList())
        if (queueItems.isEmpty()) {
            binding.queueEmptyLabel.visibility = View.VISIBLE
        }
    }

    private fun runQueuedDownload(item: QueueItem): File? {
        val request = YoutubeDLRequest(item.url)
        val outTemplate = File(workDir, "%(title)s.%(ext)s").absolutePath
        request.addOption("-o", outTemplate)
        request.addOption("--no-playlist")
        request.addOption("--retries", "15")
        request.addOption("--fragment-retries", "15")
        request.addOption("--socket-timeout", "30")
        request.addOption("--continue")

        when (item.qualityChoice) {
            "Best available (video+audio)" -> {
                request.addOption("-f", "bestvideo+bestaudio/best")
                request.addOption("--merge-output-format", "mp4")
            }
            "1080p" -> {
                request.addOption("-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]")
                request.addOption("--merge-output-format", "mp4")
            }
            "720p" -> {
                request.addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]")
                request.addOption("--merge-output-format", "mp4")
            }
            "480p" -> {
                request.addOption("-f", "bestvideo[height<=480]+bestaudio/best[height<=480]")
                request.addOption("--merge-output-format", "mp4")
            }
            "Audio only (m4a)" -> {
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            }
        }

        val before = workDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        try {
            YoutubeDL.getInstance().execute(request, item.id) { progress, _, _ ->
                item.progress = progress.toInt().coerceIn(0, 100)
                mainScope.launch { queueAdapter.updateItem(item) }
            }
        } catch (e: Exception) {
            if (item.status == QueueStatus.CANCELLED || item.status == QueueStatus.PAUSED) {
                return null
            }
            throw e
        }

        val after = workDir.listFiles()?.toList() ?: emptyList()
        return after.firstOrNull { it.name !in before }
            ?: after.maxByOrNull { it.lastModified() }
    }

    private fun onQueueItemAction(item: QueueItem) {
        when (item.status) {
            QueueStatus.DOWNLOADING -> pauseQueueItem(item)
            QueueStatus.PAUSED -> resumeQueueItem(item)
            else -> removeQueueItem(item)
        }
    }

    private fun pauseQueueItem(item: QueueItem) {
        item.status = QueueStatus.PAUSED
        queueAdapter.updateItem(item)
        try {
            YoutubeDL.getInstance().destroyProcessById(item.id)
        } catch (e: Exception) {
            // process may have already finished naturally - safe to ignore
        }
    }

    private fun resumeQueueItem(item: QueueItem) {
        item.status = QueueStatus.QUEUED
        queueAdapter.updateItem(item)
        ensureQueueProcessing()
    }

    private fun removeQueueItem(item: QueueItem) {
        if (item.status == QueueStatus.DOWNLOADING) {
            item.status = QueueStatus.CANCELLED
            try {
                YoutubeDL.getInstance().destroyProcessById(item.id)
            } catch (e: Exception) {
                // ignore - process may already be gone
            }
        }
        removeQueueItemSilently(item)
    }

    // ---------------- Move finished file into public Downloads/Dark-Download ----------------
    private fun moveToPublicDownloads(sourceFile: File): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mimeType = if (sourceFile.extension == "m4a" || sourceFile.extension == "mp3") {
                "audio/*"
            } else {
                "video/*"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DOWNLOAD_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create entry in Downloads")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            sourceFile.delete()
            return File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER),
                sourceFile.name
            )
        } else {
            val darkDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBFOLDER
            )
            darkDownloadDir.mkdirs()
            val destFile = File(darkDownloadDir, sourceFile.name)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            sourceFile.delete()
            return destFile
        }
    }

    // ---------------- Library (downloaded videos/music/photos in Dark-Download) ----------------
    private fun refreshLibrary() {
        libraryVideos.clear()
        libraryAudio.clear()
        libraryPhotos.clear()

        val videoExts = listOf(".mp4", ".mkv", ".webm", ".mov")
        val audioExts = listOf(".m4a", ".mp3")
        val photoExts = listOf(".jpg", ".jpeg", ".png", ".webp")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH
            )
            // Only show files this app put in Downloads/Dark-Download - not
            // anything else that might exist elsewhere in Downloads.
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(RELATIVE_DOWNLOAD_PATH)
            val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    val lower = name.lowercase()
                    when {
                        videoExts.any { lower.endsWith(it) } ->
                            libraryVideos.add(LibraryItem(name, uri, MediaKind.VIDEO, "video/*"))
                        audioExts.any { lower.endsWith(it) } ->
                            libraryAudio.add(LibraryItem(name, uri, MediaKind.AUDIO, "audio/*"))
                        photoExts.any { lower.endsWith(it) } ->
                            libraryPhotos.add(LibraryItem(name, uri, MediaKind.PHOTO, "image/*"))
                    }
                }
            }
        } else {
            val darkDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBFOLDER
            )
            darkDownloadDir.mkdirs()
            darkDownloadDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    val lower = file.name.lowercase()
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    when {
                        videoExts.any { lower.endsWith(it) } ->
                            libraryVideos.add(LibraryItem(file.name, uri, MediaKind.VIDEO, "video/*"))
                        audioExts.any { lower.endsWith(it) } ->
                            libraryAudio.add(LibraryItem(file.name, uri, MediaKind.AUDIO, "audio/*"))
                        photoExts.any { lower.endsWith(it) } ->
                            libraryPhotos.add(LibraryItem(file.name, uri, MediaKind.PHOTO, "image/*"))
                    }
                }
        }

        renderLibraryList()
    }

    private fun playLibraryItem(item: LibraryItem) {
        if (item.kind == MediaKind.PHOTO) {
            // Photos open in the system's default image viewer/gallery -
            // this app's built-in player is for video/audio playback only.
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to view this image", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, item.uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, item.name)
            putExtra(PlayerActivity.EXTRA_MIME_TYPE, item.mimeType)
        }
        startActivity(intent)
    }

    private fun shareLibraryItem(item: LibraryItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    // ---------------- Status helper ----------------
    private fun setStatus(text: String, isError: Boolean) {
        binding.statusLabel.animate().alpha(0f).setDuration(100).withEndAction {
            binding.statusLabel.text = text
            binding.statusLabel.setTextColor(
                ContextCompat.getColor(this, if (isError) R.color.accent else R.color.text_muted)
            )
            binding.statusLabel.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun pulseSuccess(view: View) {
        view.animate()
            .scaleX(1.03f).scaleY(1.03f)
            .setDuration(140)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()
    }
}
