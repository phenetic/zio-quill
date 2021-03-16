package io.getquill

import com.datastax.driver.core._
import com.typesafe.config.Config
import io.getquill.CassandraZioContext._
import io.getquill.context.StandardContext
import io.getquill.context.cassandra.{ CassandraBaseContext, CqlIdiom }
import io.getquill.context.zio.ZioContext
import io.getquill.util.Messages.fail
import io.getquill.util.ZioConversions._
import io.getquill.util.{ ContextLogger, LoadConfig }
import zio.blocking.{ Blocking, blocking }
import zio.stream.ZStream
import zio.{ Chunk, ChunkBuilder, Has, ZIO, ZLayer, ZManaged }

import scala.jdk.CollectionConverters._
import scala.util.Try

object CassandraZioContext {
  type BlockingSession = Has[ZioCassandraSession] with Blocking
  type CIO[T] = ZIO[BlockingSession, Throwable, T]
  type CStream[T] = ZStream[BlockingSession, Throwable, T]
  object Layers {
    def sessionFromContextConfig(config: CassandraContextConfig) = {
      val managed =
        for {
          env <- ZManaged.environment[Blocking]
          block = env.get[Blocking.Service]
          // Evaluate the configuration inside of 'effect' and then create the session from it
          session <- ZManaged.fromAutoCloseable(
            ZIO.effect(ZioCassandraSession(config.cluster, config.keyspace, config.preparedStatementCacheSize))
          )
        } yield Has(session) ++ Has(block)
      ZLayer.fromManagedMany(managed)
    }

    def sessionFromConfig(config: Config) = sessionFromContextConfig(CassandraContextConfig(config))
    // Call the by-name constructor for the construction to fail inside of the effect if it fails
    def sessionFromPrefix(configPrefix: String) = sessionFromContextConfig(CassandraContextConfig(LoadConfig(configPrefix)))
  }
  implicit class CioExt[T](cio: CIO[T]) {
    def provideSession(session: ZioCassandraSession): ZIO[Blocking, Throwable, T] =
      for {
        block <- ZIO.environment[Blocking]
        result <- cio.provide(Has(session) ++ block)
      } yield result
  }
}

class CassandraZioContext[N <: NamingStrategy](val naming: N)
  extends CassandraBaseContext[N]
  with ZioContext[CqlIdiom, N]
  with StandardContext[CqlIdiom, N] {

  private val logger = ContextLogger(classOf[CassandraZioContext[_]])

  override type Error = Throwable
  override type Environment = Has[ZioCassandraSession] with Blocking

  override type StreamResult[T] = CStream[T]
  override type RunActionResult = Unit
  override type Result[T] = CIO[T]

  override type RunQueryResult[T] = List[T]
  override type RunQuerySingleResult[T] = T
  override type RunBatchActionResult = Unit

  override type PrepareRow = BoundStatement
  override type ResultRow = Row

  protected def page(rs: ResultSet): CIO[Chunk[Row]] = ZIO.succeed { // TODO Is this right? Was Task.defer in monix
    val available = rs.getAvailableWithoutFetching
    val builder = ChunkBuilder.make[Row]()
    builder.sizeHint(available)
    while (rs.getAvailableWithoutFetching() > 0) {
      builder += rs.one()
    }
    builder.result()
  }

  private[getquill] def execute(cql: String, prepare: Prepare, csession: ZioCassandraSession, fetchSize: Option[Int]) =
    blocking {
      prepareRowAndLog(cql, prepare)
        .mapEffect { p =>
          // Set the fetch size of the result set if it exists
          fetchSize match {
            case Some(value) => p.setFetchSize(value)
            case None        =>
          }
          p
        }
        .flatMap(p => {
          csession.session.executeAsync(p).asCio
        })
    }

  val streamBlocker: ZStream[Blocking, Nothing, Any] =
    ZStream.managed(zio.blocking.blockingExecutor.toManaged_.flatMap { executor =>
      ZManaged.lock(executor)
    })

  def streamQuery[T](fetchSize: Option[Int], cql: String, prepare: Prepare = identityPrepare, extractor: Extractor[T] = identityExtractor) = {
    val stream =
      for {
        env <- ZStream.environment[Has[ZioCassandraSession]]
        csession = env.get[ZioCassandraSession]
        rs <- ZStream.fromEffect(execute(cql, prepare, csession, fetchSize))
        row <- ZStream.unfoldChunkM(rs) { rs =>
          // keep taking pages while chunk sizes are non-zero
          val nextPage = page(rs)
          nextPage.flatMap { chunk =>
            if (chunk.length > 0) {
              rs.fetchMoreResults().asCio.map(rs => Some((chunk, rs)))
            } else
              ZIO.succeed(None)
          }
        }
      } yield extractor(row)

    // Run the entire chunking flow on the blocking executor
    streamBlocker *> stream
  }

  def executeQuery[T](cql: String, prepare: Prepare = identityPrepare, extractor: Extractor[T] = identityExtractor): CIO[List[T]] = blocking {
    for {
      env <- ZIO.environment[Has[ZioCassandraSession] with Blocking]
      csession = env.get[ZioCassandraSession]
      rs <- execute(cql, prepare, csession, None)
      rows <- ZIO.effect(rs.all())
    } yield (rows.asScala.map(extractor).toList)
  }

  def executeQuerySingle[T](cql: String, prepare: Prepare = identityPrepare, extractor: Extractor[T] = identityExtractor): CIO[T] = blocking {
    executeQuery(cql, prepare, extractor).map(handleSingleResult(_))
    for {
      env <- ZIO.environment[Has[ZioCassandraSession] with Blocking]
      csession = env.get[ZioCassandraSession]
      rs <- execute(cql, prepare, csession, None)
      rows <- ZIO.effect(rs.all())
      singleRow <- ZIO.effect(handleSingleResult(rows.asScala.map(extractor).toList))
    } yield singleRow
  }

  def executeAction[T](cql: String, prepare: Prepare = identityPrepare): CIO[Unit] = blocking {
    for {
      env <- ZIO.environment[BlockingSession]
      r <- prepareRowAndLog(cql, prepare).provide(env)
      csession = env.get[ZioCassandraSession]
      result <- csession.session.executeAsync(r).asCio
    } yield result
  }

  def executeBatchAction(groups: List[BatchGroup]): CIO[Unit] = blocking {
    for {
      env <- ZIO.environment[Has[ZioCassandraSession] with Blocking]
      result <- {
        val batchGroups =
          groups.flatMap {
            case BatchGroup(cql, prepare) =>
              prepare
                .map(prep => executeAction(cql, prep).provide(env))
          }
        ZIO.collectAll(batchGroups)
      }
    } yield result
  }

  private[getquill] def prepareRowAndLog(cql: String, prepare: Prepare = identityPrepare): CIO[PrepareRow] =
    for {
      env <- ZIO.environment[Has[ZioCassandraSession] with Blocking]
      csession = env.get[ZioCassandraSession]
      boundStatement <- {
        csession.prepareAsync(cql)
          .mapEffect(prepare)
          .map(p => p._2)
      }
    } yield boundStatement

  def probingSession: Option[ZioCassandraSession] = None

  def probe(statement: String): scala.util.Try[_] = {
    probingSession match {
      case Some(csession) =>
        Try(csession.prepare(statement))
      case None =>
        Try(())
    }
  }

  def close(): Unit = fail("Zio Cassandra Session does not need to be closed because it does not keep internal state.")
}