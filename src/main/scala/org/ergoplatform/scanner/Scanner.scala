package org.ergoplatform.scanner

import com.google.common.primitives.UnsignedInts
import scalaj.http.{Http, HttpOptions}
import io.circe.parser._
import org.bouncycastle.util.BigIntegers
import org.ergoplatform.ErgoBox.{R4, R5, R6, R7, R8}
import org.ergoplatform.{DataInput, ErgoAddressEncoder, ErgoBox, ErgoBoxCandidate, ErgoScriptPredef, P2PKAddress, UnsignedInput}
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.state.ErgoStateContext
import org.ergoplatform.nodeView.wallet.scanning.{ContainsAssetPredicate, EqualsScanningPredicate, ScanningPredicate}
import org.ergoplatform.settings.{ErgoSettings, LaunchParameters}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import org.ergoplatform.wallet.interpreter.{ErgoProvingInterpreter, TransactionHintsBag}
import org.ergoplatform.wallet.secrets.{ExtendedSecretKey, PrimitiveSecretKey}
import scorex.crypto.hash.Digest32
import scorex.util.ScorexLogging
import scorex.util.encode.Base16
import sigmastate.Values
import sigmastate.Values.{ByteArrayConstant, IntConstant, LongConstant, SigmaPropConstant, SigmaPropValue}
import sigmastate.basics.DLogProtocol.DLogProverInput
import sigmastate.eval.RuntimeIRContext
import sigmastate.interpreter.ContextExtension
import special.sigma.SigmaProp
import swaydb.Glass

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try


object BasicDataStructures {
  type ScanId = Int
  type Identifier = String

  case class Scan(id: ScanId, scanningPredicate: ScanningPredicate)

  case class ExtractionRules(scans: Seq[Scan])

  case class ExtractedOutput(scan: Scan, output: ErgoBox, tx: ErgoTransaction)

  case class ExtractedInput(spentBox: ErgoBox, spendingTx: ErgoTransaction)

  case class ExtractionResult(spentTrackedInputs: Seq[ExtractedInput], createdOutputs: Seq[ExtractedOutput])

}

object DatabaseStructures {

  import swaydb._
  import swaydb.serializers.Default._
  import scala.concurrent.duration._

  //Create an in-memory map instance
  val pledgeBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val campaignBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val paymentBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val systemBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val nftIndex = memory.Map[String, String, Nothing, Glass]()

}

object Scanner extends App with ScorexLogging {

  import BasicDataStructures._
  import DatabaseStructures._

  def bytesToString(bs: Array[Byte]): String = Base16.encode(bs)

  val controlBoxNftIdString = "43dcfba80c77008cfa31632d989e9193c092fdf9381d55dcc9181cec78cab700"
  val controlBoxNftId = Base16.decode(controlBoxNftIdString).get
  val controlBoxScanId = 1
  val controlBoxScan = Scan(controlBoxScanId, ContainsAssetPredicate(Digest32 @@ controlBoxNftId))

  val tokenSaleNftId = Base16.decode("28faaad9cc3090ee03686589b08c3965be271c86b650a88f356e57da568862fc").get
  val tokenSaleScanId = 2
  val tokenSaleScan = Scan(tokenSaleScanId, ContainsAssetPredicate(Digest32 @@ tokenSaleNftId))

  // ErgoFund pledge script, we find pledges by finding boxes (outputs) protected by the script
  val pledgeScriptBytes = ErgoAddressEncoder(0: Byte)
    .fromString("XUFypmadXVvYmBWtiuwDioN1rtj6nSvqgzgWjx1yFmHAVndPaAEgnUvEvEDSkpgZPRmCYeqxewi8ZKZ4Pamp1M9DAdu8d4PgShGRDV9inwzN6TtDeefyQbFXRmKCSJSyzySrGAt16")
    .get
    .contentBytes

  val pledgeScanId = 3
  val pledgeScan = Scan(pledgeScanId, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(pledgeScriptBytes)))

  // We scan for ErgoFund campaign data, stored in outputs with the ErgoFund token
  val campaignTokenId = Base16.decode("08fc8bd24f0eaa011db3342131cb06eb890066ac6d7e6f7fd61fcdd138bd1e2c").get
  val campaignScanId = 4
  val campaignScan = Scan(campaignScanId, ContainsAssetPredicate(Digest32 @@ campaignTokenId))

  //
  val paymentTree = ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
    .fromString("9f2UU1Jo52WDMCCCu9GYdiWSmb5V7jzP8FdRkVqqvCHyD72gTTR")
    .get
    .script

  val paymentPkBytes = paymentTree.bytes

  val paymentScanId = 5
  val paymentScan = Scan(paymentScanId, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(paymentPkBytes)))

  val exampleRules = ExtractionRules(Seq(controlBoxScan, tokenSaleScan, pledgeScan, campaignScan, paymentScan))

  val serverUrl = "http://213.239.193.208:9053/"

  val bestChainHeaderIds = mutable.Map[Int, Identifier](509323 -> "9744efa8817753229972967dd9feeb6dafd1a625f5ad3fe38fe998ff6f122219")

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
      tx.inputs.foreach { input =>
        // todo: check if a tracked output is spent by the input, please note an output from the same block
        //  could be spent
      }
      tx.outputs.foreach { out =>
        extractionRules.scans.foreach { scan =>
          if (scan.scanningPredicate.filter(out)) {
            createdOutputs += ExtractedOutput(scan, out, tx)
          }
        }
      }
    }

    // todo: spent inputs instead of Seq.empty
    ExtractionResult(Seq.empty, createdOutputs)
  }

  def ergoFundProcess(extractionResult: BasicDataStructures.ExtractionResult): Unit = {
    extractionResult.createdOutputs.foreach { out =>
      val boxId = bytesToString(out.output.id)
      out.scan.id match {
        case i: Int if i == controlBoxScanId =>
          nftIndex.put(bytesToString(controlBoxNftId), boxId)
          systemBoxes.put(boxId, out.output.bytes)
          log.info("Registered control box: " + boxId)
        case i: Int if i == tokenSaleScanId =>
          nftIndex.put(bytesToString(tokenSaleNftId), boxId)
          systemBoxes.put(boxId, out.output.bytes)
          log.info("Registered tokensale box: " + boxId)
        case i: Int if i == pledgeScanId =>

        case i: Int if i == campaignScanId =>

        case i: Int if i == paymentScanId =>
          log.info("Registered payment box: " + boxId)
          paymentBoxes.put(boxId, out.output.bytes)
      }
    }
  }

  def registerCampaign(currentHeight: Int,
                       campaignId: Int,
                       campaignDesc: String,
                       campaignScript: SigmaPropConstant,
                       deadline: Int,
                       minToRaise: Long): Try[Unit] = Try {
    val fee = 10000000 //0.01 ERG

    implicit val me = ErgoAddressEncoder.apply(ErgoAddressEncoder.MainnetNetworkPrefix)
    val keyBytes = Base16.decode("128f6307c0fb8094e733633d2ee4c3bbd06228411aa86cc5566e9d543b41623b").get
    val key = DLogProverInput(BigIntegers.fromUnsignedByteArray(keyBytes))

    val controlBoxId = nftIndex.get(controlBoxNftIdString).get
    val controlBoxBytes = systemBoxes.get(controlBoxId).get
    val controlBox = ErgoBoxSerializer.parseBytes(controlBoxBytes)

    val price = controlBox.additionalRegisters(R4).asInstanceOf[LongConstant].value.asInstanceOf[Long]
    log.info("price: " + price)

    val inputBoxes = mutable.Buffer[ErgoBox]()
    var collectedAmount = 0L
    val inputsCount = paymentBoxes.values.takeWhile { boxBytes =>
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      inputBoxes += box
      collectedAmount += box.value
      collectedAmount < price + fee
    }.count

    log.info("inputs: " + inputsCount)
    log.info("Collected payments: " + collectedAmount)
    log.info("price: " + price)
    log.info("price + fee: " + (price + fee))

    if (price + fee > collectedAmount) {
      throw new Exception("price + fee > collectedAmount")
    }

    val inputs = inputBoxes.map(b => new UnsignedInput(b.id, ContextExtension.empty)).toIndexedSeq
    val dataInputs = IndexedSeq(DataInput(controlBox.id))

    val regs = Map(
      R4 -> IntConstant(campaignId),
      R5 -> ByteArrayConstant(campaignDesc.getBytes("UTF-8")),
      R6 -> campaignScript,
      R7 -> IntConstant(deadline),
      R8 -> LongConstant(minToRaise)
    )

    val campaignAddress = me.fromString("4MQyMKvMbnCJG3aJ").get.script
    val campaignPrice = 1000000000L
    val campaignOutput = new ErgoBoxCandidate(campaignPrice, campaignAddress, currentHeight,
                                                additionalRegisters = regs)

    val feeAmount = 2000000L // 0.002 ERG
    val feeOutput = new ErgoBoxCandidate(feeAmount, ErgoScriptPredef.feeProposition(), currentHeight)

    val changeAmount = collectedAmount - campaignPrice - feeAmount
    val changeOutput = new ErgoBoxCandidate(changeAmount, paymentTree, currentHeight)

    val outputs = IndexedSeq[ErgoBoxCandidate](campaignOutput, changeOutput, feeOutput)
    val unsignedTx = UnsignedErgoTransaction(inputs, dataInputs, outputs)

    implicit val ir = new RuntimeIRContext
    val settings = ErgoSettings.read()

    val prover = new ErgoProvingInterpreter(IndexedSeq(PrimitiveSecretKey(key)), LaunchParameters)
    val tx = prover.sign(unsignedTx, inputBoxes.toIndexedSeq, IndexedSeq(controlBox),
      ErgoStateContext.empty(settings), TransactionHintsBag.empty)
    println("tx: " + tx)
  }


  var lastHeight = 509323 // we start from some recent block

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
          ergoFundProcess(extractionResult)
          val extractedCount = extractionResult.createdOutputs.length
          log.info("Extracted: " + extractedCount + " outputs")
          if (extractedCount > 0) {
            log.info("New Ergofund outputs found: " + extractionResult.createdOutputs)
          }
          step()
        case None =>
          log.info(s"No block found @ height $newHeight")
          val campaignId = 0
          val campaignDesc = "Test campaign"
          val campaignScript = SigmaPropConstant(
            ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
              .fromString("9f2UU1Jo52WDMCCCu9GYdiWSmb5V7jzP8FdRkVqqvCHyD72gTTR")
              .get.asInstanceOf[P2PKAddress].pubkey
          )

          val deadline = 550000
          val minToRaise = 100000000000L
          registerCampaign(lastHeight, campaignId, campaignDesc, campaignScript, deadline, minToRaise)
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
