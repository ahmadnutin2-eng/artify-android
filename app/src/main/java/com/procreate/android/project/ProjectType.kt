package com.procreate.android.project

import androidx.room.TypeConverter

/**
 * The two intentionally separate editing experiences supported by an Artify project.
 *
 * Enum names are persisted, so existing names must not be changed. New project types may be
 * appended without changing the database schema.
 */
enum class ProjectType {
    DRAWING,
    URBAN_DESIGN;

    companion object {
        /**
         * Reads both the current values and the short-lived names used by early development
         * builds. Unknown database values safely open as a regular drawing.
         */
        fun fromStorage(value: String?): ProjectType = when (value?.uppercase()) {
            URBAN_DESIGN.name, "URBAN", "CAD" -> URBAN_DESIGN
            else -> DRAWING
        }
    }
}

/** Keeps Room's representation explicit instead of relying on its implicit enum converter. */
class ProjectTypeConverters {
    @TypeConverter
    fun projectTypeToString(value: ProjectType): String = value.name

    @TypeConverter
    fun stringToProjectType(value: String): ProjectType = ProjectType.fromStorage(value)
}
