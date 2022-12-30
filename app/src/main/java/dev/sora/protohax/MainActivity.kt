package dev.sora.protohax

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.ip.IpAddress
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.sora.protohax.ContextUtils.hasInternetPermission
import dev.sora.protohax.ContextUtils.isAppExists
import dev.sora.protohax.ContextUtils.readString
import dev.sora.protohax.ContextUtils.readStringOrDefault
import dev.sora.protohax.ContextUtils.toast
import dev.sora.protohax.ContextUtils.writeString
import dev.sora.protohax.activity.LogcatActivity
import dev.sora.protohax.activity.MicrosoftLoginActivity


class MainActivity : Activity(), NetBareListener {

    private val configBuilder: NetBareConfig.Builder
        get() = NetBareConfig.Builder()
        .setMtu(4096)
        .setAddress(IpAddress("10.1.10.1", 32))
        .setSession("ProtoHax")
        .addRoute(IpAddress("0.0.0.0", 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button = findViewById<FloatingActionButton>(R.id.floating_button)
        val input = findViewById<TextView>(R.id.name_edit_text)
        button.setOnClickListener {
            val targetPkgName = input.text.toString()
            if (!packageManager.isAppExists(targetPkgName)) {
                toast(getString(R.string.target_app_not_exists, targetPkgName))
                return@setOnClickListener
            }
            writeString(KEY_TARGET_PACKAGE_CACHE, targetPkgName)
            runMitMProxy(targetPkgName)
        }
        input.text = readStringOrDefault(KEY_TARGET_PACKAGE_CACHE, "com.mojang.minecraftpe")
        input.setOnLongClickListener {
            appChooser()
            true
        }

        NetBare.get().registerNetBareListener(this)
        updateConnStatus()
        updateMicrosoftButton()
        findViewById<Button>(R.id.button_show_logs).setOnClickListener {
            val myIntent = Intent(this, LogcatActivity::class.java)
            this.startActivity(myIntent)
        }
    }

    private fun appChooser() {
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle(R.string.select_apps)
        val listItems = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { it.hasInternetPermission && it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != "dev.sora.protohax" }
            .map {
                packageManager.getApplicationLabel(it.applicationInfo).toString() + " - " + it.packageName
            }.sortedBy { it }.toTypedArray()
        dialog.setItems(listItems) { dialog, which ->
            val item = listItems[which].split(" - ").last()
            writeString(KEY_TARGET_PACKAGE_CACHE, item)
            findViewById<TextView>(R.id.name_edit_text).text = item
            dialog.dismiss()
        }
        dialog.setNegativeButton("Cancel") { dialog, which ->
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateMicrosoftButton() {
        val button1 = findViewById<Button>(R.id.button_microsoft_login)
        button1.text = getString(if (readString(KEY_MICROSOFT_REFRESH_TOKEN).isNullOrEmpty()) {
            R.string.microsoft_login
        } else {
            R.string.microsoft_logout
        })
        button1.setOnClickListener {
            if (readString(KEY_MICROSOFT_REFRESH_TOKEN).isNullOrEmpty()) {
                // login
                val myIntent = Intent(this, MicrosoftLoginActivity::class.java)
                this.startActivityForResult(myIntent, REQUEST_CODE_MICROSOFT_LOGIN_OK)
            } else {
                // logout
                writeString(KEY_MICROSOFT_REFRESH_TOKEN, "")
                updateMicrosoftButton()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetBare.get().unregisterNetBareListener(this)
    }

    private fun updateConnStatus(status: Boolean = NetBare.get().isActive) {
        val button = findViewById<FloatingActionButton>(R.id.floating_button)
        button.backgroundTintList = ColorStateList.valueOf(getColor(if (status) R.color.actionbtn_active else R.color.actionbtn_inactive))

        val text1 = findViewById<TextView>(R.id.bottomAppBarText)
        text1.setText(if (status) R.string.connected else R.string.not_connected)
        text1.setOnClickListener {
            if (status) {
                val intent = packageManager.getLaunchIntentForPackage(findViewById<TextView>(R.id.name_edit_text).text.toString())
                startActivity(intent)
            }
        }
    }

    override fun onServiceStarted() {
        runOnUiThread {
            updateConnStatus(true)
        }
    }

    override fun onServiceStopped() {
        runOnUiThread {
            updateConnStatus(false)
        }
    }

    private fun runMitMProxy(targetPkgName: String) {
        try {
            if (!Settings.canDrawOverlays(this)) {
                toast(R.string.request_overlay)
                val myIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                this.startActivityForResult(myIntent, REQUEST_CODE_WITH_MITM_RECALL)
                return
            }
            if (!NetBare.get().isActive) {
                val intent = NetBare.get().prepare()
                if (intent != null) {
                    this.startActivityForResult(intent, REQUEST_CODE_WITH_MITM_RECALL_ONLY_OK)
                    return
                }
                NetBare.get().start(configBuilder
                    .addAllowedApplication(targetPkgName)
                    .build())
                toast(getString(R.string.start_proxy_toast, targetPkgName))
            } else {
                NetBare.get().stop()
                toast(R.string.stop_proxy_toast)
            }
        } catch (e: Throwable) {
            toast(e.toString())
            Log.e("ProtoHax", "mitm", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_WITH_MITM_RECALL
            || (resultCode == RESULT_OK && requestCode == REQUEST_CODE_WITH_MITM_RECALL_ONLY_OK)) {
            runMitMProxy(readString(KEY_TARGET_PACKAGE_CACHE) ?: return)
        } else if (requestCode == REQUEST_CODE_MICROSOFT_LOGIN_OK && resultCode == RESPONSE_CODE_MICROSOFT_LOGIN_OK) {
            updateMicrosoftButton()
        }
    }

    companion object {
        private const val KEY_TARGET_PACKAGE_CACHE = "TARGET_PACKAGE"
        const val KEY_MICROSOFT_REFRESH_TOKEN = "MICROSOFT_REFRESH_TOKEN"
        private const val REQUEST_CODE_WITH_MITM_RECALL = 0
        private const val REQUEST_CODE_WITH_MITM_RECALL_ONLY_OK = 1
        private const val REQUEST_CODE_MICROSOFT_LOGIN_OK = 2
        const val RESPONSE_CODE_MICROSOFT_LOGIN_OK = 1
    }
}