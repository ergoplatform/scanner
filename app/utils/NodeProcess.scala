package utils

import io.circe.Decoder
import io.circe.parser.parse
import models._
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.Header
import scalaj.http.{Http, HttpOptions}
import settings.Configuration

import scala.collection.mutable

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
    val mainChainId = mainChainHeaderIdAtHeight(height).get
    mainChainHeaderWithHeaderId(mainChainId)
  }

  def mainChainFullBlockWithHeaderId(headerId: String): Option[ErgoFullBlock] = {
    implicit val txDecoder: Decoder[ErgoFullBlock] = ErgoFullBlock.jsonDecoder

    val txsAsString = getJsonAsString(serverUrl + s"blocks/$headerId")
    val txsAsJson = parse(txsAsString).toOption.get

    val ergoFullBlock = txsAsJson.as[ErgoFullBlock].toOption
    ergoFullBlock
  }

  def processTransactions(headerId: String,
                          extractionRules: ExtractionRulesModel): ExtractionResultModel = {

    val ergoFullBlock = mainChainFullBlockWithHeaderId(headerId).get

    val createdOutputs = mutable.Buffer[ExtractionOutputResultModel]()
    val createdInputs = mutable.Buffer[ExtractionInputResultModel]()
    ergoFullBlock.transactions.foreach { tx =>
      tx.inputs.zipWithIndex.map {
      case (input, index) =>
          createdInputs += ExtractionInputResult(input, index.toShort, ergoFullBlock.header, tx)
      }
      tx.outputs.foreach { out =>
        val condition = extractionRules.scans.map(_.scanningPredicate.filter(out)).reduce(_ || _)
        if (condition) createdOutputs += ExtractionOutputResult(out, ergoFullBlock.header, tx)
      }
    }
    ExtractionResultModel(createdInputs, createdOutputs)
  }
}
