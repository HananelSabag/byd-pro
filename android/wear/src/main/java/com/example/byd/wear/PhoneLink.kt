package com.example.byd.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * The watch's only way to the car: messages to the BYD Pro app on the phone, which sends the
 * command to the server. The phone publishes the car's state back (the phone's WearSync, same paths).
 *
 * Protocol 2 (2.8): a tap is "COMMAND|id|tapAt"; the phone acks it on [PATH_ACK] right away and
 * publishes the command's record under that id until it is final. A 2.7 phone ignores that
 * format (no ack comes, and the watch says so after a few seconds); a watch that knows it talks
 * to a 2.7 phone (its state has no "proto") sends the old plain "COMMAND" instead.
 */
object PhoneLink {
    const val PATH_STATE = "/byd/state"
    const val PATH_ACK = "/byd/ack"
    const val PATH_LOG_REQUEST = "/byd/log-request"
    private const val PATH_LOG = "/byd/log"
    private const val PATH_COMMAND = "/byd/command"
    private const val PATH_REFRESH = "/byd/refresh"
    private const val CAPABILITY = "byd_phone" // declared by the phone app

    /** Ack payload for a command the phone won't run. */
    const val ACK_REFUSED = "REFUSED"

    /** Commands a watch button can send; the phone accepts only these. */
    const val LOCK = "LOCK"
    const val UNLOCK = "UNLOCK"
    const val TRUNK = "TRUNK" // opens a closed tailgate, closes an open one
    const val QUICK_COOL = "QUICK_COOL"
    const val QUICK_HEAT = "QUICK_HEAT"
    const val CLIMATE_OFF = "CLIMATE_OFF"
    val COMMANDS = setOf(LOCK, UNLOCK, TRUNK, QUICK_COOL, QUICK_HEAT, CLIMATE_OFF)

    /** False when no phone (with BYD Pro on it) is reachable: the message didn't leave the watch. */
    suspend fun command(context: Context, tap: Tap): Boolean {
        val payload = if (tap.legacy) tap.command else "${tap.command}|${tap.id}|${tap.at}"
        return send(context, PATH_COMMAND, payload.toByteArray())
    }

    /** Asks the phone for a fresh state; it reads the car unless it did so seconds ago. */
    suspend fun refresh(context: Context): Boolean = send(context, PATH_REFRESH, ByteArray(0))

    /** The watch's debug log, for the phone's Settings → Share debug log. */
    fun sendLog(context: Context, nodeId: String, text: String) {
        runCatching { Wearable.getMessageClient(context).sendMessage(nodeId, PATH_LOG, text.takeLast(LOG_MAX_CHARS).toByteArray()) }
    }

    /** The state the Data Layer already holds (delivered earlier, even while the app was closed). */
    suspend fun current(context: Context): DataMap? = runCatching {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_STATE).build()
        val items = Wearable.getDataClient(context).getDataItems(uri).await()
        try {
            items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
        } finally {
            items.release()
        }
    }.getOrNull()

    private suspend fun send(context: Context, path: String, data: ByteArray): Boolean = runCatching {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return false
        Wearable.getMessageClient(context).sendMessage(phone.id, path, data).await()
        true
    }.getOrDefault(false)

    private const val LOG_MAX_CHARS = 60_000 // a message carries ~100 KB at most
}
