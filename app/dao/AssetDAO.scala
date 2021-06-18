package dao

import javax.inject.{Inject, Singleton}
import models.ExtractedAssetModel
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait AssetComponent { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  class AssetTable(tag: Tag) extends Table[ExtractedAssetModel](tag, "ASSETS") {
    def tokenId = column[String]("TOKEN_ID")
    def boxId = column[String]("BOX_ID")
    def headerId = column[String]("HEADER_ID")
    def index = column[Short]("INDEX")
    def value = column[Long]("VALUE")
    def * = (tokenId, boxId, headerId, index, value) <> (ExtractedAssetModel.tupled, ExtractedAssetModel.unapply)
  }
}

@Singleton()
class AssetDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(implicit executionContext: ExecutionContext)
  extends AssetComponent
    with DbUtils
    with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  val assets = TableQuery[AssetTable]

  /**
   * inserts a Asset of box into db
   * @param asset asset
   */
  def insert(asset: ExtractedAssetModel ): Future[Unit] = db.run(assets += asset).map(_ => ())

  /**
   * inserts assets into db
   * @param assets Seq of ExtractedAssetModel
   */
  def insert(assets: Seq[ExtractedAssetModel]): DBIO[Option[Int]] = this.assets ++= assets


  def save(assets: Seq[ExtractedAssetModel]): Future[Unit] = {
    db.run(insert(assets)).map(_ => ())
  }

  /**
   * @param tokenId token id
   * @return whether this asset exists for a specific TokenId or not
   */
  def exists(tokenId: String): Boolean = {
    val res = db.run(assets.filter(_.tokenId === tokenId).exists.result)
    Await.result(res, 5.second)
  }

}
