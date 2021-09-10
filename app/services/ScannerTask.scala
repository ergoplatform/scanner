package services

import java.io.{PrintWriter, StringWriter}

import dao._
import javax.inject.Inject
import play.api.Logger
import utils.NodeProcess

import scala.annotation.tailrec
import models._
import settings.Configuration


class ScannerTask @Inject()( extractedBlockDAO: ExtractedBlockDAO, extractionResultDAO: ExtractionResultDAO,
                             forkedResultDAO: ForkedResultDAO, scanDAO: ScanDAO) {

  private val logger: Logger = Logger(this.getClass)

  @tailrec
  private def step(lastHeight: Int): Unit = {
    if (!Configuration.isActiveScanning) {
      logger.warn("Scanner task stopped, please start scanning process.")
      return
    }
    val localId = extractedBlockDAO.getHeaderIdByHeight(lastHeight)
    if (localId == NodeProcess.mainChainHeaderIdAtHeight(lastHeight).get) {
      // no fork
      val newHeight = lastHeight + 1
      NodeProcess.mainChainHeaderAtHeight(newHeight) match {
        case Some(header) =>
          logger.info(s"Processing block at height: $newHeight, id: ${header.id}")
          val extractionResult = NodeProcess.processTransactions(header.id, scanDAO.selectAll)
          extractionResultDAO.storeOutputsAndRelatedData(extractionResult.createdOutputs, ExtractedBlock(header))
          extractionResultDAO.spendOutputsAndStoreRelatedData(extractionResult.extractedInputs)
          val extractedCount = extractionResult.createdOutputs.length
          logger.info("Extracted: " + extractedCount + " outputs")
          step(newHeight)
        case None =>
          logger.info(s"No block found @ height $newHeight")
      }
    } else {
      var syncHeight = lastHeight - 1
      while (extractedBlockDAO.getHeaderIdByHeight(lastHeight) !=
        NodeProcess.mainChainHeaderIdAtHeight(syncHeight).get) {
        syncHeight -= 1
      }
      for(height <- syncHeight + 1 until lastHeight) {
        forkedResultDAO.migrateBlockByHeight(height)
      }
      step(syncHeight)
    }
  }


  def getStackTraceStr(e: Throwable): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    e.printStackTrace(pw)
    sw.toString
  }

  def start(): Unit = {
    try{
      val lastHeight = extractedBlockDAO.getLastHeight
      step(lastHeight)
    }
    catch {
      case a: Throwable =>
        logger.error(getStackTraceStr(a))
    }
  }

}
