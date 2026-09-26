package com.procreate.android.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.procreate.android.project.ProjectType
import com.procreate.android.project.ProjectTypeConverters
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "artworks")
data class Artwork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val thumbnailPath: String,
    val filePath: String,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    /** Separates the drawing and Urban Design entry points without inspecting flattened pixels. */
    @ColumnInfo(defaultValue = "'DRAWING'")
    val projectType: ProjectType = ProjectType.DRAWING,
    /** Editable versioned project.json; null identifies a legacy flattened-only artwork. */
    val documentPath: String? = null
)

@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artworks ORDER BY createdAt DESC")
    fun getAllArtworks(): Flow<List<Artwork>>

    @Query("SELECT * FROM artworks WHERE id = :id LIMIT 1")
    suspend fun getArtworkById(id: Long): Artwork?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtwork(artwork: Artwork): Long

    @Update
    suspend fun updateArtwork(artwork: Artwork)

    @Delete
    suspend fun deleteArtwork(artwork: Artwork)
}

@Entity(tableName = "custom_brushes")
data class CustomBrushEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tipImagePath: String,
    /** The brush's paper texture, when the pack it came from shipped one. */
    val grainImagePath: String? = null,
    val size: Float,
    val opacity: Float,
    val hardness: Float,
    val spacing: Float,
    val createdAt: Long
)

@Dao
interface CustomBrushDao {
    @Query("SELECT * FROM custom_brushes ORDER BY createdAt DESC")
    fun getAll(): Flow<List<CustomBrushEntity>>

    @Insert
    suspend fun insert(brush: CustomBrushEntity)

    @Delete
    suspend fun delete(brush: CustomBrushEntity)
}

@Database(entities = [Artwork::class, CustomBrushEntity::class], version = 4, exportSchema = false)
@TypeConverters(ProjectTypeConverters::class)
abstract class ArtworkDatabase : RoomDatabase() {
    abstract fun artworkDao(): ArtworkDao
    abstract fun customBrushDao(): CustomBrushDao

    companion object {
        /** Additive only: existing artwork rows and their flattened PNG paths remain untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE artworks ADD COLUMN projectType TEXT NOT NULL DEFAULT 'DRAWING'"
                )
                db.execSQL("ALTER TABLE artworks ADD COLUMN documentPath TEXT")
            }
        }

        /**
         * Adds the imported grain path. Nullable and additive, so every brush a user already has
         * keeps working untouched and simply reports no texture of its own.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_brushes ADD COLUMN grainImagePath TEXT")
            }
        }

        @Volatile
        private var INSTANCE: ArtworkDatabase? = null

        fun getDatabase(context: Context): ArtworkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ArtworkDatabase::class.java,
                    "artwork_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Version 1 never shipped with editable project documents and has no known
                    // schema contract. Version 2 -> 3 is explicitly migrated above.
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CustomBrushRepository(private val dao: CustomBrushDao) {
    val all: Flow<List<CustomBrushEntity>> = dao.getAll()
    suspend fun insert(brush: CustomBrushEntity) = dao.insert(brush)
    suspend fun delete(brush: CustomBrushEntity) = dao.delete(brush)
}

class ArtworkRepository(private val artworkDao: ArtworkDao) {
    val allArtworks: Flow<List<Artwork>> = artworkDao.getAllArtworks()

    suspend fun getById(id: Long): Artwork? = artworkDao.getArtworkById(id)

    /** Inserts a new row (id == 0) or replaces the existing one, returning its row id. */
    suspend fun insert(artwork: Artwork): Long = artworkDao.insertArtwork(artwork)

    suspend fun update(artwork: Artwork) {
        artworkDao.updateArtwork(artwork)
    }

    suspend fun delete(artwork: Artwork) {
        artworkDao.deleteArtwork(artwork)
    }
}
