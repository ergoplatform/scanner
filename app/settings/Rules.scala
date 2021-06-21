package settings

import models.{ExtractionRulesModel, Scan}
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox}
import org.ergoplatform.nodeView.wallet.scanning.{ContainsAssetPredicate, EqualsScanningPredicate}
import scorex.crypto.hash.Digest32
import scorex.util.encode.Base16
import sigmastate.Values

object Rules {
  // TODO: Implement a general solution for rules (ex: Add to config)
  // ErgoFund pledge script, we find pledges by finding boxes (outputs) protected by the script
  val pledgeScriptBytes: Array[Byte] = ErgoAddressEncoder(0: Byte)
    .fromString("XUFypmadXVvYmBWtiuwDioN1rtj6nSvqgzgWjx1yFmHAVndPaAEgnUvEvEDSkpgZPRmCYeqxewi8ZKZ4Pamp1M9DAdu8d4PgShGRDV9inwzN6TtDeefyQbFXRmKCSJSyzySrGAt16")
    .get
    .contentBytes

  val pledgeScan = Scan(1, EqualsScanningPredicate(ErgoBox.R1, Values.ByteArrayConstant(pledgeScriptBytes)))

  // We scan for ErgoFund campaign data, stored in outputs with the ErgoFund token
  val campaignTokenId: Array[Byte] = Base16.decode("08fc8bd24f0eaa011db3342131cb06eb890066ac6d7e6f7fd61fcdd138bd1e2c").get
  val campaignScan = Scan(2, ContainsAssetPredicate(Digest32 @@ campaignTokenId))

  val exampleRules = ExtractionRulesModel(Seq(pledgeScan, campaignScan))
}
