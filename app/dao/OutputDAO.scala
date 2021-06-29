package dao

import javax.inject.{Inject, Singleton}
import models.ExtractedOutputModel
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait OutputComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class OutputTable(tag: Tag) extends Table[ExtractedOutputModel](tag, "OUTPUTS") {
    def boxId = column[String]("BOX_ID")
    def txId = column[String]("TX_ID")
    def headerId = column[String]("HEADER_ID")
    def value = column[Long]("VALUE")
    def creationHeight = column[Int]("CREATION_HEIGHT")
    def index = column[Short]("INDEX")
    def ergoTree = column[String]("ERGO_TREE")
    def timestamp = column[Long]("TIMESTAMP")
    def mainChain = column[Boolean]("MAIN_CHAIN", O.Default(true))
    def * = (boxId, txId, headerId, value, creationHeight, index, ergoTree, timestamp, mainChain) <> (ExtractedOutputModel.tupled, ExtractedOutputModel.unapply)
  }

  class OutputForkTable(tag: Tag) extends Table[ExtractedOutputModel](tag, "OUTPUTS_FORK") {
    def boxId = column[String]("BOX_ID")
    def txId = column[String]("TX_ID")
    def headerId = column[String]("HEADER_ID")
    def value = column[Long]("VALUE")
    def creationHeight = column[Int]("CREATION_HEIGHT")
    def index = column[Short]("INDEX")
    def ergoTree = column[String]("ERGO_TREE")
    def timestamp = column[Long]("TIMESTAMP")
    def mainChain = column[Boolean]("MAIN_CHAIN", O.Default(true))
    def * = (boxId, txId, headerId, value, creationHeight, index, ergoTree, timestamp, mainChain) <> (ExtractedOutputModel.tupled, ExtractedOutputModel.unapply)
  }
}

@Singleton()
class OutputDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends OutputComponent
    with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  val outputs = TableQuery[OutputTable]
  val outputsFork = TableQuery[OutputForkTable]

  /**
   * inserts a output into db
   * @param output output
   */
  def insert(output: ExtractedOutputModel ): Future[Unit] = db.run(outputs += output).map(_ => ())

  /**
   * create query for insert data
   * @param outputs Seq of output
   */
  def insert(outputs: Seq[ExtractedOutputModel]): DBIO[Option[Int]] = this.outputs ++= outputs

  /**
   * exec insert query
   * @param outputs Seq of input
   */
  def save(outputs: Seq[ExtractedOutputModel]): Future[Unit] = {
    db.run(insert(outputs)).map(_ => ())
  }

  def doSpentIfExist(boxId: String, txId: String): Future[Unit] = {
    db.run(outputs.filter(_.boxId === boxId).map(_.txId).update(txId)).map(_ => ())
  }

  /**
   * @param boxId box id
   * @return whether this box exists for a specific boxId or not
   */
  def exists(boxId: String): Boolean = {
    val res = db.run(outputs.filter(_.boxId === boxId).exists.result)
    Await.result(res, 5.second)
  }

  /**
   * @param headerId header id
   */
  def migrateForkByHeaderId(headerId: String): Unit = {
    val outputs = Await.result(getByHeaderId(headerId), 5.second)
    db.run(this.outputsFork ++= outputs)
    deleteByHeaderId(headerId)
  }

  /**
   * @param headerId header id
   * @return Output record(s) associated with the header
   */
  def getByHeaderId(headerId: String): Future[Seq[OutputTable#TableElementType]] = {
    db.run(outputs.filter(_.headerId === headerId).result)
  }

  /**
   * @param headerId header id
   * @return Number of rows deleted
   */
  def deleteByHeaderId(headerId: String): Future[Int] = {
    db.run(outputs.filter(_.headerId === headerId).delete)
  }

  /**
   * @param headerId header id
   * @return Box id(s) associated with the header
   */
  def getBoxIdsByHeaderId(headerId: String): Seq[String] = {
    val res = db.run(outputs.filter(_.headerId === headerId).map(_.boxId).result)
    Await.result(res, 5.second)
  }
}
