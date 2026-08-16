package com.darkroot.ytdownloader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.darkroot.ytdownloader.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.versionText.text = "Version $versionName"
        } catch (e: Exception) {
            // Keep the default "Version 1.0" text from the layout if this fails
        }
    }
}
