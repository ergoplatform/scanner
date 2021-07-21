package dao

import play.api.Logger

import javax.inject.Inject
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class ForkedResultDAO @Inject() (extractedBlockDAO: ExtractedBlockDAO, transactionDAO: TransactionDAO, inputDAO: InputDAO, outputDAO: OutputDAO, assetDAO: AssetDAO, registerDAO: RegisterDAO, protected val dbConfigProvider: DatabaseConfigProvider) (implicit executionContext: ExecutionContext)
        extends DbUtils with HasDatabaseConfigProvider[JdbcProfile] {

    /**
     * Migrate blocks from a detected fork to alternate tables.
     * @param height height of block to be migrated
     * */
    def migrateBlockByHeight(height: Int): Unit = {

        //TODO below lookups are blocking using Await
        val headerId: String = extractedBlockDAO.getHeaderIdByHeight(height)
        val boxIds: Seq[String] = outputDAO.getBoxIdsByHeaderId(headerId)

        val action = for {
            _ <- extractedBlockDAO.migrateForkByHeaderId(headerId)
            _ <- transactionDAO.migrateForkByHeaderId(headerId)
            _ <- outputDAO.migrateForkByHeaderId(headerId)
            _ <- assetDAO.migrateForkByHeaderId(headerId)
            _ <- registerDAO.migrateForkByBoxIds(boxIds)
            _ <- inputDAO.migrateForkByHeaderId(headerId)
        } yield {

        }
        val response = execTransact(action)
        Await.result(response, Duration.Inf)
    }
}
