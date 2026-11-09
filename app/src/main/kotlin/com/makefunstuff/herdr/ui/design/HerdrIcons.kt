package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icon set, by Lucide's names. Where the Herdr desktop's workbench draws a dot-matrix
 * glyph (its px-chrome/gw set: search, settings, plus, chevrons, server, terminal, pin…) the icon
 * is that glyph (`GlyphArt`); the rest are Lucide (lucide.dev, ISC licence) at its own geometry:
 * a 24-unit grid, 2-unit strokes, no fills, generated from lucide-react 1.47.0's icon nodes.
 * Sizes (docs/DESIGN.md): 20dp in bars and rows, 16dp inside buttons and chips, 18dp in menus.
 */
object HerdrIcons {
    val Activity: ImageVector by lazy { lucide("Activity", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2") }
    val ALargeSmall: ImageVector by lazy { lucide("ALargeSmall", "m15 16 2.536-7.328a1.02 1.02 1 0 1 1.928 0L22 16", "M15.697 14h5.606", "m2 16 4.039-9.69a.5.5 0 0 1 .923 0L11 16", "M3.304 13h6.392") }
    val Archive: ImageVector get() = GlyphArt.Archive
    val ArchiveRestore: ImageVector by lazy { lucide("ArchiveRestore", "M3 3h18a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-18a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M4 8v11a2 2 0 0 0 2 2h2", "M20 8v11a2 2 0 0 1-2 2h-2", "m9 15 3-3 3 3", "M12 12v9") }
    val ArrowDown: ImageVector by lazy { lucide("ArrowDown", "M12 5v14", "m19 12-7 7-7-7") }
    val ArrowLeft: ImageVector by lazy { lucide("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }
    val ArrowLeftRight: ImageVector by lazy { lucide("ArrowLeftRight", "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4") }
    val ArrowRight: ImageVector by lazy { lucide("ArrowRight", "M5 12h14", "m12 5 7 7-7 7") }
    val ArrowUp: ImageVector by lazy { lucide("ArrowUp", "m5 12 7-7 7 7", "M12 19V5") }
    val Bell: ImageVector by lazy { lucide("Bell", "M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }
    val BellOff: ImageVector by lazy { lucide("BellOff", "M10.268 21a2 2 0 0 0 3.464 0", "M17 17H4a1 1 0 0 1-.74-1.673C4.59 13.956 6 12.499 6 8a6 6 0 0 1 .258-1.742", "m2 2 20 20", "M8.668 3.01A6 6 0 0 1 18 8c0 2.687.77 4.653 1.707 6.05") }
    val BellRing: ImageVector by lazy { lucide("BellRing", "M10.268 21a2 2 0 0 0 3.464 0", "M22 8c0-2.3-.8-4.3-2-6", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326", "M4 2C2.8 3.7 2 5.7 2 8") }
    val Bookmark: ImageVector by lazy { lucide("Bookmark", "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z") }
    val BookOpen: ImageVector by lazy { lucide("BookOpen", "M12 5v16", "M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z") }
    val Bot: ImageVector by lazy { lucide("Bot", "M12 8V4H8", "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2Z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2") }
    val Box: ImageVector by lazy { lucide("Box", "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z", "m3.3 7 8.7 5 8.7-5", "M12 22V12") }
    val Brain: ImageVector by lazy { lucide("Brain", "M12 18V5", "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4", "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5", "M17.997 5.125a4 4 0 0 1 2.526 5.77", "M18 18a4 4 0 0 0 2-7.464", "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517", "M6 18a4 4 0 0 1-2-7.464", "M6.003 5.125a4 4 0 0 0-2.526 5.77") }
    val BrushCleaning: ImageVector by lazy { lucide("BrushCleaning", "m16 22-1-4", "M19 14a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2h-3a1 1 0 0 1-1-1V4a2 2 0 0 0-4 0v5a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2v1a1 1 0 0 0 1 1", "M19 14H5l-1.973 6.767A1 1 0 0 0 4 22h16a1 1 0 0 0 .973-1.233z", "m8 22 1-4") }
    val Bug: ImageVector by lazy { lucide("Bug", "M12 20v-9", "M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4z", "M14.12 3.88 16 2", "M21 21a4 4 0 0 0-3.81-4", "M21 5a4 4 0 0 1-3.55 3.97", "M22 13h-4", "M3 21a4 4 0 0 1 3.81-4", "M3 5a4 4 0 0 0 3.55 3.97", "M6 13H2", "m8 2 1.88 1.88", "M9 7.13V6a3 3 0 1 1 6 0v1.13") }
    val Calendar: ImageVector by lazy { lucide("Calendar", "M8 2v3", "M16 2v3", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M3 9h18") }
    val CalendarClock: ImageVector by lazy { lucide("CalendarClock", "M16 14v2.2l1.6 1", "M16 2v3", "M21 7.338V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h2.338", "M3 9h5.859", "M8 2v3", "M10 16a6 6 0 1 0 12 0a6 6 0 1 0 -12 0") }
    val Camera: ImageVector by lazy { lucide("Camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val Car: ImageVector by lazy { lucide("Car", "M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2", "M5 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M9 17h6", "M15 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0") }
    val ChartLine: ImageVector by lazy { lucide("ChartLine", "M3 3v16a2 2 0 0 0 2 2h16", "m19 9-5 5-4-4-3 3") }
    val CheckCheck: ImageVector by lazy { lucide("CheckCheck", "M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16") }
    val Check: ImageVector get() = GlyphArt.Check
    val ChevronDown: ImageVector get() = GlyphArt.ChevronDown
    val ChevronLeft: ImageVector get() = GlyphArt.ChevronLeft
    val ChevronRight: ImageVector get() = GlyphArt.ChevronRight
    val ChevronUp: ImageVector by lazy { lucide("ChevronUp", "m18 15-6-6-6 6") }
    val Circle: ImageVector by lazy { lucide("Circle", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val CircleAlert: ImageVector by lazy { lucide("CircleAlert", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 8L12 12", "M12 16L12.01 16") }
    val CircleCheck: ImageVector get() = GlyphArt.CircleCheck
    val CircleCheckBig: ImageVector by lazy { lucide("CircleCheckBig", "M21.801 10A10 10 0 1 1 17 3.335", "m9 11 3 3L22 4") }
    val CirclePlay: ImageVector by lazy { lucide("CirclePlay", "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val CirclePlus: ImageVector by lazy { lucide("CirclePlus", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M8 12h8", "M12 8v8") }
    val CircleStop: ImageVector by lazy { lucide("CircleStop", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M10 9h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z") }
    val CircleX: ImageVector by lazy { lucide("CircleX", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "m15 9-6 6", "m9 9 6 6") }
    val ClipboardPaste: ImageVector by lazy { lucide("ClipboardPaste", "M11 14h10", "M16 4h2a2 2 0 0 1 2 2v1.344", "m17 18 4-4-4-4", "M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 1.793-1.113", "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z") }
    val ClipboardList: ImageVector by lazy { lucide("ClipboardList", "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z", "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01") }
    val Clock: ImageVector get() = GlyphArt.Clock
    val Cloud: ImageVector by lazy { lucide("Cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z") }
    val CloudDownload: ImageVector by lazy { lucide("CloudDownload", "M12 13v8l-4-4", "m12 21 4-4", "M4.393 15.269A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.436 8.284") }
    val CloudUpload: ImageVector by lazy { lucide("CloudUpload", "M12 13v8", "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "m8 17 4-4 4 4") }
    val Code: ImageVector by lazy { lucide("Code", "m16 18 6-6-6-6", "m8 6-6 6 6 6") }
    val Container: ImageVector by lazy { lucide("Container", "M22 7.7c0-.6-.4-1.2-.8-1.5l-6.3-3.9a1.72 1.72 0 0 0-1.7 0l-10.3 6c-.5.2-.9.8-.9 1.4v6.6c0 .5.4 1.2.8 1.5l6.3 3.9a1.72 1.72 0 0 0 1.7 0l10.3-6c.5-.3.9-1 .9-1.5Z", "M10 21.9V14L2.1 9.1", "m10 14 11.9-6.9", "M14 19.8v-8.1", "M18 17.5V9.4") }
    val Copy: ImageVector by lazy { lucide("Copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val Cpu: ImageVector by lazy { lucide("Cpu", "M12 20v2", "M12 2v2", "M17 20v2", "M17 2v2", "M2 12h2", "M2 17h2", "M2 7h2", "M20 12h2", "M20 17h2", "M20 7h2", "M7 20v2", "M7 2v2", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z", "M9 8h6a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1Z") }
    val Crop: ImageVector by lazy { lucide("Crop", "M6 2v14a2 2 0 0 0 2 2h14", "M18 22V8a2 2 0 0 0-2-2H2") }
    val Database: ImageVector by lazy { lucide("Database", "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0", "M3 5V19A9 3 0 0 0 21 19V5", "M3 12A9 3 0 0 0 21 12") }
    val Download: ImageVector by lazy { lucide("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val Ellipsis: ImageVector by lazy { lucide("Ellipsis", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0") }
    /** Three lines: the drawer, as 3.1's phone has it (the workbench sidebar glyph reads as a window at 20dp). */
    val Menu: ImageVector by lazy { lucide("Menu", "M4 6h16", "M4 12h16", "M4 18h16") }
    val Eraser: ImageVector by lazy { lucide("Eraser", "M21 21H8a2 2 0 0 1-1.42-.587l-3.994-3.999a2 2 0 0 1 0-2.828l10-10a2 2 0 0 1 2.829 0l5.999 6a2 2 0 0 1 0 2.828L12.834 21", "m5.082 11.09 8.828 8.828") }
    val ExternalLink: ImageVector by lazy { lucide("ExternalLink", "M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6") }
    val Eye: ImageVector by lazy { lucide("Eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val EyeOff: ImageVector by lazy { lucide("EyeOff", "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20") }
    val File: ImageVector by lazy { lucide("File", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5") }
    val FileCode: ImageVector by lazy { lucide("FileCode", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 12.5 8 15l2 2.5", "m14 12.5 2 2.5-2 2.5") }
    val FileDiff: ImageVector by lazy { lucide("FileDiff", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M9 10h6", "M12 13V7", "M9 17h6") }
    val FileSearch: ImageVector by lazy { lucide("FileSearch", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M9 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0", "M13.3 16.3 15 18") }
    val FileText: ImageVector get() = GlyphArt.FileText
    val FingerprintPattern: ImageVector by lazy { lucide("FingerprintPattern", "M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4", "M14 13.12c0 2.38 0 6.38-1 8.88", "M17.29 21.02c.12-.6.43-2.3.5-3.02", "M2 12a10 10 0 0 1 18-6", "M2 16h.01", "M21.8 16c.2-2 .131-5.354 0-6", "M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2", "M8.65 22c.21-.66.45-1.32.57-2", "M9 6.8a6 6 0 0 1 9 5.2v2") }
    val Flame: ImageVector by lazy { lucide("Flame", "M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0 5 5 0 0 1 1-3 1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4") }
    val FlaskConical: ImageVector by lazy { lucide("FlaskConical", "M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2", "M6.453 15h11.094", "M8.5 2h7") }
    val Focus: ImageVector by lazy { lucide("Focus", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2") }
    val Folder: ImageVector get() = GlyphArt.Folder
    val FolderGit2: ImageVector by lazy { lucide("FolderGit2", "M18 19a5 5 0 0 1-5-5v8", "M9 20H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v5", "M11 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M18 19a2 2 0 1 0 4 0a2 2 0 1 0 -4 0") }
    val FolderOpen: ImageVector by lazy { lucide("FolderOpen", "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2") }
    val Gauge: ImageVector by lazy { lucide("Gauge", "m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0") }
    val GitBranch: ImageVector get() = GlyphArt.GitBranch
    val GitCommitHorizontal: ImageVector by lazy { lucide("GitCommitHorizontal", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 12L9 12", "M15 12L21 12") }
    val GitCompare: ImageVector get() = GlyphArt.GitCompare
    val GitFork: ImageVector by lazy { lucide("GitFork", "M9 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M15 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M18 9v2c0 .6-.4 1-1 1H7c-.6 0-1-.4-1-1V9", "M12 12v3") }
    val GitMerge: ImageVector by lazy { lucide("GitMerge", "M15 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M6 21V9a9 9 0 0 0 9 9") }
    val GitPullRequest: ImageVector get() = GlyphArt.GitPullRequest
    val Globe: ImageVector by lazy { lucide("Globe", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20") }
    val Hammer: ImageVector by lazy { lucide("Hammer", "m15 12-9.373 9.373a1 1 0 0 1-3.001-3L12 9", "m18 15 4-4", "m21.5 11.5-1.914-1.914A2 2 0 0 1 19 8.172v-.344a2 2 0 0 0-.586-1.414l-1.657-1.657A6 6 0 0 0 12.516 3H9l1.243 1.243A6 6 0 0 1 12 8.485V10l2 2h1.172a2 2 0 0 1 1.414.586L18.5 14.5") }
    val HardDrive: ImageVector by lazy { lucide("HardDrive", "M10 16h.01", "M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M21.946 12.013H2.054", "M6 16h.01") }
    val Hash: ImageVector by lazy { lucide("Hash", "M4 9L20 9", "M4 15L20 15", "M10 3L8 21", "M16 3L14 21") }
    val Phone: ImageVector by lazy { lucide("Phone", "M13.832 16.568a1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 6.392 6.384") }
    val Headphones: ImageVector by lazy { lucide("Headphones", "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3") }
    val Heart: ImageVector by lazy { lucide("Heart", "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5") }
    val Highlighter: ImageVector by lazy { lucide("Highlighter", "m9 11-6 6v3h9l3-3", "m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4") }
    val History: ImageVector by lazy { lucide("History", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2") }
    val Hourglass: ImageVector by lazy { lucide("Hourglass", "M5 22h14", "M5 2h14", "M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22", "M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2") }
    val House: ImageVector by lazy { lucide("House", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Image: ImageVector by lazy { lucide("Image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Images: ImageVector by lazy { lucide("Images", "M18 22H4a2 2 0 0 1-2-2V6", "m22 13-1.296-1.296a2.41 2.41 0 0 0-3.408 0L11 18", "M10 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M8 2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z") }
    val Inbox: ImageVector by lazy { lucide("Inbox", "M22 12L16 12L14 15L10 15L8 12L2 12", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z") }
    val LogIn: ImageVector by lazy { lucide("LogIn", "m10 17 5-5-5-5", "M15 12H3", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4") }
    val LogOut: ImageVector by lazy { lucide("LogOut", "m16 17 5-5-5-5", "M21 12H9", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4") }
    val Info: ImageVector by lazy { lucide("Info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 16v-4", "M12 8h.01") }
    val Keyboard: ImageVector by lazy { lucide("Keyboard", "M10 8h.01", "M12 12h.01", "M14 8h.01", "M16 12h.01", "M18 8h.01", "M6 8h.01", "M7 16h10", "M8 12h.01", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z") }
    val KeyRound: ImageVector by lazy { lucide("KeyRound", "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z", "M16 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0") }
    val Layers: ImageVector get() = GlyphArt.Layers
    val LayoutDashboard: ImageVector by lazy { lucide("LayoutDashboard", "M4 3h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z", "M15 3h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M15 12h5a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1Z", "M4 16h5a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z") }
    val Link: ImageVector by lazy { lucide("Link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71") }
    val List: ImageVector by lazy { lucide("List", "M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13") }
    val ListChecks: ImageVector by lazy { lucide("ListChecks", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "m3 7 2 2 4-4") }
    val ListTodo: ImageVector by lazy { lucide("ListTodo", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "M4 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z") }
    val Lock: ImageVector by lazy { lucide("Lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z", "M7 11V7a5 5 0 0 1 10 0v4") }
    val Mail: ImageVector by lazy { lucide("Mail", "m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z") }
    val Maximize2: ImageVector by lazy { lucide("Maximize2", "M15 3h6v6", "m21 3-7 7", "m3 21 7-7", "M9 21H3v-6") }
    val MemoryStick: ImageVector by lazy { lucide("MemoryStick", "M12 12v-2", "M12 18v-2", "M16 12v-2", "M16 18v-2", "M2 11h1.5", "M20 18v-2", "M20.5 11H22", "M4 18v-2", "M8 12v-2", "M8 18v-2", "M4 6h16a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2Z") }
    val MessageSquare: ImageVector by lazy { lucide("MessageSquare", "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z") }
    val MessageSquarePlus: ImageVector by lazy { lucide("MessageSquarePlus", "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z", "M12 8v6", "M9 11h6") }
    val MessagesSquare: ImageVector by lazy { lucide("MessagesSquare", "M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z", "M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1") }
    val Mic: ImageVector by lazy { lucide("Mic", "M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 2a3 3 0 0 1 3 3v7a3 3 0 0 1 -6 0v-7a3 3 0 0 1 3 -3Z") }
    val MicOff: ImageVector by lazy { lucide("MicOff", "M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7.12 7.12 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12") }
    val Minimize2: ImageVector by lazy { lucide("Minimize2", "m14 10 7-7", "M20 10h-6V4", "m3 21 7-7", "M4 14h6v6") }
    val Minus: ImageVector get() = GlyphArt.Minus
    val Monitor: ImageVector get() = GlyphArt.Monitor
    val Moon: ImageVector by lazy { lucide("Moon", "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401") }
    val MoveUpRight: ImageVector by lazy { lucide("MoveUpRight", "M13 5H19V11", "M19 5L5 19") }
    val Network: ImageVector by lazy { lucide("Network", "M17 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z", "M3 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z", "M10 2h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1Z", "M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3", "M12 12V8") }
    val Newspaper: ImageVector by lazy { lucide("Newspaper", "M15 18h-5", "M18 14h-8", "M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-4 0v-9a2 2 0 0 1 2-2h2", "M11 6h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z") }
    val NotebookPen: ImageVector by lazy { lucide("NotebookPen", "M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4", "M2 6h4", "M2 10h4", "M2 14h4", "M2 18h4", "M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z") }
    val Package: ImageVector by lazy { lucide("Package", "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z", "M12 22V12", "M3.29 7L12 12L20.71 7", "m7.5 4.27 9 5.15") }
    val Pencil: ImageVector by lazy { lucide("Pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4") }
    val PenLine: ImageVector by lazy { lucide("PenLine", "M12 20h9", "M16.376 3.622a1 1 0 0 1 3.002 3.002L7.368 18.635a2 2 0 0 1-.855.506l-2.872.838a.5.5 0 0 1-.62-.62l.838-2.872a2 2 0 0 1 .506-.854z") }
    val PictureInPicture2: ImageVector by lazy { lucide("PictureInPicture2", "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4", "M14 13h6a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-3a2 2 0 0 1 2 -2Z") }
    val Pin: ImageVector get() = GlyphArt.Pin
    val PinOff: ImageVector get() = GlyphArt.PinOff
    val Pause: ImageVector by lazy { lucide("Pause", "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z") }
    val Play: ImageVector by lazy { lucide("Play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val Plug: ImageVector by lazy { lucide("Plug", "M12 22v-5", "M15 8V2", "M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1z", "M9 8V2") }
    val Plus: ImageVector get() = GlyphArt.Plus
    val Power: ImageVector by lazy { lucide("Power", "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04") }
    val Puzzle: ImageVector by lazy { lucide("Puzzle", "M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z") }
    val QrCode: ImageVector by lazy { lucide("QrCode", "M4 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M17 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M4 16h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M21 16h-3a2 2 0 0 0-2 2v3", "M21 21v.01", "M12 7v3a2 2 0 0 1-2 2H7", "M3 12h.01", "M12 3h.01", "M12 16v.01", "M16 12h1", "M21 12v.01", "M12 21v-1") }
    val RefreshCcw: ImageVector by lazy { lucide("RefreshCcw", "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16", "M16 16h5v5") }
    val RefreshCw: ImageVector by lazy { lucide("RefreshCw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val Reply: ImageVector by lazy { lucide("Reply", "M20 18v-2a4 4 0 0 0-4-4H4", "M9 17l-5-5 5-5") }
    val Rocket: ImageVector by lazy { lucide("Rocket", "M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5", "M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09", "M9 12a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.4 22.4 0 0 1-4 2z", "M9 12H4s.55-3.03 2-4c1.62-1.08 5 .05 5 .05") }
    val RotateCcw: ImageVector by lazy { lucide("RotateCcw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val RotateCw: ImageVector by lazy { lucide("RotateCw", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5") }
    val ScanQrCode: ImageVector by lazy { lucide("ScanQrCode", "M17 12v4a1 1 0 0 1-1 1h-4", "M17 3h2a2 2 0 0 1 2 2v2", "M17 8V7", "M21 17v2a2 2 0 0 1-2 2h-2", "M3 7V5a2 2 0 0 1 2-2h2", "M7 17h.01", "M7 21H5a2 2 0 0 1-2-2v-2", "M8 7h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z") }
    val ScanSearch: ImageVector by lazy { lucide("ScanSearch", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "m16 16-1.9-1.9") }
    val Scissors: ImageVector by lazy { lucide("Scissors", "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M8.12 8.12 12 12", "M20 4 8.12 15.88", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M14.8 14.8 20 20") }
    val Search: ImageVector get() = GlyphArt.Search
    /** The workbench's sidebar glyph: opens the spaces drawer. */
    val Sidebar: ImageVector get() = GlyphArt.Sidebar
    val SquarePen: ImageVector get() = GlyphArt.SquarePen
    val Send: ImageVector by lazy { lucide("Send", "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z", "m21.854 2.147-10.94 10.939") }
    val Server: ImageVector get() = GlyphArt.Server
    val Settings: ImageVector get() = GlyphArt.Settings
    val ShieldCheck: ImageVector by lazy { lucide("ShieldCheck", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4") }
    val SkipForward: ImageVector by lazy { lucide("SkipForward", "M21 4v16", "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z") }
    val Smartphone: ImageVector by lazy { lucide("Smartphone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z", "M12 18h.01") }
    val Sparkle: ImageVector by lazy { lucide("Sparkle", "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z") }
    val Sparkles: ImageVector by lazy { lucide("Sparkles", "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z", "M20 2v4", "M22 4h-4", "M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0") }
    val Square: ImageVector get() = GlyphArt.Square
    val SquareKanban: ImageVector by lazy { lucide("SquareKanban", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M8 7v7", "M12 7v4", "M16 7v9") }
    val SquareTerminal: ImageVector get() = GlyphArt.Terminal
    val Star: ImageVector by lazy { lucide("Star", "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z") }
    val Sun: ImageVector by lazy { lucide("Sun", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41") }
    val Sunrise: ImageVector by lazy { lucide("Sunrise", "M12 2v8", "m4.93 10.93 1.41 1.41", "M2 18h2", "M20 18h2", "m19.07 10.93-1.41 1.41", "M22 22H2", "m8 6 4-4 4 4", "M16 18a4 4 0 0 0-8 0") }
    val Tag: ImageVector by lazy { lucide("Tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", "M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0") }
    val Terminal: ImageVector get() = GlyphArt.Terminal
    val TestTubeDiagonal: ImageVector by lazy { lucide("TestTubeDiagonal", "M21 7 6.82 21.18a2.83 2.83 0 0 1-3.99-.01a2.83 2.83 0 0 1 0-4L17 3", "m16 2 6 6", "M12 16H4") }
    val TextSelect: ImageVector by lazy { lucide("TextSelect", "M14 21h1", "M14 3h1", "M19 3a2 2 0 0 1 2 2", "M21 14v1", "M21 19a2 2 0 0 1-2 2", "M21 9v1", "M3 14v1", "M3 9v1", "M5 21a2 2 0 0 1-2-2", "M5 3a2 2 0 0 0-2 2", "M7 12h10", "M7 16h6", "M7 8h8", "M9 21h1", "M9 3h1") }
    val TextWrap: ImageVector by lazy { lucide("TextWrap", "m16 16-3 3 3 3", "M3 12h14.5a1 1 0 0 1 0 7H13", "M3 19h6", "M3 5h18") }
    val Timer: ImageVector by lazy { lucide("Timer", "M10 2L14 2", "M12 14L15 11", "M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0") }
    val Trash2: ImageVector by lazy { lucide("Trash2", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2") }
    val TriangleAlert: ImageVector by lazy { lucide("TriangleAlert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01") }
    val Undo2: ImageVector by lazy { lucide("Undo2", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11") }
    val Upload: ImageVector by lazy { lucide("Upload", "M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4") }
    val User: ImageVector by lazy { lucide("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0") }
    val Volume2: ImageVector by lazy { lucide("Volume2", "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728") }
    val VolumeX: ImageVector by lazy { lucide("VolumeX", "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "m22 9-6 6", "m16 9 6 6") }
    val WandSparkles: ImageVector by lazy { lucide("WandSparkles", "m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72", "m14 7 3 3", "M5 6v4", "M19 14v4", "M10 2v2", "M7 8H3", "M21 16h-4", "M11 3H9") }
    val Workflow: ImageVector by lazy { lucide("Workflow", "M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z", "M7 11v4a2 2 0 0 0 2 2h4", "M15 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z") }
    val Wrench: ImageVector by lazy { lucide("Wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z") }
    val X: ImageVector get() = GlyphArt.X
    val Zap: ImageVector by lazy { lucide("Zap", "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z") }

    /** A Lucide icon by its name, as a command's `# icon:` gives it (`git-branch`, `GitBranch`, `trash-2`), and a few plain words (`deploy`, `test`); null for one the app doesn't carry. */
    fun named(name: String): ImageVector? = byName[name.lowercase().filter { it.isLetterOrDigit() }]?.invoke()

    private val byName: Map<String, () -> ImageVector> by lazy {
        mapOf(
            "activity" to { Activity },
            "alargesmall" to { ALargeSmall },
            "archive" to { Archive },
            "archiverestore" to { ArchiveRestore },
            "arrowdown" to { ArrowDown },
            "arrowleft" to { ArrowLeft },
            "arrowleftright" to { ArrowLeftRight },
            "arrowright" to { ArrowRight },
            "arrowup" to { ArrowUp },
            "bell" to { Bell },
            "belloff" to { BellOff },
            "bellring" to { BellRing },
            "bookmark" to { Bookmark },
            "bookopen" to { BookOpen },
            "bot" to { Bot },
            "box" to { Box },
            "brain" to { Brain },
            "brushcleaning" to { BrushCleaning },
            "bug" to { Bug },
            "calendar" to { Calendar },
            "calendarclock" to { CalendarClock },
            "car" to { Car },
            "chartline" to { ChartLine },
            "check" to { Check },
            "checkcheck" to { CheckCheck },
            "chevrondown" to { ChevronDown },
            "chevronleft" to { ChevronLeft },
            "chevronright" to { ChevronRight },
            "chevronup" to { ChevronUp },
            "circle" to { Circle },
            "circlealert" to { CircleAlert },
            "circlecheck" to { CircleCheck },
            "circlecheckbig" to { CircleCheckBig },
            "circleplay" to { CirclePlay },
            "circleplus" to { CirclePlus },
            "circlestop" to { CircleStop },
            "circlex" to { CircleX },
            "clipboardpaste" to { ClipboardPaste },
            "clipboardlist" to { ClipboardList },
            "clock" to { Clock },
            "cloud" to { Cloud },
            "clouddownload" to { CloudDownload },
            "cloudupload" to { CloudUpload },
            "code" to { Code },
            "container" to { Container },
            "copy" to { Copy },
            "cpu" to { Cpu },
            "database" to { Database },
            "download" to { Download },
            "ellipsis" to { Ellipsis },
            "eraser" to { Eraser },
            "externallink" to { ExternalLink },
            "eye" to { Eye },
            "file" to { File },
            "filecode" to { FileCode },
            "filediff" to { FileDiff },
            "filesearch" to { FileSearch },
            "filetext" to { FileText },
            "flame" to { Flame },
            "flaskconical" to { FlaskConical },
            "focus" to { Focus },
            "folder" to { Folder },
            "foldergit2" to { FolderGit2 },
            "folderopen" to { FolderOpen },
            "gauge" to { Gauge },
            "gitbranch" to { GitBranch },
            "gitcommithorizontal" to { GitCommitHorizontal },
            "gitcompare" to { GitCompare },
            "gitfork" to { GitFork },
            "gitmerge" to { GitMerge },
            "gitpullrequest" to { GitPullRequest },
            "globe" to { Globe },
            "hammer" to { Hammer },
            "harddrive" to { HardDrive },
            "hash" to { Hash },
            "heart" to { Heart },
            "history" to { History },
            "hourglass" to { Hourglass },
            "house" to { House },
            "inbox" to { Inbox },
            "info" to { Info },
            "keyboard" to { Keyboard },
            "keyround" to { KeyRound },
            "layers" to { Layers },
            "layoutdashboard" to { LayoutDashboard },
            "link" to { Link },
            "list" to { List },
            "listchecks" to { ListChecks },
            "listtodo" to { ListTodo },
            "lock" to { Lock },
            "login" to { LogIn },
            "logout" to { LogOut },
            "mail" to { Mail },
            "maximize2" to { Maximize2 },
            "memorystick" to { MemoryStick },
            "messagesquare" to { MessageSquare },
            "messagesquareplus" to { MessageSquarePlus },
            "messagessquare" to { MessagesSquare },
            "mic" to { Mic },
            "micoff" to { MicOff },
            "minimize2" to { Minimize2 },
            "monitor" to { Monitor },
            "moon" to { Moon },
            "network" to { Network },
            "newspaper" to { Newspaper },
            "notebookpen" to { NotebookPen },
            "package" to { Package },
            "pencil" to { Pencil },
            "pictureinpicture2" to { PictureInPicture2 },
            "pin" to { Pin },
            "pinoff" to { PinOff },
            "pause" to { Pause },
            "play" to { Play },
            "plug" to { Plug },
            "plus" to { Plus },
            "power" to { Power },
            "puzzle" to { Puzzle },
            "qrcode" to { QrCode },
            "refreshccw" to { RefreshCcw },
            "refreshcw" to { RefreshCw },
            "rocket" to { Rocket },
            "rotateccw" to { RotateCcw },
            "rotatecw" to { RotateCw },
            "scanqrcode" to { ScanQrCode },
            "scansearch" to { ScanSearch },
            "scissors" to { Scissors },
            "search" to { Search },
            "send" to { Send },
            "server" to { Server },
            "settings" to { Settings },
            "shieldcheck" to { ShieldCheck },
            "skipforward" to { SkipForward },
            "smartphone" to { Smartphone },
            "sparkle" to { Sparkle },
            "sparkles" to { Sparkles },
            "square" to { Square },
            "squarekanban" to { SquareKanban },
            "squareterminal" to { SquareTerminal },
            "star" to { Star },
            "sun" to { Sun },
            "sunrise" to { Sunrise },
            "tag" to { Tag },
            "terminal" to { Terminal },
            "testtubediagonal" to { TestTubeDiagonal },
            "textselect" to { TextSelect },
            "textwrap" to { TextWrap },
            "timer" to { Timer },
            "trash2" to { Trash2 },
            "trianglealert" to { TriangleAlert },
            "upload" to { Upload },
            "user" to { User },
            "volume2" to { Volume2 },
            "wandsparkles" to { WandSparkles },
            "workflow" to { Workflow },
            "wrench" to { Wrench },
            "x" to { X },
            "zap" to { Zap },
            "trash" to { Trash2 },
            "broom" to { BrushCleaning },
            "clean" to { BrushCleaning },
            "test" to { TestTubeDiagonal },
            "testtube" to { TestTubeDiagonal },
            "ai" to { Sparkles },
            "magic" to { WandSparkles },
            "deploy" to { Rocket },
            "build" to { Hammer },
            "terminalsquare" to { SquareTerminal },
            "checkcircle" to { CircleCheck },
            "alerttriangle" to { TriangleAlert },
            "warning" to { TriangleAlert },
            "xcircle" to { CircleX },
            "morehorizontal" to { Ellipsis },
            "home" to { House },
            "edit" to { Pencil },
            "refresh" to { RefreshCw },
            "playcircle" to { CirclePlay },
            "flask" to { FlaskConical },
            "disk" to { HardDrive },
            "git" to { GitBranch },
            "pr" to { GitPullRequest },
            "time" to { Clock },
            "web" to { Globe },
        )
    }
}

/** Arrows that point the way text reads, so they flip in right-to-left languages. */
private val Mirrored = setOf("ArrowLeft", "ArrowRight", "ChevronLeft", "ChevronRight", "Send", "MessageSquare", "MessagesSquare", "Reply", "Undo2")

private fun lucide(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = name in Mirrored).apply {
        paths.forEach {
            addPath(
                addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
