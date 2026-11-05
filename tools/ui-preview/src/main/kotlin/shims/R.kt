@file:Suppress("unused")

package com.makefunstuff.herdr

/** The app's resources the shared code names, as the preview has them (fonts from app/src/main/res/font). */
object R {
    object font {
        private val files = listOf("geist_regular", "geist_medium", "geist_semibold", "geist_mono_regular", "geist_mono_medium", "geist_mono_semibold")
        const val geist_regular = 0
        const val geist_medium = 1
        const val geist_semibold = 2
        const val geist_mono_regular = 3
        const val geist_mono_medium = 4
        const val geist_mono_semibold = 5

        fun file(id: Int): String = files[id] + ".ttf"
    }
}
