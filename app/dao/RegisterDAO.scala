package dao

import javax.inject.{Inject, Singleton}
import models.ExtractedRegisterModel
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait RegisterComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class RegisterTable(tag: Tag) extends Table[ExtractedRegisterModel](tag, "BOX_REGISTERS") {
    def id = column[String]("ID")
    def boxId = column[String]("BOX_ID")
    def value = column[String]("VALUE")
    def * = (id, boxId, value) <> (ExtractedRegisterModel.tupled, ExtractedRegisterModel.unapply)
  }

  class RegisterForkTable(tag: Tag) extends Table[ExtractedRegisterModel](tag, "BOX_REGISTERS_FORK") {
    def id = column[String]("ID")
    def boxId = column[String]("BOX_ID")
    def value = column[String]("VALUE")
    def * = (id, boxId, value) <> (ExtractedRegisterModel.tupled, ExtractedRegisterModel.unapply)
  }
}

@Singleton()
class RegisterDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends RegisterComponent
    with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  val registers = TableQuery[RegisterTable]
  val registersFork = TableQuery[RegisterForkTable]
  
  /**
   * inserts a register of box into db
   * @param register register
   */
  def insert(register: ExtractedRegisterModel ): Future[Unit] = db.run(registers += register).map(_ => ())

  /**
   * create query for insert data
   * @param registers Seq of register
   */
  def insert(registers: Seq[ExtractedRegisterModel]): DBIO[Option[Int]] = this.registers ++= registers

  /**
   * exec insert query
   * @param registers Seq of register
   */
  def save(registers: Seq[ExtractedRegisterModel]): Future[Unit] = {
    db.run(insert(registers)).map(_ => ())
  }

  /**
   * @param tokenId token id
   * @return whether this token exists for a specific tokenId or not
   */
  def exists(tokenId: String): Boolean = {
    val res = db.run(registers.filter(_.id === tokenId).exists.result)
    Await.result(res, 5.second)
  }

  /**
   * @param boxId box id
   */
  def migrateForkByBoxId(boxId: String): Unit = {
    val inputs = Await.result(getByBoxId(boxId), 5.second)
    db.run(this.registersFork ++= inputs)
    deleteByBoxId(boxId)
  }

  /**
   * @param boxId box id
   * @return Input record(s) associated with the header
   */
  def getByBoxId(boxId: String): Future[Seq[RegisterTable#TableElementType]] = {
    db.run(registers.filter(_.boxId === boxId).result)
  }

  /**
   * @param boxId box id
   * @return Number of rows deleted
   */
  def deleteByBoxId(boxId: String): Future[Int] = {
    db.run(registers.filter(_.boxId === boxId).delete)
  }
}
