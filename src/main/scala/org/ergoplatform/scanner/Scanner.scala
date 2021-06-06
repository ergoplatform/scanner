package org.ergoplatform.scanner

import scalaj.http.{Http, HttpOptions}
import io.circe.parser._
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox}
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.wallet.scanning.{ContainsAssetPredicate, EqualsScanningPredicate, ScanningPredicate}
import scorex.crypto.hash.Digest32
import scorex.util.ScorexLogging
import scorex.util.encode.Base16
import sigmastate.Values

import scala.annotation.tailrec
import scala.collection.mutable


object Scanner extends App with ScorexLogging {

  type ScanId = Int
  type Identifier = String

  case class Scan(id: ScanId, scanningPredicate: ScanningPredicate)

  case class ExtractionRules(scans: Seq[Scan])

  case class ExtractedOutput(output: ErgoBox, tx: ErgoTransaction)

  case class ExtractedInput(spentBox: ErgoBox, spendingTx: ErgoTransaction)

  case class ExtractionResult(spentTrackedInputs: Seq[ExtractedInput], createdOutputs: Seq[ExtractedOutput])

  // ErgoFund pledge script
  val pledgeScriptBytes = ErgoAddressEncoder(0: Byte)
    .fromString("XUFypmadXVvYmBWtiuwDioN1rtj6nSvqgzgWjx1yFmHAVndPaAEgnUvEvEDSkpgZPRmCYeqxewi8ZKZ4Pamp1M9DAdu8d4PgShGRDV9inwzN6TtDeefyQbFXRmKCSJSyzySrGAt16")
    .get
    .contentBytes

  val pledgeScan = Scan(1, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(pledgeScriptBytes)))

  val campaignTokenId = Base16.decode("08fc8bd24f0eaa011db3342131cb06eb890066ac6d7e6f7fd61fcdd138bd1e2c").get
  val campaignScan = Scan(2, ContainsAssetPredicate(Digest32 @@ campaignTokenId))


  val exampleRules = ExtractionRules(Seq(pledgeScan, campaignScan))

  val serverUrl = "http://213.239.193.208:9053/"

  val bestChainHeaderIds = mutable.Map[Int, Identifier](504910 -> "c89d70cf4d7664676dd53e091617d40aa183ba6e0485cd077b7b74e983c77fd2")

  private def getJsonAsString(url: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(10000))
      .asString
      .body
  }

  def mainchainHeaderIdAtHeight(height: Int): Option[String] = {
    val blockUrl = serverUrl + s"blocks/at/$height"

    val parseResult = parse(getJsonAsString(blockUrl)).toOption.get

    val mainChainId = parseResult.as[Seq[String]].toOption.get.headOption
    mainChainId
  }

  def processTransactions(headerId: String,
                          extractionRules: ExtractionRules): ExtractionResult = {
    implicit val txDecoder = BlockTransactions.jsonDecoder

    val txsAsString = getJsonAsString(s"http://213.239.193.208:9053/blocks/$headerId/transactions")
    val txsAsJson = parse(txsAsString).toOption.get

    val createdOutputs = mutable.Buffer[ExtractedOutput]()

    val transactions = txsAsJson.as[BlockTransactions].toOption.get.transactions
    transactions.foreach { tx =>
      tx.inputs.foreach{input =>
        // todo: check if a tracked output is spent by the input, please note an output from the same block
        //  could be spent
      }
      tx.outputs.foreach { out =>
        extractionRules.scans.foreach { scan =>
          if (scan.scanningPredicate.filter(out)) {
            createdOutputs += ExtractedOutput(out, tx)
          }
        }
      }
    }

    // todo: spent inputs instead of Seq.empty
    ExtractionResult(Seq.empty, createdOutputs)
  }

  var lastHeight = 504910

  @tailrec
  def step(): Unit = {
    val myId = bestChainHeaderIds(lastHeight)
    if (myId == mainchainHeaderIdAtHeight(lastHeight).get) {
      // no fork
      val newHeight = lastHeight + 1
      mainchainHeaderIdAtHeight(newHeight) match {
        case Some(headerId) =>
          log.info(s"Processing block at height: $newHeight, id: $headerId")
          lastHeight = newHeight
          bestChainHeaderIds += newHeight -> headerId
          val extractionResult = processTransactions(headerId, exampleRules)
          log.info("Extracted: " + extractionResult.createdOutputs.length + " outputs")
          step()
        case None =>
          log.info(s"No block found @ height $newHeight")
          Thread.sleep(60 * 1000) // 1 minute
          step()
      }
    } else {
      // go back to detect fork depth and process the fork
    }
  }

  //start scanning cycle from lastHeight
  step()
}
