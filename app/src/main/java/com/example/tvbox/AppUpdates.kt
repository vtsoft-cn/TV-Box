package com.example.tvbox

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

internal class AppUpdates(private val activity: MainActivity) : Closeable {
    private val repository = UpdateRepository(activity.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    private var operation: Operation? = null

    fun check() {
        val (progress, task) = showProgress(R.string.update_checking)
        executor.execute {
            val result = runCatching { repository.check(task::track) }
            activity.runOnUiThread {
                if (operation !== task) return@runOnUiThread
                progress.dismiss()
                result.fold(
                    onSuccess = { update ->
                        if (update == null) showMessage(R.string.update_none)
                        else offerDownload(update)
                    },
                    onFailure = { error ->
                        if (error is UpdateRepository.NoCompatibleApk) {
                            showMessage(R.string.update_no_compatible_apk)
                        } else {
                            Log.w("AppUpdates", "Update check failed", error)
                            showMessage(R.string.update_check_failed)
                        }
                    }
                )
            }
        }
    }

    private fun offerDownload(update: UpdateRepository.Update) {
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_available)
                .setMessage(
                    activity.getString(
                        R.string.update_available_details,
                        update.label, update.abi, Formatter.formatFileSize(activity, update.size)
                    )
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_download) { _, _ -> download(update) }
        )
    }

    private fun download(update: UpdateRepository.Update) {
        val (progress, task) = showProgress(R.string.update_downloading)
        executor.execute {
            val result = runCatching {
                repository.download(update, task::track) { percent ->
                    activity.runOnUiThread {
                        if (operation === task) {
                            progress.setMessage(activity.getString(R.string.update_download_progress, percent))
                        }
                    }
                }
            }
            activity.runOnUiThread {
                if (operation !== task) return@runOnUiThread
                progress.dismiss()
                result.fold(
                    onSuccess = ::offerInstall,
                    onFailure = { error ->
                        Log.w("AppUpdates", "Update download failed", error)
                        showMessage(
                            if (error is UpdateRepository.InvalidApk) R.string.update_invalid
                            else R.string.update_download_failed
                        )
                    }
                )
            }
        }
    }

    private fun offerInstall(apk: File) {
        val prompt = showDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_ready)
                .setMessage(R.string.update_ready_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_install, null)
        )
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val packageName = activity.packageName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !activity.packageManager.canRequestPackageInstalls()
                ) {
                    prompt.setMessage(activity.getString(R.string.update_allow_install))
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                } else {
                    val uri = FileProvider.getUriForFile(activity, "$packageName.updates", apk)
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            } catch (error: ActivityNotFoundException) {
                Log.w("AppUpdates", "Update activity unavailable", error)
                prompt.setMessage(activity.getString(R.string.update_install_failed))
            } catch (error: SecurityException) {
                Log.w("AppUpdates", "Unable to open update activity", error)
                prompt.setMessage(activity.getString(R.string.update_install_failed))
            }
        }
    }

    private fun showProgress(message: Int): Pair<AlertDialog, Operation> {
        val task = Operation()
        val progress = showDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_check)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> task.cancel() }
                .setOnCancelListener { task.cancel() }
        )
        operation = task
        progress.setOnDismissListener {
            if (dialog === progress) dialog = null
            if (operation === task) operation = null
            task.cancel()
        }
        return progress to task
    }

    private fun showMessage(message: Int) {
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_check)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        )
    }

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog =
        builder.show().also { prompt ->
            dialog = prompt
            prompt.setOnDismissListener { if (dialog === prompt) dialog = null }
        }

    override fun close() {
        operation?.cancel()
        operation = null
        dialog?.dismiss()
        dialog = null
        executor.shutdownNow()
    }

    private class Operation {
        @Volatile private var cancelled = false
        @Volatile private var call: Call? = null

        fun track(call: Call) {
            this.call = call
            if (cancelled) call.cancel()
        }

        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }
}
