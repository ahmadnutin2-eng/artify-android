package com.procreate.android.collaboration

import android.graphics.Bitmap

enum class StrokePhase { START, MOVE, END }

/** Lightweight points are sent while the pen is down so the partner sees motion immediately. */
data class LiveStrokeEvent(
    val strokeId: String,
    val phase: StrokePhase,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val color: Int,
    val size: Float,
    val opacity: Float,
    val eraser: Boolean
)

/** Exact pixels for a completed local stroke. [bitmap] ownership passes to the receiver. */
data class LocalStrokePatch(
    val strokeId: String,
    val left: Int,
    val top: Int,
    val bitmap: Bitmap
)

data class RemoteStrokePatch(
    val participantId: String,
    val participantName: String,
    val strokeId: String,
    val left: Int,
    val top: Int,
    val pngBytes: ByteArray
)

data class CollaborationSession(
    val sessionId: String,
    val localParticipantId: String,
    val localParticipantName: String,
    val partnerParticipantId: String,
    val partnerParticipantName: String,
    val canvasWidth: Int,
    val canvasHeight: Int
)

sealed class CollaborationEvent {
    data object Connecting : CollaborationEvent()
    data object Searching : CollaborationEvent()
    data class Matched(val session: CollaborationSession) : CollaborationEvent()
    data class PartnerStroke(
        val participantId: String,
        val participantName: String,
        val stroke: LiveStrokeEvent
    ) : CollaborationEvent()
    data class PartnerPatch(val patch: RemoteStrokePatch) : CollaborationEvent()
    data class PartnerLeft(val participantName: String) : CollaborationEvent()
    data class Error(val message: String) : CollaborationEvent()
    data object Closed : CollaborationEvent()
}
