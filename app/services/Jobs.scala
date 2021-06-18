package services

import akka.actor.{Actor, ActorLogging}
import play.api.Logger

import scala.util.Try

object JobsInfo {
  val InitBestBlockInDb = "store best block in db"
  val blockChainScan = "block scanned"
}

class Jobs(scanner: ScannerTask, initBestBlock: InitBestBlockTask) extends Actor with ActorLogging {
  private val logger: Logger = Logger(this.getClass)

  /**
   * periodically start scanner, task.
   */
  def receive: PartialFunction[Any, Unit] = {
    case JobsInfo.InitBestBlockInDb =>
      logger.info("Start job Store Best Block task.")
      Try(initBestBlock.store_block())
    case JobsInfo.blockChainScan =>
      logger.info("Start job scanner task.")
      Try(scanner.start())
  }
}

