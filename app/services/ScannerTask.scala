package services

import java.io.{PrintWriter, StringWriter}

import dao._
import javax.inject.Inject
import play.api.Logger
import utils.NodeProcess

import scala.annotation.tailrec
import models._
import settings.Rules


class ScannerTask @Inject()(extractedBlockDAO: ExtractedBlockDAO, extractionResultDAO: ExtractionResultDAO) {

  private val logger: Logger = Logger(this.getClass)

  @tailrec
  private def step(lastHeight: Int): Unit = {
    val localId = extractedBlockDAO.getHeaderIdByHeight(lastHeight)
    if (localId == NodeProcess.mainChainHeaderIdAtHeight(lastHeight).get) {
      // no fork
      val newHeight = lastHeight + 1
      NodeProcess.mainChainHeaderAtHeight(newHeight) match {
        case Some(header) =>
          logger.info(s"Processing block at height: $newHeight, id: ${header.id}")
          val extractionResult = NodeProcess.processTransactions(header.id, Rules.exampleRules)
          extractionResultDAO.save(extractionResult, ExtractedBlock(header))
          val extractedCount = extractionResult.createdOutputs.length
          logger.info("Extracted: " + extractedCount + " outputs")
          if (extractedCount > 0) {
            logger.info("New Ergofund outputs found: " + extractionResult.createdOutputs)
          }
          step(newHeight)
        case None =>
          logger.info(s"No block found @ height $newHeight")
      }
    } else {
      // TODO: go back to detect fork depth and process the fork
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
