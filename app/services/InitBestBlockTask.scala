package services

import dao.ExtractedBlockDAO
import javax.inject.Inject
import org.ergoplatform.modifiers.history.Header
import play.api.Logger
import settings.Configuration
import utils.NodeProcess
import models.ExtractedBlock

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class InitBestBlockTask @Inject()(extractedBlockDAO: ExtractedBlockDAO) (implicit executionContext: ExecutionContext) {

  private val logger: Logger = Logger(this.getClass)

  /**
  * Add best block into db if don't exist data in table
   */
  def store_block(): Unit = {
    val header: Header = NodeProcess.mainChainHeaderWithHeaderId(Configuration.serviceConf.bestBlockId).get
    val queryResult = extractedBlockDAO.count().flatMap { count =>
      logger.info("Initializing data")
      if (count == 0) {
        logger.info("No block found. Need to insert best block.")
        val rows = Seq(ExtractedBlock(header))
        extractedBlockDAO.save(rows)
      } else {
        logger.info("Already found a block data. No need to insert any blocks")
        Future.successful(Option(0))
      }
    }
    Await.result(queryResult, Duration.Inf)
    logger.info("Stop task store best block.")
  }

}
