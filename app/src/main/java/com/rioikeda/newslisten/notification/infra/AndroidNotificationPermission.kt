package com.rioikeda.newslisten.notification.infra

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rioikeda.newslisten.notification.app.NotificationPermission

/**
 * API 33 未満は POST_NOTIFICATIONS 権限自体が存在しないため常に許可扱い
 * （MainActivity のランタイム要求ガードと同じ判定基準）。権限は実行時に変わるので、読むたびに評価する。
 */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {
    override val isGranted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
