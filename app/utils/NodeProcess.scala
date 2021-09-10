package utils

import io.circe.Decoder
import io.circe.parser.parse
import models._
import org.ergoplatform.ErgoBox
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.Header
import scalaj.http.{Http, HttpOptions}
import settings.Configuration

import scala.collection.mutable
import scala.util.{Failure, Success}

object NodeProcess {

  val serverUrl: String = Configuration.serviceConf.serverUrl

  private def getJsonAsString(url: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString
      .body
  }

  def lastHeight: Int = {
    val infoUrl = serverUrl + s"info"
    parse(getJsonAsString(infoUrl)).toTry match {
      case Success(infoJs) =>
        infoJs.hcursor.downField("fullHeight").as[Int].getOrElse(throw new Exception("can't parse fullHeight"))
      case Failure(exception) => throw exception
    }
  }

  def mainChainHeaderIdAtHeight(height: Int): Option[String] = {
    val blockUrl = serverUrl + s"blocks/at/$height"

    val parseResult = parse(getJsonAsString(blockUrl)).toOption.get

    val mainChainId = parseResult.as[Seq[String]].toOption.get.headOption
    mainChainId
  }

  def mainChainHeaderWithHeaderId(headerId: String): Option[Header] = {
    implicit val headerDecoder: Decoder[Header] = Header.jsonDecoder

    val blockHeaderUrl = serverUrl + s"blocks/$headerId/header"
    val parseResultBlockHeader = parse(getJsonAsString(blockHeaderUrl)).toOption.get
    val blockHeader = parseResultBlockHeader.as[Header].toOption
    blockHeader
  }

  def mainChainHeaderAtHeight(height: Int): Option[Header] = {
    val mainChainId = mainChainHeaderIdAtHeight(height)
    if (mainChainId.nonEmpty) mainChainHeaderWithHeaderId(mainChainId.get)
    else None
  }

  def mainChainFullBlockWithHeaderId(headerId: String): Option[ErgoFullBlock] = {
    implicit val txDecoder: Decoder[ErgoFullBlock] = ErgoFullBlock.jsonDecoder

    val txsAsString = getJsonAsString(serverUrl + s"blocks/$headerId")
    val txsAsJson = parse(txsAsString).toOption.get

    val ergoFullBlock = txsAsJson.as[ErgoFullBlock].toOption
    ergoFullBlock
  }

  /**
   *
   * @param box ErgoBox
   * @param rules scanRules
   * @return Int, If the box matches, the first scan ID will be returned, otherwise 0
   */
  def checkBox(box: ErgoBox, rules: Seq[ScanModel]): Int = {
    rules.foreach(scanRule => {
      if (scanRule.trackingRule.filter(box)) return scanRule.scanId
    })
    0
  }

  def processTransactions(
                           headerId: String,
                           extractionRules: ExtractionRulesModel
                         ): ExtractionResultModel = {

    val ergoFullBlock = mainChainFullBlockWithHeaderId(headerId).get

    val createdOutputs = mutable.Buffer[ExtractionOutputResultModel]()
    val extractedInputs = mutable.Buffer[ExtractionInputResultModel]()
    ergoFullBlock.transactions.foreach { tx =>
      tx.inputs.zipWithIndex.map {
        case (input, index) =>
          extractedInputs += ExtractionInputResult(
            input,
            index.toShort,
            ergoFullBlock.header,
            tx
          )
      }
      tx.outputs.foreach { out =>
        val scanId = checkBox(out, extractionRules.scans)
        if (scanId > 0)
          createdOutputs += ExtractionOutputResult(
            out,
            ergoFullBlock.header,
            tx,
            scanId
          )
      }
    }
    ExtractionResultModel(extractedInputs, createdOutputs)
  }
}
