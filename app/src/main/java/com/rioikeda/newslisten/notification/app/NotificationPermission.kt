package com.rioikeda.newslisten.notification.app

/** 通知権限の状態。権限は実行時に変わるので、読むたびに評価する。 */
interface NotificationPermission {
    val isGranted: Boolean
}
