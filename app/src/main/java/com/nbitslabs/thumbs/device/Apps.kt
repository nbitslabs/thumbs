package com.nbitslabs.thumbs.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.nbitslabs.thumbs.mcp.ToolException

data class AppEntry(val label: String, val packageName: String, val activity: String)

object Apps {
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName to it.activity }
            .sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    }.getOrNull()

    /** Resolves an app by package name or launcher label (exact, then prefix, then substring). */
    fun resolve(context: Context, query: String): AppEntry {
        val apps = launchable(context)
        val q = query.trim().lowercase()
        val match = apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.filter { it.label.lowercase().startsWith(q) }.minByOrNull { it.label.length }
            ?: apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }
            ?: apps.filter { it.packageName.lowercase().contains(q) }.minByOrNull { it.packageName.length }
        return match ?: throw ToolException("No installed app matches '$query'. Use list_apps to see what's installed.")
    }

    fun launch(context: Context, app: AppEntry, fresh: Boolean) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }

    fun openUri(context: Context, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolException("No app can open '$uri'.")
        }
    }

    fun startIntent(
        context: Context,
        action: String?,
        data: String?,
        type: String?,
        pkg: String?,
        component: String?,
        categories: List<String>,
        extras: Map<String, String>,
    ): String {
        val intent = Intent()
        action?.let(intent::setAction)
        when {
            data != null && type != null -> intent.setDataAndType(Uri.parse(data), type)
            data != null -> intent.data = Uri.parse(data)
            type != null -> intent.type = type
        }
        pkg?.let(intent::setPackage)
        component?.let {
            intent.component = ComponentName.unflattenFromString(it)
                ?: throw ToolException("component must look like 'com.example/.MainActivity'")
        }
        categories.forEach(intent::addCategory)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Don't pre-resolve: package visibility rules would hide most handlers from us, while
        // startActivity itself isn't subject to them.
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw ToolException("No activity handles that intent.")
        } catch (e: SecurityException) {
            throw ToolException("Android refused to start the activity: ${e.message}")
        }
        return intent.component?.flattenToShortString() ?: intent.toUri(0)
    }
}
