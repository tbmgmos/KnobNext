package com.knobnext.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object KnobIcons {
    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val PlayPause = icon("PlayPause", "M3,5v14l9,-7zM14,5h3v14h-3zM19,5h2v14h-2z")
    val Play = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val FastForward = icon("FastForward", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
    val FastRewind = icon("FastRewind", "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z")
    val Block = icon(
        "Block",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 " +
            "3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1" +
            "C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z",
    )
    val Check = icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19L21,7l-1.41,-1.41z")
    val Close = icon(
        "Close",
        "M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12z",
    )
    val ArrowForward = icon("ArrowForward", "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z")
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val VolumeUp = icon(
        "VolumeUp",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
            "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )
    val MusicNote = icon("MusicNote", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    val Lock = icon(
        "Lock",
        "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10" +
            "c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM9,8V6c0,-1.66 1.34,-3 3,-3" +
            "s3,1.34 3,3v2H9z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
