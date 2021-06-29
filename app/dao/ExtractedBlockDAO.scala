package dao

import javax.inject.{Inject, Singleton}
import models.ExtractedBlockModel
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait ExtractedBlockComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class ExtractedBlockTable(tag: Tag) extends Table[ExtractedBlockModel](tag, "HEADERS") {
    def id = column[String]("ID")
    def parentId = column[String]("PARENT_ID")
    def height = column[Int]("HEIGHT")
    def timestamp = column[Long]("TIMESTAMP")
    def mainChain = column[Boolean]("MAIN_CHAIN")
    def * = (id, parentId, height, timestamp, mainChain) <> (ExtractedBlockModel.tupled, ExtractedBlockModel.unapply)
  }

  class ExtractedBlockForkTable(tag: Tag) extends Table[ExtractedBlockModel](tag, "HEADERS_FORK") {
    def id = column[String]("ID")
    def parentId = column[String]("PARENT_ID")
    def height = column[Int]("HEIGHT")
    def timestamp = column[Long]("TIMESTAMP")
    def mainChain = column[Boolean]("MAIN_CHAIN")
    def * = (id, parentId, height, timestamp, mainChain) <> (ExtractedBlockModel.tupled, ExtractedBlockModel.unapply)
  }
}

@Singleton()
class ExtractedBlockDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
    extends DbUtils
      with ExtractedBlockComponent
      with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._


  val extractedBlocks = TableQuery[ExtractedBlockTable]
  val extractedBlocksFork = TableQuery[ExtractedBlockForkTable]

  /**
   * insert Seq[extractedBlock] into db
   * @param extractedBlocks blocks
   */
  def insert(extractedBlocks: Seq[ExtractedBlockModel]): DBIO[Option[Int]]= {
    this.extractedBlocks ++= extractedBlocks
  }


  def save(extractedBlocks: Seq[ExtractedBlockModel]): Future[Unit] = {
    db.run(insert(extractedBlocks)).map(_ => ())
  }

  def count(): Future[Int] = {
    db.run(extractedBlocks.length.result)
  }

  /**
   * whether headerId exists in extractedBlock
   * @param headerId block id
   * @return boolean result
   */
  def exists(headerId: String): Boolean = {
    val res = db.run(extractedBlocks.filter(_.id === headerId).exists.result)
    Await.result(res, 5.second)
  }

  /**
   * @param height block Height
   * @return Header Id associated with the height
   */
  def getHeaderIdByHeight(height: Int): String = {
    val res = db.run(extractedBlocks.filter(_.height === height).map(_.id).result.headOption.asTry)
    val out = Await.result(res, 5.second)
    notFoundHandle(out)
  }

  /**
   * @return Last Height
   */
  def getLastHeight: Int = {
    val res = db.run(extractedBlocks.sortBy(_.height.desc.nullsLast).map(_.height).result.headOption.asTry)
    val out = Await.result(res, 5.second)
    notFoundHandle(out)
  }

  /**
   * deletes all extractedBlocks from db
   */
  def deleteAll(): Unit = {
    val res = db.run(extractedBlocks.delete)
    Await.result(res, 5.second)
  }

  /**
   * @param headerId header id
   */
  def migrateForkByHeaderId(headerId: String): Unit = {
    val header = Await.result(getByHeaderId(headerId), 5.second)
    db.run(this.extractedBlocksFork ++= header)
    deleteByHeaderId(headerId)
  }

  /**
   * @param headerId header id
   * @return Header record(s) associated with the id
   */
  def getByHeaderId(headerId: String): Future[Seq[ExtractedBlockTable#TableElementType]] = {
    db.run(extractedBlocks.filter(_.id === headerId).result)
  }

  /**
   * @param headerId header id
   * @return Number of rows deleted
   */
  def deleteByHeaderId(headerId: String): Future[Int] = {
    db.run(extractedBlocks.filter(_.id === headerId).delete)
  }
}
