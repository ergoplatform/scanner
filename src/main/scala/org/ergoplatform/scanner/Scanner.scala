package org.ergoplatform.scanner

import io.circe.Json
import scalaj.http.{Http, HttpOptions}
import io.circe.parser._
import org.bouncycastle.util.BigIntegers
import org.ergoplatform.ErgoBox.{R4, R5, R6, R7, R8, TokenId}
import org.ergoplatform.{DataInput, ErgoAddressEncoder, ErgoBox, ErgoBoxCandidate, ErgoScriptPredef, P2PKAddress, UnsignedInput}
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.state.ErgoStateContext
import org.ergoplatform.nodeView.wallet.scanning.{ContainsAssetPredicate, EqualsScanningPredicate, ScanningPredicate}
import org.ergoplatform.scanner.ErgoFundStructures.{Campaign, Pledge}
import org.ergoplatform.settings.{Constants, ErgoSettings, LaunchParameters}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import org.ergoplatform.wallet.interpreter.{ErgoProvingInterpreter, TransactionHintsBag}
import org.ergoplatform.wallet.secrets.PrimitiveSecretKey
import scorex.crypto.hash.Digest32
import scorex.util.ScorexLogging
import scorex.util.encode.Base16
import sigmastate.{SByte, Values}
import sigmastate.Values.{ByteArrayConstant, CollectionConstant, ErgoTree, IntConstant, LongConstant, SigmaPropConstant}
import sigmastate.basics.DLogProtocol.DLogProverInput
import sigmastate.eval.RuntimeIRContext
import sigmastate.interpreter.ContextExtension
import sigmastate.serialization.ErgoTreeSerializer
import special.collection.Coll
import sigmastate.eval.Colls

import scala.annotation.tailrec
import scala.collection.mutable
import sigmastate.eval._
import special.sigma.SigmaProp
import swaydb.data.slice.Slice
import swaydb.serializers.Serializer

object BasicDataStructures {
  type ScanId = Int
  type Identifier = String

  case class Scan(id: ScanId, scanningPredicate: ScanningPredicate)

  case class ExtractionRules(scans: Seq[Scan])

  case class ExtractedOutput(scan: Scan, output: ErgoBox, tx: ErgoTransaction)

  case class ExtractedInput(spentBox: ErgoBox, spendingTx: ErgoTransaction)

  case class ExtractionResult(spentTrackedInputs: Seq[ExtractedInput], createdOutputs: Seq[ExtractedOutput])

}

object ErgoFundStructures {

  type CampaignId = Int

  type PledgeId = String

  case class Campaign(id: CampaignId, desc: String, script: ErgoTree, deadline: Int, toRaise: Long)

  case class Pledge(pledgeId: PledgeId, campaignId: CampaignId, backerScript: ErgoTree, projectScript: ErgoTree,
                    deadline: Int, toRaise: Long)

  //binary campaign data serializer for SwayDB
  implicit val campaignSerialiser =
    new Serializer[Campaign] {
      override def write(cmp: Campaign): Slice[Byte] = {
        val descLen = cmp.desc.length
        val scriptBytes = cmp.script.bytes
        val scriptBytesLen = scriptBytes.length

        Slice
          .ofBytesScala(200) //allocate enough length to add all fields
          .addInt(cmp.id)
          .addInt(descLen)
          .addStringUTF8(cmp.desc)
          .addInt(scriptBytesLen)
          .addAll(scriptBytes)
          .addInt(cmp.deadline)
          .addLong(cmp.toRaise)
          .close() //optionally close to discard unused space
      }

      override def read(slice: Slice[Byte]): Campaign = {
        val reader = slice.createReader()
        val id = reader.readInt()
        val descLen = reader.readInt()
        val desc = reader.readStringUTF8(descLen)
        val scriptBytesLen = reader.readInt()
        val scriptBytes = reader.read(scriptBytesLen).toArray
        val deadline = reader.readInt()
        val toRaise = reader.readLong()

        val script = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(scriptBytes)
        Campaign(id, desc, script, deadline, toRaise)
      }
    }

  //binary campaign data serializer for SwayDB
  implicit val pledgeSerialiser =
    new Serializer[Pledge] {
      override def write(pl: Pledge): Slice[Byte] = {
        val backerScriptBytes = pl.backerScript.bytes
        val backerScriptBytesLen = backerScriptBytes.length

        val projectScriptBytes = pl.projectScript.bytes
        val projectScriptBytesLen = projectScriptBytes.length

        Slice
          .ofBytesScala(200) //allocate enough length to add all fields
          .addStringUTF8(pl.pledgeId)
          .addInt(pl.campaignId)
          .addInt(backerScriptBytesLen)
          .addAll(backerScriptBytes)
          .addInt(projectScriptBytesLen)
          .addAll(projectScriptBytes)
          .addInt(pl.deadline)
          .addLong(pl.toRaise)
          .close() //optionally close to discard unused space
      }

      override def read(slice: Slice[Byte]): Pledge = {
        val reader = slice.createReader()

        val pledgeId = reader.readStringUTF8(Constants.ModifierIdSize * 2)
        val campaignId = reader.readInt()
        val backerScriptBytesLen = reader.readInt()
        val backerScriptBytes = reader.read(backerScriptBytesLen).toArray
        val projectScriptBytesLen = reader.readInt()
        val projectScriptBytes = reader.read(projectScriptBytesLen).toArray
        val deadline = reader.readInt()
        val toRaise = reader.readLong()

        val backerScript = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(backerScriptBytes)
        val projectScript = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(projectScriptBytes)
        Pledge(pledgeId, campaignId, backerScript, projectScript, deadline, toRaise)
      }
    }

}

object DatabaseStructures {

  import swaydb._
  import swaydb.serializers.Default._
  import scala.concurrent.duration._
  import org.ergoplatform.scanner.ErgoFundStructures._

  // Create an in-memory map instance
  val pledgeBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val campaignBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val paymentBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val systemBoxes = memory.Map[String, Array[Byte], Nothing, Glass]()

  val nftIndex = memory.Map[String, String, Nothing, Glass]()

  // Campaigns
  val campaigns = memory.Map[CampaignId, Campaign, Nothing, Glass]()

  // Pledges
  val pledges = memory.Map[PledgeId, Pledge, Nothing, Glass]()
}

object Scanner extends App with ScorexLogging {

  import BasicDataStructures._
  import DatabaseStructures._
  import ErgoTree.fromProposition

  // used in methods producing transactions
  implicit val me = ErgoAddressEncoder.apply(ErgoAddressEncoder.MainnetNetworkPrefix)

  def bytesToString(bs: Array[Byte]): String = Base16.encode(bs)

  val controlBoxNftIdString = "baeaa079efe3473dd7468e55b20da961c79155da9a848236ec7a5cf0d74cf99a"
  val controlBoxNftId = Base16.decode(controlBoxNftIdString).get
  val controlBoxScanId = 1
  val controlBoxScan = Scan(controlBoxScanId, ContainsAssetPredicate(Digest32 @@ controlBoxNftId))

  val tokenSaleNftIdString = "48ab32adaf7eb132c76c077fdeb3df6a6692417168f1541c83a5545c10c63f81"
  val tokenSaleNftId = Base16.decode(tokenSaleNftIdString).get
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
  val campaignTokenId = Base16.decode("07a57a489d187734ad4c960514fdbcb179beae5822774954ac1564085e641dce").get
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

  val bestChainHeaderIds = mutable.Map[Int, Identifier](516000 -> "1a9cad7745fb39ba30b6d1a43f524eb43870ddf31bd04f6ac7a3b95f1a23f8a0")

  private def getJsonAsString(url: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(120000))
      .asString
      .body
  }

  private def postJson(url: String, json: Json): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .postData(json.toString())
      .option(HttpOptions.readTimeout(120000))
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
          val pledgeBox = out.output
          val value = pledgeBox.value
          val campaignId = pledgeBox.get(R4).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]

          val backerScriptProp = pledgeBox.get(R5).get.asInstanceOf[SigmaPropConstant].value.asInstanceOf[SigmaProp]
          val projectScriptProp = pledgeBox.get(R6).get.asInstanceOf[SigmaPropConstant].value.asInstanceOf[SigmaProp]
          val backerScriptTree = ErgoTree.fromProposition(backerScriptProp)
          val projectScriptTree = ErgoTree.fromProposition(projectScriptProp)

          val deadline = pledgeBox.get(R7).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]
          val minToRaise = pledgeBox.get(R8).get.asInstanceOf[LongConstant].value.asInstanceOf[Long]

          val pledge = Pledge(Base16.encode(pledgeBox.id), campaignId, backerScriptTree, projectScriptTree, deadline, minToRaise)
          log.info("Registered pledge box: " + boxId)
          pledges.put(pledge.pledgeId, pledge)

        case i: Int if i == campaignScanId =>
          val campaignBox = out.output
          val value = campaignBox.value // todo: check the valur is above threshold
          val campaignId = campaignBox.get(R4).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]
          val campaignDescBytes = campaignBox.get(R5).get.asInstanceOf[CollectionConstant[SByte.type]].value.asInstanceOf[Array[Byte]]
          val campaignDesc = new String(campaignDescBytes, "UTF-8")
          val campaignScriptProp = campaignBox.get(R6).get.asInstanceOf[SigmaPropConstant].value.asInstanceOf[SigmaProp]
          val campaignScriptTree = ErgoTree.fromProposition(campaignScriptProp)
          val campaignDeadline = campaignBox.get(R7).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]
          val campaignToRaise = campaignBox.get(R8).get.asInstanceOf[LongConstant].value.asInstanceOf[Long]

          val campaign = Campaign(campaignId, campaignDesc, campaignScriptTree, campaignDeadline, campaignToRaise)
          log.info(s"Registered campaign box: $boxId, campaign: $campaign")
          campaigns.put(campaign.id, campaign)

        case i: Int if i == paymentScanId =>
          paymentBoxes.put(boxId, out.output.bytes)
          log.info("Registered payment box: " + boxId)
      }
    }
  }

  def makePledge(): Unit = {
    //todo: finish
  }

  def registerCampaign(currentHeight: Int,
                       campaignId: Int,
                       campaignDesc: String,
                       campaignScript: SigmaPropConstant,
                       deadline: Int,
                       minToRaise: Long): Unit = {

    def deductToken(tokens: Coll[(TokenId, Long)], index: Int): Coll[(TokenId, Long)] = {
      val atIdx = tokens.apply(index)
      tokens.updated(index, atIdx._1 -> (atIdx._2 - 1))
    }

    val keyBytes = Base16.decode("").get
    val key = DLogProverInput(BigIntegers.fromUnsignedByteArray(keyBytes))

    val controlBoxId = nftIndex.get(controlBoxNftIdString).get
    val controlBoxBytes = systemBoxes.get(controlBoxId).get
    val controlBox = ErgoBoxSerializer.parseBytes(controlBoxBytes)

    log.info("Control box ID: " + controlBoxId)

    val tokensaleBoxId = nftIndex.get(tokenSaleNftIdString).get
    val tokensaleBoxBytes = systemBoxes.get(tokensaleBoxId).get
    val tokensaleBox = ErgoBoxSerializer.parseBytes(tokensaleBoxBytes)
    val tokansaleOutput = new ErgoBoxCandidate(tokensaleBox.value, tokensaleBox.ergoTree, currentHeight,
      deductToken(tokensaleBox.additionalTokens, 1), tokensaleBox.additionalRegisters)

    val price = controlBox.additionalRegisters(R4).asInstanceOf[LongConstant].value.asInstanceOf[Long]
    val devRewardScriptBytes = controlBox.additionalRegisters(R5).asInstanceOf[CollectionConstant[SByte.type]].value.asInstanceOf[Coll[Byte]].toArray
    val devRewardScript = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(devRewardScriptBytes)
    val devRewardOutput = new ErgoBoxCandidate(price, devRewardScript, currentHeight)

    val inputBoxes = mutable.Buffer[ErgoBox]()
    inputBoxes += tokensaleBox
    var collectedAmount = tokensaleBox.value

    val inputsCount = paymentBoxes.values.takeWhile { boxBytes =>
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      inputBoxes += box
      collectedAmount += box.value
      collectedAmount < tokensaleBox.value + price + fee
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
    val campaignAmt = 1000000000L
    val campaignTokens: Array[(TokenId, Long)] = Array((Digest32 @@ campaignTokenId) -> 1)
    val campaignOutput = new ErgoBoxCandidate(
      campaignAmt,
      campaignAddress,
      currentHeight,
      additionalTokens = Colls.fromArray(campaignTokens),
      additionalRegisters = regs)

    val feeAmount = 2000000L // 0.002 ERG
    val feeOutput = new ErgoBoxCandidate(feeAmount, ErgoScriptPredef.feeProposition(), currentHeight)

    val changeAmount = collectedAmount - campaignAmt - feeAmount - tokensaleBox.value - price
    val changeOutput = new ErgoBoxCandidate(changeAmount, paymentTree, currentHeight)

    val outputs = IndexedSeq[ErgoBoxCandidate](tokansaleOutput, devRewardOutput, campaignOutput, changeOutput, feeOutput)
    val unsignedTx = UnsignedErgoTransaction(inputs, dataInputs, outputs)

    implicit val ir = new RuntimeIRContext
    val settings = ErgoSettings.read()

    val prover = new ErgoProvingInterpreter(IndexedSeq(PrimitiveSecretKey(key)), LaunchParameters)
    val tx = prover.sign(unsignedTx, inputBoxes.toIndexedSeq, IndexedSeq(controlBox),
      ErgoStateContext.empty(settings), TransactionHintsBag.empty).get
    val json = ErgoTransaction.ergoLikeTransactionEncoder(tx)
    val txId = postJson(serverUrl + "transactions", json)
    println("tx id: " + txId)
  }


  var lastHeight = 516000 // we start from some recent block

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
      //    registerCampaign(lastHeight, campaignId, campaignDesc, campaignScript, deadline, minToRaise)
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