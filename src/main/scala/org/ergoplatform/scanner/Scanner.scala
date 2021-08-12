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
import sigmastate.{SByte, SSigmaProp, Values}
import sigmastate.Values.{ByteArrayConstant, CollectionConstant, ConstantNode, ErgoTree, IntConstant, LongConstant, SigmaPropConstant}
import sigmastate.basics.DLogProtocol.DLogProverInput
import sigmastate.eval.RuntimeIRContext
import sigmastate.interpreter.ContextExtension
import sigmastate.serialization.{ErgoTreeSerializer, ValueSerializer}
import special.collection.Coll
import sigmastate.eval.Colls

import scala.annotation.tailrec
import scala.collection.mutable
import sigmastate.eval._
import special.sigma.SigmaProp
import swaydb.data.slice.Slice
import swaydb.serializers.Serializer

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

object ErgoFundStructures {

  type CampaignId = Int

  type PledgeId = String

  case class Campaign(id: CampaignId, desc: String, script: ErgoTree, deadline: Int, toRaise: Long)

  case class Pledge(pledgeId: PledgeId, campaignId: CampaignId, backerScript: ErgoTree, projectScript: ErgoTree,
                    deadline: Int, toRaise: Long)

  //binary serializers for SwayDB below
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
  import org.ergoplatform.scanner.ErgoFundStructures._

  // for boxes corresponding to pledges
  val pledgeBoxes = persistent.Map[String, Array[Byte], Nothing, Glass](dir = "/tmp/data/pledgeBoxes")

  // for boxes corresponding to campaigns
  val campaignBoxes = persistent.Map[String, Array[Byte], Nothing, Glass](dir = "/tmp/data/campaignBoxes")

  // boxes used by offchain service to pay for campaign registration. In the real deployed ErgoFund likely
  // a user should pay via a proxy service or DApp connector
  val paymentBoxes = persistent.Map[String, Array[Byte], Nothing, Glass](dir = "/tmp/data/paymentBoxes")

  // control box and tokensale box stored there
  val systemBoxes = persistent.Map[String, Array[Byte], Nothing, Glass](dir = "/tmp/data/systemBoxes")

  // actual NftId -> BoxId correspondences
  val nftIndex = persistent.Map[String, String, Nothing, Glass](dir = "/tmp/data/nftIndex")

  // Campaigns
  val campaigns = persistent.Map[CampaignId, Campaign, Nothing, Glass](dir = "/tmp/data/campaigns")

  // Pledges
  val pledges = persistent.Map[PledgeId, Pledge, Nothing, Glass](dir = "/tmp/data/pledges")
}

object Scanner extends App with ScorexLogging {

  import BasicDataStructures._
  import DatabaseStructures._
  import ErgoTree.fromProposition

  // used in methods producing and signing transactions

  implicit val ir = new RuntimeIRContext
  implicit val me = ErgoAddressEncoder.apply(ErgoAddressEncoder.MainnetNetworkPrefix)
  val txFee = 10000000 //0.01 ERG
  val settings = ErgoSettings.read()

  def bytesToString(bs: Array[Byte]): String = Base16.encode(bs)

  val controlBoxNftIdString = "72c3fbce3243d491d81eb564cdab1662b1f8d4c7e312b88870cec79b7cfd4321"
  val controlBoxNftId = Base16.decode(controlBoxNftIdString).get
  val controlBoxScanId = 1
  val controlBoxScan = Scan(controlBoxScanId, ContainsAssetPredicate(Digest32 @@ controlBoxNftId))

  val tokenSaleNftIdString = "15b0ae41c24230069ff96dacbac0932850ac0c2a0924daf72a39e88cbcf3acd5"
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
  // The scan will find tokensale boxes also, we filter them out later
  val campaignTokenId = Base16.decode("05b66b97e5802f6447b67fe30cb4055e14d6b17bb14f5f563d65c9622c43a659").get
  val campaignScanId = 4
  val campaignScan = Scan(campaignScanId, ContainsAssetPredicate(Digest32 @@ campaignTokenId))

  // Script of boxes used to pay for registering campaigns. In deployed ErgoFund should be replaced with a user paying
  // via proxy service or DApp connector.
  val paymentTree = ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
    .fromString("9f2UU1Jo52WDMCCCu9GYdiWSmb5V7jzP8FdRkVqqvCHyD72gTTR")
    .get
    .script

  val paymentPkBytes = paymentTree.bytes

  val paymentScanId = 5
  val paymentScan = Scan(paymentScanId, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(paymentPkBytes)))

  val exampleRules = ExtractionRules(Seq(controlBoxScan, tokenSaleScan, pledgeScan, campaignScan, paymentScan))

  val serverUrl = "http://213.239.193.208:9053/"

  val bestChainHeaderIds = mutable.Map[Int, Identifier](553033 -> "447779fe8f73fd3b5c90ae063099342480ae03d963662675a2ce4f79081ecd6e")

  private def getJsonAsString(url: String): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .option(HttpOptions.readTimeout(180000))
      .asString
      .body
  }

  private def postJson(url: String, json: Json): String = {
    Http(s"$url")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("Charset", "UTF-8")
      .postData(json.toString())
      .option(HttpOptions.readTimeout(180000))
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

  // ErgoFund-specific logic to process scans-related data extracted from the blockchain
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
          val value = campaignBox.value
          if (value >= 1000000000L) {
            val res = Try {
              val campaignId = campaignBox.get(R4).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]
              val campaignDescBytes = campaignBox.get(R5).get.asInstanceOf[CollectionConstant[SByte.type]].value.toArray.asInstanceOf[Array[Byte]]
              val campaignDesc = new String(campaignDescBytes, "UTF-8")
              val campaignScriptProp = campaignBox.get(R6).get.asInstanceOf[SigmaPropConstant].value.asInstanceOf[SigmaProp]
              val campaignScriptTree = ErgoTree.fromProposition(campaignScriptProp)
              val campaignDeadline = campaignBox.get(R7).get.asInstanceOf[IntConstant].value.asInstanceOf[Int]
              val campaignToRaise = campaignBox.get(R8).get.asInstanceOf[LongConstant].value.asInstanceOf[Long]

              val campaign = Campaign(campaignId, campaignDesc, campaignScriptTree, campaignDeadline, campaignToRaise)
              log.info(s"Registered campaign box: $boxId, campaign: $campaign")
              campaigns.put(campaign.id, campaign)
            }
            if (res.isFailure) {
              val t = res.toEither.left.get
              log.warn("Campaign box parsing failed: ", t)
            }
          }
        case i: Int if i == paymentScanId =>
          paymentBoxes.put(boxId, out.output.bytes)
          log.info("Registered payment box: " + boxId)
      }
    }
  }


  def registerCampaign(currentHeight: Int,
                       campaignDesc: String,
                       campaignScript: SigmaPropConstant,
                       deadline: Int,
                       minToRaise: Long): Unit = {

    def deductToken(tokens: Coll[(TokenId, Long)], index: Int): Coll[(TokenId, Long)] = {
      val atIdx = tokens.apply(index)
      tokens.updated(index, atIdx._1 -> (atIdx._2 - 1))
    }

    val campaignkeyBytes = Base16.decode("").get
    val campaignKey = DLogProverInput(BigIntegers.fromUnsignedByteArray(campaignkeyBytes))

    val controlBoxId = nftIndex.get(controlBoxNftIdString).get
    val controlBoxBytes = systemBoxes.get(controlBoxId).get
    val controlBox = ErgoBoxSerializer.parseBytes(controlBoxBytes)

    println("Control box: " + controlBox)
    log.info("Control box ID: " + controlBoxId)

    val tokensaleBoxId = nftIndex.get(tokenSaleNftIdString).get
    val tokensaleBoxBytes = systemBoxes.get(tokensaleBoxId).get
    val tokensaleBox = ErgoBoxSerializer.parseBytes(tokensaleBoxBytes)
    val campaignId = tokensaleBox.additionalRegisters(R4).asInstanceOf[IntConstant].value.asInstanceOf[Int]

    val updTokensaleRegs = tokensaleBox.additionalRegisters.updated(R4, IntConstant(campaignId + 1))
    val tokensaleOutput = new ErgoBoxCandidate(tokensaleBox.value, tokensaleBox.ergoTree, currentHeight,
      deductToken(tokensaleBox.additionalTokens, 1), updTokensaleRegs)

    val price = controlBox.additionalRegisters(R4).asInstanceOf[LongConstant].value.asInstanceOf[Long]
    val devRewardScript = controlBox.additionalRegisters(R5).asInstanceOf[ConstantNode[SSigmaProp.type]].value.asInstanceOf[CSigmaProp].sigmaTree
    val devRewardOutput = new ErgoBoxCandidate(price, devRewardScript, currentHeight)

    val inputBoxes = mutable.Buffer[ErgoBox]()
    inputBoxes += tokensaleBox
    var collectedAmount = tokensaleBox.value

    val inputsCount = paymentBoxes.values.takeWhile { boxBytes =>
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      inputBoxes += box
      collectedAmount += box.value
      collectedAmount < tokensaleBox.value + price + txFee
    }.count

    log.info("inputs: " + inputsCount)
    log.info("Collected payments: " + collectedAmount)
    log.info("price: " + price)
    log.info("price + fee: " + (price + txFee))

    if (price + txFee > collectedAmount) {
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

    val feeOutput = new ErgoBoxCandidate(txFee, ErgoScriptPredef.feeProposition(), currentHeight)

    val changeAmount = collectedAmount - campaignAmt - txFee - tokensaleBox.value - price
    val changeOutput = new ErgoBoxCandidate(changeAmount, paymentTree, currentHeight)

    val outputs = IndexedSeq[ErgoBoxCandidate](tokensaleOutput, devRewardOutput, campaignOutput, changeOutput, feeOutput)
    val unsignedTx = UnsignedErgoTransaction(inputs, dataInputs, outputs)

    val prover = new ErgoProvingInterpreter(IndexedSeq(PrimitiveSecretKey(campaignKey)), LaunchParameters)
    val tx = prover.sign(unsignedTx, inputBoxes.toIndexedSeq, IndexedSeq(controlBox),
      ErgoStateContext.empty(settings), TransactionHintsBag.empty).get
    val json = ErgoTransaction.ergoLikeTransactionEncoder(tx)
    val txId = postJson(serverUrl + "transactions", json)
    println("tx id: " + txId)
  }


  var lastHeight = bestChainHeaderIds.last._1 // we start from some recent block

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
          val campaignDesc = "Test campaign"
          val campaignScript = SigmaPropConstant(
            ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
              .fromString("9f2UU1Jo52WDMCCCu9GYdiWSmb5V7jzP8FdRkVqqvCHyD72gTTR")
              .get.asInstanceOf[P2PKAddress].pubkey
          )

          val deadline = 555000
          val minToRaise = 100000000000L
        //  registerCampaign(lastHeight, campaignDesc, campaignScript, deadline, minToRaise)
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








object Tests extends App {
  //todo: move to proper tests

  val campaignScript = SigmaPropConstant(
    ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
      .fromString("9f2UU1Jo52WDMCCCu9GYdiWSmb5V7jzP8FdRkVqqvCHyD72gTTR")
      .get.asInstanceOf[P2PKAddress].pubkey
  )
  val campaign = Campaign(4, "cmp", campaignScript, 500, 2000000000L)

  val ser = ErgoFundStructures.campaignSerialiser
  val cbs = ser.write(campaign)

  assert(ser.read(cbs) == campaign)

  println(campaign.script.toProposition(true).asInstanceOf[SigmaPropConstant])


  println(Base16.encode(ValueSerializer.serialize(LongConstant(100000000))))

  println(Base16.encode(ValueSerializer.serialize(IntConstant(555000))))
}