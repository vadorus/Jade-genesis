package com.jadegenesis.mobile.memory

import androidx.room3.EntityInsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.getColumnIndexOrThrow
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Double
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL", "MemberExtensionConflict"])
internal class MemoryDao_Impl(
  __db: RoomDatabase,
) : MemoryDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfMemoryEntity: EntityInsertAdapter<MemoryEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfMemoryEntity = object : EntityInsertAdapter<MemoryEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `memory_events` (`id`,`type`,`content`,`source`,`confidence`,`originNode`,`createdAt`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: MemoryEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.type)
        statement.bindText(3, entity.content)
        statement.bindText(4, entity.source)
        statement.bindDouble(5, entity.confidence)
        statement.bindText(6, entity.originNode)
        statement.bindLong(7, entity.createdAt)
      }
    }
  }

  public override suspend fun insert(event: MemoryEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfMemoryEntity.insert(_connection, event)
  }

  public override suspend fun latest(limit: Int): List<MemoryEntity> {
    val _sql: String = "SELECT * FROM memory_events ORDER BY createdAt DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfConfidence: Int = getColumnIndexOrThrow(_stmt, "confidence")
        val _columnIndexOfOriginNode: Int = getColumnIndexOrThrow(_stmt, "originNode")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<MemoryEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: MemoryEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpConfidence: Double
          _tmpConfidence = _stmt.getDouble(_columnIndexOfConfidence)
          val _tmpOriginNode: String
          _tmpOriginNode = _stmt.getText(_columnIndexOfOriginNode)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = MemoryEntity(_tmpId,_tmpType,_tmpContent,_tmpSource,_tmpConfidence,_tmpOriginNode,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun search(query: String, limit: Int): List<MemoryEntity> {
    val _sql: String = """
        |
        |        SELECT * FROM memory_events
        |        WHERE content LIKE '%' || ? || '%'
        |        ORDER BY createdAt DESC
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfSource: Int = getColumnIndexOrThrow(_stmt, "source")
        val _columnIndexOfConfidence: Int = getColumnIndexOrThrow(_stmt, "confidence")
        val _columnIndexOfOriginNode: Int = getColumnIndexOrThrow(_stmt, "originNode")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _result: MutableList<MemoryEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: MemoryEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpSource: String
          _tmpSource = _stmt.getText(_columnIndexOfSource)
          val _tmpConfidence: Double
          _tmpConfidence = _stmt.getDouble(_columnIndexOfConfidence)
          val _tmpOriginNode: String
          _tmpOriginNode = _stmt.getText(_columnIndexOfOriginNode)
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = MemoryEntity(_tmpId,_tmpType,_tmpContent,_tmpSource,_tmpConfidence,_tmpOriginNode,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM memory_events"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredColumnConverters(): List<KClass<*>> = emptyList()

    public fun getRequiredDaoReturnTypeConverters(): List<KClass<*>> = emptyList()
  }
}
