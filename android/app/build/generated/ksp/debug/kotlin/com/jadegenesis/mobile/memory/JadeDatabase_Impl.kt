package com.jadegenesis.mobile.memory

import androidx.room3.InvalidationTracker
import androidx.room3.RoomOpenDelegate
import androidx.room3.migration.AutoMigrationSpec
import androidx.room3.migration.Migration
import androidx.room3.util.TableInfo
import androidx.room3.util.TableInfo.Companion.read
import androidx.room3.util.dropFtsSyncTriggers
import androidx.room3.util.performClear
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL", "MemberExtensionConflict"])
internal class JadeDatabase_Impl : JadeDatabase() {
  private val _memoryDao: Lazy<MemoryDao> = lazy {
    MemoryDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1, "3df796d37176e659754e8d25fbc9fd7d", "df4daaca4e831985ab4f0fc946a2adb0") {
      public override suspend fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `memory_events` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `source` TEXT NOT NULL, `confidence` REAL NOT NULL, `originNode` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '3df796d37176e659754e8d25fbc9fd7d')")
      }

      public override suspend fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `memory_events`")
      }

      public override suspend fun onCreate(connection: SQLiteConnection) {
      }

      public override suspend fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override suspend fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override suspend fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override suspend fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsMemoryEvents: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsMemoryEvents.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("type", TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("content", TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("source", TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("confidence", TableInfo.Column("confidence", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("originNode", TableInfo.Column("originNode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMemoryEvents.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysMemoryEvents: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesMemoryEvents: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoMemoryEvents: TableInfo = TableInfo("memory_events", _columnsMemoryEvents, _foreignKeysMemoryEvents, _indicesMemoryEvents)
        val _existingMemoryEvents: TableInfo = read(connection, "memory_events")
        if (!_infoMemoryEvents.equals(_existingMemoryEvents)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |memory_events(com.jadegenesis.mobile.memory.MemoryEntity).
              | Expected:
              |""".trimMargin() + _infoMemoryEvents + """
              |
              | Found:
              |""".trimMargin() + _existingMemoryEvents)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "memory_events")
  }

  public override suspend fun clearAllTables() {
    performClear(this, false, "memory_events")
  }

  protected override fun getRequiredColumnTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _columnTypeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _columnTypeConvertersMap.put(MemoryDao::class, MemoryDao_Impl.getRequiredColumnConverters())
    return _columnTypeConvertersMap
  }

  protected override fun getRequiredDaoReturnTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _daoReturnTypeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _daoReturnTypeConvertersMap.put(MemoryDao::class, MemoryDao_Impl.getRequiredDaoReturnTypeConverters())
    return _daoReturnTypeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun memoryDao(): MemoryDao = _memoryDao.value
}
