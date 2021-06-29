package dao

import javax.inject.{Inject, Singleton}
import models._
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait InputComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class InputTable(tag: Tag) extends Table[ExtractedInputModel](tag, "INPUTS") {
    def boxId = column[String]("BOX_ID")
    def txId = column[String]("TX_ID")
    def headerId = column[String]("HEADER_ID")
    def proofBytes = column[String]("PROOF_BYTES")
    def index = column[Short]("INDEX")
    def mainChain = column[Boolean]("MAIN_CHAIN", O.Default(true))
    def * = (boxId, txId, headerId, proofBytes, index, mainChain) <> (ExtractedInputModel.tupled, ExtractedInputModel.unapply)
  }

  class InputForkTable(tag: Tag) extends Table[ExtractedInputModel](tag, "INPUTS_FORK") {
    def boxId = column[String]("BOX_ID")
    def txId = column[String]("TX_ID")
    def headerId = column[String]("HEADER_ID")
    def proofBytes = column[String]("PROOF_BYTES")
    def index = column[Short]("INDEX")
    def mainChain = column[Boolean]("MAIN_CHAIN", O.Default(true))
    def * = (boxId, txId, headerId, proofBytes, index, mainChain) <> (ExtractedInputModel.tupled, ExtractedInputModel.unapply)
  }
}

@Singleton()
class InputDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends InputComponent
    with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  val inputs = TableQuery[InputTable]
  val inputsFork = TableQuery[InputForkTable]

  /**
   * inserts a input into db
   * @param input input
   */
  def insert(input: ExtractedInputModel): Future[Unit] = db.run(inputs += input).map(_ => ())

  /**
   * create query for insert data
   * @param inputs Seq of input
   */
  def insert(inputs: Seq[ExtractedInputModel]): DBIO[Option[Int]] = this.inputs ++= inputs

  /**
   * exec insert query
   * @param inputs Seq of input
   */
  def save(inputs: Seq[ExtractedInputModel]): Future[Unit] = {
    db.run(insert(inputs)).map(_ => ())
  }


  /**
   * @param boxId box id
   * @return whether this box exists for a specific boxId or not
   */
  def exists(boxId: String): Boolean = {
    val res = db.run(inputs.filter(_.boxId === boxId).exists.result)
    Await.result(res, 5.second)
  }

  /**
   * @param headerId header id
   */
  def migrateForkByHeaderId(headerId: String): Unit = {
    val inputs = Await.result(getByHeaderId(headerId), 5.second)
    db.run(this.inputsFork ++= inputs)
    deleteByHeaderId(headerId)
  }

  /**
   * @param headerId header id
   * @return Input record(s) associated with the header
   */
  def getByHeaderId(headerId: String): Future[Seq[InputTable#TableElementType]] = {
    db.run(inputs.filter(_.headerId === headerId).result)
  }

  /**
   * @param headerId header id
   * @return Number of rows deleted
   */
  def deleteByHeaderId(headerId: String): Future[Int] = {
    db.run(inputs.filter(_.headerId === headerId).delete)
  }
}
