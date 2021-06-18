package dao

import javax.inject.Inject
import models.{ExtractedAssetModel, ExtractedBlockModel, ExtractedInputModel, ExtractedOutputModel, ExtractedRegisterModel, ExtractedTransactionModel, ExtractionResultModel}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import utils.DbUtils

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}


class ExtractionResultDAO @Inject() (extractedBlockDAO: ExtractedBlockDAO, transactionDAO: TransactionDAO, inputDAO: InputDAO, outputDAO: OutputDAO, assetDAO: AssetDAO, registerDAO: RegisterDAO, protected val dbConfigProvider: DatabaseConfigProvider) (implicit executionContext: ExecutionContext)
    extends DbUtils with HasDatabaseConfigProvider[JdbcProfile] {

  /**
  * store data into db as transactionally.
   * @param extractionResult : ExtractionResultModel extracted data
   * @param extractedBlockModel: ExtractedBlockModel extracted block
   */
  def save(extractionResult: ExtractionResultModel, extractedBlockModel: ExtractedBlockModel): Unit = {
    var transactions: Seq[ExtractedTransactionModel] = Seq()
    var outputs: Seq[ExtractedOutputModel] = Seq()
    var inputs: Seq[ExtractedInputModel] = Seq()
    var assets: Seq[ExtractedAssetModel] = Seq()
    var registers: Seq[ExtractedRegisterModel] = Seq()
    extractionResult.createdOutputs.foreach(obj => {
      outputs = outputs :+ obj.extractedOutput
      transactions = transactions :+ obj.extractedTransaction
      assets = assets ++ obj.extractedAssets
      registers = registers ++ obj.extractedRegisters
    })
    extractionResult.spentTrackedInputs.foreach(obj => {
      inputs = inputs :+ obj.extractedInput
      transactions = transactions :+ obj.extractedTransaction
    })
    val action = for {
        _ <- extractedBlockDAO.insert(Seq(extractedBlockModel))
        _ <- transactionDAO.insert(transactions.distinct)
        _ <- outputDAO.insert(outputs)
        _ <- assetDAO.insert(assets)
        _ <- registerDAO.insert(registers)
        _ <- inputDAO.insert(inputs)
    } yield {

    }
    val response = execTransact(action)
    Await.result(response, Duration.Inf)
  }
}
