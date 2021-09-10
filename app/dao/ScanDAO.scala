package dao

import javax.inject.{Inject, Singleton}
import models._
import org.ergoplatform.nodeView.wallet.scanning.{ScanningPredicate, ScanningPredicateSerializer}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait ScanComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  implicit val ScanningPredicateColumnType = MappedColumnType.base[ScanningPredicate, Array[Byte]](
     s => ScanningPredicateSerializer.toBytes(s),
     i => ScanningPredicateSerializer.parseBytes(i)
  )

  class ScanTable(tag: Tag) extends Table[ScanModel](tag, "SCANS") {
    def scanId = column[Int]("SCAN_ID", O.PrimaryKey, O.AutoInc)
    def scanName = column[String]("SCAN_NAME")
    def scanningPredicate = column[ScanningPredicate]("SCANNING_PREDICATE")
    def * = (scanId, scanName, scanningPredicate) <> (ScanModel.tupled, ScanModel.unapply)
  }
}

@Singleton()
class ScanDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends ScanComponent
    with HasDatabaseConfigProvider[JdbcProfile] with DbUtils{

  import profile.api._

  val scans = TableQuery[ScanTable]

  /**
   * inserts a scan into db
   * @param scan Scan
   */
  def insert(scan: ScanModel): Unit = execAwait(DBIO.seq(scans += scan).map(_ => ()))
  val insertQuery = scans returning scans.map(_.scanId) into ((item, id) => item.copy(scanId = id))

  def create(scan: ScanModel) : ScanModel = {
    execAwait(insertQuery += scan)
  }

  /**
   * @param scanId Types.ScanId
   * @return whether this scan exists for a specific scanId or not
   */
  def exists(scanId: Types.ScanId): DBIO[Boolean] = {
    scans.filter(_.scanId === scanId).exists.result
  }


  /**
   * @param scanId Types.ScanId
   * @return Number of rows deleted
   */
  def deleteById(scanId: Types.ScanId): Int = {
    execAwait(scans.filter(_.scanId === scanId).delete)
  }

  /**
   * @return Int number of scanning rules
   */
  def count(): Int = {
    execAwait(scans.length.result)
  }

  /**
   * @return All scan record(s)
   */
  def selectAll: ExtractionRulesModel = {
    ExtractionRulesModel(execAwait(scans.result))
  }

}
