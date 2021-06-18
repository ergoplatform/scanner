package services

import akka.actor.ActorSystem
import dao.ExtractedBlockDAO

import settings.{Configuration, ScannerConf}

import javax.inject.Inject
import models.Types.Identifier
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox}
import org.ergoplatform.nodeView.wallet.scanning.{ContainsAssetPredicate, EqualsScanningPredicate}
import play.api.Logger
import scorex.crypto.hash.Digest32
import scorex.util.encode.Base16
import sigmastate.Values
import utils.NodeProcess
import scala.annotation.tailrec
import scala.collection.mutable

class Scanner @Inject()(extractedBlockDAO: ExtractedBlockDAO) {

  private val log: Logger = Logger(this.getClass)

  import models._

  lazy val config: ScannerConf = Configuration.serviceConf

  // ErgoFund pledge script, we find pledges by finding boxes (outputs) protected by the script
  val pledgeScriptBytes = ErgoAddressEncoder(0: Byte)
    .fromString("XUFypmadXVvYmBWtiuwDioN1rtj6nSvqgzgWjx1yFmHAVndPaAEgnUvEvEDSkpgZPRmCYeqxewi8ZKZ4Pamp1M9DAdu8d4PgShGRDV9inwzN6TtDeefyQbFXRmKCSJSyzySrGAt16")
    .get
    .contentBytes

  val pledgeScan = Scan(1, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(pledgeScriptBytes)))

  // We scan for ErgoFund campaign data, stored in outputs with the ErgoFund token
  val campaignTokenId = Base16.decode("08fc8bd24f0eaa011db3342131cb06eb890066ac6d7e6f7fd61fcdd138bd1e2c").get
  val campaignScan = Scan(2, ContainsAssetPredicate(Digest32 @@ campaignTokenId))

  val exampleRules = ExtractionRules(Seq(pledgeScan, campaignScan))

  var lastHeight: Int = config.startFromHeight // we start from some recent block

  // TODO: Add this parameter to migration and config
  val bestChainHeaderIds = mutable.Map[Int, Identifier](504910 -> "c89d70cf4d7664676dd53e091617d40aa183ba6e0485cd077b7b74e983c77fd2")

  @tailrec
  private def step(): Unit = {
    // TODO: Remove bestChainHeaderIds
    val localId = extractedBlockDAO.getHeaderIdByHeight(lastHeight)
    if (localId == NodeProcess.mainChainHeaderIdAtHeight(lastHeight).get) {
      // no fork
      val newHeight = lastHeight + 1
      NodeProcess.mainChainHeaderAtHeight(newHeight) match {
        case Some(header) =>
          log.info(s"Processing block at height: $newHeight, id: ${header.id}")
          lastHeight = newHeight
          extractedBlockDAO.insert(ExtractedBlock(header.id, header.parentId, header.height, header.timestamp))
          val extractionResult = NodeProcess.processTransactions(header.id, exampleRules)
          val extractedCount = extractionResult.createdOutputs.length
          log.info("Extracted: " + extractedCount + " outputs")
          if (extractedCount > 0) {
            log.info("New Ergofund outputs found: " + extractionResult.createdOutputs)
          }
          step()
        case None =>
          log.info(s"No block found @ height $newHeight")
      }
    } else {
      // go back to detect fork depth and process the fork
    }
  }

  //start scanning cycle from lastHeight
  step()

}
