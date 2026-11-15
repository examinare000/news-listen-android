package com.rioikeda.newslisten.settings.domain

/** 登録済みの RSS 配信元 1 件。url が一意キー。RSS の検証は backend の責務。 */
data class RssSourceEntry(val name: String, val url: String)
