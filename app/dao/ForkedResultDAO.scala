package dao

import javax.inject.Inject
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.ExecutionContext

class ForkedResultDAO @Inject() (extractedBlockDAO: ExtractedBlockDAO, transactionDAO: TransactionDAO, inputDAO: InputDAO, outputDAO: OutputDAO, assetDAO: AssetDAO, registerDAO: RegisterDAO, protected val dbConfigProvider: DatabaseConfigProvider) (implicit executionContext: ExecutionContext)
        extends DbUtils with HasDatabaseConfigProvider[JdbcProfile] {

    def migrateBlockByHeight(height: Int) {

        val headerId: String = extractedBlockDAO.getHeaderIdByHeight(height)
        val boxIds: Seq[String] = outputDAO.getBoxIdsByHeaderId(headerId)

        extractedBlockDAO.migrateForkByHeaderId(headerId)
        transactionDAO.migrateForkByHeaderId(headerId)
        outputDAO.migrateForkByHeaderId(headerId)
        assetDAO.migrateForkByHeaderId(headerId)
        boxIds.foreach(id => registerDAO.migrateForkByBoxId(id))
        inputDAO.migrateForkByHeaderId(headerId)
    }
}
