package com.cluda.tradersbit.signals

import com.cluda.tradersbit.signals.database.SignalTable
import com.typesafe.config.ConfigFactory
import slick.driver.PostgresDriver.api._
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.{JdbcBackend, StaticQuery}
import slick.lifted.TableQuery

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object DatabaseUtilBlockingForTests {

  def dropTableIfItExists(tableName: String, executionContext: ExecutionContext): Unit = {
    val database: JdbcBackend.DatabaseDef = Database.forConfig("database", ConfigFactory.load())
    implicit val session = database.createSession()
    implicit val ec = executionContext

    try {
      (StaticQuery.u + "DROP TABLE " + tableName).execute
    } catch {
      case _: Throwable =>
        // ignore
    }
  }

  def createDummySignalsTable(streamID: String, executionContext: ExecutionContext): Unit = {
    val database: JdbcBackend.DatabaseDef = Database.forConfig("database", ConfigFactory.load())
    implicit val session = database.createSession()
    implicit val ec = executionContext

    val signalsTable = TableQuery[SignalTable]((tag: Tag) => new SignalTable(tag, streamID))
    Await.result(database.run(signalsTable.schema.create), 5.seconds)
    Await.result(database.run(
      signalsTable ++= TestData.signalSeq.reverse
    ) map(x => println("DatabaseUtilBlockingForTests: database created")), 5.seconds)
  }

}