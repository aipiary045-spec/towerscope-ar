package com.towerscope.ar.network

import android.content.Context
import android.content.Intent

object TestResultExport {

    fun shareText(context: Context, title: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share results").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareCsv(context: Context, title: String, csv: String, filename: String = "results.csv") {
        shareText(context, title, csv)
    }
}
